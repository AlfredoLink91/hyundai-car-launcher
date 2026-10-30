package com.autosense.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Clima sin depender del celular: cuando la tablet tiene internet (Wi-Fi de casa u hotspot)
 * descarga el pronóstico por hora de 3 días de Open-Meteo y lo guarda. En el auto, sin
 * internet, se muestra el clima de la hora actual desde ese pronóstico guardado.
 */
class ForecastWeather(private val context: Context) {

    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                try {
                    if (hasInternet()) download()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo descargar el pronóstico: ${e.message}")
                }
                Thread.sleep(if (hasForecast()) REFRESH_MS else RETRY_MS)
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    /**
     * Clima de ahora: el "actual" medido si es reciente (hay internet); si no, el de la hora
     * desde el pronóstico guardado. Null si no hay nada guardado.
     */
    fun current(): WeatherNow? {
        val body = prefs.getString(KEY_FORECAST, null) ?: return null
        return try {
            val json = JSONObject(body)
            val place = prefs.getString(KEY_PLACE, null)
            json.optJSONObject("current")?.let { now ->
                val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).parse(now.getString("time"))
                if (time != null && System.currentTimeMillis() - time.time < CURRENT_MAX_AGE_MS) {
                    return WeatherNow(
                        temp = now.getDouble("temperature_2m").roundToInt(),
                        feelsLike = now.optDouble("apparent_temperature").takeIf { !it.isNaN() }?.roundToInt(),
                        code = correctedCode(now.getInt("weather_code"), now.optDouble("precipitation", 0.0)),
                        place = place,
                        isDay = if (now.has("is_day")) now.optInt("is_day") == 1 else isDaytimeNow()
                    )
                }
            }
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val feels = hourly.optJSONArray("apparent_temperature")
            val codes = hourly.getJSONArray("weather_code")
            val rain = hourly.optJSONArray("precipitation")
            val day = hourly.optJSONArray("is_day")
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(Date())
            for (i in 0 until times.length()) {
                if (times.getString(i) == now) {
                    return WeatherNow(
                        temp = temps.getDouble(i).roundToInt(),
                        feelsLike = feels?.optDouble(i)?.takeIf { !it.isNaN() }?.roundToInt(),
                        code = correctedCode(codes.getInt(i), rain?.optDouble(i, 0.0) ?: 0.0),
                        place = place,
                        isDay = day?.optInt(i, -1)?.takeIf { it >= 0 }?.let { it == 1 } ?: isDaytimeNow()
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // El modelo a veces marca llovizna o lluvia que en la calle no se siente: llovizna de menos
    // de 0.3 mm o lluvia de menos de 0.1 mm se muestran como poco nublado
    private fun correctedCode(code: Int, precipitationMm: Double): Int {
        val drizzle = code in 51..57
        val rain = code in 61..67 || code in 80..82
        return when {
            drizzle && precipitationMm < 0.3 -> 2
            rain && precipitationMm < 0.1 -> 2
            else -> code
        }
    }

    private fun hasForecast() = current() != null

    // Respaldo si el pronóstico guardado no trae día/noche: de 6:00 a 17:59 es de día
    private fun isDaytimeNow(): Boolean = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) in 6..17

    // Nombre de la ciudad o colonia para mostrar junto al clima (necesita internet; se guarda)
    private fun savePlaceName(lat: Double, lon: Double) {
        try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale("es")).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return
            val place = address.locality ?: address.subAdminArea ?: address.adminArea ?: return
            prefs.edit().putString(KEY_PLACE, place).apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener el nombre del lugar: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun download() {
        val (lat, lon) = location()
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,weather_code,precipitation,is_day" +
                    "&hourly=temperature_2m,apparent_temperature,weather_code,precipitation,is_day&forecast_days=3&timezone=auto"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        JSONObject(body).getJSONObject("hourly") // valida antes de guardar
        prefs.edit().putString(KEY_FORECAST, body).apply()
        savePlaceName(lat, lon)
        Log.i(TAG, "Pronóstico guardado para $lat,$lon")
    }

    @SuppressLint("MissingPermission")
    private fun location(): Pair<Double, Double> {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best: Location? = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }
                .maxByOrNull { it.time }
            if (best != null) {
                prefs.edit()
                    .putString(KEY_LAT, best.latitude.toString())
                    .putString(KEY_LON, best.longitude.toString())
                    .apply()
                return Pair(best.latitude, best.longitude)
            }
        }
        // Última ubicación conocida o, si nunca hubo, Ciudad de México
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: 19.43
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: -99.13
        return Pair(lat, lon)
    }

    companion object {
        private const val TAG = "AutoSenseForecast"
        private const val KEY_FORECAST = "forecast"
        private const val KEY_LAT = "lat"
        private const val KEY_LON = "lon"
        private const val KEY_PLACE = "place"
        private const val REFRESH_MS = 15 * 60 * 1000L
        private const val CURRENT_MAX_AGE_MS = 75 * 60 * 1000L
        private const val RETRY_MS = 60 * 1000L

        // Ícono a color para cada tipo de clima; de noche, luna en lugar de sol
        fun icon(code: Int, isDay: Boolean = true): Int = when (code) {
            0, 1 -> if (isDay) R.drawable.ic_wx_sun else R.drawable.ic_wx_moon
            2 -> if (isDay) R.drawable.ic_wx_partly else R.drawable.ic_wx_moon_partly
            3 -> R.drawable.ic_wx_cloud
            45, 48 -> R.drawable.ic_wx_fog
            95, 96, 99 -> R.drawable.ic_wx_storm
            in 51..86 -> R.drawable.ic_wx_rain
            else -> R.drawable.ic_wx_cloud
        }

        fun describe(code: Int, isDay: Boolean = true): String = when (code) {
            0 -> "Despejado"
            1 -> if (isDay) "Mayormente soleado" else "Mayormente despejado"
            2 -> "Poco nublado"
            3 -> "Nublado"
            45, 48 -> "Niebla"
            51, 53, 55, 56, 57 -> "Llovizna"
            61, 63, 65, 66, 67, 80, 81, 82 -> "Lluvia"
            71, 73, 75, 77, 85, 86 -> "Nieve"
            95, 96, 99 -> "Tormenta"
            else -> "Sin datos"
        }
    }
}

data class WeatherNow(val temp: Int, val feelsLike: Int?, val code: Int, val place: String?, val isDay: Boolean = true)
