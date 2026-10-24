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

    var mode = Mode.LOCKED_PLAY
    var gridFilter = GridFilter.MAIN_ANGLES
    var showAimLine = true
    var gridAlpha = 175

    // Rectángulo del tapete de la mesa
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(650f, 700f)      // Bola Blanca
    var targetBall = PointF(1550f, 520f)  // Bola Objetivo
    var ballRadius = 25f                  // Radio calibrable de la bola (22..30px)
    var selectedPocketIndex = -1          // -1 = auto-detectar mejor buchaca, 0..5 = buchaca fijada

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
        strokeWidth = 2.0f
    }
    private val paintTableBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4E342E")
        style = Paint.Style.STROKE
        strokeWidth = 14f
    }
    private val paintTableInnerRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintPocket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121212")
        style = Paint.Style.FILL
    }
    private val paintPocketRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
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
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    // Pinceles para la Línea de Puntería Calibrada y Buchacas
    private val paintCueShot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#80D8FF"))
    }
    private val paintGhostBall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val paintTargetDirectPot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676") // Verde neón brillante para tiro a buchaca
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
        setShadowLayer(10f, 0f, 0f, Color.parseColor("#00E676"))
    }
    private val paintTargetBankShot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Dorado ámbar para rebotes a buchaca
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#FFAB00"))
    }
    private val paintPocketHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        setShadowLayer(14f, 0f, 0f, Color.parseColor("#00E676"))
    }
    private val paintDeflection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 225, 245, 254)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
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
            val marginX = w * 0.10f
            val marginY = h * 0.16f
            tableRect.set(marginX, marginY, w - marginX, h - marginY)
            cueBall.set(tableRect.left + tableRect.width() * 0.30f, tableRect.centerY() + 80f)
            targetBall.set(tableRect.left + tableRect.width() * 0.65f, tableRect.centerY() - 60f)
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
            redDx == 0 -> Color.rgb(225, 230, 240)       // 90° Vertical
            redDy == 0 -> Color.rgb(210, 215, 225)       // 0° Horizontal
            redDx == 1 && redDy == 1 -> Color.rgb(255, 235, 59)  // 1:1 (45°)
            redDx == 2 && redDy == 1 -> Color.rgb(0, 230, 118)   // 1:2 (26.6°)
            redDx == 1 && redDy == 2 -> Color.rgb(0, 229, 255)   // 2:1 (63.4°)
            redDx == 3 && redDy == 1 -> Color.rgb(255, 64, 129)  // 1:3 (18.4°)
            redDx == 1 && redDy == 3 -> Color.rgb(224, 64, 251)  // 3:1 (71.6°)
            redDx == 4 && redDy == 1 -> Color.rgb(255, 171, 0)   // 1:4 (14.0°)
            redDx == 1 && redDy == 4 -> Color.rgb(255, 82, 82)   // 4:1 (76.0°)
            redDx == 3 && redDy == 2 -> Color.rgb(118, 255, 3)   // 2:3 (33.7°)
            redDx == 2 && redDy == 3 -> Color.rgb(64, 196, 255)  // 3:2 (56.3°)
            redDx == 4 && redDy == 3 -> Color.rgb(255, 138, 101) // 3:4 (36.9°)
            redDx == 3 && redDy == 4 -> Color.rgb(179, 136, 255) // 4:3 (53.1°)
            redDx == 5 && redDy == 4 -> Color.rgb(38, 198, 218)  // 4:5 (38.7°)
            redDx == 7 && redDy == 4 -> Color.rgb(240, 98, 146)  // 4:7 (29.7°)
            else -> {
                val hue = (angleDeg * 5.7f) % 360f
                Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.95f))
            }
        }
    }

    /**
     * Devuelve los centros de caída (drop centers) calibrados de las 6 buchacas.
     * Tienen un inset hacia adentro para que la bola entre por la boca limpia sin golpear las muelas de la banda.
     */
    fun getPocketsList(): List<PointF> {
        val cx = tableRect.centerX()
        val cornerInset = ballRadius * 0.95f
        val sideInset = ballRadius * 0.35f
        return listOf(
            PointF(tableRect.left + cornerInset, tableRect.top + cornerInset),       // 0: Corner Top-Left
            PointF(cx, tableRect.top + sideInset),                                   // 1: Center Top
            PointF(tableRect.right - cornerInset, tableRect.top + cornerInset),      // 2: Corner Top-Right
            PointF(tableRect.left + cornerInset, tableRect.bottom - cornerInset),    // 3: Corner Bottom-Left
            PointF(cx, tableRect.bottom - sideInset),                                // 4: Center Bottom
            PointF(tableRect.right - cornerInset, tableRect.bottom - cornerInset)    // 5: Corner Bottom-Right
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Marco exterior de madera e interior
        canvas.drawRect(tableRect, paintTableBorder)
        canvas.drawRect(tableRect, paintTableInnerRim)

        // 2. Las 6 Buchacas visuales
        drawPockets(canvas)

        // 3. Cuadrícula de Diamantes por Ángulo (Imágenes 3 y 4)
        if (gridFilter != GridFilter.OFF) {
            drawDiamondGridLines(canvas)
        }

        // 4. Marcadores y números de diamantes (0..8 y 0..4)
        drawDiamondsAndLabels(canvas)

        // 5. LÍNEAS DE REFERENCIA CALIBRADAS (Tiro Directo a Buchaca + Rebotes en Banda a Buchaca)
        if (showAimLine) {
            drawSmartAimAndBankGuidance(canvas)
        }

        // 6. Si estamos ajustando mesa, dibujar manejadores en las esquinas
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.TARGET_AIM) {
            canvas.drawText(
                "🎯 Toca una buchaca para tiro directo o en banda. Arrastra la bola para ubicarla.",
                tableRect.left + 20f, tableRect.top - 40f, paintHint
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

    /**
     * MOTOR DE PUNTERÍA INTELIGENTE CALIBRADO:
     * 1. Resuelve el tiro directo perfecto hacia la buchaca mediante la Bola Fantasma física real.
     * 2. SI LA GUÍA TOCA UNA BANDA: Calcula y extiende la guía en espejo para que SIEMPRE BUSQUE UNA BUCHACA.
     */
    private fun drawSmartAimAndBankGuidance(canvas: Canvas) {
        val pockets = getPocketsList()

        // 1. Determinar la buchaca objetivo: la elegida por el usuario o la que tenga el mejor corte físico
        var bestPocketIdx = selectedPocketIndex
        var bestCutAngle = 999f

        if (bestPocketIdx == -1) {
            // Auto-detectar la buchaca con menor ángulo de corte (< 75°)
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

                if (cutAngleDeg < bestCutAngle && cutAngleDeg <= 75f) {
                    bestCutAngle = cutAngleDeg
                    bestPocketIdx = i
                }
            }
        }

        val directPocket = if (bestPocketIdx in 0..5) pockets[bestPocketIdx] else null

        if (directPocket != null && (bestCutAngle <= 75f || selectedPocketIndex != -1)) {
            // === TIRO DIRECTO A BUCHACA CON BOLA FANTASMA EXACTA ===
            val potDx = directPocket.x - targetBall.x
            val potDy = directPocket.y - targetBall.y
            val potDist = hypot(potDx.toDouble(), potDy.toDouble()).toFloat()
            val potDirX = potDx / potDist
            val potDirY = potDy / potDist

            val ghostX = targetBall.x - potDirX * (ballRadius * 2f)
            val ghostY = targetBall.y - potDirY * (ballRadius * 2f)

            // Línea del taco (Blanca a Bola Fantasma)
            canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueShot)
            canvas.drawCircle(ghostX, ghostY, ballRadius, paintGhostBall)

            // Línea de la Bola Objetivo EXTENDIDA directamente al fondo de la Buchaca
            canvas.drawLine(targetBall.x, targetBall.y, directPocket.x, directPocket.y, paintTargetDirectPot)
            drawPocketHighlight(canvas, directPocket, bestPocketIdx, "★ ENTRA EN BUCHACA #${bestPocketIdx + 1}", true)

            // Desvío de la blanca a 90 grados
            val deflDirX = -potDirY
            val deflDirY = potDirX
            val cueDeflectHit = raycastCushion(PointF(ghostX, ghostY), deflDirX, deflDirY)
            canvas.drawLine(ghostX, ghostY, cueDeflectHit.point.x, cueDeflectHit.point.y, paintDeflection)

        } else {
            // === SI NO HAY TIRO DIRECTO O APUNTA A BANDA: BUSCADOR DE REBOTE A BUCHACA ===
            drawBankShotSeekingPocket(canvas, pockets)
        }

        // 3. Dibujar las bolas con su tamaño calibrado
        drawBalls(canvas)
    }

    /**
     * CUANDO SE ENCUENTRA CON UNA BANDA: Extiende la guía calculando la reflexión en espejo
     * para que SIEMPRE BUSQUE Y CONECTE DIRECTAMENTE CON UNA BUCHACA.
     */
    private fun drawBankShotSeekingPocket(canvas: Canvas, pockets: List<PointF>) {
        val dx = targetBall.x - cueBall.x
        val dy = targetBall.y - cueBall.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 5f) return
        val dirX = dx / dist
        val dirY = dy / dist

        // 1. Raycast de la bola objetivo hasta la banda con la que choca
        val firstCushionHit = raycastCushion(targetBall, dirX, dirY)

        // 2. Línea desde la bola objetivo hasta la banda
        canvas.drawLine(targetBall.x, targetBall.y, firstCushionHit.point.x, firstCushionHit.point.y, paintTargetBankShot)

        // 3. De entre todas las buchacas, calcular cuál buchaca conecta por rebote en esta banda
        val bestBank = findBestBankShotToPocket(targetBall, firstCushionHit.cushionSide, pockets)

        if (bestBank != null) {
            // Línea de rebote EXTENDIDA DIRECTAMENTE AL FONDO DE LA BUCHACA ENCONTRADA
            canvas.drawLine(bestBank.bouncePoint.x, bestBank.bouncePoint.y, bestBank.pocketPoint.x, bestBank.pocketPoint.y, paintTargetBankShot)
            drawPocketHighlight(canvas, bestBank.pocketPoint, bestBank.pocketIndex, "★ REBOTE A BUCHACA #${bestBank.pocketIndex + 1}", false)

            // Línea del taco de la blanca
            val shotDx = bestBank.bouncePoint.x - targetBall.x
            val shotDy = bestBank.bouncePoint.y - targetBall.y
            val shotDist = hypot(shotDx.toDouble(), shotDy.toDouble()).toFloat()
            if (shotDist > 1f) {
                val uX = shotDx / shotDist
                val uY = shotDy / shotDist
                val ghostX = targetBall.x - uX * (ballRadius * 2f)
                val ghostY = targetBall.y - uY * (ballRadius * 2f)
                canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueShot)
                canvas.drawCircle(ghostX, ghostY, ballRadius, paintGhostBall)
            }
        } else {
            // Si el rebote no conecta con una buchaca de 1 banda, reflejar el rayo buscando la siguiente banda
            val refDirX = if (firstCushionHit.cushionSide == CushionSide.LEFT || firstCushionHit.cushionSide == CushionSide.RIGHT) -dirX else dirX
            val refDirY = if (firstCushionHit.cushionSide == CushionSide.TOP || firstCushionHit.cushionSide == CushionSide.BOTTOM) -dirY else dirY

            val secondHit = raycastCushion(firstCushionHit.point, refDirX, refDirY)
            canvas.drawLine(firstCushionHit.point.x, firstCushionHit.point.y, secondHit.point.x, secondHit.point.y, paintTargetBankShot)
        }
    }

    /**
     * Calcula analíticamente mediante la Ley de Reflexión por Espejo el punto exacto en la banda
     * que hace que la bola rebote DIRECTAMENTE hacia una buchaca.
     */
    private fun findBestBankShotToPocket(origin: PointF, cushion: CushionSide, pockets: List<PointF>): BankSolution? {
        val boundLeft = tableRect.left + ballRadius
        val boundRight = tableRect.right - ballRadius
        val boundTop = tableRect.top + ballRadius
        val boundBottom = tableRect.bottom - ballRadius

        var bestSolution: BankSolution? = null
        var minAngleDiff = Float.MAX_VALUE

        for (i in pockets.indices) {
            val pocket = pockets[i]

            // Calcular punto reflejado de la buchaca detrás de la banda
            val mirrorPocket = PointF(pocket.x, pocket.y)
            when (cushion) {
                CushionSide.TOP -> mirrorPocket.y = 2 * boundTop - pocket.y
                CushionSide.BOTTOM -> mirrorPocket.y = 2 * boundBottom - pocket.y
                CushionSide.LEFT -> mirrorPocket.x = 2 * boundLeft - pocket.x
                CushionSide.RIGHT -> mirrorPocket.x = 2 * boundRight - pocket.x
                else -> continue
            }

            // Hallar intersección entre la línea origin -> mirrorPocket y la banda
            val deltaX = mirrorPocket.x - origin.x
            val deltaY = mirrorPocket.y - origin.y

            val bouncePoint = PointF()

            when (cushion) {
                CushionSide.TOP, CushionSide.BOTTOM -> {
                    val cushionY = if (cushion == CushionSide.TOP) boundTop else boundBottom
                    if (abs(deltaY) < 0.001f) continue
                    val t = (cushionY - origin.y) / deltaY
                    if (t <= 0f || t >= 1f) continue
                    val hitX = origin.x + t * deltaX
                    if (hitX !in boundLeft..boundRight) continue
                    bouncePoint.set(hitX, cushionY)
                }
                CushionSide.LEFT, CushionSide.RIGHT -> {
                    val cushionX = if (cushion == CushionSide.LEFT) boundLeft else boundRight
                    if (abs(deltaX) < 0.001f) continue
                    val t = (cushionX - origin.x) / deltaX
                    if (t <= 0f || t >= 1f) continue
                    val hitY = origin.y + t * deltaY
                    if (hitY !in boundTop..boundBottom) continue
                    bouncePoint.set(cushionX, hitY)
                }
                else -> continue
            }

            // Comprobar la diferencia de ángulo entre la dirección actual y este rebote a buchaca
            val curDx = bouncePoint.x - origin.x
            val curDy = bouncePoint.y - origin.y
            val dist = hypot(curDx.toDouble(), curDy.toDouble()).toFloat()

            if (dist < minAngleDiff) {
                minAngleDiff = dist
                val angleDeg = Math.toDegrees(atan2(abs(curDy).toDouble(), abs(curDx).toDouble())).toFloat()
                bestSolution = BankSolution(bouncePoint, cushion, i, pocket, angleDeg)
            }
        }

        return bestSolution
    }

    private fun drawBalls(canvas: Canvas) {
        val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val cueStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cuePaint)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cueStroke)
        canvas.drawCircle(cueBall.x, cueBall.y, 4f, Paint().apply { color = Color.RED })
        canvas.drawText("BLANCA", cueBall.x - 42f, cueBall.y - (ballRadius + 10f), paintText)

        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935"); style = Paint.Style.FILL }
        val targetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetPaint)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetStroke)
        canvas.drawText("OBJETIVO", targetBall.x - 48f, targetBall.y - (ballRadius + 10f), paintText)
    }

    private fun drawPocketHighlight(canvas: Canvas, p: PointF, pocketIdx: Int, label: String, isDirect: Boolean) {
        paintPocketHalo.color = if (isDirect) Color.parseColor("#00E676") else Color.parseColor("#FFD600")
        canvas.drawCircle(p.x, p.y, ballRadius * 2.2f, paintPocketHalo)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDirect) Color.parseColor("#00E676") else Color.parseColor("#FFD600")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 2f, 2f, Color.BLACK)
        }
        val offsetY = if (p.y < tableRect.centerY()) 55f else -35f
        canvas.drawText(label, p.x - 85f, p.y + offsetY, labelPaint)
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
            if (t > 0.1f && t < minT) {
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
            if (t > 0.1f && t < minT) {
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
            if (t > 0.1f && t < minT) {
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
            if (t > 0.1f && t < minT) {
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

    fun detectCueBallFromBitmap(bmp: Bitmap) {
        val left = tableRect.left.toInt().coerceIn(0, bmp.width - 1)
        val right = tableRect.right.toInt().coerceIn(0, bmp.width - 1)
        val top = tableRect.top.toInt().coerceIn(0, bmp.height - 1)
        val bottom = tableRect.bottom.toInt().coerceIn(0, bmp.height - 1)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        for (y in top..bottom step 4) {
            for (x in left..right step 4) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (r > 238 && g > 238 && b > 238 && abs(r - g) < 12 && abs(g - b) < 12) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        if (count > 8) {
            cueBall.x = (sumX / count).toFloat()
            cueBall.y = (sumY / count).toFloat()
            invalidate()
        }
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

                // Si en modo TARGET_AIM se toca en una buchaca, fijarla inmediatamente
                if (mode == Mode.TARGET_AIM && activeDragItem == -1) {
                    val pockets = getPocketsList()
                    for (i in pockets.indices) {
                        val p = pockets[i]
                        if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) < 85f) {
                            selectedPocketIndex = i
                            invalidate()
                            return true
                        }
                    }
                    // Si toca en otra parte del tapete, mover la bola objetivo ahí y volver a auto-detectar
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