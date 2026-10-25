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

    // Hilo dedicado de visión artificial en segundo plano para no congelar la interfaz
    private var scannerThread: HandlerThread? = null
    private var scannerHandler: Handler? = null

    private var screenWidth = 2800
    private var screenHeight = 1260
    private var screenDensity = 480
    private var scanWidth = 1400
    private var scanHeight = 630

    private var reusableBitmap: Bitmap? = null
    private var pixelArray = IntArray(0)

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

        // Escaneo a media resolución con aceleración por hardware GPU (ultra-rápido < 3ms)
        scanWidth = max(700, screenWidth / 2)
        scanHeight = max(315, screenHeight / 2)
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

            // Iniciar hilo de procesamiento en segundo plano
            scannerThread = HandlerThread("BallScannerThread").apply { start() }
            scannerHandler = Handler(scannerThread!!.looper)

            imageReader = ImageReader.newInstance(scanWidth, scanHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "BilliardScanner",
                scanWidth, scanHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )

            // Procesar cada cuadro directamente en el hilo de segundo plano
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processFrameOnBackground(image)
                image.close()
            }, scannerHandler)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * PROCESAMIENTO DE VISIÓN ARTIFICIAL EN SEGUNDO PLANO (CERO BLOQUEO DE UI)
     */
    private fun processFrameOnBackground(image: android.media.Image) {
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val targetWidth = image.width + rowPadding / pixelStride
            val targetHeight = image.height

            if (reusableBitmap == null || reusableBitmap?.width != targetWidth || reusableBitmap?.height != targetHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                pixelArray = IntArray(targetWidth * targetHeight)
            }

            buffer.rewind()
            reusableBitmap?.copyPixelsFromBuffer(buffer)
            reusableBitmap?.getPixels(pixelArray, 0, targetWidth, 0, 0, targetWidth, targetHeight)

            // Factor de escala entre la imagen capturada y la pantalla real
            val scaleX = screenWidth.toFloat() / image.width
            val scaleY = screenHeight.toFloat() / image.height

            val tblLeft = (billiardCanvasView.tableRect.left / scaleX).toInt().coerceIn(0, targetWidth - 1)
            val tblRight = (billiardCanvasView.tableRect.right / scaleX).toInt().coerceIn(0, targetWidth - 1)
            val tblTop = (billiardCanvasView.tableRect.top / scaleY).toInt().coerceIn(0, targetHeight - 1)
            val tblBottom = (billiardCanvasView.tableRect.bottom / scaleY).toInt().coerceIn(0, targetHeight - 1)

            if (tblRight <= tblLeft || tblBottom <= tblTop) return

            // 1. Estimar color promedio del paño de la mesa
            val sampleCoords = listOf(
                Pair(tblLeft + (tblRight - tblLeft) / 4, tblTop + (tblBottom - tblTop) / 4),
                Pair(tblLeft + 3 * (tblRight - tblLeft) / 4, tblTop + (tblBottom - tblTop) / 4),
                Pair(tblLeft + (tblRight - tblLeft) / 4, tblTop + 3 * (tblBottom - tblTop) / 4),
                Pair(tblLeft + 3 * (tblRight - tblLeft) / 4, tblTop + 3 * (tblBottom - tblTop) / 4),
                Pair(tblLeft + (tblRight - tblLeft) / 2, tblTop + (tblBottom - tblTop) / 4),
                Pair(tblLeft + (tblRight - tblLeft) / 2, tblTop + 3 * (tblBottom - tblTop) / 4)
            )
            val rList = mutableListOf<Int>(); val gList = mutableListOf<Int>(); val bList = mutableListOf<Int>()
            for ((sx, sy) in sampleCoords) {
                val idx = sy.coerceIn(0, targetHeight - 1) * targetWidth + sx.coerceIn(0, targetWidth - 1)
                val c = pixelArray[idx]
                rList.add((c shr 16) and 0xFF); gList.add((c shr 8) and 0xFF); bList.add(c and 0xFF)
            }
            rList.sort(); gList.sort(); bList.sort()
            val feltR = rList[rList.size / 2]
            val feltG = gList[gList.size / 2]
            val feltB = bList[bList.size / 2]

            // 2. Extraer puntos de bola blanca y otras bolas
            val scanR = (billiardCanvasView.ballRadius / scaleX)
            val step = max(3, (scanR / 3.0f).toInt())

            val whitePoints = mutableListOf<PointF>()
            val otherPoints = mutableListOf<PointF>()

            for (y in (tblTop + scanR.toInt())..(tblBottom - scanR.toInt()) step step) {
                val rowOffset = y * targetWidth
                for (x in (tblLeft + scanR.toInt())..(tblRight - scanR.toInt()) step step) {
                    val c = pixelArray[rowOffset + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF

                    if (r > 205 && g > 205 && b > 205 && abs(r - g) < 18 && abs(g - b) < 18) {
                        whitePoints.add(PointF(x.toFloat(), y.toFloat()))
                    } else {
                        val diff = abs(r - feltR) + abs(g - feltG) + abs(b - feltB)
                        if (diff > 48 || (r < 45 && g < 45 && b < 45)) {
                            otherPoints.add(PointF(x.toFloat(), y.toFloat()))
                        }
                    }
                }
            }

            // 3. Posicionar Bola Blanca
            var detectedCueX = billiardCanvasView.cueBall.x
            var detectedCueY = billiardCanvasView.cueBall.y
            if (whitePoints.size >= 4) {
                var sumX = 0f; var sumY = 0f
                for (pt in whitePoints) { sumX += pt.x; sumY += pt.y }
                val scanCueX = sumX / whitePoints.size
                val scanCueY = sumY / whitePoints.size
                detectedCueX = scanCueX * scaleX
                detectedCueY = scanCueY * scaleY
            }

            // 4. Agrupar todas las demás bolas en la mesa
            val balls = mutableListOf<PointF>()
            val clusterDist = scanR * 1.35f
            val scanCueX = detectedCueX / scaleX
            val scanCueY = detectedCueY / scaleY

            for (pt in otherPoints) {
                if (hypot((pt.x - scanCueX).toDouble(), (pt.y - scanCueY).toDouble()) < clusterDist * 1.1f) continue

                var merged = false
                for (b in balls) {
                    if (hypot((pt.x - b.x / scaleX).toDouble(), (pt.y - b.y / scaleY).toDouble()) < clusterDist) {
                        b.x = (b.x + pt.x * scaleX) * 0.5f
                        b.y = (b.y + pt.y * scaleY) * 0.5f
                        merged = true
                        break
                    }
                }
                if (!merged && balls.size < 16) {
                    balls.add(PointF(pt.x * scaleX, pt.y * scaleY))
                }
            }

            // 5. DETECTAR EL TACO Y LA DIRECCIÓN DESDE LA PUNTA DEL TACO
            var detectedAimAngle = billiardCanvasView.aimAngle
            val sampleR = scanR * 2.3f
            var maxCueBrightness = -1
            var bestAngle = detectedAimAngle

            for (deg in 0 until 360 step 2) {
                val rad = Math.toRadians(deg.toDouble()).toFloat()
                val sx = (scanCueX + cos(rad) * sampleR).toInt().coerceIn(0, targetWidth - 1)
                val sy = (scanCueY + sin(rad) * sampleR).toInt().coerceIn(0, targetHeight - 1)
                val c = pixelArray[sy * targetWidth + sx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF

                // Si detecta la línea guía brillante que sale hacia adelante
                val brightness = (r + g + b) / 3
                if (brightness > maxCueBrightness && brightness > 170) {
                    maxCueBrightness = brightness
                    bestAngle = rad
                }
                // O si detecta el cuerpo de madera del taco apuntando por detrás
                if (r > 115 && g in 55..140 && b < 85 && brightness > 80) {
                    val forwardAngle = (rad + Math.PI).toFloat()
                    bestAngle = forwardAngle
                }
            }
            detectedAimAngle = bestAngle

            // Enviar resultados al lienzo de forma fluida y sin bloqueos
            billiardCanvasView.updateDetectionFromBackground(detectedCueX, detectedCueY, detectedAimAngle, balls)

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

        // 2. Botón flotante mínimo (inicia minimizado a una bolita 🎱)
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
        scannerThread?.quitSafely()
        scannerThread = null
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