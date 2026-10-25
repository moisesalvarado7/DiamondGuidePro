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
            text = "🎱 Aim Guide Pro"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 10)
        }

        val subtitle = TextView(this).apply {
            text = "Líneas de Referencia Automáticas para 8 Ball Pool\n(Sigue el juego en tiempo real tiro a tiro)"
            textSize = 13f
            setTextColor(Color.parseColor("#90CAF9"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        // Botón principal: Inicia el seguimiento automático del juego
        val btnStartAuto = Button(this).apply {
            text = "🟢 Iniciar Guías Automáticas en Vivo\n(Detecta blanca y bolas en cada tiro)"
            textSize = 15f
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setPadding(30, 26, 30, 26)
            setOnClickListener {
                if (checkOverlayPermission()) {
                    startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQ)
                }
            }
        }

        val btnStop = Button(this).apply {
            text = "🔴 Cerrar Guías"
            textSize = 14f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                Toast.makeText(this@MainActivity, "Guías cerradas", Toast.LENGTH_SHORT).show()
            }
        }

        val instructions = TextView(this).apply {
            text = "💡 Cómo funciona:\n" +
                    "1. Toca 'Iniciar Guías Automáticas' y concede el permiso de captura.\n" +
                    "2. Abre 8 Ball Pool: La aplicación detecta la bola blanca y todas las bolas en la mesa.\n" +
                    "3. Muestra la línea blanca y todos sus rebotes.\n" +
                    "4. En cuanto la blanca toque el borde de cualquier bola, genera automáticamente la segunda línea con todos sus rebotes en banda.\n" +
                    "5. Sigue el juego de forma 100% automática sin tener que editar nada en cada tiro."
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(10, 30, 10, 0)
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(btnStartAuto)
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
            Toast.makeText(this, "¡Asistente automático activado! Abre 8 Ball Pool.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true) // Minimizar para ir directo al juego
        }
    }
}