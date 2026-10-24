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
import android.widget.Toast

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

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        setupDisplayMetrics()

        // 1. Iniciar las vistas superpuestas SIEMPRE primero para garantizar que se muestren
        if (!::billiardCanvasView.isInitialized) {
            setupOverlays()
        }

        // 2. Configurar captura de pantalla de forma segura para Android 14
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")
        if (resultCode == Activity.RESULT_OK && data != null) {
            setupMediaProjection(resultCode, data)
        }

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
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)

            // En Android 14 (API 34) es OBLIGATORIO registrar el Callback antes de createVirtualDisplay
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "BilliardScanner",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        // 1. Lienzo a pantalla completa con las guías de diamantes
        // Inicia en modo passthrough (FLAG_NOT_TOUCHABLE) para no interferir con el juego
        billiardCanvasView = BilliardCanvasView(this)
        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(billiardCanvasView, canvasParams)

        // 2. Barra flotante con controles rápidos
        controlBar = createControlBar()
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 60
            y = 40
        }
        windowManager.addView(controlBar, controlParams)
    }

    private fun createControlBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(220, 18, 24, 36))
            setPadding(12, 8, 12, 8)
        }

        // Botón para mover la barra flotante con el dedo
        val btnMove = Button(this).apply {
            text = "✥"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
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

        // Botón 1: Alternar Cuadrícula de Guías de Diamantes (Imágenes 3 y 4)
        val btnGrid = Button(this).apply {
            text = "💎 Guías: TODAS"
            textSize = 10f
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnClickListener {
                billiardCanvasView.gridFilter = when (billiardCanvasView.gridFilter) {
                    BilliardCanvasView.GridFilter.ALL -> {
                        text = "💎 Guías: CLAVE"
                        BilliardCanvasView.GridFilter.MAIN_ANGLES
                    }
                    BilliardCanvasView.GridFilter.MAIN_ANGLES -> {
                        text = "💎 Guías: OFF"
                        BilliardCanvasView.GridFilter.OFF
                    }
                    BilliardCanvasView.GridFilter.OFF -> {
                        text = "💎 Guías: TODAS"
                        BilliardCanvasView.GridFilter.ALL
                    }
                }
                billiardCanvasView.invalidate()
            }
        }

        // Botón 2: Calibrar esquinas de la mesa
        val btnResize = Button(this).apply {
            text = "📐 Mesa"
            textSize = 10f
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnClickListener {
                setCanvasTouchable(true)
                billiardCanvasView.mode = BilliardCanvasView.Mode.ADJUST_TABLE
                billiardCanvasView.invalidate()
                Toast.makeText(this@OverlayService, "Arrastra las esquinas de color a las troneras de tu juego", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 3: Modo Jugar (Lienzo passthrough para disparar en 8 Ball Pool)
        val btnPlay = Button(this).apply {
            text = "🎮 Jugar"
            textSize = 10f
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnClickListener {
                setCanvasTouchable(false)
                billiardCanvasView.mode = BilliardCanvasView.Mode.LOCKED_PLAY
                billiardCanvasView.saveTableRect()
                billiardCanvasView.invalidate()
                Toast.makeText(this@OverlayService, "Guías fijadas. ¡Puedes disparar en tu juego!", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 4: Escaneo automático de bola blanca si la captura está activa
        val btnAutoScan = Button(this).apply {
            text = "👁️ Auto"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setOnClickListener { scanScreenForCueBall() }
        }

        // Botón 5: Cerrar Overlay
        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { stopSelf() }
        }

        bar.addView(btnMove)
        bar.addView(btnGrid)
        bar.addView(btnResize)
        bar.addView(btnPlay)
        bar.addView(btnAutoScan)
        bar.addView(btnClose)
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

    private fun scanScreenForCueBall() {
        if (imageReader == null || mediaProjection == null) {
            Toast.makeText(this, "Captura no iniciada. Las guías de diamantes siguen activas.", Toast.LENGTH_SHORT).show()
            return
        }

        billiardCanvasView.visibility = View.INVISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            try {
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
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                billiardCanvasView.visibility = View.VISIBLE
                billiardCanvasView.invalidate()
            }
        }, 60)
    }

    private fun startForegroundNotification() {
        val channelId = "billiard_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Diamond Guide Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Diamond Guide Pro")
                .setContentText("Guías de diamantes activas sobre tu juego")
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
        if (::billiardCanvasView.isInitialized) {
            windowManager.removeView(billiardCanvasView)
        }
        if (::controlBar.isInitialized) {
            windowManager.removeView(controlBar)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}