package com.autosense.launcher

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

/** Abre las apps de cada pestaña sin cerrar AutoSense si alguna no existe en la tablet. */
object AppLauncher {

    const val WAZE = "com.waze"
    const val MAPS = "com.google.android.apps.maps"

    fun openForTab(context: Context, tabId: Int) {
        when (tabId) {
            R.id.tab_mapa -> if (!openApp(context, WAZE)) toast(context, "Instala Waze")
            R.id.tab_musica -> if (!openApp(context, "com.spotify.music")) toast(context, "Instala Spotify")
            // La tablet es solo Wi-Fi y no tiene marcador: se abren los contactos
            R.id.tab_telefono -> if (!openApp(context, "com.android.contacts")) safeStart(context, Intent(Intent.ACTION_DIAL))
            R.id.tab_camara -> if (!openApp(context, "net.sourceforge.opencamera")) {
                safeStart(context, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
            }
            R.id.tab_ajustes -> safeStart(context, Intent(Settings.ACTION_SETTINGS))
            R.id.tab_apps -> safeStart(context, Intent(context, AppsActivity::class.java))
        }
    }

    fun openApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return safeStart(context, intent)
    }

    fun safeStart(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            toast(context, "No hay una app para esto en la tablet")
            false
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
