package com.autosense.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothSocket
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Recibe el clima desde la app companion del celular por Bluetooth clásico (RFCOMM).
 * El celular debe estar emparejado con la tablet antes de usarlo.
 * Cada línea que llega es un JSON: {"temp":23,"desc":"Despejado"}
 */
class BluetoothWeather(private val onWeather: (String) -> Unit) {

    @Volatile
    private var running = false
    private var socket: BluetoothSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                try {
                    readFromPairedPhones()
                } catch (e: Exception) {
                    Log.w(TAG, "Error leyendo el clima", e)
                }
                if (running) Thread.sleep(RETRY_MS)
            }
        }.start()
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (e: Exception) {
        }
    }

    private fun readFromPairedPhones() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) {
            Log.i(TAG, "Bluetooth apagado")
            return
        }
        adapter.cancelDiscovery()
        // Solo teléfonos: las bocinas y audífonos no tienen el servicio y cada intento tarda
        val phones = adapter.bondedDevices.filter {
            it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
        }
        if (phones.isEmpty()) Log.i(TAG, "No hay teléfonos emparejados")
        for (device in phones) {
            if (!running) return
            try {
                Log.i(TAG, "Conectando a ${device.name}")
                val s = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket = s
                s.connect()
                Log.i(TAG, "Conectado a ${device.name}")
                BufferedReader(InputStreamReader(s.inputStream)).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, "Clima recibido: $line")
                        val json = JSONObject(line)
                        onWeather("${json.getInt("temp")}°C ${json.getString("desc")}")
                    }
                }
                s.close()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Sin servicio de AutoSense en ${device.name}: ${e.message}")
                try {
                    socket?.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "AutoSenseWeather"
        // Mismo UUID que usa la app companion del celular
        val SERVICE_UUID: UUID = UUID.fromString("7d2f1a3e-5b6c-4e8f-9a1b-2c3d4e5f6a7b")
        // El clima llega por internet; el S25 es solo respaldo, así que se busca cada 5 minutos
        private const val RETRY_MS = 5 * 60 * 1000L
    }
}
