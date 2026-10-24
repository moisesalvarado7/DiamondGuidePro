package com.billiard.diamondoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var billiardCanvasView: BilliardCanvasView
    private lateinit var controlContainer: FrameLayout
    private lateinit var expandedBar: LinearLayout
    private lateinit var minimizedBubble: Button
    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var controlParams: WindowManager.LayoutParams

    private var isExpanded = true
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

        if (!::billiardCanvasView.isInitialized) {
            setupOverlays()
        }

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

        // 1. Lienzo de pantalla completa
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

        // 2. Contenedor del panel flotante colapsable / pequeño
        controlContainer = FrameLayout(this)
        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 30
        }

        buildControlViews()
        windowManager.addView(controlContainer, controlParams)
    }

    private fun dpToPx(dp: Int): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    private fun createButtonDrawable(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(6).toFloat()
            setColor(bgColor)
            setStroke(dpToPx(1), Color.argb(100, 255, 255, 255))
        }
    }

    private fun buildControlViews() {
        controlContainer.removeAllViews()

        // 1. Burbuja mínima flotante (cuando está oculto)
        minimizedBubble = Button(this).apply {
            text = "🎱"
            textSize = 17f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(225, 15, 22, 33))
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
            layoutParams = FrameLayout.LayoutParams(dpToPx(42), dpToPx(42))
            setPadding(0, 0, 0, 0)
            visibility = View.GONE

            setOnTouchListener(object : View.OnTouchListener {
                var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
                var isDrag = false

                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initX = controlParams.x; initY = controlParams.y
                            touchX = e.rawX; touchY = e.rawY
                            isDrag = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(e.rawX - touchX)
                            val dy = abs(e.rawY - touchY)
                            if (dx > 8 || dy > 8) {
                                isDrag = true
                                controlParams.x = initX + (e.rawX - touchX).toInt()
                                controlParams.y = initY + (e.rawY - touchY).toInt()
                                windowManager.updateViewLayout(controlContainer, controlParams)
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDrag) {
                                toggleExpand(true)
                            }
                        }
                    }
                    return true
                }
            })
        }

        // 2. Barra expandida horizontal compacta
        expandedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.argb(225, 14, 20, 30))
                setStroke(dpToPx(1), Color.argb(120, 0, 229, 255))
            }
            setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(4))
            gravity = Gravity.CENTER_VERTICAL
        }

        // Botón mover barra flotante
        val btnDrag = Button(this).apply {
            text = "✥"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(34))
            setPadding(0, 0, 0, 0)
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
                            windowManager.updateViewLayout(controlContainer, controlParams)
                        }
                    }
                    return true
                }
            })
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(32)
        ).apply {
            setMargins(dpToPx(2), 0, dpToPx(2), 0)
        }

        // Botón 1: Puntería Extendida (Línea de Blanca a Buchaca con Rebotes)
        val btnAim = Button(this).apply {
            text = "🎯 Tiro"
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createButtonDrawable(Color.argb(180, 0, 60, 90))
            layoutParams = btnParams
            setPadding(dpToPx(7), 0, dpToPx(7), 0)
            setOnClickListener {
                setCanvasTouchable(true)
                billiardCanvasView.mode = BilliardCanvasView.Mode.TARGET_AIM
                billiardCanvasView.invalidate()
                Toast.makeText(this@OverlayService, "Arrastra la bola blanca/objetivo o toca una buchaca para apuntar", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 2: Alternar Cuadrícula de Guías de Diamantes
        val btnGrid = Button(this).apply {
            text = "💎 Guías"
            textSize = 10f
            setTextColor(Color.parseColor("#E040FB"))
            background = createButtonDrawable(Color.argb(160, 40, 20, 60))
            layoutParams = btnParams
            setPadding(dpToPx(7), 0, dpToPx(7), 0)
            setOnClickListener {
                billiardCanvasView.gridFilter = when (billiardCanvasView.gridFilter) {
                    BilliardCanvasView.GridFilter.MAIN_ANGLES -> {
                        text = "💎 Todo"
                        BilliardCanvasView.GridFilter.ALL
                    }
                    BilliardCanvasView.GridFilter.ALL -> {
                        text = "💎 Off"
                        BilliardCanvasView.GridFilter.OFF
                    }
                    BilliardCanvasView.GridFilter.OFF -> {
                        text = "💎 Clave"
                        BilliardCanvasView.GridFilter.MAIN_ANGLES
                    }
                }
                billiardCanvasView.invalidate()
            }
        }

        // Botón 3: Calibrar esquinas de la mesa
        val btnResize = Button(this).apply {
            text = "📐 Mesa"
            textSize = 10f
            setTextColor(Color.parseColor("#FFD600"))
            background = createButtonDrawable(Color.argb(160, 60, 50, 10))
            layoutParams = btnParams
            setPadding(dpToPx(7), 0, dpToPx(7), 0)
            setOnClickListener {
                setCanvasTouchable(true)
                billiardCanvasView.mode = BilliardCanvasView.Mode.ADJUST_TABLE
                billiardCanvasView.invalidate()
                Toast.makeText(this@OverlayService, "Arrastra las esquinas a las troneras de la mesa", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón Calibración rápida del tamaño de la bola
        val btnCalib = Button(this).apply {
            text = "📏 ${billiardCanvasView.ballRadius.toInt()}"
            textSize = 10f
            setTextColor(Color.WHITE)
            background = createButtonDrawable(Color.argb(160, 45, 55, 75))
            layoutParams = btnParams
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener {
                val newR = billiardCanvasView.cycleBallRadius()
                text = "📏 ${newR.toInt()}"
                Toast.makeText(this@OverlayService, "Calibración de bola: ${newR.toInt()}px", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 4: JUGAR (Bloquea el lienzo y colapsa la barra a la burbuja para que no estorbe)
        val btnPlay = Button(this).apply {
            text = "🎮 Jugar"
            textSize = 10f
            setTextColor(Color.parseColor("#00E676"))
            background = createButtonDrawable(Color.argb(180, 10, 60, 30))
            layoutParams = btnParams
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setOnClickListener {
                setCanvasTouchable(false)
                billiardCanvasView.mode = BilliardCanvasView.Mode.LOCKED_PLAY
                billiardCanvasView.saveTableRect()
                billiardCanvasView.invalidate()
                toggleExpand(false) // Minimizar automáticamente a la bolita
                Toast.makeText(this@OverlayService, "¡Lienzo fijado! Dispara en tu juego.", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 5: Escaneo rápido
        val btnAutoScan = Button(this).apply {
            text = "👁️"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createButtonDrawable(Color.argb(140, 40, 40, 50))
            layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(32)).apply {
                setMargins(dpToPx(2), 0, dpToPx(2), 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { scanScreenForCueBall() }
        }

        // Botón 6: Minimizar manualmente (ocultar panel)
        val btnMinimize = Button(this).apply {
            text = "◀"
            textSize = 11f
            setTextColor(Color.parseColor("#90CAF9"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(32))
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleExpand(false) }
        }

        // Botón 7: Cerrar Overlay
        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(32))
            setPadding(0, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }

        expandedBar.addView(btnDrag)
        expandedBar.addView(btnAim)
        expandedBar.addView(btnGrid)
        expandedBar.addView(btnResize)
        expandedBar.addView(btnCalib)
        expandedBar.addView(btnPlay)
        expandedBar.addView(btnAutoScan)
        expandedBar.addView(btnMinimize)
        expandedBar.addView(btnClose)

        controlContainer.addView(expandedBar)
        controlContainer.addView(minimizedBubble)
    }

    private fun toggleExpand(expand: Boolean) {
        isExpanded = expand
        if (expand) {
            expandedBar.visibility = View.VISIBLE
            minimizedBubble.visibility = View.GONE
        } else {
            expandedBar.visibility = View.GONE
            minimizedBubble.visibility = View.VISIBLE
        }
        windowManager.updateViewLayout(controlContainer, controlParams)
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
            Toast.makeText(this, "Para auto-escáner, abre la app con 'Activar con Detección'.", Toast.LENGTH_SHORT).show()
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
                .setContentText("Guías de puntería y diamantes activas")
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
        if (::controlContainer.isInitialized) {
            windowManager.removeView(controlContainer)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}