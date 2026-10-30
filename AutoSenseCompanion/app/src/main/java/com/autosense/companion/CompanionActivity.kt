package com.autosense.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class CompanionActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_companion)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)

        toggle.setOnClickListener {
            if (serviceRunning) {
                stopService(Intent(this, WeatherService::class.java))
                serviceRunning = false
                render()
            } else {
                requestNeededPermissions()
            }
        }
        render()
    }

    private fun requestNeededPermissions() {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter {
            // Estos permisos existen desde Android 12 (S) o 13 (Tiramisu); en versiones
            // anteriores no se piden en tiempo de ejecución.
            when (it) {
                Manifest.permission.BLUETOOTH_CONNECT -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                Manifest.permission.POST_NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                else -> true
            } && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startWeatherService()
        } else {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            // Se arranca aunque el usuario niegue la ubicación: el servicio usa una ciudad por defecto
            startWeatherService()
        }
    }

    private fun startWeatherService() {
        val intent = Intent(this, WeatherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serviceRunning = true
        render()
    }

    private fun render() {
        status.text = if (serviceRunning) {
            "Servicio activo.\nEmpareja este celular con la tablet en Bluetooth."
        } else {
            "Servicio detenido"
        }
        toggle.text = if (serviceRunning) "Detener" else "Iniciar"
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 10
    }
}
