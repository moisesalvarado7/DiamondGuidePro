package com.billiard.diamondoverlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
            setBackgroundColor(Color.parseColor("#0F141C"))
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(this).apply {
            text = "🎱 Diamond Guide Pro"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val subtitle = TextView(this).apply {
            text = "Guías de Diamantes y Rebotes para 8 Ball Pool\n(Mismo color = Líneas paralelas de rebote)"
            textSize = 13f
            setTextColor(Color.parseColor("#90CAF9"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Botón principal: Inicia las guías de inmediato sin diálogos molestos
        val btnStartDirect = Button(this).apply {
            text = "🟢 Activar Guías de Diamantes\n(Superponer directamente en tu juego)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setPadding(30, 26, 30, 26)
            setOnClickListener {
                if (checkOverlayPermission()) {
                    startOverlayDirectly()
                }
            }
        }

        val btnStartWithAuto = Button(this).apply {
            text = "👁️ Activar con Detección Automática\n(Requiere captura de pantalla)"
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            setPadding(24, 20, 24, 20)
            setOnClickListener {
                if (checkOverlayPermission()) {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "🔴 Cerrar Guías Flotantes"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Guías cerradas", Toast.LENGTH_SHORT).show()
            }
        }

        val instructions = TextView(this).apply {
            text = "💡 Cómo usar:\n" +
                    "1. Toca 'Activar Guías' y abre 8 Ball Pool.\n" +
                    "2. En la barra flotante toca '📐 Mesa' para arrastrar las esquinas al tapete del juego.\n" +
                    "3. Toca '🎮 Jugar' para bloquear las guías y disparar a través de ellas.\n" +
                    "4. Toca '💎 Guías' para alternar entre todas las líneas o las clave."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(10, 30, 10, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(btnStartDirect)
        layout.addView(TextView(this).apply { height = 24 })
        layout.addView(btnStartWithAuto)
        layout.addView(TextView(this).apply { height = 24 })
        layout.addView(btnStop)
        layout.addView(instructions)
        setContentView(layout)
    }

    private fun checkOverlayPermission(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Por favor concede el permiso para mostrar sobre otras aplicaciones", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ)
            return false
        }
        return true
    }

    private fun startOverlayDirectly() {
        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "¡Guías de diamantes activadas! Abre tu juego.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true) // Minimizar para que abra el juego
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ) {
            if (Settings.canDrawOverlays(this)) {
                startOverlayDirectly()
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
            Toast.makeText(this, "¡Guías activadas con detección!", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }
    }
}