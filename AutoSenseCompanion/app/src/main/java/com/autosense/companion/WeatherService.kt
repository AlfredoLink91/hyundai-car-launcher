package com.autosense.companion

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Espera a que la tablet se conecte por Bluetooth (SPP) y le manda el clima
 * una línea JSON por minuto: {"temp":23,"desc":"Despejado"}
 * El clima sale de Open-Meteo (gratis, sin llave) usando la ubicación del celular.
 */
class WeatherService : Service() {

    @Volatile
    private var running = false
    private var serverSocket: BluetoothServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            Thread { acceptLoop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            Log.i(TAG, "Esperando a la tablet por Bluetooth")
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir el servidor Bluetooth", e)
            return
        }
        while (running) {
            try {
                val client = serverSocket?.accept() ?: break
                Log.i(TAG, "Tablet conectada: ${client.remoteDevice.address}")
                // Cada conexión en su propio hilo: si la tablet se reconecta, no espera a que muera la anterior
                Thread {
                    try {
                        handleClient(client)
                    } catch (e: Exception) {
                        Log.w(TAG, "Conexion con la tablet terminada: ${e.message}")
                    }
                }.start()
            } catch (e: Exception) {
                Log.w(TAG, "Conexion con la tablet terminada", e)
                // Si el socket se cerró, el ciclo termina
            }
        }
    }

    private fun handleClient(client: BluetoothSocket) {
        client.use { socket ->
            val out = socket.outputStream
            var cachedLine = ""
            var cachedAt = 0L
            while (running) {
                // Se consulta el clima cada 10 minutos y se reenvía cada minuto
                if (System.currentTimeMillis() - cachedAt > REFRESH_MS || cachedLine.isEmpty()) {
                    cachedLine = fetchWeatherLine() ?: cachedLine
                    cachedAt = System.currentTimeMillis()
                }
                if (cachedLine.isNotEmpty()) {
                    out.write((cachedLine + "\n").toByteArray())
                    out.flush()
                    Log.i(TAG, "Clima enviado: $cachedLine")
                }
                Thread.sleep(SEND_MS)
            }
        }
    }

    private fun fetchWeatherLine(): String? {
        return try {
            val (lat, lon) = currentLocation()
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val current = JSONObject(body).getJSONObject("current")
            val temp = current.getDouble("temperature_2m").roundToInt()
            val desc = describe(current.getInt("weather_code"))
            JSONObject().put("temp", temp).put("desc", desc).toString()
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo obtener el clima", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(): Pair<Double, Double> {
        val granted = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            val best: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }
                .maxByOrNull { it.time }
            if (best != null) return Pair(best.latitude, best.longitude)
        }
        // Sin ubicación: Ciudad de México como respaldo
        return Pair(19.43, -99.13)
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "Despejado"
        1, 2 -> "Poco nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        51, 53, 55, 56, 57 -> "Llovizna"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Lluvia"
        71, 73, 75, 77, 85, 86 -> "Nieve"
        95, 96, 99 -> "Tormenta"
        else -> "Sin datos"
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Clima para AutoSense", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AutoSense Companion")
            .setContentText("Enviando clima a la tablet")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "weather"
        private const val TAG = "AutoSenseCompanion"
        private const val NOTIFICATION_ID = 1
        private const val SERVICE_NAME = "AutoSenseWeather"
        // Mismo UUID que usa la tablet
        private val SPP_UUID: UUID = UUID.fromString("7d2f1a3e-5b6c-4e8f-9a1b-2c3d4e5f6a7b")
        private const val REFRESH_MS = 10 * 60 * 1000L
        private const val SEND_MS = 60 * 1000L
    }
}
