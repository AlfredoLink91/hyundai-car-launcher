package com.autosense.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Reloj y clima": la tablet se vuelve una estación meteorológica a pantalla completa.
 * Reloj gigante (hora del sistema), fecha en español, temperatura, sensación térmica y estado del
 * cielo reales (Open-Meteo), sobre un fondo que cambia con el clima y la hora del día.
 *
 * - La hora se actualiza localmente cada segundo; el clima, cada 5 minutos mientras se ve.
 * - Al salir de la pantalla se detienen el reloj, las animaciones y las consultas.
 * - Sin internet se conserva el último clima válido y se indica cuándo se actualizó.
 */
class ClockWeatherActivity : Activity() {

    private lateinit var settings: ClockWeatherSettings
    private val handler = Handler(Looper.getMainLooper())
    private val spanish = Locale("es", "ES")

    private lateinit var sky: SkyView
    private lateinit var icon: WeatherIconView
    private lateinit var content: LinearLayout
    private lateinit var placeRow: LinearLayout
    private lateinit var placeIcon: ImageView
    private lateinit var placeView: TextView
    private lateinit var dateView: TextView
    private lateinit var backButton: TextView
    private lateinit var gearButton: ImageView
    private lateinit var hourCard: FrameLayout
    private lateinit var minuteCard: FrameLayout
    private lateinit var hourView: TextView
    private lateinit var minuteView: TextView
    private lateinit var colonView: TextView
    private lateinit var ampmView: TextView
    private lateinit var secondsView: TextView
    private lateinit var weatherPanel: LinearLayout
    private lateinit var tempView: TextView
    private lateinit var unitView: TextView
    private lateinit var divider: View
    private lateinit var descView: TextView
    private lateinit var feelsView: TextView
    private lateinit var updatedView: TextView
    private lateinit var overlay: FrameLayout
    private lateinit var overlayBody: LinearLayout

    private var reading: ClockWeatherReading? = null
    private var visible = false
    private var fetching = false
    private var lastFailed = false
    private var waitingForFix = false
    private var lastMinute = -1
    private var lastDate = ""
    private var laidOutFor = 0L
    private var unit = 10f

    private val thin by lazy { Typeface.create("sans-serif-thin", Typeface.NORMAL) }
    private val light by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val regular by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    // --- Ciclo de vida ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ClockWeatherSettings(this)
        reading = settings.lastReading
        setContentView(buildUi())
        reading?.let { showWeather(it, force = true) } ?: showNoWeather()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // Encendida mientras el carro da corriente, igual que el inicio
        if (CarPower.isPlugged(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sky.setRunning(true)
        icon.setRunning(true)
        if (!entered) {
            entered = true
            content.post { playEntrance() }
        }
        startAmbient()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        if (isDebugBuild) {
            val filter = IntentFilter(ACTION_DEBUG_WEATHER)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(debugWeatherReceiver, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(debugWeatherReceiver, filter)
        }
        lastMinute = -1
        tick.run()
        if (settings.locationMode == null) showSettings(setup = true) else scheduleWeather()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        sky.setRunning(false)
        icon.setRunning(false)
        stopAmbient()
        handler.removeCallbacksAndMessages(null)
        stopLocationRequest()
        try { unregisterReceiver(timeReceiver) } catch (e: Exception) { }
        if (isDebugBuild) try { unregisterReceiver(debugWeatherReceiver) } catch (e: Exception) { }
    }

    // Solo en la versión de desarrollo: muestra el fondo de otro clima para probarlo sin esperar
    // a que llueva (am broadcast -a com.autosense.launcher.DEBUG_WEATHER --ei code 63 --ez day true)
    private val isDebugBuild by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private val debugWeatherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.getIntExtra("code", 0)
            val day = intent.getBooleanExtra("day", true)
            val base = reading ?: return
            showWeather(base.copy(code = code, isDay = day), force = true)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE && settings.locationMode != null) hideSettings() else super.onBackPressed()
    }

    // --- Reloj (hora del sistema, sin internet) ---

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            if (visible) handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L + 10L)
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastMinute = -1
            lastDate = ""
            updateClock()
        }
    }

    private fun updateClock() {
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.MINUTE)
        val use24 = settings.use24h
        val seconds = settings.showSeconds
        if (minute != lastMinute) {
            // Al cambiar el minuto (no al abrir la pantalla) los números ruedan hacia arriba
            val animate = lastMinute != -1
            lastMinute = minute
            val hour = if (use24) now.get(Calendar.HOUR_OF_DAY) else now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
            roll(hourView, "%02d".format(hour), animate)
            roll(minuteView, "%02d".format(minute), animate)
            ampmView.text = if (now.get(Calendar.AM_PM) == Calendar.AM) "a. m." else "p. m."
            ampmView.visibility = if (use24) View.GONE else View.VISIBLE
            // La fecha cambia sola al pasar la medianoche
            val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", spanish).format(now.time).uppercase(spanish)
            if (date != lastDate) {
                lastDate = date
                dateView.text = date
            }
            updateUpdatedLabel()
        }
        secondsView.visibility = if (seconds) View.VISIBLE else View.GONE
        if (seconds) {
            // Los segundos cambian sin animación: rodar cada segundo obligaría a redibujar sin parar
            secondsView.text = "%02d".format(now.get(Calendar.SECOND))
            colonView.alpha = 1f
        }
        // (sin segundos, los dos puntos laten: lo hace el reloj de animación, ver updateAmbient)
    }

    // --- Clima (cada 5 minutos mientras se ve) ---

    private val weatherTick = Runnable { refreshWeather() }

    /** Consulta ya si no hay datos recientes; si no, espera lo que falta para cumplir 5 minutos. */
    private fun scheduleWeather() {
        handler.removeCallbacks(weatherTick)
        val age = reading?.let { System.currentTimeMillis() - it.fetchedAt } ?: Long.MAX_VALUE
        handler.postDelayed(weatherTick, if (age >= REFRESH_MS) 0L else REFRESH_MS - age)
    }

    private fun refreshWeather() {
        handler.removeCallbacks(weatherTick)
        if (!visible || fetching) return
        val coords = currentCoordinates()
        if (coords == null) {
            // Aún sin ubicación (esperando el GPS o sin configurar): se reintenta en el siguiente ciclo
            updateUpdatedLabel()
            handler.postDelayed(weatherTick, if (waitingForFix) 15_000L else REFRESH_MS)
            return
        }
        fetching = true
        updateUpdatedLabel()
        val auto = settings.locationMode == "auto"
        val needsName = auto && (settings.placeName == null || movedFar(coords))
        Thread {
            val result = ClockWeatherApi.fetch(coords.first, coords.second)
            val name = if (needsName && result != null) ClockWeatherApi.placeName(applicationContext, coords.first, coords.second) else null
            handler.post {
                fetching = false
                if (result != null) {
                    lastFailed = false
                    if (auto) {
                        settings.latitude = coords.first
                        settings.longitude = coords.second
                        if (name != null) settings.placeName = name
                    }
                    settings.lastReading = result
                    showWeather(result, force = false)
                } else {
                    // Sin internet o error: se conservan los últimos datos y se reintenta en el siguiente ciclo
                    lastFailed = true
                }
                showPlace()
                updateUpdatedLabel()
                if (visible) handler.postDelayed(weatherTick, REFRESH_MS)
            }
        }.start()
    }

    /** Coordenadas según el modo: la ciudad elegida o la ubicación de la tablet. */
    @SuppressLint("MissingPermission")
    private fun currentCoordinates(): Pair<Double, Double>? {
        when (settings.locationMode) {
            "manual" -> {
                val lat = settings.latitude ?: return null
                val lon = settings.longitude ?: return null
                return lat to lon
            }
            "auto" -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
                    return savedCoordinates()
                }
                val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                    .maxByOrNull { it.time }
                if (best != null) return best.latitude to best.longitude
                // Sin ubicación reciente: se pide una al sistema y, mientras, se usa la última guardada
                requestFix(lm)
                return savedCoordinates()
            }
            else -> return null
        }
    }

    private fun savedCoordinates(): Pair<Double, Double>? {
        val lat = settings.latitude ?: return null
        val lon = settings.longitude ?: return null
        return lat to lon
    }

    private fun movedFar(c: Pair<Double, Double>): Boolean {
        val lat = settings.latitude ?: return true
        val lon = settings.longitude ?: return true
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, c.first, c.second, out)
        return out[0] > 3_000f
    }

    private val fixListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            waitingForFix = false
            stopLocationRequest()
            refreshWeather()
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun requestFix(lm: LocationManager) {
        if (waitingForFix) return
        waitingForFix = true
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { try { lm.isProviderEnabled(it) } catch (e: Exception) { false } }
            .forEach { try { lm.requestSingleUpdate(it, fixListener, Looper.getMainLooper()) } catch (e: Exception) { } }
    }

    private fun stopLocationRequest() {
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(fixListener) } catch (e: Exception) { }
        waitingForFix = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            settings.locationMode = "auto"
            refreshWeather()
        } else if (savedCoordinates() == null) {
            // Sin permiso no hay GPS: se ofrece elegir la ciudad a mano
            settings.locationMode = null
            showSettings(setup = true)
        }
    }

    // --- Mostrar datos ---

    private fun showWeather(r: ClockWeatherReading, force: Boolean) {
        val changed = force || !r.sameWeather(reading)
        reading = r
        if (!changed) return  // mismo clima: sin redibujar ni animar
        val look = SkyLook.of(r.code, r.isDay)
        sky.setLook(look)
        icon.setLook(look)
        // La temperatura cuenta hasta el valor nuevo (al llegar el primer dato o cuando cambia)
        val newTemp = r.temperature.roundToInt()
        countTo(newTemp, tempView.text.toString().toIntOrNull() ?: (newTemp - 8))
        unitView.visibility = View.VISIBLE
        descView.text = ClockWeatherApi.describe(r.code, r.isDay)
        feelsView.text = "Sensación térmica: ${r.feelsLike.roundToInt()} °C"
        feelsView.visibility = View.VISIBLE
        showPlace()
        if (!force) {
            // Cambio de clima: aparece suavemente
            weatherPanel.alpha = 0.4f
            weatherPanel.animate().alpha(1f).setDuration(700).start()
        }
    }

    private var tempAnimator: android.animation.ValueAnimator? = null

    private fun countTo(target: Int, from: Int) {
        tempAnimator?.cancel()
        if (from == target) { tempView.text = target.toString(); return }
        tempAnimator = android.animation.ValueAnimator.ofInt(from, target).apply {
            duration = 900
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { tempView.text = it.animatedValue.toString() }
            start()
        }
    }

    // --- Animaciones (todas se detienen al salir de la pantalla) ---

    private var entered = false

    /** Número que rueda: el viejo sube y se desvanece, el nuevo entra desde abajo. */
    private fun roll(view: TextView, value: String, animate: Boolean, fast: Boolean = false) {
        if (view.text.toString() == value) return
        if (!animate || view.text.isNullOrEmpty() || view.height == 0) {
            view.text = value
            return
        }
        val dist = view.height * (if (fast) 0.3f else 0.4f)
        view.animate().cancel()
        view.animate().translationY(-dist).alpha(0f).setDuration(if (fast) 110L else 180L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                view.text = value
                view.translationY = dist
                view.animate().translationY(0f).alpha(1f).setDuration(if (fast) 200L else 340L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).withEndAction(null).start()
            }.start()
    }

    /** Entrada: encabezado, paneles del reloj y del clima aparecen uno tras otro subiendo suavemente. */
    private fun playEntrance() {
        val top = content.getChildAt(0)
        val side = ampmView.parent as View
        val items = listOf(top, hourCard, colonView, minuteCard, side, weatherPanel)
        val rise = unit * 3f
        items.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = rise
            if (v === hourCard || v === minuteCard || v === weatherPanel) { v.scaleX = 0.94f; v.scaleY = 0.94f }
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(80L + i * 90L).setDuration(700)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    /**
     * Un solo reloj de animación para toda la pantalla: en cada cuadro se mueven el cielo, el ícono,
     * los paneles que flotan, el reflejo de luz y los dos puntos que laten, y la pantalla se dibuja
     * UNA vez. El ritmo lo marca el cielo (≈15 cuadros/s en calma, ≈25 con lluvia): suave a la vista
     * y ligero para la tablet.
     */
    private var frameStart = 0L
    private val frame = object : Runnable {
        override fun run() {
            if (!visible) return
            updateAmbient((android.os.SystemClock.uptimeMillis() - frameStart) / 1000f)
            sky.invalidate()
            icon.invalidate()
            handler.postDelayed(this, sky.frameMs)
        }
    }

    private fun startAmbient() {
        stopAmbient()
        frameStart = android.os.SystemClock.uptimeMillis()
        frame.run()
    }

    private fun updateAmbient(t: Float) {
        // Los paneles flotan (cada uno a su ritmo) cuando ya terminó la entrada
        if (t > ENTRANCE_S) {
            val lift = unit * 0.55f
            val ramp = ((t - ENTRANCE_S) / 1.5f).coerceAtMost(1f)
            fun float(period: Float, phase: Float) = -lift * ramp * (0.5f - 0.5f * kotlin.math.cos(2 * Math.PI.toFloat() * t / period + phase))
            hourCard.translationY = float(4.2f, 0f)
            minuteCard.translationY = float(4.9f, 1.1f)
            weatherPanel.translationY = float(5.8f, 2.3f)
        }
        // Reflejo: cruza la hora, luego los minutos y luego el clima; se repite cada 9 segundos
        val cycle = ((t - 2.2f) % 9f) / 1.5f
        listOf(hourCard, minuteCard, weatherPanel).forEachIndexed { i, p ->
            val s = if (t < 2.2f) -1f else (cycle - i * 0.35f) / 1.2f
            (p.background as? GlassPanelDrawable)?.sheen = if (s in 0f..1f) s else -1f
        }
        // Sin segundos, los dos puntos laten suavemente al ritmo del reloj
        if (!settings.showSeconds && t > ENTRANCE_S) {
            val ms = System.currentTimeMillis() % 2000L
            colonView.alpha = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.cos(Math.PI * ms / 1000.0).toFloat())
        }
    }

    private fun stopAmbient() {
        handler.removeCallbacks(frame)
        listOf(hourCard, minuteCard, weatherPanel).forEach {
            it.translationY = 0f
            (it.background as? GlassPanelDrawable)?.sheen = -1f
        }
    }

    private fun showNoWeather() {
        val night = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 6..17
        sky.setLook(SkyLook(!night, 0.3f))
        icon.setLook(SkyLook(!night, 0.3f))
        tempView.text = "--"
        unitView.visibility = View.VISIBLE
        descView.text = if (settings.locationMode == null) "Elige tu ubicación" else "Buscando el clima…"
        feelsView.visibility = View.GONE
        showPlace()
    }

    private fun showPlace() {
        val name = settings.placeName
        placeView.text = when {
            name != null -> name.uppercase(spanish)
            settings.locationMode == "auto" -> "MI UBICACIÓN"
            else -> "SIN UBICACIÓN"
        }
    }

    private fun updateUpdatedLabel() {
        val r = reading
        val time = r?.let { SimpleDateFormat("h:mm a", spanish).format(Date(it.fetchedAt)).replace("AM", "a. m.").replace("PM", "p. m.") }
        updatedView.text = when {
            settings.locationMode == null -> "Toca ⚙ para elegir tu ubicación"
            r == null && fetching -> "Consultando el clima…"
            r == null && waitingForFix -> "Buscando tu ubicación…"
            r == null -> "Sin conexión · se reintentará en 5 minutos"
            lastFailed && System.currentTimeMillis() - r.fetchedAt > REFRESH_MS -> "Sin conexión · último dato a las $time"
            else -> "Actualizado a las $time"
        }
    }

    // --- Interfaz ---

    private fun buildUi(): View {
        // Sin color de fondo propio: el cielo cubre toda la pantalla (una capa menos que pintar)
        val root = FrameLayout(this)
        window.setBackgroundDrawable(null)
        sky = SkyView(this)
        root.addView(sky, FrameLayout.LayoutParams(MATCH, MATCH))

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Arriba: volver al inicio | ciudad y fecha | ajustes
        val top = FrameLayout(this)
        backButton = TextView(this).apply {
            text = "‹  Inicio"
            setTextColor(WHITE)
            gravity = Gravity.CENTER
            typeface = regular
            contentDescription = "Volver al inicio"
            setOnClickListener { finish() }
        }
        top.addView(backButton, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP))
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        placeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        placeIcon = ImageView(this).apply { setImageResource(R.drawable.ic_place); alpha = 0.9f }
        placeView = label(light, 0xF2FFFFFF.toInt()).apply { letterSpacing = 0.18f }
        placeRow.addView(placeIcon)
        placeRow.addView(placeView)
        heading.addView(placeRow, LinearLayout.LayoutParams(WRAP, WRAP))
        dateView = label(regular, 0xE6FFFFFF.toInt()).apply { letterSpacing = 0.14f; gravity = Gravity.CENTER }
        heading.addView(dateView, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(heading, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
        gearButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Ajustes de reloj y clima"
            setOnClickListener { showSettings(setup = false) }
        }
        top.addView(gearButton, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.TOP))
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        content.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Centro: reloj gigante en dos paneles de vidrio
        val clockRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        hourView = label(regular, WHITE).apply { includeFontPadding = false; gravity = Gravity.CENTER }
        minuteView = label(thin, WHITE).apply { includeFontPadding = false; gravity = Gravity.CENTER }
        hourCard = FrameLayout(this).apply { addView(hourView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) }
        minuteCard = FrameLayout(this).apply { addView(minuteView, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) }
        colonView = label(thin, WHITE).apply { text = ":"; includeFontPadding = false }
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
        ampmView = label(light, 0xE6FFFFFF.toInt())
        secondsView = label(thin, 0xDDFFFFFF.toInt()).apply { includeFontPadding = false }
        side.addView(ampmView)
        side.addView(secondsView)
        clockRow.addView(hourCard)
        clockRow.addView(colonView)
        clockRow.addView(minuteCard)
        clockRow.addView(side)
        content.addView(clockRow, LinearLayout.LayoutParams(WRAP, WRAP))

        content.addView(View(this), LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Abajo: ícono, temperatura, estado, sensación térmica y última actualización
        weatherPanel = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        icon = WeatherIconView(this)
        weatherPanel.addView(icon)
        val tempRow = LinearLayout(this).apply { gravity = Gravity.TOP }
        tempView = label(light, WHITE).apply { includeFontPadding = false }
        unitView = label(light, 0xE6FFFFFF.toInt()).apply { text = "°C"; includeFontPadding = false }
        tempRow.addView(tempView)
        tempRow.addView(unitView)
        weatherPanel.addView(tempRow)
        divider = View(this).apply { setBackgroundColor(0x44FFFFFF) }
        weatherPanel.addView(divider)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        descView = label(medium, WHITE)
        feelsView = label(regular, 0xF0FFFFFF.toInt())
        updatedView = label(regular, 0xA6FFFFFF.toInt())
        info.addView(descView)
        info.addView(feelsView)
        info.addView(updatedView)
        weatherPanel.addView(info)
        content.addView(weatherPanel, LinearLayout.LayoutParams(WRAP, WRAP))

        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            isClickable = true
        }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Tamaños según la pantalla (horizontal o vertical, cualquier resolución)
        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val key = (v.width.toLong() shl 32) or v.height.toLong()
            if (v.width > 0 && key != laidOutFor) {
                laidOutFor = key
                v.post { applySizes(v.width, v.height) }
            }
        }
        return root
    }

    private fun label(face: Typeface, color: Int) = TextView(this).apply {
        typeface = face
        setTextColor(color)
        maxLines = 1
    }

    /** Proporciones calculadas para que todo aproveche la pantalla sin cortarse ni encimarse. */
    private fun applySizes(w: Int, h: Int) {
        val portrait = h > w
        // En vertical la pantalla es angosta: textos un poco mayores para que nada quede diminuto
        unit = min(w.toFloat(), h * 1.6f) / 100f * (if (portrait) 1.3f else 1f)
        val u = unit
        val pad = (u * 2.6f).toInt()
        content.setPadding(pad, (u * 2.2f).toInt(), pad, (u * 2f).toInt())
        val glassInset = (u * 1.5f).toInt()
        // Sin sombra en el texto: en Android 8.1 las sombras de letras grandes se ven como un contorno negro.
        // La lectura la dan el vidrio oscuro y el degradado del fondo arriba y abajo.
        fun TextView.px(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_PX, size)

        // Encabezado
        val button = (u * 4.6f).toInt().coerceAtLeast(dp(48))
        backButton.px(u * 1.7f)
        backButton.background = GlassPanelDrawable(button / 2f, glassInset / 2)
        backButton.minHeight = button
        backButton.setPadding(button / 3, 0, button / 3, glassInset / 2)
        gearButton.background = GlassPanelDrawable(button / 2f, glassInset / 2)
        gearButton.layoutParams = (gearButton.layoutParams as FrameLayout.LayoutParams).apply { width = button; height = button }
        gearButton.setPadding(button / 4, button / 4, button / 4, button / 4)
        placeView.px(u * 1.55f)
        placeIcon.layoutParams = LinearLayout.LayoutParams((u * 1.9f).toInt(), (u * 1.9f).toInt()).apply { marginEnd = (u * 0.6f).toInt() }
        dateView.px(u * (if (portrait) 2.1f else 1.85f))
        (dateView.layoutParams as LinearLayout.LayoutParams).topMargin = (u * 0.5f).toInt()
        // En vertical, la fecha baja una línea para no chocar con los botones
        dateView.maxWidth = (w - button * 2 - pad * 2 - u * 2).toInt()
        dateView.maxLines = 2

        // Reloj: el protagonista. Se limita al ancho disponible para no cortarse nunca.
        var clock = if (portrait) w * 0.27f else h * 0.28f
        // Ancho total ≈ 2 paneles (2.56 × tamaño) + dos puntos + "a. m." / segundos (≈ 0.9 × tamaño)
        val cardInset = (u * 1.8f).toInt()
        clock = min(clock, (w - pad * 2 - cardInset * 4) / 3.6f)
        hourView.px(clock)
        minuteView.px(clock)
        colonView.px(clock * 0.8f)
        colonView.setPadding((clock * 0.04f).toInt(), 0, (clock * 0.04f).toInt(), (clock * 0.08f).toInt())
        ampmView.px(clock * 0.15f)
        secondsView.px(clock * 0.24f)
        val side = ampmView.parent as LinearLayout
        side.setPadding((clock * 0.08f).toInt(), 0, 0, 0)
        for (card in listOf(hourCard, minuteCard)) {
            card.background = GlassPanelDrawable(clock * 0.16f, cardInset)
            // Ancho fijo para que el reloj no "baile" al cambiar los números
            card.layoutParams = LinearLayout.LayoutParams((clock * 1.28f + cardInset * 2).toInt(), WRAP)
            card.setPadding(cardInset, cardInset + (clock * 0.06f).toInt(), cardInset, (cardInset * 1.4f + clock * 0.08f).toInt())
        }

        // Clima
        val temp = if (portrait) w * 0.13f else h * 0.12f
        tempView.px(temp)
        unitView.px(temp * 0.36f)
        unitView.setPadding((u * 0.3f).toInt(), (temp * 0.12f).toInt(), 0, 0)
        val iconSize = (temp * 1.3f).toInt()
        icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (u * 1.2f).toInt() }
        divider.layoutParams = LinearLayout.LayoutParams(maxOf(1, (u * 0.12f).toInt()), (temp * 0.9f).toInt()).apply {
            marginStart = (u * 2.4f).toInt(); marginEnd = (u * 2.4f).toInt()
        }
        // Nunca por debajo de un tamaño cómodo de leer
        val sp = resources.displayMetrics.scaledDensity
        descView.px(maxOf(u * 2.3f, 18 * sp))
        feelsView.px(maxOf(u * 1.85f, 15 * sp))
        updatedView.px(maxOf(u * 1.25f, 12 * sp))
        (feelsView.layoutParams as LinearLayout.LayoutParams).topMargin = (u * 0.3f).toInt()
        (updatedView.layoutParams as LinearLayout.LayoutParams).topMargin = (u * 0.6f).toInt()
        val infoWidth = w - pad * 2 - iconSize - temp * 2.2f - u * 9
        listOf(descView, feelsView, updatedView).forEach { it.maxWidth = infoWidth.toInt().coerceAtLeast(dp(160)) }
        val panelInset = (u * 1.6f).toInt()
        weatherPanel.background = GlassPanelDrawable(u * 2.4f, panelInset)
        weatherPanel.setPadding(panelInset + (u * 2f).toInt(), panelInset + (u * 1.2f).toInt(),
            panelInset + (u * 3f).toInt(), (panelInset * 1.4f + u * 1.2f).toInt())

        content.requestLayout()
        if (overlay.visibility == View.VISIBLE) showSettings(setup = settings.locationMode == null)
    }

    // --- Ajustes (ubicación, formato de hora, segundos, botón en el inicio) ---

    private fun showSettings(setup: Boolean) {
        val u = unit
        overlay.removeAllViews()
        overlay.visibility = View.VISIBLE
        val scroll = ScrollView(this)
        overlayBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (u * 1.6f).toInt()
            background = GlassPanelDrawable(u * 2.2f, inset, 0xE0142233.toInt())
            setPadding(inset + (u * 2.4f).toInt(), inset + (u * 2f).toInt(), inset + (u * 2.4f).toInt(), (inset * 1.4f + u * 2f).toInt())
        }
        scroll.addView(overlayBody)
        val panelWidth = min((u * 56).toInt(), resources.displayMetrics.widthPixels - dp(24))
        overlay.addView(scroll, FrameLayout.LayoutParams(panelWidth, WRAP, Gravity.CENTER))

        fun text(value: String, size: Float, face: Typeface = regular, color: Int = WHITE, top: Float = 0f) =
            TextView(this).apply {
                text = value; typeface = face; setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                setPadding(0, (u * top).toInt(), 0, 0)
            }.also { overlayBody.addView(it) }

        text(if (setup) "¿Dónde quieres ver el clima?" else "Reloj y clima", u * 2.6f, medium)
        text(if (setup) "Elige una opción para mostrar el clima real de tu zona. Puedes cambiarla cuando quieras."
            else "Ajustes de esta pantalla. Se guardan en la tablet.", u * 1.5f, regular, 0xB3FFFFFF.toInt(), 0.6f)

        // Ubicación
        text("UBICACIÓN", u * 1.3f, medium, 0x99FFFFFF.toInt(), 2.4f).letterSpacing = 0.15f
        val current = when (settings.locationMode) {
            "auto" -> "Automática (GPS de la tablet)" + (settings.placeName?.let { " · $it" } ?: "")
            "manual" -> "Ciudad elegida: ${settings.placeName}"
            else -> "Sin configurar"
        }
        text(current, u * 1.7f, regular, WHITE, 0.6f)
        overlayBody.addView(button("Usar mi ubicación (GPS)", u) { useGps() }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (u * 1.2f).toInt() })

        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val field = EditText(this).apply {
            hint = "Buscar ciudad (ej.: San Salvador)"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.7f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine()
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val search = { searchCity(field, results) }
        field.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false
        }
        searchRow.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        searchRow.addView(button("Buscar", u) { search() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = (u * 1f).toInt() })
        overlayBody.addView(searchRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (u * 1f).toInt() })
        overlayBody.addView(results)

        // Hora
        text("HORA", u * 1.3f, medium, 0x99FFFFFF.toInt(), 2.4f).letterSpacing = 0.15f
        overlayBody.addView(toggle("Formato de 24 horas", settings.use24h, u) { settings.use24h = it; lastMinute = -1; updateClock() })
        overlayBody.addView(toggle("Mostrar segundos", settings.showSeconds, u) { settings.showSeconds = it; lastMinute = -1; updateClock() })

        // Inicio
        text("INICIO", u * 1.3f, medium, 0x99FFFFFF.toInt(), 2.4f).letterSpacing = 0.15f
        overlayBody.addView(toggle("Mostrar el botón «Reloj y clima» en el inicio", settings.enabled, u) { settings.enabled = it })

        overlayBody.addView(button("Listo", u, primary = true) {
            if (settings.locationMode == null) {
                field.requestFocus()
                field.error = "Busca una ciudad o usa el GPS"
            } else hideSettings()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (u * 2.4f).toInt() })
        if (setup) {
            overlayBody.addView(button("Volver al inicio", u) { finish() },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (u * 1f).toInt() })
        }
    }

    private fun hideSettings() {
        hideKeyboard()
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        showPlace()
        updateUpdatedLabel()
    }

    private fun useGps() {
        settings.locationMode = "auto"
        settings.placeName = null
        settings.lastReading = null
        reading = null
        showNoWeather()
        hideSettings()
        refreshWeather()
    }

    private fun searchCity(field: EditText, results: LinearLayout) {
        val query = field.text.toString()
        if (query.isBlank()) return
        hideKeyboard()
        results.removeAllViews()
        val u = unit
        val status = TextView(this).apply {
            text = "Buscando…"
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.5f)
            setPadding(0, (u * 0.8f).toInt(), 0, 0)
        }
        results.addView(status)
        Thread {
            val cities = ClockWeatherApi.searchCities(query)
            handler.post {
                if (overlay.visibility != View.VISIBLE) return@post
                results.removeAllViews()
                if (cities.isEmpty()) {
                    status.text = "No se encontró la ciudad o no hay internet. Intenta con otro nombre."
                    results.addView(status)
                }
                cities.forEach { city ->
                    results.addView(TextView(this).apply {
                        text = city.name + if (city.detail.isNotBlank()) "  ·  ${city.detail}" else ""
                        setTextColor(WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.6f)
                        minHeight = dp(48)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding((u * 1.2f).toInt(), 0, (u * 1.2f).toInt(), 0)
                        background = GradientDrawable().apply { cornerRadius = u; setColor(0x1AFFFFFF) }
                        setOnClickListener { chooseCity(city) }
                    }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = (u * 0.6f).toInt() })
                }
            }
        }.start()
    }

    private fun chooseCity(city: City) {
        stopLocationRequest()
        settings.useCity(city)
        reading = null
        lastFailed = false
        showNoWeather()
        hideSettings()
        refreshWeather()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun button(label: String, u: Float, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(WHITE)
        typeface = medium
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.6f)
        minHeight = dp(48)
        setPadding((u * 2f).toInt(), (u * 0.8f).toInt(), (u * 2f).toInt(), (u * 0.8f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = u * 3f
            setColor(if (primary) 0xFF2F80ED.toInt() else 0x2EFFFFFF)
            setStroke(1, 0x55FFFFFF)
        }
        setOnClickListener { onClick() }
    }

    private fun toggle(label: String, checked: Boolean, u: Float, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u * 1.7f)
        isChecked = checked
        minHeight = dp(52)
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Clima nuevo cada 5 minutos mientras la pantalla está abierta. */
        private const val REFRESH_MS = 5 * 60 * 1000L
        private const val REQUEST_LOCATION = 7
        private const val ACTION_DEBUG_WEATHER = "com.autosense.launcher.DEBUG_WEATHER"
        /** Duración de la animación de entrada (los paneles empiezan a flotar después). */
        private const val ENTRANCE_S = 1.3f
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
