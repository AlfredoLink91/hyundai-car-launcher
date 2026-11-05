package com.autosense.launcher

import android.content.Context
import android.location.Geocoder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Datos y ajustes de la pantalla "Reloj y clima" (ClockWeatherActivity).
 * Todo se guarda en el equipo, así que se conserva al reiniciar la app o la tablet.
 * El clima es real (Open-Meteo, sin cuenta): una sola consulta trae temperatura, sensación
 * térmica, estado del cielo y si es de día o de noche.
 */
class ClockWeatherSettings(context: Context) {

    private val prefs = context.getSharedPreferences("clock_weather", Context.MODE_PRIVATE)

    /** Botón "Reloj y clima" visible en el inicio. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var use24h: Boolean
        get() = prefs.getBoolean("use24h", false)
        set(v) = prefs.edit().putBoolean("use24h", v).apply()

    var showSeconds: Boolean
        get() = prefs.getBoolean("seconds", false)
        set(v) = prefs.edit().putBoolean("seconds", v).apply()

    /** "auto" (GPS de la tablet), "manual" (ciudad elegida) o null si aún no se configura. */
    var locationMode: String?
        get() = prefs.getString("mode", null)
        set(v) = prefs.edit().putString("mode", v).apply()

    /** Coordenadas y nombre del lugar (la ciudad elegida o la última ubicación del GPS). */
    var latitude: Double?
        get() = prefs.getString("lat", null)?.toDoubleOrNull()
        set(v) = prefs.edit().putString("lat", v?.toString()).apply()
    var longitude: Double?
        get() = prefs.getString("lon", null)?.toDoubleOrNull()
        set(v) = prefs.edit().putString("lon", v?.toString()).apply()
    var placeName: String?
        get() = prefs.getString("place", null)
        set(v) = prefs.edit().putString("place", v).apply()

    /** Último clima válido (se muestra si no hay internet). */
    var lastReading: ClockWeatherReading?
        get() = prefs.getString("reading", null)?.let { ClockWeatherReading.fromJson(it) }
        set(v) = prefs.edit().putString("reading", v?.toJson()).apply()

    fun useCity(city: City) {
        prefs.edit()
            .putString("mode", "manual")
            .putString("lat", city.latitude.toString())
            .putString("lon", city.longitude.toString())
            .putString("place", city.name)
            .remove("reading")
            .apply()
    }
}

data class ClockWeatherReading(
    val temperature: Double,
    val feelsLike: Double,
    val code: Int,
    val isDay: Boolean,
    /** Hora (ms) de la consulta exitosa. */
    val fetchedAt: Long,
    val latitude: Double,
    val longitude: Double
) {
    /** Mismo clima que se muestra: no hace falta redibujar ni animar nada. */
    fun sameWeather(o: ClockWeatherReading?) = o != null && Math.round(temperature) == Math.round(o.temperature) &&
            Math.round(feelsLike) == Math.round(o.feelsLike) && code == o.code && isDay == o.isDay

    fun toJson(): String = JSONObject()
        .put("t", temperature).put("f", feelsLike).put("c", code).put("d", isDay)
        .put("at", fetchedAt).put("lat", latitude).put("lon", longitude).toString()

    companion object {
        fun fromJson(s: String): ClockWeatherReading? = try {
            val j = JSONObject(s)
            ClockWeatherReading(j.getDouble("t"), j.getDouble("f"), j.getInt("c"), j.getBoolean("d"),
                j.getLong("at"), j.optDouble("lat"), j.optDouble("lon"))
        } catch (e: Exception) {
            null
        }
    }
}

data class City(val name: String, val detail: String, val latitude: Double, val longitude: Double)

object ClockWeatherApi {

    private const val TAG = "HyundaiClockWeather"

    /** Una sola consulta con todo lo necesario. Null si no hay internet o falla. */
    fun fetch(lat: Double, lon: Double): ClockWeatherReading? = try {
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=auto")
        val now = JSONObject(body ?: throw IllegalStateException("sin respuesta")).getJSONObject("current")
        ClockWeatherReading(
            temperature = now.getDouble("temperature_2m"),
            feelsLike = now.getDouble("apparent_temperature"),
            code = now.getInt("weather_code"),
            isDay = now.optInt("is_day", 1) == 1,
            fetchedAt = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon
        )
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo consultar el clima: ${e.message}")
        null
    }

    /** Buscador de ciudades (Open-Meteo Geocoding), en español. */
    fun searchCities(query: String): List<City> = try {
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(query.trim(), "UTF-8") + "&count=8&language=es&format=json")
        val results = JSONObject(body ?: throw IllegalStateException("sin respuesta")).optJSONArray("results")
        (0 until (results?.length() ?: 0)).map { i ->
            val r = results!!.getJSONObject(i)
            City(
                name = r.getString("name"),
                detail = listOfNotNull(r.optString("admin1").takeIf { it.isNotBlank() },
                    r.optString("country").takeIf { it.isNotBlank() }).joinToString(", "),
                latitude = r.getDouble("latitude"),
                longitude = r.getDouble("longitude")
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo buscar la ciudad: ${e.message}")
        emptyList()
    }

    /** Nombre de la ciudad para una ubicación del GPS (null si no se puede averiguar). */
    fun placeName(context: Context, lat: Double, lon: Double): String? = try {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale("es")).getFromLocation(lat, lon, 1)?.firstOrNull()?.let {
            it.locality ?: it.subAdminArea ?: it.adminArea
        }
    } catch (e: Exception) {
        null
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return try {
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conn.disconnect()
        }
    }

    /** Descripción en español del código de clima de Open-Meteo (WMO). */
    fun describe(code: Int, isDay: Boolean): String = when (code) {
        0 -> if (isDay) "Soleado" else "Despejado"
        1 -> if (isDay) "Mayormente soleado" else "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45 -> "Neblina"
        48 -> "Neblina con escarcha"
        51, 53, 55 -> "Llovizna"
        56, 57 -> "Llovizna helada"
        61 -> "Lluvia ligera"
        63 -> "Lluvioso"
        65 -> "Lluvia intensa"
        66, 67 -> "Lluvia helada"
        71, 73, 75, 77 -> "Nieve"
        80 -> "Chubascos ligeros"
        81 -> "Chubascos"
        82 -> "Chubascos fuertes"
        85, 86 -> "Nevadas"
        95 -> "Tormentas"
        96, 99 -> "Tormentas con granizo"
        else -> "Sin datos"
    }
}
