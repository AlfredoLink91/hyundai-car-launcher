package com.autosense.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Permiso de administrador de dispositivo: solo para apagar la pantalla al instante. */
class ScreenOffAdmin : DeviceAdminReceiver()

/**
 * Menú del botón ⏻: apagar pantalla, reiniciar o apagar.
 * Android no deja a las apps reiniciar ni apagar por su cuenta: esas dos opciones abren
 * el menú de apagado del sistema (el mismo del botón físico), donde se confirma con un toque.
 */
object PowerMenu {

    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        lateinit var dialog: AlertDialog
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.float_bg)
        }
        fun option(text: String, action: () -> Unit) {
            list.addView(TextView(activity).apply {
                this.text = text
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(64)
                setPadding(dp(24), 0, dp(24), 0)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        option("🌙  Apagar pantalla") { screenOff(activity) }
        option("🔄  Reiniciar") { systemPowerMenu(activity) }
        option("🔌  Apagar") { systemPowerMenu(activity) }
        dialog = AlertDialog.Builder(activity).setView(list).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun screenOff(activity: Activity) {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(activity, ScreenOffAdmin::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            // Una sola vez: Android pide confirmar el permiso para apagar la pantalla
            activity.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Permite que el botón ⏻ de Accent apague la pantalla al instante. No se usa para nada más."))
        }
    }

    private fun systemPowerMenu(activity: Activity) {
        val service = SplitScreen.service
        if (service != null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        } else {
            Toast.makeText(activity, "Activa \"Hyundai · Pantalla dividida\" en Accesibilidad, o mantén presionado el botón de encendido", Toast.LENGTH_LONG).show()
        }
    }
}
