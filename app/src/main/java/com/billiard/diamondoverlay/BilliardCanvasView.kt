package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode {
        LOCKED_PLAY,   // Modo normal: toques pasan a 8 Ball Pool (FLAG_NOT_TOUCHABLE), guías fijas
        ADJUST_AIM,    // Modo tiro: lienzo táctil para mover Bola Blanca, Bola Objetivo y guiar la trayectoria
        ADJUST_TABLE   // Modo mesa: calibrar las 4 esquinas del tapete
    }

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

    // Rectángulo del tapete de juego y puntos principales (coordenadas estándar de 8 Ball Pool)
    var tableRect = RectF(500f, 264f, 2294f, 1182f)
    var cueBall = PointF(700f, 650f)       // ⚪ Bola Blanca (Punto de inicio de línea 1)
    var targetBall = PointF(1300f, 650f)   // 🎯 Bola Objetivo a meter (Punto de inicio de línea 2)
    var aimAngle = 0f                      // Ángulo hacia donde saldrá disparada la bola objetivo
    var ballRadius = 25f                   // Radio automático calculado de la bola

    // Lista inmutable de todas las bolas detectadas en la mesa para magnetic snapping
    @Volatile var detectedBalls: List<PointF> = emptyList()

    private val prefs = context.getSharedPreferences("diamond_guide_prefs", Context.MODE_PRIVATE)

    // Elementos de arrastre táctil
    private var activeDragItem = -1
    private val ITEM_NONE = -1
    private val ITEM_CORNER_0 = 0
    private val ITEM_CORNER_1 = 1
    private val ITEM_CORNER_2 = 2
    private val ITEM_CORNER_3 = 3
    private val ITEM_CUE_BALL = 4
    private val ITEM_TARGET_BALL = 5
    private val ITEM_GHOST_BALL = 6
    private val ITEM_AIM_HANDLE = 7

    // =========================================================================
    // PINCELES DE DIBUJO
    // =========================================================================

    // Tapete y troneras
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
    private val paintPocketGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    // 1. PINCELES DE LA PRIMERA TRAYECTORIA (BOLA BLANCA HACIA EL IMPACTO)
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

    // Bola Fantasma (○) en la punta final de la 1ra línea al tocar la otra bola
    private val paintGhostRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
        setShadowLayer(6f, 0f, 0f, Color.argb(200, 255, 255, 255))
    }

    // 2. PINCELES DE LA SEGUNDA TRAYECTORIA (BOLA A ANOTAR TRAS EL IMPACTO)
    private val paintTargetBallFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.FILL
    }
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

    // Pincel para visualizar bolas detectadas en la mesa (opcional)
    private val paintDetectedBallOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    // Pinceles de interfaz en modo ajuste
    private val paintBallLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 1f, 1f, Color.BLACK)
    }
    private val paintBannerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 10, 16, 26)
        style = Paint.Style.FILL
    }
    private val paintBannerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }
    private val paintBannerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 1f, 1f, Color.BLACK)
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

    fun resetTableToDefault(w: Float, h: Float) {
        // Coordenadas calculadas y verificadas al milímetro para 8 Ball Pool en pantalla horizontal
        val left = w * 0.1787f
        val right = w * 0.8193f
        val top = h * 0.2094f
        val bottom = h * 0.9380f
        tableRect.set(left, top, right, bottom)
        ballRadius = tableRect.height() * 0.042f
        saveTableRect()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!loadTableRect(w.toFloat(), h.toFloat())) {
            resetTableToDefault(w.toFloat(), h.toFloat())
        }
        ballRadius = tableRect.height() * 0.042f
        if (!loadBallPositions()) {
            cueBall.set(tableRect.left + tableRect.width() * 0.35f, tableRect.centerY() + 50f)
            targetBall.set(tableRect.left + tableRect.width() * 0.65f, tableRect.centerY() - 40f)
            aimAngle = atan2(targetBall.y - cueBall.y, targetBall.x - cueBall.x)
        }
    }

    fun saveTableRect() {
        prefs.edit()
            .putFloat("rect_left", tableRect.left)
            .putFloat("rect_top", tableRect.top)
            .putFloat("rect_right", tableRect.right)
            .putFloat("rect_bottom", tableRect.bottom)
            .putInt("table_config_version", 3)
            .apply()
    }

    private fun loadTableRect(w: Float, h: Float): Boolean {
        val version = prefs.getInt("table_config_version", 0)
        if (version < 3) return false // Forzar migración a las coordenadas exactas de 8 Ball Pool
        val l = prefs.getFloat("rect_left", -1f)
        val t = prefs.getFloat("rect_top", -1f)
        val r = prefs.getFloat("rect_right", -1f)
        val b = prefs.getFloat("rect_bottom", -1f)
        if (l > 0 && r > l && b > t) {
            val tw = r - l
            val th = b - t
            val aspect = tw / th
            // Validación de proporción real de mesa de billar (~2:1)
            if (aspect in 1.6f..2.4f && t >= h * 0.12f && b <= h * 0.98f && l >= w * 0.10f && r <= w * 0.92f) {
                tableRect.set(l, t, r, b)
                return true
            }
        }
        return false
    }

    fun saveBallPositions() {
        prefs.edit()
            .putFloat("cue_x", cueBall.x)
            .putFloat("cue_y", cueBall.y)
            .putFloat("target_x", targetBall.x)
            .putFloat("target_y", targetBall.y)
            .putFloat("aim_angle", aimAngle)
            .apply()
    }

    private fun loadBallPositions(): Boolean {
        val cx = prefs.getFloat("cue_x", -1f)
        val cy = prefs.getFloat("cue_y", -1f)
        val tx = prefs.getFloat("target_x", -1f)
        val ty = prefs.getFloat("target_y", -1f)
        val angle = prefs.getFloat("aim_angle", Float.NaN)
        if (cx > 0f && cy > 0f && tx > 0f && ty > 0f) {
            cueBall.set(cx, cy)
            targetBall.set(tx, ty)
            aimAngle = if (!angle.isNaN()) angle else atan2(ty - cy, tx - cx)
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
     * Calcula la posición exacta de la Bola Fantasma (○) donde la blanca hace contacto con la bola objetivo
     */
    fun getGhostBallPoint(): PointF {
        val gx = targetBall.x - cos(aimAngle) * (ballRadius * 2f)
        val gy = targetBall.y - sin(aimAngle) * (ballRadius * 2f)
        return PointF(gx, gy)
    }

    /**
     * Apunta directamente la segunda línea a la tronera seleccionada con 1 solo toque
     */
    fun alignShotToPocket(pocket: PointF) {
        val dx = pocket.x - targetBall.x
        val dy = pocket.y - targetBall.y
        aimAngle = atan2(dy, dx)
        saveBallPositions()
        invalidate()
    }

    /**
     * Ajuste magnético a bolas detectadas en la mesa para máxima comodidad
     */
    fun snapToNearestDetectedBall(pt: PointF, maxDist: Float = ballRadius * 1.5f): PointF {
        var nearest: PointF? = null
        var minDist = maxDist
        for (ball in detectedBalls) {
            val d = hypot(ball.x - pt.x, ball.y - pt.y)
            if (d < minDist) {
                minDist = d
                nearest = ball
            }
        }
        return nearest ?: pt
    }

    /**
     * Actualiza la detección de bolas en segundo plano (NUNCA altera los límites ni tamaño de la mesa)
     */
    fun updateDetectionFromBackground(balls: List<PointF>) {
        detectedBalls = ArrayList(balls)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Límites interiores de la mesa y las troneras
        canvas.drawRect(tableRect, paintInnerCushion)
        drawPockets(canvas)

        // 2. Bolas detectadas en la mesa (indicadores sutiles para orientación)
        val currentBalls = detectedBalls
        for (ball in currentBalls) {
            if (hypot(ball.x - cueBall.x, ball.y - cueBall.y) > ballRadius * 1.4f &&
                hypot(ball.x - targetBall.x, ball.y - targetBall.y) > ballRadius * 1.4f) {
                canvas.drawCircle(ball.x, ball.y, ballRadius, paintDetectedBallOutline)
            }
        }

        // 3. TRAYECTORIAS DINÁMICAS DUALES:
        //    - Línea 1: Desde la Bola Blanca hasta la punta de contacto (Ghost Ball ○) + desvío a 90°
        //    - Línea 2: Desde la Bola Objetivo hacia el destino deseado con todos los rebotes en bandas
        drawDualDynamicTrajectories(canvas)

        // 4. DIBUJAR LAS DOS BOLAS PRINCIPALES:
        // A) ⚪ Bola Blanca
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueFill)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintCueRing)
        canvas.drawCircle(cueBall.x, cueBall.y, 3.5f, paintCueDot)

        // B) 🎯 Bola Objetivo a meter
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, paintTargetBallFill)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, paintTargetRing)
        canvas.drawCircle(targetBall.x, targetBall.y, 3.5f, paintCueDot)

        // 5. MODO AJUSTE DE TIRO: Muestra etiquetas de ayuda, punta de contacto y troneras táctiles
        if (mode == Mode.ADJUST_AIM) {
            drawAimAdjustOverlay(canvas)
        }

        // 6. MODO AJUSTE DE MESA: Muestra las 4 esquinas para calibrar
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
     * DIBUJA AMBAS TRAYECTORIAS DINÁMICAS DE FORMA ULTRA PRECISA Y DETERMINÍSTICA
     */
    private fun drawDualDynamicTrajectories(canvas: Canvas) {
        val gp = getGhostBallPoint()

        // =========================================================================
        // 1. PRIMERA TRAYECTORIA: DE LA BOLA BLANCA A LA PUNTA DE CONTACTO (GHOST BALL)
        // =========================================================================
        canvas.drawLine(cueBall.x, cueBall.y, gp.x, gp.y, paintCueLine)
        canvas.drawCircle(gp.x, gp.y, ballRadius, paintGhostRing)

        // Desvío a 90° de la bola blanca tras golpear la bola objetivo
        val dx = gp.x - cueBall.x
        val dy = gp.y - cueBall.y
        val len = hypot(dx, dy)
        val wx = if (len > 0.01f) dx / len else 1f
        val wy = if (len > 0.01f) dy / len else 0f
        val ux = cos(aimAngle)
        val uy = sin(aimAngle)

        // Vector tangente perpendicular al impulso
        val t1x = -uy
        val t1y = ux
        val dot = wx * t1x + wy * t1y
        val deflectX = if (dot >= 0f) t1x else -t1x
        val deflectY = if (dot >= 0f) t1y else -t1y

        val cueDeflectSegments = traceRaySegments(gp, deflectX, deflectY, maxBounces = 3)
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

        // =========================================================================
        // 2. SEGUNDA TRAYECTORIA: DE LA BOLA OBJETIVO HACIA DONDE DESEE CON SUS REBOTES
        // =========================================================================
        val targetColor = TargetLineColor.values()[currentColorIndex]
        paintTargetLineCore.color = targetColor.mainColor
        paintTargetLineOutline.color = targetColor.outlineColor
        paintTargetRing.color = targetColor.mainColor
        val isDark = (currentColorIndex == 0) // Fondo negro con fino contorno blanco

        val targetSegments = traceRaySegments(targetBall, ux, uy, maxBounces = 4)
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

    private fun drawAimAdjustOverlay(canvas: Canvas) {
        val gp = getGhostBallPoint()
        val pockets = getPocketsList()

        // 1. Banner superior explicativo
        val bannerW = 720f
        val bannerH = 46f
        val bannerX = tableRect.centerX()
        val bannerY = (tableRect.top - 40f).coerceAtLeast(32f)
        val bannerRect = RectF(bannerX - bannerW / 2f, bannerY - bannerH / 2f, bannerX + bannerW / 2f, bannerY + bannerH / 2f)
        canvas.drawRoundRect(bannerRect, 18f, 18f, paintBannerBg)
        canvas.drawRoundRect(bannerRect, 18f, 18f, paintBannerBorder)
        canvas.drawText("🎯 Mueve ⚪ Blanca, 🎯 Bola Objetivo, la 🔘 Punta o toca una Tronera", bannerX, bannerY + 7f, paintBannerText)

        // 2. Etiquetas flotantes sobre las bolas
        canvas.drawText("⚪ Blanca", cueBall.x, cueBall.y - ballRadius - 14f, paintBallLabel)
        canvas.drawText("🎯 Objetivo", targetBall.x, targetBall.y - ballRadius - 14f, paintBallLabel)
        canvas.drawText("🔘 Contacto", gp.x, gp.y - ballRadius - 14f, paintBallLabel)

        // 3. Troneras resaltadas con aura cyan para apuntar con 1 toque
        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, ballRadius * 1.55f, paintPocketGlow)
        }

        // 4. Manija de guía de dirección (punta directriz de la 2da línea)
        val steerDist = ballRadius * 3.8f
        val sx = targetBall.x + cos(aimAngle) * steerDist
        val sy = targetBall.y + sin(aimAngle) * steerDist
        canvas.drawCircle(sx, sy, 15f, paintCueRing)
        canvas.drawCircle(sx, sy, 7f, paintCueDot)
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

        // Botón central para restablecer al tamaño estándar perfecto de 8 Ball Pool
        val btnW = 340f
        val btnH = 50f
        val cx = tableRect.centerX()
        val cy = tableRect.centerY()
        val resetRect = RectF(cx - btnW / 2f, cy - btnH / 2f, cx + btnW / 2f, cy + btnH / 2f)
        canvas.drawRoundRect(resetRect, 18f, 18f, paintBannerBg)
        canvas.drawRoundRect(resetRect, 18f, 18f, paintBannerBorder)
        canvas.drawText("🔄 Restablecer Mesa Exacta", cx, cy + 7f, paintBannerText)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.LOCKED_PLAY) return false
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (mode == Mode.ADJUST_TABLE) {
                    val btnW = 340f
                    val btnH = 50f
                    val cx = tableRect.centerX()
                    val cy = tableRect.centerY()
                    if (x in (cx - btnW / 2f)..(cx + btnW / 2f) && y in (cy - btnH / 2f)..(cy + btnH / 2f)) {
                        resetTableToDefault(width.toFloat(), height.toFloat())
                        android.widget.Toast.makeText(context, "Mesa restablecida a proporciones estándar de 8 Ball Pool", android.widget.Toast.LENGTH_SHORT).show()
                        activeDragItem = ITEM_NONE
                        return true
                    }

                    activeDragItem = when {
                        hypot(x - tableRect.left, y - tableRect.top) < 70f -> ITEM_CORNER_0
                        hypot(x - tableRect.right, y - tableRect.top) < 70f -> ITEM_CORNER_1
                        hypot(x - tableRect.left, y - tableRect.bottom) < 70f -> ITEM_CORNER_2
                        hypot(x - tableRect.right, y - tableRect.bottom) < 70f -> ITEM_CORNER_3
                        else -> ITEM_NONE
                    }
                    return true
                }

                if (mode == Mode.ADJUST_AIM) {
                    // 1. Comprobar si tocó directamente una tronera para apuntar al instante
                    val pockets = getPocketsList()
                    val pocketTouchR = ballRadius * 2.2f
                    for (p in pockets) {
                        if (hypot(x - p.x, y - p.y) < pocketTouchR) {
                            alignShotToPocket(p)
                            activeDragItem = ITEM_NONE
                            return true
                        }
                    }

                    // 2. Comprobar si tocó la punta de contacto (Ghost Ball ○)
                    val gp = getGhostBallPoint()
                    if (hypot(x - gp.x, y - gp.y) < ballRadius * 1.6f) {
                        activeDragItem = ITEM_GHOST_BALL
                        return true
                    }

                    // 3. Comprobar si tocó la Bola Objetivo (🎯)
                    if (hypot(x - targetBall.x, y - targetBall.y) < ballRadius * 1.6f) {
                        activeDragItem = ITEM_TARGET_BALL
                        return true
                    }

                    // 4. Comprobar si tocó la Bola Blanca (⚪)
                    if (hypot(x - cueBall.x, y - cueBall.y) < ballRadius * 1.6f) {
                        activeDragItem = ITEM_CUE_BALL
                        return true
                    }

                    // 5. Comprobar si tocó la manija de guía directriz
                    val steerDist = ballRadius * 3.8f
                    val sx = targetBall.x + cos(aimAngle) * steerDist
                    val sy = targetBall.y + sin(aimAngle) * steerDist
                    if (hypot(x - sx, y - sy) < ballRadius * 2.0f) {
                        activeDragItem = ITEM_AIM_HANDLE
                        return true
                    }

                    // 6. Toque en cualquier parte del tapete para guiar el tiro hacia esa dirección
                    activeDragItem = ITEM_AIM_HANDLE
                    aimAngle = atan2(y - targetBall.y, x - targetBall.x)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.ADJUST_TABLE) {
                    when (activeDragItem) {
                        ITEM_CORNER_0 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                        ITEM_CORNER_1 -> { tableRect.right = x; tableRect.top = y; invalidate() }
                        ITEM_CORNER_2 -> { tableRect.left = x; tableRect.bottom = y; invalidate() }
                        ITEM_CORNER_3 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                    }
                    return true
                }

                if (mode == Mode.ADJUST_AIM) {
                    when (activeDragItem) {
                        ITEM_CUE_BALL -> {
                            cueBall.x = x.coerceIn(tableRect.left + ballRadius, tableRect.right - ballRadius)
                            cueBall.y = y.coerceIn(tableRect.top + ballRadius, tableRect.bottom - ballRadius)
                            invalidate()
                        }
                        ITEM_TARGET_BALL -> {
                            targetBall.x = x.coerceIn(tableRect.left + ballRadius, tableRect.right - ballRadius)
                            targetBall.y = y.coerceIn(tableRect.top + ballRadius, tableRect.bottom - ballRadius)
                            invalidate()
                        }
                        ITEM_GHOST_BALL -> {
                            // Mover el punto de contacto alrededor de la bola objetivo
                            val angle = atan2(y - targetBall.y, x - targetBall.x)
                            aimAngle = angle + PI.toFloat()
                            invalidate()
                        }
                        ITEM_AIM_HANDLE -> {
                            // Guiar la punta de la 2da línea hacia el lugar deseado
                            val angle = atan2(y - targetBall.y, x - targetBall.x)
                            aimAngle = angle
                            invalidate()
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.ADJUST_TABLE) {
                    if (activeDragItem in 0..3) {
                        saveTableRect()
                        ballRadius = tableRect.height() * 0.042f
                    }
                    activeDragItem = ITEM_NONE
                    return true
                }

                if (mode == Mode.ADJUST_AIM) {
                    if (activeDragItem == ITEM_TARGET_BALL) {
                        val snapped = snapToNearestDetectedBall(targetBall)
                        targetBall.set(snapped.x, snapped.y)
                    } else if (activeDragItem == ITEM_CUE_BALL) {
                        val snapped = snapToNearestDetectedBall(cueBall)
                        if (hypot(snapped.x - targetBall.x, snapped.y - targetBall.y) > ballRadius * 2.2f) {
                            cueBall.set(snapped.x, snapped.y)
                        }
                    }
                    saveBallPositions()
                    activeDragItem = ITEM_NONE
                    invalidate()
                    return true
                }
            }
        }
        return true
    }
}