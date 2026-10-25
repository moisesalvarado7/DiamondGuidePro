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

    private var isExpanded = false // Inicia minimizado para no estorbar
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reusableBitmap: Bitmap? = null

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
        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("DATA")
        val hasProjection = (resultCode == Activity.RESULT_OK && data != null)

        startForegroundNotification(hasProjection)
        setupDisplayMetrics()

        if (!::billiardCanvasView.isInitialized) {
            setupOverlays()
        }

        if (hasProjection && data != null) {
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

            // Iniciar escaneo automático continuo de inmediato
            isContinuousScan = true
            scanHandler.post(scanRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        // 1. Lienzo de pantalla completa completamente pasante al tacto
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

        // 2. Botón flotante mínimo (inicia en la esquina superior)
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

        // 1. Burbuja mínima flotante
        minimizedBubble = Button(this).apply {
            text = "🎱"
            textSize = 17f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 15, 22, 33))
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
            layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40))
            setPadding(0, 0, 0, 0)
            visibility = if (isExpanded) View.GONE else View.VISIBLE

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

        // 2. Barrita expandida ultra-compacta
        expandedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.argb(240, 14, 20, 30))
                setStroke(dpToPx(1), Color.argb(140, 0, 229, 255))
            }
            setPadding(dpToPx(5), dpToPx(3), dpToPx(5), dpToPx(3))
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
        }

        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dpToPx(30)
        ).apply {
            setMargins(dpToPx(2), 0, dpToPx(2), 0)
        }

        // Botón 1: Color de la segunda línea
        val currentLabel = BilliardCanvasView.TargetLineColor.values()[billiardCanvasView.currentColorIndex].label
        val btnColor = Button(this).apply {
            text = "🎨 $currentLabel"
            textSize = 10f
            setTextColor(Color.parseColor("#FFD600"))
            background = createButtonDrawable(Color.argb(160, 60, 50, 10))
            layoutParams = btnParams
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener {
                val nextLabel = billiardCanvasView.cycleTargetColor()
                text = "🎨 $nextLabel"
                Toast.makeText(this@OverlayService, "Color de 2da línea: $nextLabel", Toast.LENGTH_SHORT).show()
            }
        }

        // Botón 2: Calibrar esquinas de la mesa (solo si es necesario)
        val btnMesa = Button(this).apply {
            text = "📐 Mesa"
            textSize = 10f
            setTextColor(Color.parseColor("#90CAF9"))
            background = createButtonDrawable(Color.argb(160, 20, 40, 60))
            layoutParams = btnParams
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener {
                val isAdjusting = (billiardCanvasView.mode == BilliardCanvasView.Mode.ADJUST_TABLE)
                if (!isAdjusting) {
                    setCanvasTouchable(true)
                    billiardCanvasView.mode = BilliardCanvasView.Mode.ADJUST_TABLE
                    text = "✓ Listo"
                    Toast.makeText(this@OverlayService, "Arrastra las 4 esquinas a las troneras", Toast.LENGTH_SHORT).show()
                } else {
                    setCanvasTouchable(false)
                    billiardCanvasView.mode = BilliardCanvasView.Mode.LOCKED_PLAY
                    billiardCanvasView.saveTableRect()
                    text = "📐 Mesa"
                    toggleExpand(false)
                    Toast.makeText(this@OverlayService, "Mesa calibrada", Toast.LENGTH_SHORT).show()
                }
                billiardCanvasView.invalidate()
            }
        }

        // Botón 3: Minimizar a la bolita
        val btnMinimize = Button(this).apply {
            text = "◀"
            textSize = 11f
            setTextColor(Color.parseColor("#90CAF9"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(30))
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleExpand(false) }
        }

        // Botón 4: Cerrar
        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(30))
            setPadding(0, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }

        expandedBar.addView(btnColor)
        expandedBar.addView(btnMesa)
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

    private var isContinuousScan = false
    private val scanHandler = Handler(Looper.getMainLooper())
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (isContinuousScan) {
                performScan()
                scanHandler.postDelayed(this, 120) // Actualización suave a ~8-10 fps en tiempo real
            }
        }
    }

    private fun performScan() {
        if (imageReader == null || mediaProjection == null) return
        try {
            val image = imageReader?.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val targetWidth = image.width + rowPadding / pixelStride
            val targetHeight = image.height

            // Reutilizar bitmap para evitar recolección de basura (GC pauses)
            if (reusableBitmap == null || reusableBitmap?.width != targetWidth || reusableBitmap?.height != targetHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            }

            buffer.rewind()
            reusableBitmap?.copyPixelsFromBuffer(buffer)
            image.close()

            reusableBitmap?.let { bmp ->
                billiardCanvasView.detectAllBallsAndAimFromBitmap(bmp)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundNotification(hasProjection: Boolean) {
        val channelId = "billiard_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Diamond Guide Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Diamond Guide Pro")
                .setContentText("Guías automáticas de 8 Ball Pool activas")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else Notification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (hasProjection) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            } else {
                if (hasProjection) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else 0
            }
            if (type != 0) {
                startForeground(1, notification, type)
            } else {
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isContinuousScan = false
        scanHandler.removeCallbacks(scanRunnable)
        if (::billiardCanvasView.isInitialized) {
            windowManager.removeView(billiardCanvasView)
        }
        if (::controlContainer.isInitialized) {
            windowManager.removeView(controlContainer)
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}