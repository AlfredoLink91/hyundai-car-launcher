package com.autosense.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Tablero de conducción. A diferencia de la pantalla de inicio, esta sí puede ir en pantalla
 * dividida: Waze o Maps en una parte y este tablero en la otra. Al abrirse, el auto gira para
 * quedar de espaldas (avanzando) sobre una carretera animada que sigue la velocidad del GPS.
 * El Home usa su propia pantalla (MainActivity), así que al volver no queda nada de este modo.
 */
class DockActivity : Activity() {

    private lateinit var dashboard: Dashboard
    private var sizeClass = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_dock)
        dashboard = Dashboard(this, window.decorView) { tab -> openTab(tab) }
        dashboard.start()
        hideSystemBars()

        // El diseño se adapta al ancho que le toque en la división (⅓, ½ o ⅔ de la pantalla)
        findViewById<View>(R.id.dock_root).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applyWidth(v.width / resources.displayMetrics.density)
        }
        enterDriveMode()
        setupSliderFade()
    }

    // Brillo y volumen: casi transparentes mientras no se usan (como los botones ◁ ○ ▢);
    // al tocarlos se ven normales y, al soltarlos, vuelven a desvanecerse
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSliderFade() {
        listOf(R.id.brightness_column to R.id.brightness_slider, R.id.volume_column to R.id.volume_slider)
            .forEach { (columnId, sliderId) ->
                val column = findViewById<View>(columnId)
                val fadeOut = Runnable { column.animate().alpha(SLIDER_IDLE_ALPHA).setDuration(600).start() }
                column.alpha = SLIDER_IDLE_ALPHA
                findViewById<View>(sliderId).setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            column.removeCallbacks(fadeOut)
                            column.animate().alpha(1f).setDuration(150).start()
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            column.postDelayed(fadeOut, SLIDER_AWAKE_MS)
                    }
                    false
                }
            }
    }

    // Transición de entrada: aparece la carretera y el auto gira hasta quedar mirando hacia adelante
    private fun enterDriveMode() {
        findViewById<View>(R.id.road).animate().alpha(1f).setDuration(700).setStartDelay(200).start()
        findViewById<View>(R.id.car).postDelayed({
            (findViewById<View>(R.id.car) as? CarTurntableView)?.startDriving()
        }, 350)
    }

    private fun applyWidth(widthDp: Float) {
        val newClass = when {
            widthDp >= 760 -> WIDE
            widthDp >= 560 -> MEDIUM
            else -> NARROW
        }
        if (newClass == sizeClass) return
        sizeClass = newClass
        val narrow = newClass == NARROW
        val wide = newClass == WIDE

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        fun text(id: Int, sp: Float) = findViewById<TextView>(id)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        fun visible(id: Int, show: Boolean) { findViewById<View>(id)?.visibility = if (show) View.VISIBLE else View.GONE }

        // Encabezado: saludo e iconos siempre visibles; el detalle se reduce en espacios chicos
        text(R.id.greeting, if (narrow) 13f else if (wide) 18f else 15f)
        text(R.id.clock, if (narrow) 16f else 20f)
        visible(R.id.date, !narrow)
        visible(R.id.weather_detail, wide)
        // Angosto: el saludo completo tiene prioridad sobre la temperatura
        visible(R.id.weather_icon, !narrow)
        visible(R.id.weather, !narrow)
        findViewById<View>(R.id.dock_logo).layoutParams.width = dp(if (narrow) 100 else 140)

        // Columnas de brillo y volumen
        listOf(R.id.brightness_column, R.id.volume_column).forEach {
            findViewById<View>(it).layoutParams.width = dp(if (narrow) 38 else 44)
        }

        // Música: angosta y sin portada ni barra de tiempo cuando falta espacio
        findViewById<View>(R.id.music_card).layoutParams.width = dp(if (wide) 190 else if (narrow) 128 else 160)
        visible(R.id.album_art, !narrow)
        visible(R.id.track_seek, !narrow)
        visible(R.id.track_times, !narrow)
        visible(R.id.music_label, !narrow)
        findViewById<LyricsView>(R.id.lyrics)?.fits = !narrow
        // Sin portada, los controles quedan centrados en la tarjeta en vez de arriba
        (findViewById<View>(R.id.music_card) as? android.widget.LinearLayout)?.gravity =
            if (narrow) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.TOP

        // Pestañas: letra más chica si son angostas
        Dashboard.TAB_IDS.forEach { text(it, if (narrow) 11f else 14f) }

        findViewById<View>(R.id.dock_root).requestLayout()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Oculta la barra de estado y la de botones para que la pantalla dividida use todo el alto
    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // En pantalla dividida la ventana sin foco queda en pausa, pero sigue visible:
    // el tablero se actualiza mientras se vea (onStart/onStop), no solo con foco
    override fun onStart() {
        super.onStart()
        dashboard.selectTab(R.id.tab_mapa)
        dashboard.resume()
    }

    override fun onStop() {
        super.onStop()
        dashboard.pause()
    }

    override fun onDestroy() {
        dashboard.stop()
        super.onDestroy()
    }

    private fun openTab(tab: Int) {
        when (tab) {
            // El mapa ya está en la otra parte; solo se trae al frente
            R.id.tab_mapa -> AppLauncher.openApp(this, SplitScreen.navPackage)
            // Las demás pantallas se abren completas: primero se quita la división
            R.id.tab_home -> {
                finish()
                SplitScreen.exitThen { AppLauncher.safeStart(applicationContext, Intent(applicationContext, MainActivity::class.java)) }
            }
            else -> {
                finish()
                SplitScreen.exitThen { AppLauncher.openForTab(applicationContext, tab) }
            }
        }
    }

    companion object {
        private const val NARROW = 0
        private const val MEDIUM = 1
        private const val WIDE = 2
        private const val SLIDER_IDLE_ALPHA = 0.3f
        private const val SLIDER_AWAKE_MS = 2_500L
    }
}
