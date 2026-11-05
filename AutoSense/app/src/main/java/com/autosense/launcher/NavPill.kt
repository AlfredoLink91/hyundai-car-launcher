package com.autosense.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Reemplazo de la barra de botones de Android (oculta para usar la pantalla completa).
 * Una barra pequeña y traslúcida ◁ ○ ▢ siempre visible en cualquier app, incluida la pantalla
 * dividida; solo se oculta en el inicio de Hyundai. Flota encima: no le quita espacio a las apps.
 */
class NavPill(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var pill: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var splitLayout = false

    // Ventana invisible que obliga a toda la tablet a quedarse en horizontal, aunque una app
    // (como SmartTube al reproducir) pida vertical. Así la barra y la pantalla completa no se mueven.
    private val orientationLock: View = View(service).also { view ->
        val p = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try { wm.addView(view, p) } catch (e: Exception) { Log.e(TAG, "No se pudo fijar la orientación", e) }
    }

    /**
     * Se llama cada vez que una ventana pasa al frente. Solo las pantallas reales cambian la barra:
     * se ignoran System UI, el teclado, diálogos del sistema y las ventanas flotantes propias,
     * para que la barra no desaparezca por accidente.
     */
    fun onForegroundApp(pkg: String, className: String) {
        if (pkg == "com.android.systemui" || pkg == "android" || pkg.contains("inputmethod")) return
        if (pkg == service.packageName) {
            when {
                // Inicio y Reloj y clima (pantalla inmersiva con su propio botón para volver)
                className.endsWith(".MainActivity") || className.endsWith(".ClockWeatherActivity") -> hide()
                className.endsWith(".DockActivity") -> show(split = true)
                className.endsWith("Activity") -> show(split = SplitScreen.isActive)
                // Diálogos y ventanas propias (menú de apagado, ventanas flotantes): no cambian nada
            }
            return
        }
        show(split = SplitScreen.isActive)
    }

    private fun show(split: Boolean) {
        if (pill == null) create()
        if (split != splitLayout || params?.x == UNSET) {
            splitLayout = split
            position()
        }
    }

    private fun hide() {
        pill?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        pill = null
        params = null
    }

    private val prefs = service.getSharedPreferences("nav_pill", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { pill?.animate()?.alpha(IDLE_ALPHA)?.setDuration(600)?.start() }

    // Se enciende al tocarla y, si no se usa, vuelve a quedar casi transparente
    private fun lightUp() {
        handler.removeCallbacks(fade)
        pill?.animate()?.alpha(ACTIVE_ALPHA)?.setDuration(150)?.start()
        handler.postDelayed(fade, AWAKE_MS)
    }

    /**
     * Barra que se puede arrastrar con el dedo a cualquier lugar (por si tapa algo de una app).
     * Apagada (casi transparente), el primer toque solo la enciende; encendida, los botones funcionan.
     */
    private inner class PillLayout : LinearLayout(service) {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var wasAsleep = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val p = params ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                    wasAsleep = alpha < (IDLE_ALPHA + ACTIVE_ALPHA) / 2
                    lightUp()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                        dragging = true
                        // Si empezó a arrastrar, el botón bajo el dedo no se presiona
                        if (!wasAsleep) MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }.also {
                            super.dispatchTouchEvent(it)
                            it.recycle()
                        }
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        clamp(p)
                        try { wm.updateViewLayout(this, p) } catch (e: Exception) { }
                        lightUp()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    savePosition(p)
                    return true
                }
            }
            return if (wasAsleep || dragging) true else super.dispatchTouchEvent(ev)
        }
    }

    private fun create() {
        val bar = PillLayout().apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.nav_pill_bg)
            setPadding(dp(6), 0, dp(6), 0)
            alpha = IDLE_ALPHA
        }
        fun button(icon: Int, desc: String, action: Int) = ImageView(service).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = desc
            setOnClickListener { lightUp(); service.performGlobalAction(action) }
        }.also { bar.addView(it, LinearLayout.LayoutParams(dp(PILL_BUTTON_DP), LinearLayout.LayoutParams.MATCH_PARENT)) }
        button(R.drawable.ic_nav_back, "Atrás", AccessibilityService.GLOBAL_ACTION_BACK)
        button(R.drawable.ic_nav_home, "Inicio", AccessibilityService.GLOBAL_ACTION_HOME)
        button(R.drawable.ic_nav_recents, "Apps recientes", AccessibilityService.GLOBAL_ACTION_RECENTS)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, dp(PILL_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = UNSET
        }
        try {
            wm.addView(bar, p)
            pill = bar
            params = p
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo mostrar la barra de navegación", e)
        }
    }

    private val screenW get() = wm.defaultDisplay.mode.let { maxOf(it.physicalWidth, it.physicalHeight) }
    private val screenH get() = wm.defaultDisplay.mode.let { minOf(it.physicalWidth, it.physicalHeight) }
    private val barW get() = dp(PILL_BUTTON_DP * 3 + 12)
    // Con las barras del sistema ocultas (overscan), la medida física ubica la barra al ras de abajo
    private val bottomY get() = screenH - dp(PILL_HEIGHT_DP) - dp(10)

    private fun clamp(p: WindowManager.LayoutParams) {
        p.x = p.x.coerceIn(0, screenW - barW)
        p.y = p.y.coerceIn(0, bottomY)
    }

    // Se recuerda un lugar para pantalla completa y otro para pantalla dividida
    private fun key(axis: String) = axis + if (splitLayout) "_split" else "_full"

    private fun savePosition(p: WindowManager.LayoutParams) {
        prefs.edit().putInt(key("x"), p.x).putInt(key("y"), p.y).apply()
    }

    // Donde el usuario la dejó; si nunca la ha movido: abajo al centro, o en pantalla dividida
    // centrada sobre el mapa (⅓ izquierdo) para no tapar el tablero
    private fun position() {
        val bar = pill ?: return
        val p = params ?: return
        val centerX = if (splitLayout) screenW / 6 else screenW / 2
        p.x = prefs.getInt(key("x"), centerX - barW / 2)
        p.y = prefs.getInt(key("y"), bottomY)
        clamp(p)
        try { wm.updateViewLayout(bar, p) } catch (e: Exception) { }
    }

    fun destroy() {
        try { wm.removeView(orientationLock) } catch (e: Exception) { }
        hide()
    }

    companion object {
        private const val TAG = "AutoSenseNav"
        private const val PILL_BUTTON_DP = 56
        private const val PILL_HEIGHT_DP = 44
        private const val UNSET = Int.MIN_VALUE
        /** Casi transparente mientras no se usa; encendida al tocarla. */
        private const val IDLE_ALPHA = 0.22f
        private const val ACTIVE_ALPHA = 0.95f
        private const val AWAKE_MS = 4_000L
    }
}
