package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { LOCKED_PLAY, ADJUST_AIM, ADJUST_TABLE }
    enum class CushionSide { LEFT, TOP, RIGHT, BOTTOM, NONE }

    data class CushionBounce(
        val point: PointF,
        val cushionSide: CushionSide,
        val isPocket: Boolean = false
    )

    enum class TargetLineColor(val label: String, val mainColor: Int, val outlineColor: Int) {
        BLACK("Negro", Color.parseColor("#121212"), Color.WHITE),
        GOLD("Oro", Color.parseColor("#FFD600"), Color.WHITE),
        CYAN("Cyan", Color.parseColor("#00E5FF"), Color.WHITE),
        GREEN("Verde", Color.parseColor("#00E676"), Color.WHITE),
        RED("Rojo", Color.parseColor("#FF1744"), Color.WHITE)
    }

    var mode = Mode.LOCKED_PLAY
    var currentColorIndex = 0

    // Rectángulo del tapete de juego (borde interior de las bandas de goma)
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(700f, 650f)       // Bola Blanca
    var targetBall = PointF(1500f, 520f)   // Bola a Anotar
    var ballRadius = 25f                   // Radio calibrable de la bola (22..30px)
    var aimAngle = 0f                      // Ángulo de disparo del taco (en radianes)

    // Lista de otras bolas detectadas (si la cámara/escáner está activo)
    val detectedBalls = mutableListOf<PointF>()

    private var activeDragItem = -1 // 0..3=Esquinas, 4=Blanca, 5=Objetivo, 6=Manija de Puntería
    private val prefs = context.getSharedPreferences("diamond_guide_prefs", Context.MODE_PRIVATE)

    // Pinceles de las bandas y troneras
    private val paintInnerCushion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    private val paintPocket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0E14")
        style = Paint.Style.FILL
    }
    private val paintPocketRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    // 1. PINCELES DE LA BOLA BLANCA (BLANCO PURO NÍTIDO CON HALO SUAVE COMO EN LAS FOTOS)
    private val paintCueLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.6f
        setShadowLayer(6f, 0f, 0f, Color.argb(180, 255, 255, 255))
    }
    private val paintCueRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        setShadowLayer(6f, 0f, 0f, Color.WHITE)
    }
    private val paintCueFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paintCueDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
    }

    // 2. PINCELES DE LA BOLA A ANOTAR (LÍNEA OSCURA O DEL COLOR SELECCIONADO COMO EN LAS FOTOS 2 Y 3)
    private val paintTargetLineCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
    }
    private val paintTargetLineOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6.2f
    }
    private val paintTargetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
    }
    private val paintTargetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Pincel para la manija interactiva de puntería
    private val paintAimHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        ballRadius = prefs.getFloat("ball_radius", 25f)
        currentColorIndex = prefs.getInt("target_color_index", 0)
    }

    fun cycleTargetColor(): String {
        val colors = TargetLineColor.values()
        currentColorIndex = (currentColorIndex + 1) % colors.size
        prefs.edit().putInt("target_color_index", currentColorIndex).apply()
        invalidate()
        return colors[currentColorIndex].label
    }

    fun cycleBallRadius(): Float {
        ballRadius = when (ballRadius.toInt()) {
            22 -> 24f
            24 -> 26f
            26 -> 28f
            28 -> 30f
            else -> 22f
        }
        prefs.edit().putFloat("ball_radius", ballRadius).apply()
        invalidate()
        return ballRadius
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!loadTableRect()) {
            val marginX = w * 0.08f
            val marginY = h * 0.14f
            tableRect.set(marginX, marginY, w - marginX, h - marginY)
            cueBall.set(tableRect.left + tableRect.width() * 0.35f, tableRect.centerY() + 60f)
            targetBall.set(tableRect.left + tableRect.width() * 0.70f, tableRect.centerY() - 40f)
            aimAngle = atan2(targetBall.y - cueBall.y, targetBall.x - cueBall.x)
        }
    }

    fun saveTableRect() {
        prefs.edit()
            .putFloat("rect_left", tableRect.left)
            .putFloat("rect_top", tableRect.top)
            .putFloat("rect_right", tableRect.right)
            .putFloat("rect_bottom", tableRect.bottom)
            .apply()
    }

    private fun loadTableRect(): Boolean {
        val l = prefs.getFloat("rect_left", -1f)
        val t = prefs.getFloat("rect_top", -1f)
        val r = prefs.getFloat("rect_right", -1f)
        val b = prefs.getFloat("rect_bottom", -1f)
        if (l > 0 && r > l && b > t) {
            tableRect.set(l, t, r, b)
            return true
        }
        return false
    }

    fun getPocketsList(): List<PointF> {
        val cx = tableRect.centerX()
        val cornerInset = ballRadius * 0.95f
        val sideInset = ballRadius * 0.35f
        return listOf(
            PointF(tableRect.left + cornerInset, tableRect.top + cornerInset),       // 0: Sup Izq
            PointF(cx, tableRect.top + sideInset),                                   // 1: Centro Sup
            PointF(tableRect.right - cornerInset, tableRect.top + cornerInset),      // 2: Sup Der
            PointF(tableRect.left + cornerInset, tableRect.bottom - cornerInset),    // 3: Inf Izq
            PointF(cx, tableRect.bottom - sideInset),                                // 4: Centro Inf
            PointF(tableRect.right - cornerInset, tableRect.bottom - cornerInset)    // 5: Inf Der
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Línea sutil del límite interior de las bandas de la mesa y las troneras
        canvas.drawRect(tableRect, paintInnerCushion)
        drawPockets(canvas)

        // 2. Trazar las líneas de referencia EXACTAS como en las imágenes de referencia
        drawAimAndReboundGuides(canvas)

        // 3. Dibujar las dos bolas (Blanca y a Anotar) con sus anillos
        drawBilliardBalls(canvas)

        // 4. Modos de ajuste
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.ADJUST_AIM) {
            canvas.drawText(
                "🎯 Arrastra la bola blanca o la bola a anotar. Toca '🎮 Jugar' al terminar.",
                tableRect.left + 20f, tableRect.top - 35f, paintHint
            )
            // Dibujar manija para rotar el tiro
            val handleDist = ballRadius * 3.2f
            val hx = cueBall.x + cos(aimAngle) * handleDist
            val hy = cueBall.y + sin(aimAngle) * handleDist
            canvas.drawLine(cueBall.x, cueBall.y, hx, hy, paintCueLine)
            canvas.drawCircle(hx, hy, 12f, paintAimHandle)
        }
    }

    private fun drawPockets(canvas: Canvas) {
        val pockets = getPocketsList()
        val cornerRadius = ballRadius * 1.6f
        val sideRadius = ballRadius * 1.35f

        for (i in pockets.indices) {
            val p = pockets[i]
            val r = if (i == 1 || i == 4) sideRadius else cornerRadius
            canvas.drawCircle(p.x, p.y, r, paintPocket)
            canvas.drawCircle(p.x, p.y, r, paintPocketRim)
        }
    }

    /**
     * DIBUJA AMBAS LÍNEAS DE REFERENCIA:
     * - BOLA BLANCA: Línea blanca con círculo de impacto y rebotes de la blanca.
     * - BOLA A ANOTAR: Línea del color seleccionado con rebotes multi-banda y círculos en cada banda.
     */
    private fun drawAimAndReboundGuides(canvas: Canvas) {
        val dirX = cos(aimAngle)
        val dirY = sin(aimAngle)

        // Comprobar colisión entre la trayectoria de la bola blanca y la bola a anotar
        val toTargetX = targetBall.x - cueBall.x
        val toTargetY = targetBall.y - cueBall.y
        val proj = toTargetX * dirX + toTargetY * dirY
        val perp = abs(toTargetX * dirY - toTargetY * dirX)

        val hitsTargetBall = (proj > ballRadius * 0.5f) && (perp < ballRadius * 2.0f)

        val colorConfig = TargetLineColor.values()[currentColorIndex]
        paintTargetLineCore.color = colorConfig.mainColor
        paintTargetRing.color = colorConfig.mainColor

        if (hitsTargetBall) {
            // ==============================================================
            // CASO A: LA BLANCA IMPACTA EN LA BOLA A ANOTAR (Fotos 2 y 3)
            // ==============================================================
            val dOffset = sqrt(max(0f, (ballRadius * 2f).pow(2) - perp.pow(2)))
            val distToGhost = proj - dOffset
            val ghostX = cueBall.x + dirX * distToGhost
            val ghostY = cueBall.y + dirY * distToGhost

            // 1. Línea Blanca: desde la bola blanca hasta la Bola Fantasma (Ghost Ball)
            canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueLine)
            // Círculo blanco de la Bola Fantasma
            canvas.drawCircle(ghostX, ghostY, ballRadius, paintCueRing)

            // 2. Vector de impulso sobre la Bola a Anotar (de centro a centro)
            val potDx = targetBall.x - ghostX
            val potDy = targetBall.y - ghostY
            val potDist = hypot(potDx.toDouble(), potDy.toDouble()).toFloat()
            val potDirX = if (potDist > 0.1f) potDx / potDist else dirX
            val potDirY = if (potDist > 0.1f) potDy / potDist else dirY

            // 3. Trazar trayectoria multi-banda de la BOLA A ANOTAR (como en Foto 2 y 3)
            val targetBounces = traceRayWithBounces(targetBall, potDirX, potDirY, maxBounces = 4)
            drawTargetTrajectoryPath(canvas, targetBounces)

            // 4. Vector de desvío tangencial de la BOLA BLANCA (Regla de los 90°)
            val cross = dirX * potDirY - dirY * potDirX
            val tanX = if (cross > 0) -potDirY else potDirY
            val tanY = if (cross > 0) potDirX else -potDirX

            val cueBounces = traceRayWithBounces(PointF(ghostX, ghostY), tanX, tanY, maxBounces = 3)
            drawCueTrajectoryPath(canvas, cueBounces)

        } else {
            // ==============================================================
            // CASO B: LA BLANCA VA DIRECTA A LA BANDA (Foto 1)
            // ==============================================================
            val cueBounces = traceRayWithBounces(cueBall, dirX, dirY, maxBounces = 4)
            drawCueTrajectoryPath(canvas, cueBounces)
        }
    }

    /**
     * Dibuja la polilínea blanca de la Bola Blanca con círculos (○) en cada impacto de banda
     */
    private fun drawCueTrajectoryPath(canvas: Canvas, bounces: List<CushionBounce>) {
        if (bounces.size < 2) return

        for (i in 0 until bounces.size - 1) {
            val p1 = bounces[i].point
            val p2 = bounces[i + 1].point
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintCueLine)

            // Círculo blanco (○) en el punto de contacto con la banda
            if (i > 0) {
                canvas.drawCircle(p1.x, p1.y, ballRadius, paintCueRing)
            }
        }

        // Círculo final donde descansará la bola blanca
        val lastPoint = bounces.last().point
        canvas.drawCircle(lastPoint.x, lastPoint.y, ballRadius, paintCueRing)
    }

    /**
     * Dibuja la trayectoria de la BOLA A ANOTAR con el color distintivo y círculos (○) en cada banda
     */
    private fun drawTargetTrajectoryPath(canvas: Canvas, bounces: List<CushionBounce>) {
        if (bounces.size < 2) return

        // Dibujar contorno exterior si el color es oscuro para máximo contraste
        val isDarkColor = (currentColorIndex == 0) // Negro
        for (i in 0 until bounces.size - 1) {
            val p1 = bounces[i].point
            val p2 = bounces[i + 1].point

            if (isDarkColor) {
                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintTargetLineOutline)
            }
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintTargetLineCore)

            // Círculo (○) en cada punto de rebote en la banda
            if (i > 0) {
                if (isDarkColor) {
                    canvas.drawCircle(p1.x, p1.y, ballRadius, paintCueRing)
                }
                canvas.drawCircle(p1.x, p1.y, ballRadius, paintTargetRing)
            }
        }

        // Círculo final donde entrará o descansará la bola a anotar
        val lastPoint = bounces.last().point
        if (isDarkColor) {
            canvas.drawCircle(lastPoint.x, lastPoint.y, ballRadius, paintCueRing)
        }
        canvas.drawCircle(lastPoint.x, lastPoint.y, ballRadius, paintTargetRing)
    }

    /**
     * Trazador de rayos con rebote en las bandas de goma (con detección de entrada en buchacas)
     */
    private fun traceRayWithBounces(startPos: PointF, initialDirX: Float, initialDirY: Float, maxBounces: Int): List<CushionBounce> {
        val bounces = mutableListOf<CushionBounce>()
        bounces.add(CushionBounce(PointF(startPos.x, startPos.y), CushionSide.NONE))

        var curOrigin = PointF(startPos.x, startPos.y)
        var curDirX = initialDirX
        var curDirY = initialDirY

        val pockets = getPocketsList()
        val pocketMouthRadius = ballRadius * 1.5f

        for (b in 0 until maxBounces) {
            val hit = raycastCushion(curOrigin, curDirX, curDirY)

            // Comprobar si entra a alguna de las 6 buchacas
            var hitPocketIndex = -1
            for (i in pockets.indices) {
                val p = pockets[i]
                if (hypot((hit.point.x - p.x).toDouble(), (hit.point.y - p.y).toDouble()) < pocketMouthRadius) {
                    hitPocketIndex = i
                    break
                }
            }

            if (hitPocketIndex != -1) {
                // Entra limpio al centro de la buchaca
                val pocketPos = pockets[hitPocketIndex]
                bounces.add(CushionBounce(PointF(pocketPos.x, pocketPos.y), hit.cushionSide, isPocket = true))
                break
            }

            bounces.add(hit)

            // Reflejar vector de dirección en la banda
            curDirX = if (hit.cushionSide == CushionSide.LEFT || hit.cushionSide == CushionSide.RIGHT) -curDirX else curDirX
            curDirY = if (hit.cushionSide == CushionSide.TOP || hit.cushionSide == CushionSide.BOTTOM) -curDirY else curDirY

            // Avanzar ligeramente para no re-chocar con la misma banda
            curOrigin = PointF(hit.point.x + curDirX * 1.0f, hit.point.y + curDirY * 1.0f)
        }

        return bounces
    }

    private fun raycastCushion(origin: PointF, dirX: Float, dirY: Float): CushionBounce {
        var minT = Float.MAX_VALUE
        var hitSide = CushionSide.NONE
        var hitX = origin.x
        var hitY = origin.y

        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius

        if (dirX < -0.0001f) {
            val t = (boundLeft - origin.x) / dirX
            if (t > 0.2f && t < minT) {
                val y = origin.y + t * dirY
                if (y in boundTop..boundBottom) {
                    minT = t
                    hitSide = CushionSide.LEFT
                    hitX = boundLeft
                    hitY = y
                }
            }
        }
        if (dirX > 0.0001f) {
            val t = (boundRight - origin.x) / dirX
            if (t > 0.2f && t < minT) {
                val y = origin.y + t * dirY
                if (y in boundTop..boundBottom) {
                    minT = t
                    hitSide = CushionSide.RIGHT
                    hitX = boundRight
                    hitY = y
                }
            }
        }
        if (dirY < -0.0001f) {
            val t = (boundTop - origin.y) / dirY
            if (t > 0.2f && t < minT) {
                val x = origin.x + t * dirX
                if (x in boundLeft..boundRight) {
                    minT = t
                    hitSide = CushionSide.TOP
                    hitX = x
                    hitY = boundTop
                }
            }
        }
        if (dirY > 0.0001f) {
            val t = (boundBottom - origin.y) / dirY
            if (t > 0.2f && t < minT) {
                val x = origin.x + t * dirX
                if (x in boundLeft..boundRight) {
                    minT = t
                    hitSide = CushionSide.BOTTOM
                    hitX = x
                    hitY = boundBottom
                }
            }
        }

        return CushionBounce(PointF(hitX, hitY), hitSide)
    }

    private fun drawBilliardBalls(canvas: Canvas) {
        // Bola Blanca
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueFill)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueRing)
        canvas.drawCircle(cueBall.x, cueBall.y, 3.5f, paintCueDot)

        // Bola a Anotar (Target Ball)
        val colorConfig = TargetLineColor.values()[currentColorIndex]
        paintTargetFill.color = colorConfig.mainColor
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, paintTargetFill)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, paintCueRing)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius * 0.42f, paintCueFill)
    }

    private fun drawAdjustmentHandles(canvas: Canvas) {
        val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        paintCorner.color = Color.RED
        canvas.drawCircle(tableRect.left, tableRect.top, 30f, paintCorner)
        paintCorner.color = Color.YELLOW
        canvas.drawCircle(tableRect.right, tableRect.top, 30f, paintCorner)
        paintCorner.color = Color.CYAN
        canvas.drawCircle(tableRect.left, tableRect.bottom, 30f, paintCorner)
        paintCorner.color = Color.GREEN
        canvas.drawCircle(tableRect.right, tableRect.bottom, 30f, paintCorner)

        canvas.drawText(
            "📐 Arrastra las 4 esquinas a las troneras de la mesa de tu juego. Luego toca '🎮 Jugar'.",
            tableRect.left + 20f, tableRect.top - 35f, paintHint
        )
    }

    /**
     * Detección por visión si se utiliza screen capture
     */
    fun detectAllBallsAndAimFromBitmap(bmp: Bitmap) {
        val left = tableRect.left.toInt().coerceIn(0, bmp.width - 1)
        val right = tableRect.right.toInt().coerceIn(0, bmp.width - 1)
        val top = tableRect.top.toInt().coerceIn(0, bmp.height - 1)
        val bottom = tableRect.bottom.toInt().coerceIn(0, bmp.height - 1)

        if (right <= left || bottom <= top) return

        // 1. Color promedio del paño
        val midX = (left + right) / 2
        val midY = (top + bottom) / 2
        val pMid = bmp.getPixel(midX, midY)
        val feltR = Color.red(pMid)
        val feltG = Color.green(pMid)
        val feltB = Color.blue(pMid)

        val step = max(4, (ballRadius / 5).toInt())
        val whitePoints = mutableListOf<PointF>()
        val ballPoints = mutableListOf<PointF>()

        for (y in (top + ballRadius.toInt())..(bottom - ballRadius.toInt()) step step) {
            for (x in (left + ballRadius.toInt())..(right - ballRadius.toInt()) step step) {
                val pix = bmp.getPixel(x, y)
                val r = Color.red(pix)
                val g = Color.green(pix)
                val b = Color.blue(pix)

                if (r > 215 && g > 215 && b > 215 && abs(r - g) < 18 && abs(g - b) < 18) {
                    whitePoints.add(PointF(x.toFloat(), y.toFloat()))
                } else {
                    val diff = abs(r - feltR) + abs(g - feltG) + abs(b - feltB)
                    if (diff > 55 || (r < 40 && g < 40 && b < 40)) {
                        ballPoints.add(PointF(x.toFloat(), y.toFloat()))
                    }
                }
            }
        }

        // Centroide de bola blanca
        if (whitePoints.size >= 6) {
            var sx = 0f; var sy = 0f
            for (pt in whitePoints) { sx += pt.x; sy += pt.y }
            cueBall.x = sx / whitePoints.size
            cueBall.y = sy / whitePoints.size
        }

        // Agrupar centros de otras bolas
        detectedBalls.clear()
        val clusterDist = ballRadius * 1.35f
        for (pt in ballPoints) {
            if (hypot((pt.x - cueBall.x).toDouble(), (pt.y - cueBall.y).toDouble()) < clusterDist) continue

            var merged = false
            for (b in detectedBalls) {
                if (hypot((pt.x - b.x).toDouble(), (pt.y - b.y).toDouble()) < clusterDist) {
                    b.x = (b.x + pt.x) * 0.5f
                    b.y = (b.y + pt.y) * 0.5f
                    merged = true
                    break
                }
            }
            if (!merged && detectedBalls.size < 16) {
                detectedBalls.add(PointF(pt.x, pt.y))
            }
        }

        // Detectar taco / dirección de tiro
        var maxBrightness = -1
        var detectedAngle = aimAngle
        val sampleRadius = ballRadius * 2.4f

        for (deg in 0 until 360 step 3) {
            val rad = Math.toRadians(deg.toDouble()).toFloat()
            val sx = (cueBall.x + cos(rad) * sampleRadius).toInt().coerceIn(0, bmp.width - 1)
            val sy = (cueBall.y + sin(rad) * sampleRadius).toInt().coerceIn(0, bmp.height - 1)

            val pix = bmp.getPixel(sx, sy)
            val brightness = (Color.red(pix) + Color.green(pix) + Color.blue(pix)) / 3

            if (brightness > maxBrightness && brightness > 170) {
                maxBrightness = brightness
                detectedAngle = rad
            }
        }
        aimAngle = detectedAngle

        // Asignar bola a anotar más cercana en la línea de tiro
        val aimDirX = cos(aimAngle)
        val aimDirY = sin(aimAngle)
        var closestTarget: PointF? = null
        var closestDist = Float.MAX_VALUE

        for (ball in detectedBalls) {
            val toBx = ball.x - cueBall.x
            val toBy = ball.y - cueBall.y
            val dot = toBx * aimDirX + toBy * aimDirY
            if (dot > ballRadius * 0.8f) {
                val perpDist = abs(toBx * aimDirY - toBy * aimDirX)
                if (perpDist < ballRadius * 2.2f && dot < closestDist) {
                    closestDist = dot
                    closestTarget = ball
                }
            }
        }

        if (closestTarget != null) {
            targetBall.set(closestTarget.x, closestTarget.y)
        }

        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.LOCKED_PLAY) return false
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Calcular posición de la manija de apuntado
                val handleDist = ballRadius * 3.2f
                val hx = cueBall.x + cos(aimAngle) * handleDist
                val hy = cueBall.y + sin(aimAngle) * handleDist

                activeDragItem = when {
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 70f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 70f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 70f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 70f -> 3
                    hypot(x - hx, y - hy) < 50f -> 6 // Manija de puntería
                    hypot(x - cueBall.x, y - cueBall.y) < 65f -> 4 // Bola blanca
                    hypot(x - targetBall.x, y - targetBall.y) < 65f -> 5 // Bola objetivo
                    else -> -1
                }

                // Si toca en cualquier otra parte del tapete, apunta en esa dirección
                if (activeDragItem == -1 && tableRect.contains(x, y)) {
                    aimAngle = atan2(y - cueBall.y, x - cueBall.x)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeDragItem) {
                    0 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                    1 -> { tableRect.right = x; tableRect.top = y; invalidate() }
                    2 -> { tableRect.left = x; tableRect.bottom = y; invalidate() }
                    3 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                    4 -> {
                        cueBall.set(x, y)
                        aimAngle = atan2(targetBall.y - cueBall.y, targetBall.x - cueBall.x)
                        invalidate()
                    }
                    5 -> {
                        targetBall.set(x, y)
                        aimAngle = atan2(targetBall.y - cueBall.y, targetBall.x - cueBall.x)
                        invalidate()
                    }
                    6 -> {
                        aimAngle = atan2(y - cueBall.y, x - cueBall.x)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeDragItem in 0..3) {
                    saveTableRect()
                }
                activeDragItem = -1
            }
        }
        return true
    }
}