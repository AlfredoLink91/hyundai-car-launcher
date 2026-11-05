package com.autosense.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Cajón de aplicaciones: todas las apps instaladas en una cuadrícula con íconos grandes. */
class AppsActivity : Activity() {

    private data class AppEntry(val label: String, val icon: Drawable, val launch: Intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_gradient)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Encabezado con botón para volver al inicio (zona táctil grande)
        val header = TextView(this).apply {
            text = "‹  Aplicaciones"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            minHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Volver al inicio"
            setOnClickListener { finish() }
        }
        root.addView(header)

        val apps = loadApps()
        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(140)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            horizontalSpacing = dp(20)
            verticalSpacing = dp(22)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            clipToPadding = false
            selector = resources.getDrawable(android.R.color.transparent, null)
            adapter = object : BaseAdapter() {
                override fun getCount() = apps.size
                override fun getItem(position: Int) = apps[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val cell = (convertView as? LinearLayout) ?: LinearLayout(this@AppsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(8), dp(16), dp(8), dp(14))
                        setBackgroundResource(R.drawable.glass_panel)
                        addView(ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                        })
                        addView(TextView(context).apply {
                            textSize = 14f
                            setTextColor(0xFFFFFFFF.toInt())
                            gravity = Gravity.CENTER
                            maxLines = 2
                            setPadding(0, dp(6), 0, 0)
                        })
                    }
                    val app = apps[position]
                    (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                    (cell.getChildAt(1) as TextView).text = app.label
                    cell.contentDescription = app.label
                    return cell
                }
            }
            setOnItemClickListener { _, _, position, _ -> AppLauncher.safeStart(this@AppsActivity, apps[position].launch) }
        }
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Cambiar el fondo de pantalla (el que se ve detrás del inicio)
        root.addView(TextView(this).apply {
            text = "🖼  Cambiar fondo de pantalla"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.glass_panel)
            setOnClickListener { chooseWallpaper() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // Mostrar u ocultar el acceso a "Reloj y clima" en el inicio (se guarda en la tablet)
        val clockWeather = ClockWeatherSettings(this)
        root.addView(android.widget.Switch(this).apply {
            text = "Mostrar el botón «Reloj y clima» en el inicio"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            minHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundResource(R.drawable.glass_panel)
            isChecked = clockWeather.enabled
            setOnCheckedChangeListener { _, on -> clockWeather.enabled = on }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // Crédito que pide la licencia CC BY 4.0 del modelo 3D del auto
        root.addView(TextView(this).apply {
            text = "Modelo 3D del auto: \"hyundai accent 2013\" por L95XP (sketchfab.com/I95XP), licencia CC BY 4.0. Color modificado."
            textSize = 11f
            setTextColor(0x99B0C4DE.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        setContentView(root)
    }

    private fun loadApps(): List<AppEntry> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { info: ResolveInfo ->
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                AppEntry(info.loadLabel(packageManager).toString(), info.loadIcon(packageManager), launch)
            }
        return (found + hiddenSystemApps(found)).sortedBy { it.label.lowercase() }
    }

    /**
     * Apps del sistema que la tablet esconde de las listas de otras apps aunque sí se pueden abrir
     * (el explorador de archivos de Lenovo). Se agregan a mano si están instaladas.
     */
    private fun hiddenSystemApps(found: List<AppEntry>): List<AppEntry> =
        HIDDEN_APPS.mapNotNull { (pkg, activity, label) ->
            if (found.any { it.launch.component?.packageName == pkg }) return@mapNotNull null
            try {
                val component = android.content.ComponentName(pkg, activity)
                val info = packageManager.getActivityInfo(component, 0)
                val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                AppEntry(label, info.loadIcon(packageManager), launch)
            } catch (e: Exception) {
                null  // no está instalada
            }
        }

    // --- Fondo de pantalla (la tablet no trae un selector propio) ---

    private fun chooseWallpaper() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Fondo de pantalla")
            .setItems(arrayOf("Elegir una imagen de la tablet", "Volver al fondo original")) { _, which ->
                if (which == 0) {
                    val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    try {
                        startActivityForResult(Intent.createChooser(pick, "Elige la imagen de fondo"), REQUEST_WALLPAPER)
                    } catch (e: Exception) {
                        toast("No hay una app para elegir imágenes")
                    }
                } else {
                    try {
                        android.app.WallpaperManager.getInstance(this).clear()
                        toast("Listo: fondo original")
                    } catch (e: Exception) {
                        toast("No se pudo cambiar el fondo")
                    }
                }
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK || uri == null) return
        toast("Poniendo el fondo…")
        Thread {
            val ok = try {
                setWallpaper(uri)
                true
            } catch (e: Exception) {
                android.util.Log.w("HyundaiApps", "No se pudo poner el fondo", e)
                false
            }
            runOnUiThread { toast(if (ok) "Listo: fondo nuevo" else "No se pudo usar esa imagen") }
        }.start()
    }

    /** Ajusta la imagen a la pantalla (recorte centrado, sin estirar) y la pone de fondo. */
    private fun setWallpaper(uri: android.net.Uri) {
        val mode = windowManager.defaultDisplay.mode
        val w = maxOf(mode.physicalWidth, mode.physicalHeight)
        val h = minOf(mode.physicalWidth, mode.physicalHeight)
        // Primero solo las medidas, para leer la foto reducida y no llenar la memoria
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val source = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("imagen ilegible")
        val scale = maxOf(w / source.width.toFloat(), h / source.height.toFloat())
        val result = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(result).apply {
            translate((w - source.width * scale) / 2, (h - source.height * scale) / 2)
            scale(scale, scale)
            drawBitmap(source, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        source.recycle()
        android.app.WallpaperManager.getInstance(this).setBitmap(result)
        result.recycle()
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_WALLPAPER = 11
        private val HIDDEN_APPS = listOf(
            Triple("com.chaozhuo.filemanager", "com.chaozhuo.filemanager.activities.SplashActivity", "Archivos")
        )
    }
}
