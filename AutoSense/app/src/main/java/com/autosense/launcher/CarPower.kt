package com.autosense.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * Encendido automático con el carro. El cargador del carro da corriente al dar arranque:
 * - Al conectar: enciende la pantalla, abre el inicio y la deja encendida mientras haya corriente.
 * - Al desconectar (carro apagado): pausa la música y la pantalla se apaga sola en [OFF_TIMEOUT_MS].
 * Se registra desde el servicio de accesibilidad (siempre activo) y desde la pantalla de inicio.
 */
object CarPower {

    private const val TAG = "AutoSensePower"
    private const val ON_TIMEOUT_MS = 30 * 60 * 1000   // con corriente: 30 min (además la pantalla de inicio la mantiene encendida)
    private const val OFF_TIMEOUT_MS = 60 * 1000       // sin corriente: se apaga al minuto

    /** Avisa a las pantallas abiertas si hay corriente o no. */
    val listeners = mutableSetOf<(Boolean) -> Unit>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> onCarOn(context)
                Intent.ACTION_POWER_DISCONNECTED -> onCarOff(context)
            }
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        context.applicationContext.registerReceiver(
            carBluetoothReceiver, IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        )
        setScreenTimeout(context, if (isPlugged(context)) ON_TIMEOUT_MS else OFF_TIMEOUT_MS)
    }

    fun isPlugged(context: Context): Boolean {
        val status = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun onCarOn(context: Context) {
        Log.i(TAG, "Corriente conectada: encendiendo")
        setScreenTimeout(context, ON_TIMEOUT_MS)
        wakeAndShowHome(context)
        listeners.toList().forEach { it(true) }
        connectCarAudio(context.applicationContext)
    }

    /**
     * Enciende la pantalla y abre el inicio. El inicio se muestra encima del bloqueo y,
     * si el bloqueo es solo "deslizar" (sin PIN, patrón ni contraseña), lo descarta.
     */
    fun wakeAndShowHome(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "AutoSense:carOn"
        ).acquire(5_000)
        val home = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_FROM_CAR_ON, true)
        AppLauncher.safeStart(context.applicationContext, home)
    }

    // Al conectarse el Bluetooth de audio del carro (A2DP) también se enciende la pantalla
    private val carBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Bluetooth de audio conectado: encendiendo pantalla")
                wakeAndShowHome(context)
            }
        }
    }

    // --- Bluetooth del carro y música ---

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resumePending = false

    private val a2dpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1)
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Bluetooth del carro conectado")
                // Un momento para que la radio del carro tome el audio
                handler.postDelayed({ resumeMusic(context) }, 2_000)
            }
        }
    }

    /**
     * Enciende el Bluetooth (Android se reconecta solo a la radio del carro ya emparejada)
     * y, si la música se pausó al apagar el carro, la reanuda cuando el audio esté listo:
     * al conectarse el Bluetooth o, si se usa cable AUX, a los [RESUME_FALLBACK_MS].
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun connectCarAudio(context: Context) {
        try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                Log.i(TAG, "Encendiendo Bluetooth")
                adapter.enable()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo encender el Bluetooth: ${e.message}")
        }
        val prefs = context.getSharedPreferences("car_power", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PAUSED_BY_CAR, false)) return
        resumePending = true
        context.registerReceiver(a2dpReceiver, IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"))
        handler.postDelayed({ resumeMusic(context) }, RESUME_FALLBACK_MS)
    }

    private fun resumeMusic(context: Context) {
        if (!resumePending) return
        resumePending = false
        try { context.unregisterReceiver(a2dpReceiver) } catch (e: Exception) { }
        handler.removeCallbacksAndMessages(null)
        context.getSharedPreferences("car_power", Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED_BY_CAR, false).apply()
        Log.i(TAG, "Reanudando la música")
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private fun sendMediaKey(context: Context, key: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private const val KEY_PAUSED_BY_CAR = "paused_by_car"
    private const val RESUME_FALLBACK_MS = 15_000L

    private fun onCarOff(context: Context) {
        Log.i(TAG, "Corriente desconectada: la pantalla se apagará sola")
        // Pausa la música para que no siga sonando con el carro apagado; si estaba sonando,
        // se recuerda para reanudarla al volver a arrancar
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val wasPlaying = audio.isMusicActive
        context.getSharedPreferences("car_power", Context.MODE_PRIVATE).edit().putBoolean(KEY_PAUSED_BY_CAR, wasPlaying).apply()
        resumePending = false
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        DriveOverlayService.stop(context)
        setScreenTimeout(context, OFF_TIMEOUT_MS)
        listeners.toList().forEach { it(false) }
    }

    private fun setScreenTimeout(context: Context, ms: Int) {
        if (!Settings.System.canWrite(context)) return
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cambiar el tiempo de pantalla: ${e.message}")
        }
    }
}
