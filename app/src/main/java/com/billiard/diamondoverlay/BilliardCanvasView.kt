package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { LOCKED_PLAY, ADJUST_TABLE }
    enum class CushionSide { LEFT, TOP, RIGHT, BOTTOM, NONE }

    data class RaySegment(
        val p1: PointF,
        val p2: PointF,
        val hitSide: CushionSide = CushionSide.NONE,
        val isPocket: Boolean = false
    )

    enum class TargetLineColor(val label: String, val mainColor: Int, val outlineColor: Int) {
        BLACK("Negro", Color.parseColor("#151515"), Color.WHITE),
        GOLD("Oro", Color.parseColor("#FFD600"), Color.parseColor("#FFF9C4")),
        CYAN("Cyan", Color.parseColor("#00E5FF"), Color.WHITE),
        GREEN("Verde", Color.parseColor("#00E676"), Color.WHITE),
        RED("Rojo", Color.parseColor("#FF1744"), Color.WHITE)
    }

    var mode = Mode.LOCKED_PLAY
    var currentColorIndex = 0

    // Rectángulo del tapete de juego
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(700f, 650f)       // Bola Blanca
    var aimAngle = 0f                      // Ángulo de disparo del taco
    var ballRadius = 25f                   // Radio automático calculado de la bola

    // Lista inmutable atómica de todas las bolas detectadas en la mesa (100% thread-safe)
    @Volatile var detectedBalls: List<PointF> = emptyList()

    private var activeDragItem = -1
    private val prefs = context.getSharedPreferences("diamond_guide_prefs", Context.MODE_PRIVATE)

    // Pinceles del tapete y troneras
    private val paintInnerCushion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 229, 255)
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

    // 1. PINCELES DE LA PRIMERA TRAYECTORIA (BOLA BLANCA DESDE LA PUNTA DEL TACO)
    private val paintCueLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        setShadowLayer(7f, 0f, 0f, Color.argb(200, 255, 255, 255))
    }
    private val paintCueRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
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

    // 2. PINCELES DE LA SEGUNDA TRAYECTORIA (BOLA A ANOTAR TRAS EL IMPACTO)
    private val paintTargetLineCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
    }
    private val paintTargetLineOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.4f
    }
    private val paintTargetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
    }

    // Pincel para visualizar todas las bolas detectadas en la mesa
    private val paintDetectedBallOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        currentColorIndex = prefs.getInt("target_color_index", 0)
    }

    fun cycleTargetColor(): String {
        val colors = TargetLineColor.values()
        currentColorIndex = (currentColorIndex + 1) % colors.size
        prefs.edit().putInt("target_color_index", currentColorIndex).apply()
        invalidate()
        return colors[currentColorIndex].label
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!loadTableRect()) {
            val feltH = h * 0.72f
            val feltW = feltH * 2.0f
            val left = (w - feltW) / 2f
            val top = h * 0.14f
            tableRect.set(left, top, left + feltW, top + feltH)
            cueBall.set(tableRect.left + tableRect.width() * 0.35f, tableRect.centerY() + 60f)
            aimAngle = 0f
        }
        // Radio de bola automático estándar según proporciones de 8 Ball Pool
        ballRadius = tableRect.height() * 0.042f
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

    /**
     * Actualiza la detección de visión proveniente del hilo en segundo plano (sin bloquear la UI)
     */
    fun updateDetectionFromBackground(
        cueX: Float,
        cueY: Float,
        angle: Float,
        balls: List<PointF>,
        detectedFelt: RectF? = null
    ) {
        // Si es la primera detección o las coordenadas estaban en default, saltar inmediatamente
        if (cueBall.x == 700f && cueBall.y == 650f) {
            cueBall.x = cueX
            cueBall.y = cueY
            aimAngle = angle
        } else {
            // Suavizado suave de posición de bola blanca
            cueBall.x = cueBall.x * 0.25f + cueX * 0.75f
            cueBall.y = cueBall.y * 0.25f + cueY * 0.75f

            // Suavizado del ángulo del taco
            val angleDiff = atan2(sin(angle - aimAngle), cos(angle - aimAngle))
            aimAngle += (angleDiff * 0.70f)
        }

        // Calibración automática del tapete
        if (detectedFelt != null && detectedFelt.width() > 400f && detectedFelt.height() > 200f) {
            if (tableRect.left == 260f || abs(tableRect.left - detectedFelt.left) > 40f) {
                tableRect.set(detectedFelt)
                ballRadius = tableRect.height() * 0.042f
            }
        }

        detectedBalls = ArrayList(balls)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Límites interiores de la mesa y las troneras
        canvas.drawRect(tableRect, paintInnerCushion)
        drawPockets(canvas)

        // 2. Indicadores sutiles de todas las bolas detectadas en la mesa (copia inmutable snapshot)
        val currentBalls = detectedBalls
        for (ball in currentBalls) {
            if (hypot((ball.x - cueBall.x).toDouble(), (ball.y - cueBall.y).toDouble()) > ballRadius * 1.4f) {
                canvas.drawCircle(ball.x, ball.y, ballRadius, paintDetectedBallOutline)
            }
        }

        // 3. SIMULACIÓN DINÁMICA DUAL:
        //    - Línea 1: Desde la punta del taco a través de la bola blanca con todos sus rebotes.
        //    - Línea 2: Si la trayectoria de la blanca toca algún borde de otra bola, se genera
        //      automáticamente la segunda trayectoria indicando hacia dónde irá a anotar,
        //      sin perder la dinámica de ambas líneas.
        drawDualDynamicTrajectories(canvas)

        // 4. Dibujar la bola blanca
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueFill)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueRing)
        canvas.drawCircle(cueBall.x, cueBall.y, 3.5f, paintCueDot)

        // 5. Ajuste manual de esquinas si se activa
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
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
     * DIBUJA AMBAS TRAYECTORIAS DINÁMICAS EN TIEMPO REAL:
     * - Primera trayectoria: de la punta del taco a través de la blanca con todos sus rebotes.
     * - Segunda trayectoria: generada al tocar cualquier bola objetivo, mostrando a dónde irá a anotar.
     */
    private fun drawDualDynamicTrajectories(canvas: Canvas) {
        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius
        if (boundRight <= boundLeft || boundBottom <= boundTop) return

        val pockets = getPocketsList()
        val pocketRadius = ballRadius * 1.55f

        val cuePoints = mutableListOf<PointF>()
        cuePoints.add(PointF(cueBall.x, cueBall.y))
        val cueBouncePoints = mutableListOf<PointF>()

        var curPos = PointF(cueBall.x, cueBall.y)
        var curDirX = cos(aimAngle)
        var curDirY = sin(aimAngle)

        var hitBall: PointF? = null
        var ghostPoint: PointF? = null
        var impulseDirX = 0f
        var impulseDirY = 0f
        var deflectDirX = 0f
        var deflectDirY = 0f

        val maxWhiteBounces = 4
        val currentBalls = detectedBalls

        for (b in 0..maxWhiteBounces) {
            // A. Comprobar si la trayectoria blanca toca algún pedazo o borde de CUALQUIER bola en la mesa
            var nearestHitDist = Float.MAX_VALUE
            var nearestBall: PointF? = null

            for (ball in currentBalls) {
                if (hypot((ball.x - cueBall.x).toDouble(), (ball.y - cueBall.y).toDouble()) < ballRadius * 1.3f) continue

                val toTx = ball.x - curPos.x
                val toTy = ball.y - curPos.y
                val proj = toTx * curDirX + toTy * curDirY

                if (proj > 0.05f) {
                    val perpSq = (toTx * toTx + toTy * toTy) - (proj * proj)
                    val twoR = ballRadius * 2f
                    val twoRSq = twoR * twoR

                    if (perpSq in 0f..twoRSq) {
                        val offset = sqrt(max(0f, twoRSq - perpSq))
                        val d = proj - offset
                        if (d > 0.05f && d < nearestHitDist) {
                            nearestHitDist = d
                            nearestBall = ball
                        }
                    }
                }
            }

            // B. Comprobar distancia a la banda más cercana en este tramo
            var cushionDist = Float.MAX_VALUE
            var hitSide = CushionSide.NONE
            var hitCushionX = curPos.x
            var hitCushionY = curPos.y

            if (curDirX < -0.0001f) {
                val t = (boundLeft - curPos.x) / curDirX
                if (t > 0.2f && t < cushionDist) {
                    val y = curPos.y + t * curDirY
                    if (y in boundTop..boundBottom) {
                        cushionDist = t; hitSide = CushionSide.LEFT; hitCushionX = boundLeft; hitCushionY = y
                    }
                }
            }
            if (curDirX > 0.0001f) {
                val t = (boundRight - curPos.x) / curDirX
                if (t > 0.2f && t < cushionDist) {
                    val y = curPos.y + t * curDirY
                    if (y in boundTop..boundBottom) {
                        cushionDist = t; hitSide = CushionSide.RIGHT; hitCushionX = boundRight; hitCushionY = y
                    }
                }
            }
            if (curDirY < -0.0001f) {
                val t = (boundTop - curPos.y) / curDirY
                if (t > 0.2f && t < cushionDist) {
                    val x = curPos.x + t * curDirX
                    if (x in boundLeft..boundRight) {
                        cushionDist = t; hitSide = CushionSide.TOP; hitCushionX = x; hitCushionY = boundTop
                    }
                }
            }
            if (curDirY > 0.0001f) {
                val t = (boundBottom - curPos.y) / curDirY
                if (t > 0.2f && t < cushionDist) {
                    val x = curPos.x + t * curDirX
                    if (x in boundLeft..boundRight) {
                        cushionDist = t; hitSide = CushionSide.BOTTOM; hitCushionX = x; hitCushionY = boundBottom
                    }
                }
            }

            // C. Determinar si choca primero con la bola o con la banda
            if (nearestBall != null && nearestHitDist < cushionDist && nearestHitDist < 3000f) {
                // ¡COLISIÓN DETECTADA CON BOLA OBJETIVO!
                hitBall = nearestBall
                val gx = curPos.x + curDirX * nearestHitDist
                val gy = curPos.y + curDirY * nearestHitDist
                ghostPoint = PointF(gx, gy)
                cuePoints.add(PointF(gx, gy))

                // Vector normal de impacto (impulso hacia donde saldrá disparada la bola objetivo)
                val ndx = nearestBall.x - gx
                val ndy = nearestBall.y - gy
                val nLen = hypot(ndx.toDouble(), ndy.toDouble()).toFloat()
                impulseDirX = if (nLen > 0.01f) ndx / nLen else curDirX
                impulseDirY = if (nLen > 0.01f) ndy / nLen else curDirY

                // Vector de desvío a 90° de la bola blanca
                val cross = curDirX * impulseDirY - curDirY * impulseDirX
                if (cross > 0) {
                    deflectDirX = -impulseDirY
                    deflectDirY = impulseDirX
                } else {
                    deflectDirX = impulseDirY
                    deflectDirY = -impulseDirX
                }

                break
            } else if (cushionDist < 3000f) {
                val hitPt = PointF(hitCushionX, hitCushionY)
                cuePoints.add(hitPt)

                var fellInPocket = false
                for (p in pockets) {
                    if (hypot((hitPt.x - p.x).toDouble(), (hitPt.y - p.y).toDouble()) < pocketRadius) {
                        fellInPocket = true
                        break
                    }
                }
                if (fellInPocket) break

                cueBouncePoints.add(PointF(hitPt.x, hitPt.y))

                curDirX = if (hitSide == CushionSide.LEFT || hitSide == CushionSide.RIGHT) -curDirX else curDirX
                curDirY = if (hitSide == CushionSide.TOP || hitSide == CushionSide.BOTTOM) -curDirY else curDirY
                curPos = PointF(hitCushionX + curDirX * 1.0f, hitCushionY + curDirY * 1.0f)
            } else {
                break
            }
        }

        // =========================================================================
        // 1. DIBUJAR LA PRIMERA TRAYECTORIA (BOLA BLANCA)
        // =========================================================================
        for (i in 0 until cuePoints.size - 1) {
            val p1 = cuePoints[i]
            val p2 = cuePoints[i + 1]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintCueLine)
        }

        for (bp in cueBouncePoints) {
            canvas.drawCircle(bp.x, bp.y, ballRadius, paintCueRing)
        }

        // Si la blanca no toca ninguna bola, continúa mostrando todos sus rebotes (Foto 1)
        if (hitBall == null || ghostPoint == null) {
            val lastPt = cuePoints.last()
            canvas.drawCircle(lastPt.x, lastPt.y, ballRadius, paintCueRing)
            return
        }

        // =========================================================================
        // 2. CUANDO TOCA OTRA BOLA:
        //    A) Círculo de la Bola Fantasma (○) en el punto de contacto
        //    B) Desvío de la bola blanca sin perder la dinámica de la primera trayectoria
        //    C) GENERACIÓN AUTOMÁTICA DE LA SEGUNDA TRAYECTORIA (a dónde irá a anotar)
        // =========================================================================
        val gp = ghostPoint
        canvas.drawCircle(gp.x, gp.y, ballRadius, paintCueRing)

        // B) Desvío de la bola blanca tras el impacto
        val cueDeflectSegments = traceRaySegments(gp, deflectDirX, deflectDirY, maxBounces = 3)
        for (seg in cueDeflectSegments) {
            canvas.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y, paintCueLine)
            if (seg.hitSide != CushionSide.NONE && !seg.isPocket) {
                canvas.drawCircle(seg.p2.x, seg.p2.y, ballRadius, paintCueRing)
            }
        }
        if (cueDeflectSegments.isNotEmpty()) {
            val endPt = cueDeflectSegments.last().p2
            canvas.drawCircle(endPt.x, endPt.y, ballRadius, paintCueRing)
        }

        // C) SEGUNDA TRAYECTORIA: DE LA BOLA A ANOTAR HACIA LA BUCHACA CON SUS REBOTES
        val targetColor = TargetLineColor.values()[currentColorIndex]
        paintTargetLineCore.color = targetColor.mainColor
        paintTargetLineOutline.color = targetColor.outlineColor
        paintTargetRing.color = targetColor.mainColor

        val targetSegments = traceRaySegments(hitBall, impulseDirX, impulseDirY, maxBounces = 4)
        val isDark = (currentColorIndex == 0) // Negro con fino contorno blanco

        for (seg in targetSegments) {
            if (isDark) {
                canvas.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y, paintTargetLineOutline)
            }
            canvas.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y, paintTargetLineCore)

            if (seg.hitSide != CushionSide.NONE && !seg.isPocket) {
                if (isDark) {
                    canvas.drawCircle(seg.p2.x, seg.p2.y, ballRadius, paintCueRing)
                }
                canvas.drawCircle(seg.p2.x, seg.p2.y, ballRadius, paintTargetRing)
            }
        }
        if (targetSegments.isNotEmpty()) {
            val endPt = targetSegments.last().p2
            if (isDark) {
                canvas.drawCircle(endPt.x, endPt.y, ballRadius, paintCueRing)
            }
            canvas.drawCircle(endPt.x, endPt.y, ballRadius, paintTargetRing)
        }
    }

    private fun traceRaySegments(startPos: PointF, dirX: Float, dirY: Float, maxBounces: Int): List<RaySegment> {
        val segments = mutableListOf<RaySegment>()
        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius
        val pockets = getPocketsList()
        val pocketRadius = ballRadius * 1.55f

        var curPos = PointF(startPos.x, startPos.y)
        var curDirX = dirX
        var curDirY = dirY

        for (b in 0 until maxBounces) {
            var minT = Float.MAX_VALUE
            var hitSide = CushionSide.NONE
            var hitX = curPos.x
            var hitY = curPos.y

            if (curDirX < -0.0001f) {
                val t = (boundLeft - curPos.x) / curDirX
                if (t > 0.2f && t < minT) {
                    val y = curPos.y + t * curDirY
                    if (y in boundTop..boundBottom) {
                        minT = t; hitSide = CushionSide.LEFT; hitX = boundLeft; hitY = y
                    }
                }
            }
            if (curDirX > 0.0001f) {
                val t = (boundRight - curPos.x) / curDirX
                if (t > 0.2f && t < minT) {
                    val y = curPos.y + t * curDirY
                    if (y in boundTop..boundBottom) {
                        minT = t; hitSide = CushionSide.RIGHT; hitX = boundRight; hitY = y
                    }
                }
            }
            if (curDirY < -0.0001f) {
                val t = (boundTop - curPos.y) / curDirY
                if (t > 0.2f && t < minT) {
                    val x = curPos.x + t * curDirX
                    if (x in boundLeft..boundRight) {
                        minT = t; hitSide = CushionSide.TOP; hitX = x; hitY = boundTop
                    }
                }
            }
            if (curDirY > 0.0001f) {
                val t = (boundBottom - curPos.y) / curDirY
                if (t > 0.2f && t < minT) {
                    val x = curPos.x + t * curDirX
                    if (x in boundLeft..boundRight) {
                        minT = t; hitSide = CushionSide.BOTTOM; hitX = x; hitY = boundBottom
                    }
                }
            }

            if (minT > 5000f) break

            val hitPt = PointF(hitX, hitY)

            var inPocket = false
            for (p in pockets) {
                if (hypot((hitPt.x - p.x).toDouble(), (hitPt.y - p.y).toDouble()) < pocketRadius) {
                    inPocket = true
                    segments.add(RaySegment(PointF(curPos.x, curPos.y), PointF(p.x, p.y), hitSide, isPocket = true))
                    break
                }
            }

            if (inPocket) break

            segments.add(RaySegment(PointF(curPos.x, curPos.y), hitPt, hitSide, isPocket = false))

            curDirX = if (hitSide == CushionSide.LEFT || hitSide == CushionSide.RIGHT) -curDirX else curDirX
            curDirY = if (hitSide == CushionSide.TOP || hitSide == CushionSide.BOTTOM) -curDirY else curDirY
            curPos = PointF(hitX + curDirX * 1.0f, hitY + curDirY * 1.0f)
        }

        return segments
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
            "📐 Arrastra las 4 esquinas a las troneras de la mesa de tu juego.",
            tableRect.left + 20f, tableRect.top - 35f, paintHint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.LOCKED_PLAY) return false
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeDragItem = when {
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 70f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 70f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 70f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 70f -> 3
                    else -> -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeDragItem) {
                    0 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                    1 -> { tableRect.right = x; tableRect.top = y; invalidate() }
                    2 -> { tableRect.left = x; tableRect.bottom = y; invalidate() }
                    3 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeDragItem in 0..3) {
                    saveTableRect()
                    ballRadius = tableRect.height() * 0.042f
                }
                activeDragItem = -1
            }
        }
        return true
    }
}