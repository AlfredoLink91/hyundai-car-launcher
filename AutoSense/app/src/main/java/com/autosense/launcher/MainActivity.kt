package com.autosense.launcher

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/** Pantalla de inicio de AutoSense (launcher). La lógica del tablero vive en [Dashboard]. */
class MainActivity : Activity() {

    private lateinit var dashboard: Dashboard
    private val powerListener: (Boolean) -> Unit = { plugged -> runOnUiThread { keepScreenOn(plugged) } }

    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se enciende sobre la pantalla de bloqueo cuando el carro da corriente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        CarPower.register(this)
        CarPower.listeners.add(powerListener)
        SplitScreen.init(this)
        setContentView(R.layout.activity_main)

        dashboard = Dashboard(this, window.decorView) { tab -> openTab(tab) }
        dashboard.start()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            dashboard.startGps()
        }
    }

    override fun onResume() {
        super.onResume()
        dismissSwipeLock()
        // La pantalla se mantiene encendida solo mientras el carro da corriente
        keepScreenOn(CarPower.isPlugged(this))
        // De vuelta en inicio: se quita el panel de manejo y la pestaña activa regresa a Home
        DriveOverlayService.stop(this)
        // Si quedó la pantalla dividida (Waze minimizado arriba), se quita para que las apps abran completas
        if (SplitScreen.isActive) {
            SplitScreen.exitThen { AppLauncher.safeStart(applicationContext, Intent(applicationContext, MainActivity::class.java)) }
        }
        dashboard.selectTab(R.id.tab_home)
        dashboard.resume()
        // El auto del Home siempre vuelve a su orientación original
        (findViewById<android.view.View>(R.id.car) as? CarTurntableView)?.resetOrientation()
    }

    // Quita el bloqueo de "deslizar" para entrar directo al inicio (al encender o al reiniciar).
    // Si el bloqueo es seguro (PIN, patrón o contraseña) no se toca: Android lo sigue pidiendo.
    private fun dismissSwipeLock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked && !km.isKeyguardSecure) {
            km.requestDismissKeyguard(this, null)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        dismissSwipeLock()
    }

    override fun onPause() {
        super.onPause()
        dashboard.pause()
    }

    override fun onDestroy() {
        CarPower.listeners.remove(powerListener)
        dashboard.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Pantalla completa: oculta barra de estado y de navegación
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun openTab(tab: Int) {
        dashboard.selectTab(tab)
        when (tab) {
            R.id.tab_home -> Unit
            // Modo manejo: Waze a pantalla completa con el panel de AutoSense encima
            R.id.tab_mapa -> startDrive(AppLauncher.WAZE)
            R.id.nav_maps -> startDrive(AppLauncher.MAPS)
            else -> AppLauncher.openForTab(this, tab)
        }
    }

    // Abre la app de navegación a pantalla completa y pone el panel de AutoSense encima
    private fun startDrive(navPackage: String) {
        // Con el permiso de accesibilidad: pantalla dividida, mapa y tablero lado a lado.
        // Sin él: el mapa a pantalla completa con las ventanas flotantes encima.
        if (SplitScreen.available) {
            SplitScreen.start(this, navPackage)
        } else if (AppLauncher.openApp(this, navPackage) && Settings.canDrawOverlays(this)) {
            DriveOverlayService.start(this, navPackage)
        }
    }

    companion object {
        private const val REQUEST_LOCATION = 1
        const val EXTRA_FROM_CAR_ON = "from_car_on"
    }
}
