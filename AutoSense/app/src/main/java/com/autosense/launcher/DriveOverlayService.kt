package com.autosense.launcher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Modo manejo: Waze (o Google Maps) a pantalla completa y, encima, ventanas flotantes
 * separadas de la app (estado, velocímetro, música y dock). Cada ventana se arrastra
 * a donde se quiera y recuerda su posición; el resto de la pantalla es el mapa.
 */
class DriveOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private val windows = mutableListOf<View>()
    private val dashboards = mutableListOf<Dashboard>()
    private var navPackage = AppLauncher.WAZE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_NAV_PACKAGE)?.let { navPackage = it }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Servicio en primer plano para que Android no lo cierre mientras se maneja
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("drive_windows", Context.MODE_PRIVATE)

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val d = metrics.density
        // Posiciones iniciales: todo del lado derecho, dejando libre el mapa y los controles de Waze
        addWindow(R.layout.float_status, "status", w - (560 * d).toInt(), (6 * d).toInt())
        addWindow(R.layout.float_speed, "speed", w - (196 * d).toInt(), (90 * d).toInt())
        addWindow(R.layout.float_music, "music", w - (400 * d).toInt(), h - (300 * d).toInt())
        addWindow(R.layout.float_dock, "dock", w - (570 * d).toInt(), h - (150 * d).toInt())
        dashboards.forEach { it.selectTab(R.id.tab_mapa) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addWindow(layout: Int, key: String, defaultX: Int, defaultY: Int) {
        val themed = ContextThemeWrapper(this, R.style.Theme_AutoSense)
        val view = LayoutInflater.from(themed).inflate(layout, null)

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = prefs.getInt("${key}_x", defaultX).coerceAtLeast(0)
            y = prefs.getInt("${key}_y", defaultY).coerceAtLeast(0)
        }

        // Arrastrar desde cualquier parte de la ventana que no sea un botón
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val screen = resources.displayMetrics
                    params.x = (startX + (event.rawX - downX).toInt()).coerceIn(0, screen.widthPixels - view.width)
                    params.y = (startY + (event.rawY - downY).toInt()).coerceIn(0, screen.heightPixels - view.height)
                    windowManager.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) > TAP_SLOP || abs(event.rawY - downY) > TAP_SLOP
                    if (moved) {
                        prefs.edit().putInt("${key}_x", params.x).putInt("${key}_y", params.y).apply()
                    } else if (key == "music") {
                        // Un toque en el reproductor (fuera de los botones) abre Spotify
                        onTab(R.id.tab_musica)
                    }
                }
            }
            true
        }

        windowManager.addView(view, params)
        windows.add(view)
        dashboards.add(Dashboard(this, view) { tab -> onTab(tab) }.also {
            it.start()
            it.resume()
        })
    }

    private fun onTab(tab: Int) {
        when (tab) {
            // Ya estamos en el mapa: solo se asegura que la app de navegación esté al frente
            R.id.tab_mapa -> AppLauncher.openApp(this, navPackage)
            R.id.tab_home -> {
                stopSelf()
                AppLauncher.safeStart(this, Intent(this, MainActivity::class.java))
            }
            else -> {
                stopSelf()
                AppLauncher.openForTab(this, tab)
            }
        }
    }

    override fun onDestroy() {
        dashboards.forEach { it.stop() }
        dashboards.clear()
        windows.forEach { windowManager.removeView(it) }
        windows.clear()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Modo manejo", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Hyundai")
            .setContentText("Modo manejo")
            .setSmallIcon(R.drawable.ic_map)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "drive_mode"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_NAV_PACKAGE = "nav_package"
        // Movimiento mínimo (px) para considerar que se arrastró y no que se tocó
        private const val TAP_SLOP = 12f

        fun start(context: Context, navPackage: String) {
            val intent = Intent(context, DriveOverlayService::class.java).putExtra(EXTRA_NAV_PACKAGE, navPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DriveOverlayService::class.java))
        }
    }
}
