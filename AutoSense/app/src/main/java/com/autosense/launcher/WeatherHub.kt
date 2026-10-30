package com.autosense.launcher

import android.content.Context

/**
 * Una sola fuente de clima para toda la app (pantalla de inicio y panel sobre Waze):
 * el pronóstico propio por internet y, como respaldo opcional, el S25 por Bluetooth.
 */
object WeatherHub {

    val listeners = mutableSetOf<() -> Unit>()

    private var forecast: ForecastWeather? = null
    private var bluetooth: BluetoothWeather? = null
    private var phoneText: String? = null
    private var phoneAt = 0L

    fun start(context: Context) {
        if (forecast != null) return
        forecast = ForecastWeather(context.applicationContext).also { it.start() }
        bluetooth = BluetoothWeather { text ->
            phoneText = text
            phoneAt = System.currentTimeMillis()
            listeners.toList().forEach { it() }
        }.also { it.start() }
    }

    fun current(): WeatherNow? = forecast?.current()

    /** Clima recibido del celular si tiene menos de 20 minutos. */
    fun phoneWeather(): String? = phoneText?.takeIf { System.currentTimeMillis() - phoneAt < PHONE_MAX_AGE_MS }

    private const val PHONE_MAX_AGE_MS = 20 * 60 * 1000L
}
