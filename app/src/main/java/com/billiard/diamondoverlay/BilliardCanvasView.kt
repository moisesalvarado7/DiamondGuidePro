package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { ADJUST_TABLE, TARGET_AIM, LOCKED_PLAY }
    enum class GridFilter { ALL, MAIN_ANGLES, OFF }
    enum class CushionSide { LEFT, TOP, RIGHT, BOTTOM, NONE }

    data class BankSolution(
        val bouncePoint: PointF,
        val cushionSide: CushionSide,
        val pocketIndex: Int,
        val pocketPoint: PointF,
        val angleDeg: Float
    )

    data class RayHit(
        val point: PointF,
        val cushionSide: CushionSide,
        val pocketIndex: Int = -1
    )

    data class ShotDecision(
        val pocketIndex: Int,
        val cutAngleDeg: Float,
        val isDirect: Boolean,
        val isBlocked: Boolean,
        val ratingText: String,
        val ratingColor: Int
    )

    var mode = Mode.LOCKED_PLAY
    var gridFilter = GridFilter.OFF // Inicialmente OFF para que las líneas de tiro resalten limpias
    var showAimLine = true
    var gridAlpha = 80              // Sutil cuando está activo
    var isBankShotMode = false      // Alternar entre tiro directo y tiro de banda forzado

    // Rectángulo del tapete de la mesa
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(650f, 700f)      // Bola Blanca
    var targetBall = PointF(1550f, 520f)  // Bola Objetivo
    var ballRadius = 25f                  // Radio calibrable de la bola (22..30px)
    var selectedPocketIndex = -1          // -1 = auto-detectar mejor buchaca, 0..5 = buchaca fijada

    // Lista de todas las bolas detectadas en la mesa por visión artificial
    val detectedBalls = mutableListOf<PointF>()
    var aimAngle = 0f                     // Ángulo de puntería detectado (en radianes)
    var currentDecision: ShotDecision? = null

    private var activeDragItem = -1 // 0..3=Esquinas, 4=Blanca, 5=Objetivo
    private val prefs = context.getSharedPreferences("diamond_guide_prefs", Context.MODE_PRIVATE)

    // Estructura de línea del sistema de diamantes
    data class DiamondLine(
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val angleDeg: Float,
        val color: Int,
        val isMainAngle: Boolean
    )

    private val diamondLines = mutableListOf<DiamondLine>()

    // Pinceles de dibujo
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val paintTableBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E2723")
        style = Paint.Style.STROKE
        strokeWidth = 16f
    }
    private val paintTableInnerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val paintPocket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0E14")
        style = Paint.Style.FILL
    }
    private val paintPocketRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263238")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintDiamondDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 21f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    // PINCELES PREMIUM IDÉNTICOS A LA FOTO DE REFERENCIA
    // 1. Línea Blanca de trayectoria del tiro y rebotes multi-banda
    private val paintCueTrajectory = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        setShadowLayer(6f, 0f, 0f, Color.argb(180, 255, 255, 255))
    }
    // 2. Puntos blancos circulares en cada impacto de banda
    private val paintBounceDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#80D8FF"))
    }
    // 3. Bola Fantasma (anillo blanco nítido con halo)
    private val paintGhostBall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        setShadowLayer(8f, 0f, 0f, Color.WHITE)
    }
    private val paintGhostInnerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    // 4. Línea Amarilla/Oro hacia la Buchaca (Target Pot Line)
    private val paintTargetPotLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Dorado ámbar como en la foto
        style = Paint.Style.STROKE
        strokeWidth = 4.8f
        setShadowLayer(10f, 0f, 0f, Color.parseColor("#FFAB00"))
    }
    // 5. Línea Verde de desvío tangencial / carambola
    private val paintDeflectionLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Verde brillante
        style = Paint.Style.STROKE
        strokeWidth = 3.6f
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#00E676"))
    }
    // 6. Halo circular y cruz en la Buchaca objetivo
    private val paintPocketRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        setShadowLayer(12f, 0f, 0f, Color.parseColor("#00E676"))
    }
    // 7. Círculos sutiles en todas las demás bolas detectadas
    private val paintDetectedBall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val paintBallGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    // Pinceles del HUD de Decisión de Tiro
    private val paintHudBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 10, 16, 26)
        style = Paint.Style.FILL
    }
    private val paintHudBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        ballRadius = prefs.getFloat("ball_radius", 25f)
        buildDiamondGrid()
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
            cueBall.set(tableRect.left + tableRect.width() * 0.32f, tableRect.centerY() + 80f)
            targetBall.set(tableRect.left + tableRect.width() * 0.68f, tableRect.centerY() - 60f)
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

    private fun buildDiamondGrid() {
        diamondLines.clear()
        val allPoints = mutableListOf<Point>()

        for (x in 0..8) allPoints.add(Point(x, 0))
        for (x in 0..8) allPoints.add(Point(x, 4))
        for (y in 1..3) allPoints.add(Point(0, y))
        for (y in 1..3) allPoints.add(Point(8, y))

        val seenPairs = mutableSetOf<String>()

        for (p1 in allPoints) {
            for (p2 in allPoints) {
                if (p1.x == p2.x && p1.y == p2.y) continue
                if (p1.y == 0 && p2.y == 0) continue
                if (p1.y == 4 && p2.y == 4) continue
                if (p1.x == 0 && p2.x == 0) continue
                if (p1.x == 8 && p2.x == 8) continue

                val pairKey = if (p1.x < p2.x || (p1.x == p2.x && p1.y < p2.y)) {
                    "${p1.x},${p1.y}-${p2.x},${p2.y}"
                } else {
                    "${p2.x},${p2.y}-${p1.x},${p1.y}"
                }
                if (seenPairs.contains(pairKey)) continue
                seenPairs.add(pairKey)

                val dx = abs(p2.x - p1.x)
                val dy = abs(p2.y - p1.y)

                val g = gcd(dx, dy)
                val redDx = dx / g
                val redDy = dy / g

                val angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                val color = getColorForSlope(redDx, redDy, angleDeg)
                val isMain = isMainBilliardAngle(redDx, redDy)

                diamondLines.add(DiamondLine(p1.x, p1.y, p2.x, p2.y, angleDeg, color, isMain))
            }
        }
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun isMainBilliardAngle(redDx: Int, redDy: Int): Boolean {
        return (redDx == 0 || redDy == 0 ||
                (redDx == 1 && redDy == 1) ||
                (redDx == 2 && redDy == 1) ||
                (redDx == 1 && redDy == 2) ||
                (redDx == 3 && redDy == 1) ||
                (redDx == 1 && redDy == 3) ||
                (redDx == 4 && redDy == 1) ||
                (redDx == 3 && redDy == 2) ||
                (redDx == 2 && redDy == 3))
    }

    private fun getColorForSlope(redDx: Int, redDy: Int, angleDeg: Float): Int {
        return when {
            redDx == 0 -> Color.rgb(225, 230, 240)
            redDy == 0 -> Color.rgb(210, 215, 225)
            redDx == 1 && redDy == 1 -> Color.rgb(255, 235, 59)
            redDx == 2 && redDy == 1 -> Color.rgb(0, 230, 118)
            redDx == 1 && redDy == 2 -> Color.rgb(0, 229, 255)
            redDx == 3 && redDy == 1 -> Color.rgb(255, 64, 129)
            redDx == 1 && redDy == 3 -> Color.rgb(224, 64, 251)
            redDx == 4 && redDy == 1 -> Color.rgb(255, 171, 0)
            redDx == 1 && redDy == 4 -> Color.rgb(255, 82, 82)
            redDx == 3 && redDy == 2 -> Color.rgb(118, 255, 3)
            redDx == 2 && redDy == 3 -> Color.rgb(64, 196, 255)
            redDx == 4 && redDy == 3 -> Color.rgb(255, 138, 101)
            redDx == 3 && redDy == 4 -> Color.rgb(179, 136, 255)
            redDx == 5 && redDy == 4 -> Color.rgb(38, 198, 218)
            redDx == 7 && redDy == 4 -> Color.rgb(240, 98, 146)
            else -> {
                val hue = (angleDeg * 5.7f) % 360f
                Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.95f))
            }
        }
    }

    /**
     * Centros de caída de las 6 buchacas calibrados con inset hacia la boca para que la bola
     * nunca rebote en la muela de la goma.
     */
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

    fun getPocketName(index: Int): String {
        return when (index) {
            0 -> "Sup. Izquierda"
            1 -> "Centro Superior"
            2 -> "Sup. Derecha"
            3 -> "Inf. Izquierda"
            4 -> "Centro Inferior"
            5 -> "Inf. Derecha"
            else -> "Buchaca"
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Marco exterior de madera e interior
        canvas.drawRect(tableRect, paintTableBorder)
        canvas.drawRect(tableRect, paintTableInnerRim)

        // 2. Las 6 Buchacas visuales
        drawPockets(canvas)

        // 3. Cuadrícula de diamantes de fondo (si el usuario la activa con el botón)
        if (gridFilter != GridFilter.OFF) {
            drawDiamondGridLines(canvas)
        }

        // 4. Marcadores y números de diamantes (0..8 y 0..4)
        drawDiamondsAndLabels(canvas)

        // 5. Círculos de todas las demás bolas detectadas en la mesa
        drawAllDetectedBalls(canvas)

        // 6. LÍNEAS DE REFERENCIA EXTENDIDAS MULTI-BANDA Y TIRO A BUCHACA
        if (showAimLine) {
            drawReferenceGuides(canvas)
        }

        // 7. HUD DE DECISIÓN DE TIRO (Facilita decidir tirar o no)
        drawShotDecisionHUD(canvas)

        // 8. Modos de ajuste
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.TARGET_AIM) {
            canvas.drawText(
                "🎯 Toca una bola para fijarla o una buchaca. Auto-detección activa.",
                tableRect.left + 20f, tableRect.top - 45f, paintHint
            )
        }
    }

    private fun drawPockets(canvas: Canvas) {
        val pockets = getPocketsList()
        val cornerRadius = ballRadius * 1.8f
        val sideRadius = ballRadius * 1.5f

        for (i in pockets.indices) {
            val p = pockets[i]
            val r = if (i == 1 || i == 4) sideRadius else cornerRadius
            canvas.drawCircle(p.x, p.y, r, paintPocket)
            canvas.drawCircle(p.x, p.y, r, paintPocketRim)
        }
    }

    private fun drawDiamondGridLines(canvas: Canvas) {
        val wSeg = tableRect.width() / 8f
        val hSeg = tableRect.height() / 4f

        for (line in diamondLines) {
            if (gridFilter == GridFilter.MAIN_ANGLES && !line.isMainAngle) continue

            val px1 = tableRect.left + line.x1 * wSeg
            val py1 = tableRect.top + line.y1 * hSeg
            val px2 = tableRect.left + line.x2 * wSeg
            val py2 = tableRect.top + line.y2 * hSeg

            paintLine.color = line.color
            paintLine.alpha = gridAlpha
            canvas.drawLine(px1, py1, px2, py2, paintLine)
        }
    }

    private fun drawDiamondsAndLabels(canvas: Canvas) {
        val wSeg = tableRect.width() / 8f
        val hSeg = tableRect.height() / 4f

        for (i in 0..8) {
            val x = tableRect.left + i * wSeg
            canvas.drawCircle(x, tableRect.top, 5.5f, paintDiamondDot)
            canvas.drawText("$i", x - 6f, tableRect.top - 16f, paintText)

            canvas.drawCircle(x, tableRect.bottom, 5.5f, paintDiamondDot)
            canvas.drawText("$i", x - 6f, tableRect.bottom + 34f, paintText)
        }

        for (j in 0..4) {
            val y = tableRect.top + j * hSeg
            if (j in 1..3) canvas.drawCircle(tableRect.left, y, 5.5f, paintDiamondDot)
            canvas.drawText("$j", tableRect.left - 30f, y + 7f, paintText)

            if (j in 1..3) canvas.drawCircle(tableRect.right, y, 5.5f, paintDiamondDot)
            canvas.drawText("$j", tableRect.right + 14f, y + 7f, paintText)
        }
    }

    private fun drawAllDetectedBalls(canvas: Canvas) {
        for (b in detectedBalls) {
            val distCue = hypot((b.x - cueBall.x).toDouble(), (b.y - cueBall.y).toDouble())
            val distTarget = hypot((b.x - targetBall.x).toDouble(), (b.y - targetBall.y).toDouble())
            if (distCue > ballRadius * 1.4f && distTarget > ballRadius * 1.4f) {
                canvas.drawCircle(b.x, b.y, ballRadius, paintDetectedBall)
            }
        }
    }

    /**
     * DIBUJA LAS GUÍAS EXTENDIDAS DE REFERENCIA IDÉNTICAS A LA FOTO:
     * - Línea blanca de la blanca a la bola fantasma.
     * - Línea amarilla oro desde la bola objetivo directa a la buchaca respectiva.
     * - Halo circular y mira en la buchaca elegida.
     * - Desvío tangencial verde con anillo indicador.
     * - Extensión multi-banda de hasta 4 bandas con puntos blancos circulares (●) en cada impacto de banda.
     * - Si se activa modo banda o el tiro directo es imposible, traza el rebote en banda que busca la buchaca.
     */
    private fun drawReferenceGuides(canvas: Canvas) {
        val pockets = getPocketsList()

        // 1. Evaluar todas las buchacas para encontrar la "respectiva buchaca" ideal
        var bestPocketIdx = selectedPocketIndex
        var bestCutAngle = 999f
        var isBlocked = false

        if (bestPocketIdx == -1) {
            for (i in pockets.indices) {
                val p = pockets[i]
                val potDx = p.x - targetBall.x
                val potDy = p.y - targetBall.y
                val potDist = hypot(potDx.toDouble(), potDy.toDouble()).toFloat()
                if (potDist < 10f) continue

                val potDirX = potDx / potDist
                val potDirY = potDy / potDist

                val ghostX = targetBall.x - potDirX * (ballRadius * 2f)
                val ghostY = targetBall.y - potDirY * (ballRadius * 2f)

                val cueDx = ghostX - cueBall.x
                val cueDy = ghostY - cueBall.y
                val cueDist = hypot(cueDx.toDouble(), cueDy.toDouble()).toFloat()
                if (cueDist < 5f) continue

                val cueDirX = cueDx / cueDist
                val cueDirY = cueDy / cueDist

                val dot = (cueDirX * potDirX + cueDirY * potDirY).coerceIn(-1f, 1f)
                val cutAngleDeg = Math.toDegrees(acos(dot.toDouble())).toFloat()

                // Verificar si hay alguna otra bola bloqueando el camino hacia esa buchaca
                var blocked = false
                for (otherBall in detectedBalls) {
                    if (hypot((otherBall.x - targetBall.x).toDouble(), (otherBall.y - targetBall.y).toDouble()) < ballRadius * 1.5f) continue
                    if (hypot((otherBall.x - cueBall.x).toDouble(), (otherBall.y - cueBall.y).toDouble()) < ballRadius * 1.5f) continue

                    // Distancia de la otra bola al segmento targetBall -> p
                    val dSeg = distancePointToSegment(otherBall, targetBall, p)
                    if (dSeg < ballRadius * 1.85f) {
                        blocked = true
                        break
                    }
                }

                // Preferir tiros con ángulo de corte realista (< 75°) y sin bloqueo
                val effectiveAngle = if (blocked) cutAngleDeg + 50f else cutAngleDeg
                if (effectiveAngle < bestCutAngle && cutAngleDeg <= 78f) {
                    bestCutAngle = cutAngleDeg
                    bestPocketIdx = i
                    isBlocked = blocked
                }
            }
        } else {
            // Buchaca fijada manualmente
            val p = pockets[bestPocketIdx]
            val potDx = p.x - targetBall.x
            val potDy = p.y - targetBall.y
            val potDist = hypot(potDx.toDouble(), potDy.toDouble()).toFloat()
            if (potDist > 1f) {
                val potDirX = potDx / potDist
                val potDirY = potDy / potDist
                val ghostX = targetBall.x - potDirX * (ballRadius * 2f)
                val ghostY = targetBall.y - potDirY * (ballRadius * 2f)
                val cueDx = ghostX - cueBall.x
                val cueDy = ghostY - cueBall.y
                val cueDist = hypot(cueDx.toDouble(), cueDy.toDouble()).toFloat()
                val cueDirX = if (cueDist > 0.1f) cueDx / cueDist else potDirX
                val cueDirY = if (cueDist > 0.1f) cueDy / cueDist else potDirY
                val dot = (cueDirX * potDirX + cueDirY * potDirY).coerceIn(-1f, 1f)
                bestCutAngle = Math.toDegrees(acos(dot.toDouble())).toFloat()
            }
        }

        val directPocket = if (bestPocketIdx in 0..5 && !isBankShotMode && bestCutAngle <= 80f) pockets[bestPocketIdx] else null

        if (directPocket != null) {
            // === TIRO DIRECTO A LA RESPECTIVA BUCHACA ===
            val potDx = directPocket.x - targetBall.x
            val potDy = directPocket.y - targetBall.y
            val potDist = hypot(potDx.toDouble(), potDy.toDouble()).toFloat()
            val potDirX = potDx / potDist
            val potDirY = potDy / potDist

            val ghostX = targetBall.x - potDirX * (ballRadius * 2f)
            val ghostY = targetBall.y - potDirY * (ballRadius * 2f)

            // 1. Línea Blanca: Desde la Bola Blanca hasta la Bola Fantasma
            canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueTrajectory)

            // Bola Fantasma de impacto (doble anillo como en la foto)
            canvas.drawCircle(ghostX, ghostY, ballRadius, paintGhostBall)
            canvas.drawCircle(ghostX, ghostY, ballRadius * 0.45f, paintGhostInnerRing)
            canvas.drawCircle(ghostX, ghostY, 4f, paintBounceDot)

            // 2. Línea Amarilla Oro: Desde la Bola Objetivo EXTENDIDA hasta el fondo de la buchaca
            canvas.drawLine(targetBall.x, targetBall.y, directPocket.x, directPocket.y, paintTargetPotLine)
            drawTargetPocketGlow(canvas, directPocket, bestPocketIdx, true)

            // 3. Línea Verde de desvío de la blanca (tangent line a 90°)
            val cueDx = ghostX - cueBall.x
            val cueDy = ghostY - cueBall.y
            val cueDist = hypot(cueDx.toDouble(), cueDy.toDouble()).toFloat()
            val cueDirX = if (cueDist > 0.1f) cueDx / cueDist else potDirX
            val cueDirY = if (cueDist > 0.1f) cueDy / cueDist else potDirY

            val cross = cueDirX * potDirY - cueDirY * potDirX
            val tangentDirX = if (cross > 0) -potDirY else potDirY
            val tangentDirY = if (cross > 0) potDirX else -potDirX

            // Indicador verde corto en la bola objetivo
            val greenEnd = PointF(ghostX + tangentDirX * (ballRadius * 3.2f), ghostY + tangentDirY * (ballRadius * 3.2f))
            canvas.drawLine(ghostX, ghostY, greenEnd.x, greenEnd.y, paintDeflectionLine)
            canvas.drawCircle(greenEnd.x, greenEnd.y, ballRadius * 0.65f, paintDeflectionLine)

            // 4. EXTENSIÓN MULTI-BANDA DE LA BLANCA (Hasta 4 bandas continuas con puntos blancos ●)
            val multiBouncePath = traceMultiCushionBounces(PointF(ghostX, ghostY), tangentDirX, tangentDirY, maxBounces = 4)
            drawMultiBouncePath(canvas, multiBouncePath)

            // Actualizar evaluación de tiro
            updateShotDecision(bestPocketIdx, bestCutAngle, true, isBlocked)

        } else {
            // === TIRO DE BANDA EXTENDIDO (BANK SHOT) QUE BUSCA LA BUCHACA ===
            drawBankShotSeekingPocket(canvas, pockets)
        }

        // Dibujar las bolas blanca y objetivo con su estilo premium
        drawMainBalls(canvas)
    }

    /**
     * Traza el rebote continuo a través de hasta 4 bandas consecutivas.
     */
    private fun traceMultiCushionBounces(startPos: PointF, initialDirX: Float, initialDirY: Float, maxBounces: Int = 4): List<PointF> {
        val points = mutableListOf<PointF>()
        points.add(PointF(startPos.x, startPos.y))

        var curOrigin = PointF(startPos.x, startPos.y)
        var curDirX = initialDirX
        var curDirY = initialDirY

        for (b in 0 until maxBounces) {
            val hit = raycastCushion(curOrigin, curDirX, curDirY)
            points.add(PointF(hit.point.x, hit.point.y))

            // Si entra a una buchaca se detiene
            if (hit.pocketIndex != -1) break

            // Reflejar vector de dirección en la banda
            curDirX = if (hit.cushionSide == CushionSide.LEFT || hit.cushionSide == CushionSide.RIGHT) -curDirX else curDirX
            curDirY = if (hit.cushionSide == CushionSide.TOP || hit.cushionSide == CushionSide.BOTTOM) -curDirY else curDirY

            // Pequeño desplazamiento hacia adelante para evitar colisión con la misma banda
            curOrigin = PointF(hit.point.x + curDirX * 1.0f, hit.point.y + curDirY * 1.0f)
        }

        return points
    }

    /**
     * Dibuja la polilínea blanca continua de multi-banda con círculos blancos (●) en cada impacto
     * exactamente idéntico al screenshot de referencia.
     */
    private fun drawMultiBouncePath(canvas: Canvas, path: List<PointF>) {
        if (path.size < 2) return

        for (i in 0 until path.size - 1) {
            val p1 = path[i]
            val p2 = path[i + 1]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintCueTrajectory)

            // Círculo blanco nítido en cada punto de rebote en la banda
            if (i > 0) {
                canvas.drawCircle(p1.x, p1.y, 6.5f, paintBounceDot)
            }
        }

        // Círculo final donde descansará la bola tras los 4 rebotes
        val lastPoint = path.last()
        canvas.drawCircle(lastPoint.x, lastPoint.y, ballRadius, paintGhostBall)
        canvas.drawCircle(lastPoint.x, lastPoint.y, 4f, paintBounceDot)
    }

    /**
     * Traza el tiro de banda de la bola objetivo buscando la buchaca matemáticamente.
     */
    private fun drawBankShotSeekingPocket(canvas: Canvas, pockets: List<PointF>) {
        val dx = targetBall.x - cueBall.x
        val dy = targetBall.y - cueBall.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 5f) return
        val dirX = dx / dist
        val dirY = dy / dist

        // Banda hacia la que viaja
        val firstCushionHit = raycastCushion(targetBall, dirX, dirY)
        val bestBank = findBestBankShotToPocket(targetBall, firstCushionHit.cushionSide, pockets)

        if (bestBank != null) {
            // Línea de la bola objetivo a la banda
            canvas.drawLine(targetBall.x, targetBall.y, bestBank.bouncePoint.x, bestBank.bouncePoint.y, paintTargetPotLine)
            canvas.drawCircle(bestBank.bouncePoint.x, bestBank.bouncePoint.y, 6.5f, paintBounceDot)

            // Línea desde la banda directamente a la buchaca
            canvas.drawLine(bestBank.bouncePoint.x, bestBank.bouncePoint.y, bestBank.pocketPoint.x, bestBank.pocketPoint.y, paintTargetPotLine)
            drawTargetPocketGlow(canvas, bestBank.pocketPoint, bestBank.pocketIndex, false)

            // Impacto de la blanca
            val shotDx = bestBank.bouncePoint.x - targetBall.x
            val shotDy = bestBank.bouncePoint.y - targetBall.y
            val shotDist = hypot(shotDx.toDouble(), shotDy.toDouble()).toFloat()
            if (shotDist > 1f) {
                val uX = shotDx / shotDist
                val uY = shotDy / shotDist
                val ghostX = targetBall.x - uX * (ballRadius * 2f)
                val ghostY = targetBall.y - uY * (ballRadius * 2f)

                canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueTrajectory)
                canvas.drawCircle(ghostX, ghostY, ballRadius, paintGhostBall)

                // Rebound continuo de la blanca tras el impacto de banda
                val bouncePath = traceMultiCushionBounces(PointF(ghostX, ghostY), -uY, uX, maxBounces = 3)
                drawMultiBouncePath(canvas, bouncePath)
            }

            updateShotDecision(bestBank.pocketIndex, bestBank.angleDeg, false, false)
        } else {
            // Trazar rebote estándar de 2 bandas
            val refDirX = if (firstCushionHit.cushionSide == CushionSide.LEFT || firstCushionHit.cushionSide == CushionSide.RIGHT) -dirX else dirX
            val refDirY = if (firstCushionHit.cushionSide == CushionSide.TOP || firstCushionHit.cushionSide == CushionSide.BOTTOM) -dirY else dirY
            val secondHit = raycastCushion(firstCushionHit.point, refDirX, refDirY)

            canvas.drawLine(targetBall.x, targetBall.y, firstCushionHit.point.x, firstCushionHit.point.y, paintTargetPotLine)
            canvas.drawCircle(firstCushionHit.point.x, firstCushionHit.point.y, 6.5f, paintBounceDot)
            canvas.drawLine(firstCushionHit.point.x, firstCushionHit.point.y, secondHit.point.x, secondHit.point.y, paintTargetPotLine)
            canvas.drawCircle(secondHit.point.x, secondHit.point.y, 6.5f, paintBounceDot)
        }
    }

    private fun findBestBankShotToPocket(origin: PointF, cushion: CushionSide, pockets: List<PointF>): BankSolution? {
        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius

        var bestSolution: BankSolution? = null
        var minDiff = Float.MAX_VALUE

        val pocketIndicesToTest = if (selectedPocketIndex in 0..5) listOf(selectedPocketIndex) else (0..5).toList()

        for (i in pocketIndicesToTest) {
            val pocket = pockets[i]
            val mirrorPocket = PointF(pocket.x, pocket.y)
            when (cushion) {
                CushionSide.TOP -> mirrorPocket.y = 2 * boundTop - pocket.y
                CushionSide.BOTTOM -> mirrorPocket.y = 2 * boundBottom - pocket.y
                CushionSide.LEFT -> mirrorPocket.x = 2 * boundLeft - pocket.x
                CushionSide.RIGHT -> mirrorPocket.x = 2 * boundRight - pocket.x
                else -> continue
            }

            val deltaX = mirrorPocket.x - origin.x
            val deltaY = mirrorPocket.y - origin.y
            val bouncePoint = PointF()

            when (cushion) {
                CushionSide.TOP, CushionSide.BOTTOM -> {
                    val cushionY = if (cushion == CushionSide.TOP) boundTop else boundBottom
                    if (abs(deltaY) < 0.001f) continue
                    val t = (cushionY - origin.y) / deltaY
                    if (t <= 0.05f || t >= 0.95f) continue
                    val hitX = origin.x + t * deltaX
                    if (hitX !in boundLeft..boundRight) continue
                    bouncePoint.set(hitX, cushionY)
                }
                CushionSide.LEFT, CushionSide.RIGHT -> {
                    val cushionX = if (cushion == CushionSide.LEFT) boundLeft else boundRight
                    if (abs(deltaX) < 0.001f) continue
                    val t = (cushionX - origin.x) / deltaX
                    if (t <= 0.05f || t >= 0.95f) continue
                    val hitY = origin.y + t * deltaY
                    if (hitY !in boundTop..boundBottom) continue
                    bouncePoint.set(cushionX, hitY)
                }
                else -> continue
            }

            val curDx = bouncePoint.x - origin.x
            val curDy = bouncePoint.y - origin.y
            val dist = hypot(curDx.toDouble(), curDy.toDouble()).toFloat()

            if (dist < minDiff) {
                minDiff = dist
                val angleDeg = Math.toDegrees(atan2(abs(curDy).toDouble(), abs(curDx).toDouble())).toFloat()
                bestSolution = BankSolution(bouncePoint, cushion, i, pocket, angleDeg)
            }
        }

        return bestSolution
    }

    private fun drawTargetPocketGlow(canvas: Canvas, p: PointF, pocketIdx: Int, isDirect: Boolean) {
        val color = if (isDirect) Color.parseColor("#00E676") else Color.parseColor("#FFD600")
        paintPocketRing.color = color

        // Anillo exterior y cruz central
        canvas.drawCircle(p.x, p.y, ballRadius * 2.1f, paintPocketRing)
        canvas.drawCircle(p.x, p.y, 4f, paintBounceDot)
        canvas.drawLine(p.x - 12f, p.y, p.x + 12f, p.y, paintPocketRing)
        canvas.drawLine(p.x, p.y - 12f, p.x, p.y + 12f, paintPocketRing)
    }

    private fun drawMainBalls(canvas: Canvas) {
        // Bola Blanca
        paintBallGlow.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius + 2f, paintBallGlow)
        val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cuePaint)
        canvas.drawCircle(cueBall.x, cueBall.y, 3.5f, Paint().apply { color = Color.parseColor("#FF1744") })

        // Bola Objetivo
        paintBallGlow.color = Color.parseColor("#FFD600")
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius + 2f, paintBallGlow)
        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D50000"); style = Paint.Style.FILL }
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetPaint)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius * 0.45f, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
    }

    /**
     * EVALUACIÓN Y TARJETA HUD PARA FACILITAR LA DECISIÓN DE TIRO
     * Muestra de un vistazo al jugador: Buchaca destino, corte y si el tiro es fácil, medio o difícil.
     */
    private fun updateShotDecision(pocketIdx: Int, cutAngleDeg: Float, isDirect: Boolean, isBlocked: Boolean) {
        val (text, color) = when {
            isBlocked -> Pair("⚠️ BLOQUEADO POR OTRA BOLA", Color.parseColor("#FF5252"))
            cutAngleDeg <= 30f -> Pair("🟢 TIRO DIRECTO FÁCIL", Color.parseColor("#00E676"))
            cutAngleDeg <= 55f -> Pair("🟡 ÁNGULO MEDIO (PRECISO)", Color.parseColor("#FFD600"))
            cutAngleDeg <= 75f -> Pair("🟠 CORTE FINO (CUIDADO)", Color.parseColor("#FF9100"))
            else -> Pair("🔴 MUY DIFÍCIL (CONSIDERA BANDA)", Color.parseColor("#FF5252"))
        }
        currentDecision = ShotDecision(pocketIdx, cutAngleDeg, isDirect, isBlocked, text, color)
    }

    private fun drawShotDecisionHUD(canvas: Canvas) {
        val decision = currentDecision ?: return
        val hudWidth = 580f
        val hudHeight = 52f
        val hudX = tableRect.centerX() - hudWidth / 2f
        val hudY = tableRect.top + 20f

        val rect = RectF(hudX, hudY, hudX + hudWidth, hudY + hudHeight)
        canvas.drawRoundRect(rect, 14f, 14f, paintHudBg)
        paintHudBorder.color = decision.ratingColor
        canvas.drawRoundRect(rect, 14f, 14f, paintHudBorder)

        val pocketName = getPocketName(decision.pocketIndex)
        val hudText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        }
        val ratingText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = decision.ratingColor
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 0f, decision.ratingColor)
        }

        canvas.drawText("🎯 $pocketName (${decision.cutAngleDeg.toInt()}°)", hudX + 18f, hudY + 33f, hudText)
        canvas.drawText(decision.ratingText, hudX + 270f, hudY + 33f, ratingText)
    }

    private fun raycastCushion(origin: PointF, dirX: Float, dirY: Float): RayHit {
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

        return RayHit(PointF(hitX, hitY), hitSide, -1)
    }

    private fun distancePointToSegment(pt: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 0.001f) return hypot((pt.x - a.x).toDouble(), (pt.y - a.y).toDouble()).toFloat()

        val t = (((pt.x - a.x) * dx + (pt.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot((pt.x - projX).toDouble(), (pt.y - projY).toDouble()).toFloat()
    }

    private fun drawAdjustmentHandles(canvas: Canvas) {
        val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        paintCorner.color = Color.RED
        canvas.drawCircle(tableRect.left, tableRect.top, 32f, paintCorner)
        paintCorner.color = Color.YELLOW
        canvas.drawCircle(tableRect.right, tableRect.top, 32f, paintCorner)
        paintCorner.color = Color.CYAN
        canvas.drawCircle(tableRect.left, tableRect.bottom, 32f, paintCorner)
        paintCorner.color = Color.GREEN
        canvas.drawCircle(tableRect.right, tableRect.bottom, 32f, paintCorner)

        canvas.drawText(
            "📐 Arrastra las 4 esquinas a las troneras de la mesa de tu juego. Luego toca '🎮 Jugar'.",
            tableRect.left + 20f, tableRect.top - 40f, paintHint
        )
    }

    /**
     * VISIÓN ARTIFICIAL ROBUSTA:
     * 1. Muestreo de color promedio del paño usando múltiples cuadrantes para evitar reflejos o bolas centrales.
     * 2. Detección de la Bola Blanca por densidad de blanco puro (>215 RGB).
     * 3. Detección y agrupamiento de todas las demás bolas en la mesa.
     * 4. Detección del taco de billar / línea de tiro para encontrar la dirección de disparo.
     * 5. Identificación automática de la bola que se quiere meter y su respectiva buchaca.
     */
    fun detectAllBallsAndAimFromBitmap(bmp: Bitmap) {
        val left = tableRect.left.toInt().coerceIn(0, bmp.width - 1)
        val right = tableRect.right.toInt().coerceIn(0, bmp.width - 1)
        val top = tableRect.top.toInt().coerceIn(0, bmp.height - 1)
        val bottom = tableRect.bottom.toInt().coerceIn(0, bmp.height - 1)

        if (right <= left || bottom <= top) return

        // 1. Muestreo robusto de múltiples puntos del tapete para color base
        val sampleCoords = listOf(
            Pair(left + (right - left) / 4, top + (bottom - top) / 4),
            Pair(left + 3 * (right - left) / 4, top + (bottom - top) / 4),
            Pair(left + (right - left) / 4, top + 3 * (bottom - top) / 4),
            Pair(left + 3 * (right - left) / 4, top + 3 * (bottom - top) / 4),
            Pair(left + (right - left) / 2, top + (bottom - top) / 4),
            Pair(left + (right - left) / 2, top + 3 * (bottom - top) / 4)
        )
        val rVals = mutableListOf<Int>()
        val gVals = mutableListOf<Int>()
        val bVals = mutableListOf<Int>()

        for ((sx, sy) in sampleCoords) {
            val p = bmp.getPixel(sx.coerceIn(0, bmp.width - 1), sy.coerceIn(0, bmp.height - 1))
            rVals.add(Color.red(p))
            gVals.add(Color.green(p))
            bVals.add(Color.blue(p))
        }
        rVals.sort(); gVals.sort(); bVals.sort()
        val feltR = rVals[rVals.size / 2]
        val feltG = gVals[gVals.size / 2]
        val feltB = bVals[bVals.size / 2]

        // 2. Extracción de candidatos a bolas y bola blanca
        val step = max(4, (ballRadius / 5).toInt())
        val candidatePoints = mutableListOf<PointF>()
        val whitePoints = mutableListOf<PointF>()

        for (y in (top + ballRadius.toInt())..(bottom - ballRadius.toInt()) step step) {
            for (x in (left + ballRadius.toInt())..(right - ballRadius.toInt()) step step) {
                val pix = bmp.getPixel(x, y)
                val r = Color.red(pix)
                val g = Color.green(pix)
                val b = Color.blue(pix)

                // Detección de blanco puro para bola blanca
                if (r > 215 && g > 215 && b > 215 && abs(r - g) < 18 && abs(g - b) < 18) {
                    whitePoints.add(PointF(x.toFloat(), y.toFloat()))
                } else {
                    val diff = abs(r - feltR) + abs(g - feltG) + abs(b - feltB)
                    // Alto contraste con el paño o bola 8 negra
                    if (diff > 55 || (r < 40 && g < 40 && b < 40)) {
                        candidatePoints.add(PointF(x.toFloat(), y.toFloat()))
                    }
                }
            }
        }

        // Agrupar puntos blancos para ubicar la Bola Blanca
        if (whitePoints.size >= 6) {
            var sumX = 0f
            var sumY = 0f
            for (pt in whitePoints) {
                sumX += pt.x
                sumY += pt.y
            }
            cueBall.x = sumX / whitePoints.size
            cueBall.y = sumY / whitePoints.size
        }

        // Agrupar puntos de bolas objetivo (clustering por radio)
        detectedBalls.clear()
        val clusterDist = ballRadius * 1.35f

        for (pt in candidatePoints) {
            // Descartar si está pegado a la bola blanca
            if (hypot((pt.x - cueBall.x).toDouble(), (pt.y - cueBall.y).toDouble()) < clusterDist) continue

            var merged = false
            for (ball in detectedBalls) {
                if (hypot((pt.x - ball.x).toDouble(), (pt.y - ball.y).toDouble()) < clusterDist) {
                    ball.x = (ball.x + pt.x) * 0.5f
                    ball.y = (ball.y + pt.y) * 0.5f
                    merged = true
                    break
                }
            }
            if (!merged && detectedBalls.size < 16) {
                detectedBalls.add(PointF(pt.x, pt.y))
            }
        }

        // 3. Detectar dirección de puntería (taco o línea que sale de la blanca)
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

        // 4. IDENTIFICAR LA BOLA QUE QUIERO METER
        // Es la bola objetivo que intersecta la dirección del tiro o está más alineada al frente
        val aimDirX = cos(aimAngle)
        val aimDirY = sin(aimAngle)

        var bestTarget: PointF? = null
        var bestDist = Float.MAX_VALUE

        for (ball in detectedBalls) {
            val toBallX = ball.x - cueBall.x
            val toBallY = ball.y - cueBall.y
            val dot = toBallX * aimDirX + toBallY * aimDirY

            // Debe estar delante de la bola blanca
            if (dot > ballRadius * 0.8f) {
                val perpDist = abs(toBallX * aimDirY - toBallY * aimDirX)
                if (perpDist < ballRadius * 2.2f && dot < bestDist) {
                    bestDist = dot
                    bestTarget = ball
                }
            }
        }

        if (bestTarget != null) {
            targetBall.set(bestTarget.x, bestTarget.y)
            selectedPocketIndex = -1 // Auto-detectar su respectiva buchaca
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
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 80f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 80f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 80f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 80f -> 3
                    mode == Mode.TARGET_AIM && hypot(x - cueBall.x, y - cueBall.y) < 75f -> 4
                    mode == Mode.TARGET_AIM && hypot(x - targetBall.x, y - targetBall.y) < 75f -> 5
                    else -> -1
                }

                // Selección directa con toque en buchaca o bola
                if (mode == Mode.TARGET_AIM && activeDragItem == -1) {
                    val pockets = getPocketsList()
                    for (i in pockets.indices) {
                        val p = pockets[i]
                        if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) < 80f) {
                            selectedPocketIndex = i
                            invalidate()
                            return true
                        }
                    }
                    for (b in detectedBalls) {
                        if (hypot((x - b.x).toDouble(), (y - b.y).toDouble()) < ballRadius * 1.6f) {
                            targetBall.set(b.x, b.y)
                            selectedPocketIndex = -1
                            invalidate()
                            return true
                        }
                    }
                    if (tableRect.contains(x, y)) {
                        selectedPocketIndex = -1
                        targetBall.set(x, y)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeDragItem) {
                    0 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                    1 -> { tableRect.right = x; tableRect.top = y; invalidate() }
                    2 -> { tableRect.left = x; tableRect.bottom = y; invalidate() }
                    3 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                    4 -> { cueBall.set(x, y); invalidate() }
                    5 -> { targetBall.set(x, y); invalidate() }
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