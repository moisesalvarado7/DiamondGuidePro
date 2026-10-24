package com.billiard.diamondoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import kotlin.math.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var billiardCanvasView: BilliardCanvasView
    private lateinit var controlBar: LinearLayout
    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var controlParams: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenWidth = 2800
    private var screenHeight = 1260
    private var screenDensity = 480

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        setupDisplayMetrics()

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")
        if (resultCode == Activity.RESULT_OK && data != null) {
            setupMediaProjection(resultCode, data)
        }

        setupOverlays()
        return START_STICKY
    }

    private fun setupDisplayMetrics() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BilliardScanner",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        // 1. Lienzo a pantalla completa
        billiardCanvasView = BilliardCanvasView(this)
        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(billiardCanvasView, canvasParams)

        // 2. Barra de control flotante (siempre clickeable)
        controlBar = createControlBar()
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 40
        }
        windowManager.addView(controlBar, controlParams)
    }

    private fun createControlBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(210, 18, 24, 36))
            setPadding(16, 10, 16, 10)
        }

        // Botón para arrastrar la barra flotante
        val btnMove = Button(this).apply {
            text = "✥"
            textSize = 12f
            setOnTouchListener(object : View.OnTouchListener {
                var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = controlParams.x; initY = controlParams.y
                            touchX = e.rawX; touchY = e.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            controlParams.x = initX + (e.rawX - touchX).toInt()
                            controlParams.y = initY + (e.rawY - touchY).toInt()
                            windowManager.updateViewLayout(controlBar, controlParams)
                        }
                    }
                    return true
                }
            })
        }

        // Botón 1: Ajustar esquinas de la mesa
        val btnResize = Button(this).apply {
            text = "📐 Mesa"
            textSize = 11f
            setOnClickListener {
                setCanvasTouchable(true)
                billiardCanvasView.mode = BilliardCanvasView.Mode.ADJUST_TABLE
                billiardCanvasView.invalidate()
            }
        }

        // Botón 2: Mover Bolas Manualmente
        val btnBalls = Button(this).apply {
            text = "🎱 Bolas"
            textSize = 11f
            setOnClickListener {
                setCanvasTouchable(true)
                billiardCanvasView.mode = BilliardCanvasView.Mode.MOVE_BALLS
                billiardCanvasView.invalidate()
            }
        }

        // Botón 3: Escaneo Automático de Bola Blanca
        val btnAutoScan = Button(this).apply {
            text = "👁️ Auto-Blanca"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            setOnClickListener { scanScreenForCueBall() }
        }

        // Botón 4: Modo Juego (Passthrough para disparar en el juego de abajo)
        val btnPlay = Button(this).apply {
            text = "🎮 Jugar (Tocar Abajo)"
            textSize = 11f
            setOnClickListener {
                val isLocked = (canvasParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
                if (isLocked) {
                    setCanvasTouchable(true)
                    text = "🎮 Jugar (Tocar Abajo)"
                } else {
                    setCanvasTouchable(false)
                    billiardCanvasView.mode = BilliardCanvasView.Mode.LOCKED_PLAY
                    billiardCanvasView.invalidate()
                    text = "🔒 Desbloquear Guía"
                }
            }
        }

        // Botón 5: Alternar Líneas de Diamantes (3 Bandas vs Tiro Directo)
        val btnDiamonds = Button(this).apply {
            text = "💎 3 Bandas"
            textSize = 11f
            setOnClickListener {
                billiardCanvasView.showThreeCushion = !billiardCanvasView.showThreeCushion
                billiardCanvasView.invalidate()
            }
        }

        bar.addView(btnMove)
        bar.addView(btnResize)
        bar.addView(btnBalls)
        bar.addView(btnAutoScan)
        bar.addView(btnDiamonds)
        bar.addView(btnPlay)
        return bar
    }

    private fun setCanvasTouchable(touchable: Boolean) {
        if (touchable) {
            canvasParams.flags = canvasParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            canvasParams.flags = canvasParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        windowManager.updateViewLayout(billiardCanvasView, canvasParams)
    }

    /**
     * Escanea los píxeles dentro del rectángulo de la mesa para detectar automáticamente
     * el centroide del círculo más blanco y brillante (Bola Blanca).
     */
    private fun scanScreenForCueBall() {
        billiardCanvasView.visibility = View.INVISIBLE // Ocultar 50ms para leer pantalla limpia
        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                billiardCanvasView.detectCueBallFromBitmap(bitmap)
                bitmap.recycle()
            }
            billiardCanvasView.visibility = View.VISIBLE
            billiardCanvasView.invalidate()
        }, 60)
    }

    private fun startForegroundNotification() {
        val channelId = "billiard_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Billiard Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Diamond Guide Pro Activo")
                .setContentText("Lienzo superpuesto ejecutándose en tu Honor 400 Pro")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else Notification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billiardCanvasView.isInitialized) windowManager.removeView(billiardCanvasView)
        if (::controlBar.isInitialized) windowManager.removeView(controlBar)
        virtualDisplay?.release()
        mediaProjection?.stop()
    }
}