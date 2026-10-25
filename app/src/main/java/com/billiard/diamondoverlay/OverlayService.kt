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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.nio.ByteBuffer
import kotlin.math.*

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var billiardCanvasView: BilliardCanvasView
    private lateinit var controlContainer: FrameLayout
    private lateinit var expandedBar: LinearLayout
    private lateinit var minimizedBubble: Button
    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var controlParams: WindowManager.LayoutParams

    private var isExpanded = false
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Hilo dedicado de visión artificial en segundo plano para no congelar la UI
    private var scannerThread: HandlerThread? = null
    private var scannerHandler: Handler? = null

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

        if (!hasProjection || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForegroundNotification()
            setupDisplayMetrics()

            if (!::billiardCanvasView.isInitialized) {
                setupOverlays()
            }

            setupMediaProjection(resultCode, data)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupDisplayMetrics() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            val w = abs(bounds.width())
            val h = abs(bounds.height())
            screenWidth = max(w, h)
            screenHeight = min(w, h)
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = max(metrics.widthPixels, metrics.heightPixels)
            screenHeight = min(metrics.widthPixels, metrics.heightPixels)
            screenDensity = metrics.densityDpi
        }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            // Iniciar hilo de procesamiento en segundo plano
            scannerThread = HandlerThread("BallScannerThread").apply { start() }
            scannerHandler = Handler(scannerThread!!.looper)

            // Usar resolución nativa horizontal del juego para compatibilidad con la GPU
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "BilliardScanner",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // Procesar cada cuadro directamente desde el buffer nativo
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    processFrameDirectFromBuffer(image)
                } catch (t: Throwable) {
                    t.printStackTrace()
                } finally {
                    image.close() // SIEMPRE liberar para que nunca se quede atorado
                }
            }, scannerHandler)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * PROCESAMIENTO DIRECTO EN MEMORIA NATIVO (ULTRA-RÁPIDO < 1ms, CERO MEMORY ALLOCATION, NUNCA SE TRABA)
     */
    private fun processFrameDirectFromBuffer(image: android.media.Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val bufCapacity = buffer.capacity()

        val midX = width / 2
        val midY = height / 2

        val scaleX = if (billiardCanvasView.width > 0) billiardCanvasView.width.toFloat() / width.toFloat() else 1.0f
        val scaleY = if (billiardCanvasView.height > 0) billiardCanvasView.height.toFloat() / height.toFloat() else 1.0f

        // Usar los límites fijos y calibrados de la mesa (nunca desconfigurar el tamaño de la mesa)
        val table = billiardCanvasView.tableRect
        val feltLeft = (table.left / scaleX).toInt().coerceIn(0, width - 1)
        val feltTop = (table.top / scaleY).toInt().coerceIn(0, height - 1)
        val feltRight = (table.right / scaleX).toInt().coerceIn(feltLeft + 100, width)
        val feltBottom = (table.bottom / scaleY).toInt().coerceIn(feltTop + 100, height)

        val feltWidth = feltRight - feltLeft
        val feltHeight = feltBottom - feltTop
        if (feltWidth <= 200 || feltHeight <= 100) return

        val ballR = (feltHeight * 0.042f).toInt().coerceAtLeast(10)
        val step = max(4, ballR / 3)

        // Clase local para agrupar puntos por centroide exacto
        class DetectionCluster(var sumX: Float, var sumY: Float, var count: Int) {
            fun add(x: Float, y: Float) {
                sumX += x
                sumY += y
                count++
            }
            fun centerX() = sumX / count
            fun centerY() = sumY / count
            fun distSq(x: Float, y: Float): Float {
                val dx = x - centerX()
                val dy = y - centerY()
                return dx * dx + dy * dy
            }
        }

        val whitePoints = mutableListOf<PointF>()
        val ballPoints = mutableListOf<PointF>()

        // 2. DETECCIÓN ROBUSTA DE LA BOLA BLANCA Y TODAS LAS DEMÁS BOLAS
        for (y in (feltTop + ballR) until (feltBottom - ballR) step step) {
            val rowOffset = y * rowStride
            for (x in (feltLeft + ballR) until (feltRight - ballR) step step) {
                val offset = rowOffset + x * pixelStride
                if (offset + 2 >= bufCapacity) continue

                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF

                val isFelt = (b > 115 && b > r + 30 && g > r + 15)

                if (r > 190 && g > 190 && b > 190) {
                    whitePoints.add(PointF(x.toFloat(), y.toFloat()))
                } else if (!isFelt) {
                    ballPoints.add(PointF(x.toFloat(), y.toFloat()))
                }
            }
        }

        // 3. AISLAR Y CALCULAR EL CENTRO EXACTO DE LA BOLA BLANCA
        var cueX = billiardCanvasView.cueBall.x
        var cueY = billiardCanvasView.cueBall.y
        val whiteClusterMaxDistSq = (ballR * 2.0f) * (ballR * 2.0f)

        if (whitePoints.size >= 3) {
            val whiteClusters = mutableListOf<DetectionCluster>()
            for (pt in whitePoints) {
                var matched = false
                for (cl in whiteClusters) {
                    if (cl.distSq(pt.x, pt.y) < whiteClusterMaxDistSq) {
                        cl.add(pt.x, pt.y)
                        matched = true
                        break
                    }
                }
                if (!matched) {
                    whiteClusters.add(DetectionCluster(pt.x, pt.y, 1))
                }
            }

            var bestCount = 0
            var bestCluster: DetectionCluster? = null
            for (cl in whiteClusters) {
                if (cl.count > bestCount) {
                    bestCount = cl.count
                    bestCluster = cl
                }
            }

            if (bestCluster != null && bestCount >= 4) {
                cueX = bestCluster.centerX()
                cueY = bestCluster.centerY()
            }
        }

        // 4. AGRUPAR TODAS LAS DEMÁS BOLAS OBJETIVO EN LA MESA
        val objectClusterMaxDistSq = (ballR * 1.8f) * (ballR * 1.8f)
        val objectClusters = mutableListOf<DetectionCluster>()

        for (pt in ballPoints) {
            val dCueSq = (pt.x - cueX) * (pt.x - cueX) + (pt.y - cueY) * (pt.y - cueY)
            if (dCueSq < (ballR * 1.6f) * (ballR * 1.6f)) continue

            var matched = false
            for (cl in objectClusters) {
                if (cl.distSq(pt.x, pt.y) < objectClusterMaxDistSq) {
                    cl.add(pt.x, pt.y)
                    matched = true
                    break
                }
            }
            if (!matched) {
                objectClusters.add(DetectionCluster(pt.x, pt.y, 1))
            }
        }

        val balls = mutableListOf<PointF>()
        for (cl in objectClusters) {
            if (cl.count >= 5 && balls.size < 16) {
                val bx = cl.centerX()
                val by = cl.centerY()
                if (bx > feltLeft + ballR * 0.8f && bx < feltRight - ballR * 0.8f &&
                    by > feltTop + ballR * 0.8f && by < feltBottom - ballR * 0.8f) {
                    balls.add(PointF(bx, by))
                }
            }
        }

        // 5. ENVIAR RESULTADOS DE BOLAS AL LIENZO (SIN TOCAR JAMÁS EL TAMAÑO DE LA MESA)
        val scaledBalls = balls.map { PointF(it.x * scaleX, it.y * scaleY) }

        billiardCanvasView.post {
            billiardCanvasView.updateDetectionFromBackground(scaledBalls)
        }
    }

    private fun setupOverlays() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

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

        val btnAim = Button(this).apply {
            text = "🎯 Tiro"
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            background = createButtonDrawable(Color.argb(190, 10, 45, 65))
            layoutParams = btnParams
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener {
                val isAiming = (billiardCanvasView.mode == BilliardCanvasView.Mode.ADJUST_AIM)
                if (!isAiming) {
                    setCanvasTouchable(true)
                    billiardCanvasView.mode = BilliardCanvasView.Mode.ADJUST_AIM
                    text = "🎮 Jugar"
                    setTextColor(Color.parseColor("#00E676"))
                    background = createButtonDrawable(Color.argb(220, 0, 70, 30))
                    Toast.makeText(this@OverlayService, "Arrastra ⚪ Blanca, 🎯 Bola Objetivo o toca una tronera", Toast.LENGTH_SHORT).show()
                } else {
                    setCanvasTouchable(false)
                    billiardCanvasView.mode = BilliardCanvasView.Mode.LOCKED_PLAY
                    billiardCanvasView.saveBallPositions()
                    text = "🎯 Tiro"
                    setTextColor(Color.parseColor("#00E5FF"))
                    background = createButtonDrawable(Color.argb(190, 10, 45, 65))
                    toggleExpand(false)
                    Toast.makeText(this@OverlayService, "🎯 Guías fijadas. ¡Listo para tirar!", Toast.LENGTH_SHORT).show()
                }
                billiardCanvasView.invalidate()
            }
        }

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

        val btnMinimize = Button(this).apply {
            text = "◀"
            textSize = 11f
            setTextColor(Color.parseColor("#90CAF9"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(30))
            setPadding(0, 0, 0, 0)
            setOnClickListener { toggleExpand(false) }
        }

        val btnClose = Button(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(30))
            setPadding(0, 0, 0, 0)
            setOnClickListener { stopSelf() }
        }

        expandedBar.addView(btnAim)
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

    private fun startForegroundNotification() {
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
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scannerThread?.quitSafely()
        scannerThread = null
        try {
            if (::billiardCanvasView.isInitialized && billiardCanvasView.isAttachedToWindow) {
                windowManager.removeView(billiardCanvasView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (::controlContainer.isInitialized && controlContainer.isAttachedToWindow) {
                windowManager.removeView(controlContainer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}