package com.autosense.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.app.Activity
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lógica del tablero (reloj, clima, batería, velocímetro, música, navegación y pestañas),
 * compartida por la pantalla de inicio y por el panel que se dibuja encima de Waze.
 * Las vistas que un diseño no tenga simplemente se omiten.
 */
class Dashboard(
    private val context: Context,
    private val root: View,
    private val onTab: (Int) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val clockView: TextView? = root.findViewById(R.id.clock)
    private val dateView: TextView? = root.findViewById(R.id.date)
    private val speedGauge: SpeedGaugeView? = root.findViewById(R.id.speed_gauge)
    private val weatherView: TextView? = root.findViewById(R.id.weather)
    private val weatherDetail: TextView? = root.findViewById(R.id.weather_detail)
    private val weatherIcon: ImageView? = root.findViewById(R.id.weather_icon)
    private val batteryView: BatteryView? = root.findViewById(R.id.battery)
    private val batteryText: TextView? = root.findViewById(R.id.battery_text)
    private val trackTitle: TextView? = root.findViewById(R.id.track_title)
    private val trackArtist: TextView? = root.findViewById(R.id.track_artist)
    private val albumArt: ImageView? = root.findViewById(R.id.album_art)
    private val lyricsView: LyricsView? = root.findViewById<LyricsView>(R.id.lyrics)?.also {
        it.positionProvider = { currentPosition() }
    }
    private val playButton: ImageView? = root.findViewById(R.id.music_play)
    private val navTitle: TextView? = root.findViewById(R.id.nav_destino)
    private val navDetail: TextView? = root.findViewById(R.id.nav_detail)
    private val trackSeek: SeekBar? = root.findViewById(R.id.track_seek)
    private val trackElapsed: TextView? = root.findViewById(R.id.track_elapsed)
    private val trackRemaining: TextView? = root.findViewById(R.id.track_remaining)
    private var seeking = false
    private val greetingView: TextView? = root.findViewById(R.id.greeting)
    private val wifiView: WifiSignalView? = root.findViewById(R.id.wifi_signal)
    private val bluetoothView: ImageView? = root.findViewById(R.id.bt_status)
    private val roadView: RoadView? = root.findViewById(R.id.road)
    private val brightnessSlider: SeekBar? = root.findViewById(R.id.brightness_slider)
    private val volumeSlider: SeekBar? = root.findViewById(R.id.volume_slider)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var mediaController: MediaController? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var receiverRegistered = false
    private var askedWritePermission = false

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = showMedia()
        override fun onSessionDestroyed() {
            mediaController = null
            showMedia()
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            updateProgress()
            handler.postDelayed(this, 1000L)
        }
    }

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            updateConnectivity()
            handler.postDelayed(this, 30_000L)
        }
    }

    /** Velocímetro, carretera y llantas del auto con la misma velocidad (-1 = sin GPS). */
    private fun showSpeed(kmh: Int) {
        speedGauge?.speed = kmh
        roadView?.speed = kmh.coerceAtLeast(0)
        (root.findViewById<View>(R.id.car) as? CarTurntableView)?.speed = kmh.coerceAtLeast(0)
    }

    // Velocidad desde el GPS de la tablet (m/s -> km/h)
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed() && SystemClock.elapsedRealtime() > debugSpeedUntil) {
                val kmh = (location.speed * 3.6f).roundToInt()
                showSpeed(kmh)
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            showSpeed(-1)
        }
    }

    private val navListener: (String, String) -> Unit = { title, text -> handler.post { showNavigation(title, text) } }
    private val connectListener: () -> Unit = { handler.post { setupMedia() } }
    private val weatherListener: () -> Unit = { handler.post { refreshWeather() } }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return
            val percent = level * 100 / scale
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            batteryView?.update(percent, charging)
            batteryText?.text = "$percent%"
            batteryView?.let { batteryText?.setTextColor(it.levelColor()) }
            batteryView?.contentDescription = "Batería al $percent por ciento" + if (charging) ", cargando" else ""
        }
    }

    // Wi-Fi (intensidad y conexión) y Bluetooth: se actualizan al momento con los avisos del sistema
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateConnectivity()
    }

    private fun updateConnectivity() {
        wifiView?.let { view ->
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            @Suppress("DEPRECATION")
            val wifiConnected = cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI)?.isConnected == true
            view.level = if (!wifiConnected) -1 else {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                @Suppress("DEPRECATION")
                android.net.wifi.WifiManager.calculateSignalLevel(wm.connectionInfo.rssi, 5)
            }
        }
        bluetoothView?.let { view ->
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            val enabled = adapter?.isEnabled == true
            // En Android 12 o superior esta consulta pide un permiso extra: sin él se muestra solo encendido/apagado
            val connected = enabled && try {
                listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)
                    .any { adapter?.getProfileConnectionState(it) == android.bluetooth.BluetoothProfile.STATE_CONNECTED }
            } catch (e: SecurityException) {
                false
            }
            // Azul: conectado a un equipo (carro, bocina) · blanco tenue: encendido · muy tenue: apagado
            view.setColorFilter(if (connected) 0xFF4DA3FF.toInt() else 0xFFFFFFFF.toInt())
            view.alpha = if (connected) 1f else if (enabled) 0.6f else 0.25f
            view.contentDescription = when {
                connected -> "Bluetooth conectado"
                enabled -> "Bluetooth encendido"
                else -> "Bluetooth apagado"
            }
        }
    }

    /** En pantalla dividida angosta se usa letra un poco más chica (lo ajusta DockActivity). */
    private fun updateGreeting() {
        val view = greetingView ?: return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 5..11 -> "buenos días"
            in 12..18 -> "buenas tardes"
            else -> "buenas noches"
        }
        val text = "Hola $USER_NAME, $period"
        if (view.text != text) view.text = text
    }

    // Solo en la versión de desarrollo: permite simular la velocidad del GPS por ADB para probar
    // el velocímetro y la carretera sin manejar (am broadcast -a com.autosense.launcher.DEBUG_SPEED --ei kmh 60)
    private var debugSpeedUntil = 0L
    private val debugSpeedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kmh = intent.getIntExtra("kmh", 0)
            // La velocidad de prueba manda durante un minuto (el GPS real reporta 0 cada segundo)
            debugSpeedUntil = SystemClock.elapsedRealtime() + 60_000
            showSpeed(kmh)
        }
    }

    private val isDebugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    fun start() {
        if (isDebugBuild) {
            val filter = IntentFilter("com.autosense.launcher.DEBUG_SPEED")
            // Android 13+ exige indicar si el aviso puede venir de fuera de la app (aquí, de ADB)
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(debugSpeedReceiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(debugSpeedReceiver, filter)
        }
        TAB_IDS.forEach { id -> root.findViewById<View>(id)?.setOnClickListener { onTab(id) } }
        root.findViewById<View>(R.id.nav_card)?.setOnClickListener { onTab(R.id.tab_mapa) }
        root.findViewById<View>(R.id.power_button)?.setOnClickListener { (context as? Activity)?.let { PowerMenu.show(it) } }
        root.findViewById<View>(R.id.nav_maps)?.setOnClickListener { onTab(R.id.nav_maps) }
        root.findViewById<ImageView>(R.id.nav_maps_icon)?.let { icon ->
            try {
                icon.setImageDrawable(context.packageManager.getApplicationIcon(AppLauncher.MAPS))
            } catch (e: PackageManager.NameNotFoundException) {
                root.findViewById<View>(R.id.nav_maps)?.visibility = View.GONE
            }
        }
        root.findViewById<View>(R.id.music_card)?.setOnClickListener { onTab(R.id.tab_musica) }
        root.findViewById<View>(R.id.music_prev)?.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        root.findViewById<View>(R.id.music_play)?.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        root.findViewById<View>(R.id.music_next)?.setOnClickListener { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
        root.findViewById<ImageView>(R.id.nav_app_icon)?.let { icon ->
            try {
                icon.setImageDrawable(context.packageManager.getApplicationIcon("com.waze"))
            } catch (e: PackageManager.NameNotFoundException) {
                icon.setImageResource(R.drawable.ic_map)
            }
        }

        showCar3d()
        CarBus.navListeners.add(navListener)
        CarBus.connectListeners.add(connectListener)
        WeatherHub.listeners.add(weatherListener)
        WeatherHub.start(context)
        setupMedia()
        showMedia()
        startGps()
        setupQuickControls()
        setupSeek()
    }

    fun resume() {
        syncQuickControls()
        updateGreeting()
        handler.post(clockTick)
        handler.post(weatherTick)
        if (!receiverRegistered) {
            context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            context.registerReceiver(statusReceiver, IntentFilter().apply {
                addAction(android.net.wifi.WifiManager.RSSI_CHANGED_ACTION)
                addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            })
            receiverRegistered = true
        }
        updateConnectivity()
    }

    fun pause() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(weatherTick)
        if (receiverRegistered) {
            context.unregisterReceiver(batteryReceiver)
            context.unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
    }

    fun stop() {
        pause()
        if (isDebugBuild) try { context.unregisterReceiver(debugSpeedReceiver) } catch (e: Exception) { }
        CarBus.navListeners.remove(navListener)
        CarBus.connectListeners.remove(connectListener)
        WeatherHub.listeners.remove(weatherListener)
        mediaController?.unregisterCallback(mediaCallback)
        sessionListener?.let {
            (context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .removeOnActiveSessionsChangedListener(it)
        }
        locationManager.removeUpdates(locationListener)
    }

    // Si la app trae los cuadros del giro 360°, cambia la foto del auto por el auto que gira (mismo lugar y tamaño)
    private fun showCar3d() {
        val photo = root.findViewById<ImageView>(R.id.car) ?: return
        if (!CarTurntableView.hasFrames(context)) return
        val parent = photo.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(photo)
        val turntable = CarTurntableView(context).apply {
            id = R.id.car
            background = photo.background
        }
        parent.removeViewAt(index)
        parent.addView(turntable, index, photo.layoutParams)
    }

    // --- Brillo y volumen ---

    private fun setupQuickControls() {
        brightnessSlider?.max = 100
        brightnessSlider?.setOnSeekBarChangeListener(sliderListener { setBrightness(it) })
        volumeSlider?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSlider?.setOnSeekBarChangeListener(sliderListener {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        })
        syncQuickControls()
    }

    // Los deslizadores muestran el brillo y volumen reales (por si cambiaron con los botones de la tablet)
    private fun syncQuickControls() {
        if (Settings.System.canWrite(context)) useSystemBrightness()
        val brightness = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        brightnessSlider?.progress = brightness * 100 / 255
        volumeSlider?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun setBrightness(percent: Int) {
        // Mínimo 5 % para que la pantalla nunca quede totalmente negra
        val value = (percent.coerceAtLeast(5) * 255 / 100)
        if (Settings.System.canWrite(context)) {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            useSystemBrightness()
        } else {
            // Sin permiso del sistema: cambia el brillo de esta pantalla y, una sola vez,
            // abre el ajuste de Android donde el usuario permite cambiar el brillo general
            if (!askedWritePermission) {
                askedWritePermission = true
                AppLauncher.safeStart(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)))
            }
            (context as? Activity)?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = value / 255f }
            }
        }
    }

    // Quita cualquier brillo propio de la ventana para que se vea el brillo real del sistema
    private fun useSystemBrightness() {
        (context as? Activity)?.window?.let { window ->
            if (window.attributes.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    private fun sliderListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    /** Marca la pestaña activa en azul (texto e icono); las demás quedan en gris. */
    fun selectTab(selectedId: Int) {
        TAB_IDS.forEach { id ->
            val tab = root.findViewById<TextView>(id) ?: return@forEach
            val color = if (id == selectedId) ACTIVE_TAB_COLOR else INACTIVE_TAB_COLOR
            tab.setTextColor(color)
            tab.compoundDrawables[1]?.mutate()?.setTint(color)
        }
    }

    @SuppressLint("MissingPermission")
    fun startGps() {
        if (speedGauge == null) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } else {
            speedGauge.speed = -1
        }
    }

    private fun updateClock() {
        val now = Date()
        clockView?.text = SimpleDateFormat("h:mm a", Locale.US).format(now)
        dateView?.text = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "MX")).format(now)
            .replaceFirstChar { it.uppercase() }
        // Cambia de "buenos días" a "buenas tardes"/"buenas noches" sin reiniciar la app
        updateGreeting()
    }

    // Prioridad: pronóstico propio de la tablet (no necesita el celular); el S25 solo si no hay pronóstico
    private fun refreshWeather() {
        val now = WeatherHub.current()
        if (now != null) {
            // Temperatura grande y, arriba a su lado, la sensación térmica en un numerito gris
            weatherView?.text = android.text.SpannableStringBuilder("${now.temp}°C").apply {
                now.feelsLike?.let { feels ->
                    val start = length
                    append(" ${feels}°")
                    setSpan(android.text.style.SuperscriptSpan(), start, length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(0.55f), start, length, 0)
                    setSpan(android.text.style.ForegroundColorSpan(0xFF9FB2C8.toInt()), start, length, 0)
                    setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), start, length, 0)
                }
            }
            weatherView?.contentDescription = "${now.temp} grados" + (now.feelsLike?.let { ", sensación térmica $it grados" } ?: "")
            weatherDetail?.text = listOfNotNull(now.place, ForecastWeather.describe(now.code, now.isDay)).joinToString(" · ")
            weatherIcon?.setImageResource(ForecastWeather.icon(now.code, now.isDay))
            weatherIcon?.contentDescription = ForecastWeather.describe(now.code, now.isDay)
            return
        }
        val live = WeatherHub.phoneWeather()
        if (live != null) {
            weatherView?.text = live.substringBefore(" ")
            weatherDetail?.text = live.substringAfter(" ", "")
        }
        // Si no hay dato nuevo se conserva el último que se mostró
    }

    // --- Música: lee la sesión de Spotify (o la app que esté sonando) ---

    private fun setupMedia() {
        if (sessionListener != null || albumArt == null) return
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, MediaListenerService::class.java)
        try {
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                handler.post { attachController(controllers) }
            }
            msm.addOnActiveSessionsChangedListener(listener, component)
            sessionListener = listener
            attachController(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Sin acceso a notificaciones todavía; se reintenta cuando el servicio se conecte
        }
    }

    private fun attachController(controllers: List<MediaController>?) {
        val controller = controllers?.firstOrNull { it.packageName == "com.spotify.music" }
            ?: controllers?.firstOrNull()
        if (controller?.sessionToken == mediaController?.sessionToken) return
        mediaController?.unregisterCallback(mediaCallback)
        mediaController = controller
        controller?.registerCallback(mediaCallback, handler)
        showMedia()
    }

    private fun showMedia() {
        val art = albumArt ?: return
        val metadata = mediaController?.metadata
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        trackTitle?.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Spotify"
        trackArtist?.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Toca para abrir"
        if (bitmap != null) {
            // Portada de la canción a todo lo ancho
            art.setPadding(0, 0, 0, 0)
            art.setImageBitmap(bitmap)
            // Arriba en la tarjeta (en el tablero de manejo, la letra va debajo)
            art.scaleType = if (lyricsView != null) ImageView.ScaleType.FIT_START else ImageView.ScaleType.FIT_CENTER
        } else {
            // Sin canción: nota musical grande
            val pad = (16 * context.resources.displayMetrics.density).toInt()
            art.setPadding(pad, pad, pad, pad)
            art.setImageResource(R.drawable.ic_music)
            art.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val playing = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        lyricsView?.let { lyrics ->
            lyrics.setTrack(
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            )
            lyrics.playing = playing
        }
        playButton?.setImageResource(if (playing) R.drawable.ic_pause_circle else R.drawable.ic_play_pause)
        updateProgress()
    }

    // --- Progreso de la canción ---

    private fun setupSeek() {
        trackSeek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showTimes(progress.toLong() * 1000, seekBar.max.toLong() * 1000)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                seeking = false
                mediaController?.transportControls?.seekTo(seekBar.progress.toLong() * 1000)
            }
        })
    }

    // Posición actual: la última reportada más el tiempo que lleva sonando desde entonces
    private fun currentPosition(): Long? {
        val state = mediaController?.playbackState ?: return null
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        return position.coerceAtLeast(0)
    }

    private fun updateProgress() {
        val seek = trackSeek ?: return
        if (seeking) return
        val duration = mediaController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val state = mediaController?.playbackState
        if (duration <= 0 || state == null) {
            seek.progress = 0
            seek.isEnabled = false
            trackElapsed?.text = "0:00"
            trackRemaining?.text = "-0:00"
            return
        }
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0, duration)
        seek.isEnabled = true
        seek.max = (duration / 1000).toInt()
        seek.progress = (position / 1000).toInt()
        showTimes(position, duration)
    }

    private fun showTimes(position: Long, duration: Long) {
        trackElapsed?.text = formatTime(position)
        trackRemaining?.text = "-" + formatTime(duration - position)
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun showNavigation(title: String, text: String) {
        if (navTitle == null) return
        // "En ejecución" es solo Waze abierto sin ruta; las indicaciones reales llegan al navegar
        val idle = (title.isBlank() && text.isBlank()) || text.startsWith("En ejecución")
        if (idle) {
            navTitle.text = "Navegación"
            navDetail?.text = if (text.isBlank()) "Toca para navegar con Waze" else "Waze listo · toca para elegir destino"
        } else {
            navTitle.text = title.ifBlank { "Waze" }
            navDetail?.text = text
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        // Si hay sesión de Spotify se controla directo; si no, tecla multimedia del sistema
        mediaController?.transportControls?.let { controls ->
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                else -> if (mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    controls.pause()
                } else {
                    controls.play()
                }
            }
            return
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    companion object {
        val TAB_IDS = listOf(
            R.id.tab_home, R.id.tab_mapa, R.id.tab_musica,
            R.id.tab_telefono, R.id.tab_camara, R.id.tab_ajustes, R.id.tab_apps
        )
        private val ACTIVE_TAB_COLOR = 0xFF4DA3FF.toInt()
        private val INACTIVE_TAB_COLOR = 0xFFB0C4DE.toInt()
        // Nombre del saludo
        private const val USER_NAME = "Alfre"
    }
}
