package com.autosense.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad de Accent: activa la pantalla dividida de Android y da los botones
 * de navegación flotantes (atrás, inicio, recientes). Solo detecta qué app está al frente; no lee su contenido.
 */
class SplitScreenService : AccessibilityService() {
    private var navPill: NavPill? = null

    override fun onServiceConnected() {
        SplitScreen.service = this
        SplitScreen.init(this)
        navPill = NavPill(this)
        // El servicio siempre está activo: aquí se escucha cuando el carro da o quita corriente
        CarPower.register(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        SplitScreen.service = null
        navPill?.destroy()
        navPill = null
        return super.onUnbind(intent)
    }

    // Solo se usa el nombre de la app que pasa al frente, para mostrar u ocultar los botones de navegación
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { navPill?.onForegroundApp(it, event.className?.toString().orEmpty()) }
        }
    }
    override fun onInterrupt() {}
}

/** Abre la app de navegación y el tablero lado a lado, como ventanas acopladas. */
object SplitScreen {

    var service: SplitScreenService? = null
    var navPackage = AppLauncher.WAZE
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var prefs: android.content.SharedPreferences? = null

    // Si la pantalla está dividida. Se guarda para que sobreviva si el proceso de la app se reinicia
    // (por ejemplo al actualizarla) mientras la división sigue en pantalla; se descarta tras reiniciar la tablet.
    private var active = false
        set(value) {
            field = value
            prefs?.edit()?.putBoolean(KEY_ACTIVE, value)?.putLong(KEY_BOOT, bootTime())?.apply()
        }

    private fun bootTime() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("split_screen", Context.MODE_PRIVATE)
        prefs = p
        val sameBoot = kotlin.math.abs(p.getLong(KEY_BOOT, 0) - bootTime()) < 10_000
        active = p.getBoolean(KEY_ACTIVE, false) && sameBoot
    }

    val available: Boolean get() = service != null

    fun start(context: Context, nav: String) {
        val accessibility = service ?: return
        navPackage = nav
        if (!AppLauncher.openApp(context, nav)) return
        // 1) La app de navegación pasa a la mitad izquierda
        handler.postDelayed({
            accessibility.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            active = true
            // 2) El tablero ocupa la otra mitad
            handler.postDelayed({
                // Una tarea nueva normal: con la pantalla ya dividida, Android la pone en la mitad libre
                val dock = Intent(context, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AppLauncher.safeStart(context, dock)
                // 3) El foco vuelve al mapa para que cargue de inmediato
                handler.postDelayed({ AppLauncher.openApp(context, nav) }, FOCUS_MAP_DELAY_MS)
                // 4) La división se mueve a ⅓: el mapa queda con proporción de celular parado
                handler.postDelayed({ dragDividerToThird(context) }, FOCUS_MAP_DELAY_MS + DIVIDER_DELAY_MS)
            }, OPEN_DOCK_DELAY_MS)
        }, SPLIT_DELAY_MS)
    }

    // Arrastra la rayita del medio hasta un tercio de la pantalla (Android la ajusta a ⅓)
    private fun dragDividerToThird(context: Context) {
        val accessibility = service ?: return
        val metrics = context.resources.displayMetrics
        val y = metrics.heightPixels / 2f
        val path = android.graphics.Path().apply {
            moveTo(metrics.widthPixels / 2f, y)
            lineTo(metrics.widthPixels / 3f, y)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 450))
            .build()
        val sent = accessibility.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { android.util.Log.i("AutoSenseSplit", "División movida a ⅓") }
            override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { android.util.Log.w("AutoSenseSplit", "Gesto cancelado") }
        }, null)
        android.util.Log.i("AutoSenseSplit", "Gesto enviado: $sent")
    }

    val isActive: Boolean get() = active

    /** Quita la división. Devuelve false si el servicio aún no está listo (no se pudo quitar). */
    fun exit(): Boolean {
        if (!active) return true
        val s = service ?: return false
        s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        active = false
        return true
    }

    /**
     * Quita la pantalla dividida y luego hace [then] (abrir otra app a pantalla completa).
     * Si el servicio de accesibilidad todavía se está conectando (p. ej. justo después de
     * actualizar la app), reintenta unos segundos en lugar de dar la división por quitada.
     */
    fun exitThen(attempts: Int = 6, then: () -> Unit) {
        if (!active) {
            then()
            return
        }
        if (exit()) {
            handler.postDelayed(then, EXIT_DELAY_MS)
        } else if (attempts > 0) {
            handler.postDelayed({ exitThen(attempts - 1, then) }, 1_000)
        } else {
            then()
        }
    }

    private const val KEY_ACTIVE = "active"
    private const val KEY_BOOT = "boot"
    private const val SPLIT_DELAY_MS = 2500L
    private const val OPEN_DOCK_DELAY_MS = 1500L
    private const val FOCUS_MAP_DELAY_MS = 1500L
    private const val DIVIDER_DELAY_MS = 1200L
    private const val EXIT_DELAY_MS = 800L
}
