package com.billiard.diamondoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity

class MainActivity : Activity() {

    private val OVERLAY_PERMISSION_REQ = 1001
    private val SCREEN_CAPTURE_REQ = 1002
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#121824"))
            setPadding(60, 60, 60, 60)
        }

        val title = TextView(this).apply {
            text = "🎱 Diamond Guide Overlay\n(Edición Honor 400 Pro)"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val btnStart = Button(this).apply {
            text = "Activar Lienzo Flotante + Auto-Detección"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setOnClickListener { checkPermissionsAndStart() }
        }

        val btnStop = Button(this).apply {
            text = "Cerrar Lienzo"
            setBackgroundColor(Color.parseColor("#FF5252"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        }

        layout.addView(title)
        layout.addView(btnStart)
        layout.addView(TextView(this).apply { height = 30 })
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ)
        } else {
            // Pedir permiso para leer píxeles de la pantalla y detectar la bola blanca
            startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ) {
            if (Settings.canDrawOverlays(this)) {
                startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
            }
        } else if (requestCode == SCREEN_CAPTURE_REQ) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            finish() // Minimiza la app para que abras tu juego de billar
        }
    }
}