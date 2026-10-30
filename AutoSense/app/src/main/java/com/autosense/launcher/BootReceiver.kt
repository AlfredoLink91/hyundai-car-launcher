package com.autosense.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Al encender o reiniciar la tablet: prende la pantalla y abre el inicio de Hyundai.
 * El bloqueo de "deslizar" lo quita MainActivity; si hay PIN, patrón o contraseña, Android lo sigue pidiendo.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Tablet encendida: abriendo el inicio")
        CarPower.register(context.applicationContext)
        CarPower.wakeAndShowHome(context)
    }
}
