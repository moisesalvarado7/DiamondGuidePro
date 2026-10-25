package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { LOCKED_PLAY, ADJUST_AIM, ADJUST_TABLE }
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

    // Rectángulo del tapete de juego (límites interiores de las bandas de goma)
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(700f, 650f)       // Bola Blanca
    var targetBall = PointF(1500f, 520f)   // Bola a Anotar (Bola Negra u Objetivo)
    var ballRadius = 25f                   // Radio calibrable de la bola (22..30px)
    var aimAngle = 0f                      // Ángulo de puntería en radianes

    // Lista de otras bolas detectadas por la cámara
    val detectedBalls = mutableListOf<PointF>()

    private var activeDragItem = -1 // 0..3=Esquinas, 4=Blanca, 5=Objetivo, 6=Apuntar
    private val prefs = context.getSharedPreferences("diamond_guide_prefs", Context.MODE_PRIVATE)

    // Pinceles de las bandas y troneras
    private val paintInnerCushion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 229, 255)
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

    // 1. PINCELES DE LA BOLA BLANCA (BLANCO PURO NÍTIDO CON HALO)
    private val paintCueLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.6f
        setShadowLayer(6f, 0f, 0f, Color.argb(180, 255, 255, 255))
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

    // 2. PINCELES DE LA BOLA A ANOTAR (LÍNEA OSCURA O DEL COLOR SELECCIONADO)
    private val paintTargetLineCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
    }
    private val paintTargetLineOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.2f
    }
    private val paintTargetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
    }
    private val paintTargetFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Manija de apuntado e indicación
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

        // 1. Límites interiores limpios de las bandas y troneras
        canvas.drawRect(tableRect, paintInnerCushion)
        drawPockets(canvas)

        // 2. SIMULACIÓN Y TRAZADO EXACTO SEGÚN LAS IMÁGENES:
        //    - Traza la trayectoria de la bola blanca con todos sus rebotes en bandas.
        //    - Si la blanca toca algún borde o pedazo de la bola a anotar:
        //      muestra el impacto, el desvío de la blanca y traza la segunda línea de referencia para la bola a anotar.
        drawFullShotSimulation(canvas)

        // 3. Dibujar las dos bolas (Blanca y a Anotar) con sus respectivos aros
        drawBilliardBalls(canvas)

        // 4. Modos de ajuste
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.ADJUST_AIM) {
            canvas.drawText(
                "🎯 Arrastra en la pantalla para apuntar. Arrastra las bolas para posicionarlas. Toca '🎮 Jugar' al terminar.",
                tableRect.left + 20f, tableRect.top - 35f, paintHint
            )
            // Manija de puntería
            val handleDist = ballRadius * 2.8f
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
     * SIMULACIÓN COMPLETA DE FÍSICA Y REBOTES:
     * 1. Traza la trayectoria de la BOLA BLANCA con todos sus rebotes.
     * 2. Comprueba si la blanca colisiona con la BOLA A ANOTAR en algún punto de su trayectoria.
     * 3. Si colisiona:
     *    - Dibuja la bola fantasma en el punto de contacto.
     *    - Traza el desvío de la bola blanca (con sus rebotes).
     *    - Traza la SEGUNDA LÍNEA DE REFERENCIA para la bola a anotar con sus rebotes multi-banda.
     * 4. Si no colisiona:
     *    - Solo traza los rebotes continuos de la bola blanca (como en la Foto 1).
     */
    private fun drawFullShotSimulation(canvas: Canvas) {
        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius
        val pockets = getPocketsList()
        val pocketRadius = ballRadius * 1.55f

        // Trayectoria de la blanca previa al impacto
        val cuePoints = mutableListOf<PointF>()
        cuePoints.add(PointF(cueBall.x, cueBall.y))
        val cueBouncePoints = mutableListOf<PointF>()

        var curPos = PointF(cueBall.x, cueBall.y)
        var curDirX = cos(aimAngle)
        var curDirY = sin(aimAngle)

        var hitTargetBall = false
        var ghostPoint: PointF? = null
        var impulseDirX = 0f
        var impulseDirY = 0f
        var deflectDirX = 0f
        var deflectDirY = 0f

        val maxWhiteBounces = 4

        for (b in 0..maxWhiteBounces) {
            // A. Comprobar si la blanca toca algún borde o pedazo de la bola a anotar
            val toTx = targetBall.x - curPos.x
            val toTy = targetBall.y - curPos.y
            val proj = toTx * curDirX + toTy * curDirY

            var hitDist = Float.MAX_VALUE
            if (proj > 0.05f) {
                val perpSq = (toTx * toTx + toTy * toTy) - (proj * proj)
                val twoR = ballRadius * 2f
                val twoRSq = twoR * twoR

                if (perpSq in 0f..twoRSq) {
                    val offset = sqrt(max(0f, twoRSq - perpSq))
                    val d = proj - offset
                    if (d > 0.05f) {
                        hitDist = d
                    }
                }
            }

            // B. Comprobar distancia al choque con la banda más cercana
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

            // C. Comparar cuál evento ocurre primero
            if (hitDist < cushionDist && hitDist < 3000f) {
                // ¡COLISIÓN CON LA BOLA A ANOTAR!
                hitTargetBall = true
                val gx = curPos.x + curDirX * hitDist
                val gy = curPos.y + curDirY * hitDist
                ghostPoint = PointF(gx, gy)
                cuePoints.add(PointF(gx, gy))

                // Vector de impulso sobre la bola a anotar (de centro a centro)
                val ndx = targetBall.x - gx
                val ndy = targetBall.y - gy
                val nLen = hypot(ndx.toDouble(), ndy.toDouble()).toFloat()
                impulseDirX = if (nLen > 0.01f) ndx / nLen else curDirX
                impulseDirY = if (nLen > 0.01f) ndy / nLen else curDirY

                // Vector de desvío de la blanca a 90° (regla de la tangente)
                val cross = curDirX * impulseDirY - curDirY * impulseDirX
                if (cross > 0) {
                    deflectDirX = -impulseDirY
                    deflectDirY = impulseDirX
                } else {
                    deflectDirX = impulseDirY
                    deflectDirY = -impulseDirX
                }

                break // Termina el recorrido inicial de la blanca
            } else if (cushionDist < 3000f) {
                // Rebote en banda
                val hitPt = PointF(hitCushionX, hitCushionY)
                cuePoints.add(hitPt)

                // Comprobar si entra a una tronera
                var fellInPocket = false
                for (p in pockets) {
                    if (hypot((hitPt.x - p.x).toDouble(), (hitPt.y - p.y).toDouble()) < pocketRadius) {
                        fellInPocket = true
                        break
                    }
                }
                if (fellInPocket) break

                cueBouncePoints.add(PointF(hitPt.x, hitPt.y))

                // Reflejo en la banda
                curDirX = if (hitSide == CushionSide.LEFT || hitSide == CushionSide.RIGHT) -curDirX else curDirX
                curDirY = if (hitSide == CushionSide.TOP || hitSide == CushionSide.BOTTOM) -curDirY else curDirY
                curPos = PointF(hitCushionX + curDirX * 1.0f, hitCushionY + curDirY * 1.0f)
            } else {
                break
            }
        }

        // =========================================================================
        // 1. DIBUJAR LA TRAYECTORIA DE LA BOLA BLANCA
        // =========================================================================
        for (i in 0 until cuePoints.size - 1) {
            val p1 = cuePoints[i]
            val p2 = cuePoints[i + 1]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintCueLine)
        }

        // Aros blancos (○) en cada punto de rebote de la blanca en la banda
        for (bp in cueBouncePoints) {
            canvas.drawCircle(bp.x, bp.y, ballRadius, paintCueRing)
        }

        // Si NO tocó la bola a anotar (Foto 1):
        if (!hitTargetBall) {
            val lastPt = cuePoints.last()
            canvas.drawCircle(lastPt.x, lastPt.y, ballRadius, paintCueRing)
            return // Solo se muestra la blanca, la negra no tiene línea
        }

        // =========================================================================
        // 2. SI TOCÓ LA BOLA A ANOTAR (Fotos 2 y 3):
        //    A) Círculo de la Bola Fantasma (○) en el punto exacto de colisión
        //    B) Trayectoria y rebotes de desvío de la Bola Blanca
        //    C) SEGUNDA LÍNEA DE REFERENCIA: Trayectoria de la Bola a Anotar y sus rebotes
        // =========================================================================
        val gp = ghostPoint!!
        // Aro de la Bola Fantasma
        canvas.drawCircle(gp.x, gp.y, ballRadius, paintCueRing)

        // B) Desvío de la bola blanca (hasta 3 rebotes)
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

        // C) SEGUNDA LÍNEA DE REFERENCIA: BOLA A ANOTAR (hasta 4 rebotes en banda)
        val targetColor = TargetLineColor.values()[currentColorIndex]
        paintTargetLineCore.color = targetColor.mainColor
        paintTargetLineOutline.color = targetColor.outlineColor
        paintTargetRing.color = targetColor.mainColor

        val targetSegments = traceRaySegments(targetBall, impulseDirX, impulseDirY, maxBounces = 4)
        val isDark = (currentColorIndex == 0) // Negro con borde blanco

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

    /**
     * Traza segmentos lineales sucesivos con rebote en las bandas de goma
     */
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

            // Comprobar si entra a una tronera
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

    private fun drawBilliardBalls(canvas: Canvas) {
        // Bola Blanca
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueFill)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueRing)
        canvas.drawCircle(cueBall.x, cueBall.y, 3.5f, paintCueDot)

        // Bola a Anotar (Bola Negra u Objetivo)
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
     * Detección por cámara / visión si está activa
     */
    fun detectAllBallsAndAimFromBitmap(bmp: Bitmap) {
        val left = tableRect.left.toInt().coerceIn(0, bmp.width - 1)
        val right = tableRect.right.toInt().coerceIn(0, bmp.width - 1)
        val top = tableRect.top.toInt().coerceIn(0, bmp.height - 1)
        val bottom = tableRect.bottom.toInt().coerceIn(0, bmp.height - 1)

        if (right <= left || bottom <= top) return

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

        if (whitePoints.size >= 6) {
            var sx = 0f; var sy = 0f
            for (pt in whitePoints) { sx += pt.x; sy += pt.y }
            cueBall.x = sx / whitePoints.size
            cueBall.y = sy / whitePoints.size
        }

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
                activeDragItem = when {
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 70f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 70f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 70f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 70f -> 3
                    hypot(x - cueBall.x, y - cueBall.y) < 70f -> 4 // Tocar directamente la bola blanca
                    hypot(x - targetBall.x, y - targetBall.y) < 70f -> 5 // Tocar directamente la bola a anotar
                    else -> 6 // Tocar cualquier otra parte de la pantalla para apuntar la dirección del tiro
                }

                if (activeDragItem == 6) {
                    aimAngle = atan2(y - cueBall.y, x - cueBall.x)
                    invalidate()
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
                        invalidate()
                    }
                    5 -> {
                        targetBall.set(x, y)
                        invalidate()
                    }
                    6 -> {
                        // Rotar suavemente la dirección del tiro arrastrando en la pantalla
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