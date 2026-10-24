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

    data class RayHit(
        val point: PointF,
        val cushionSide: CushionSide,
        val pocketIndex: Int = -1
    )

    var mode = Mode.LOCKED_PLAY
    var gridFilter = GridFilter.MAIN_ANGLES
    var showAimLine = true
    var showThreeCushion = false
    var gridAlpha = 185

    // Rectángulo del tapete de la mesa
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(650f, 700f)      // Bola Blanca
    var targetBall = PointF(1550f, 520f)  // Bola Objetivo
    var ballRadius = 24f

    private var activeDragItem = -1 // 0=TopLeft, 1=TopRight, 2=BottomLeft, 3=BottomRight, 4=CueBall, 5=TargetBall
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
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    // Pinceles para la Línea de Puntería Extendida y Buchacas
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
        strokeWidth = 4.8f
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#00E676"))
    }
    private val paintTargetBankShot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Dorado ámbar para rebotes
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
        setShadowLayer(6f, 0f, 0f, Color.parseColor("#FFAB00"))
    }
    private val paintPocketHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        setShadowLayer(12f, 0f, 0f, Color.parseColor("#00E676"))
    }
    private val paintDeflection = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 225, 245, 254)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        buildDiamondGrid()
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
            redDx == 0 -> Color.rgb(225, 230, 240)       // 90° Vertical: Blanco hielo
            redDy == 0 -> Color.rgb(210, 215, 225)       // 0° Horizontal: Plata
            redDx == 1 && redDy == 1 -> Color.rgb(255, 235, 59)  // 1:1 (45°): Amarillo Neón
            redDx == 2 && redDy == 1 -> Color.rgb(0, 230, 118)   // 1:2 (26.6°): Verde Esmeralda
            redDx == 1 && redDy == 2 -> Color.rgb(0, 229, 255)   // 2:1 (63.4°): Cian Eléctrico
            redDx == 3 && redDy == 1 -> Color.rgb(255, 64, 129)  // 1:3 (18.4°): Rosa / Magenta
            redDx == 1 && redDy == 3 -> Color.rgb(224, 64, 251)  // 3:1 (71.6°): Púrpura Neón
            redDx == 4 && redDy == 1 -> Color.rgb(255, 171, 0)   // 1:4 (14.0°): Ámbar Dorado
            redDx == 1 && redDy == 4 -> Color.rgb(255, 82, 82)   // 4:1 (76.0°): Coral Rojo
            redDx == 3 && redDy == 2 -> Color.rgb(118, 255, 3)   // 2:3 (33.7°): Verde Lima
            redDx == 2 && redDy == 3 -> Color.rgb(64, 196, 255)  // 3:2 (56.3°): Azul Cielo
            redDx == 4 && redDy == 3 -> Color.rgb(255, 138, 101) // 3:4 (36.9°): Salmón
            redDx == 3 && redDy == 4 -> Color.rgb(179, 136, 255) // 4:3 (53.1°): Lavanda Eléctrico
            redDx == 5 && redDy == 4 -> Color.rgb(38, 198, 218)  // 4:5 (38.7°): Turquesa
            redDx == 7 && redDy == 4 -> Color.rgb(240, 98, 146)  // 4:7 (29.7°): Fucsia
            else -> {
                val hue = (angleDeg * 5.7f) % 360f
                Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.95f))
            }
        }
    }

    fun getPocketsList(): List<PointF> {
        val cx = tableRect.centerX()
        return listOf(
            PointF(tableRect.left, tableRect.top),       // 0: Sup Izq
            PointF(cx, tableRect.top),                   // 1: Centro Sup
            PointF(tableRect.right, tableRect.top),      // 2: Sup Der
            PointF(tableRect.left, tableRect.bottom),    // 3: Inf Izq
            PointF(cx, tableRect.bottom),                // 4: Centro Inf
            PointF(tableRect.right, tableRect.bottom)    // 5: Inf Der
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Marco exterior e interior
        canvas.drawRect(tableRect, paintTableBorder)
        canvas.drawRect(tableRect, paintTableInnerRim)

        // 2. Las 6 Buchacas
        drawPockets(canvas)

        // 3. Cuadrícula de Diamantes por Ángulo (Imágenes 3 y 4)
        if (gridFilter != GridFilter.OFF) {
            drawDiamondGridLines(canvas)
        }

        // 4. Marcadores y números de diamantes (0..8 y 0..4)
        drawDiamondsAndLabels(canvas)

        // 5. LÍNEAS DE REFERENCIA EXTENDIDAS DESDE LA BOLA BLANCA A LA BUCHACA Y REBOTES
        if (showAimLine) {
            drawExtendedAimTrajectory(canvas)
        }

        // 6. Si estamos ajustando mesa, dibujar manejadores en las esquinas
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.TARGET_AIM) {
            canvas.drawText(
                "🎯 Mueve la Blanca o el Objetivo. Toca una buchaca para apuntar directo. Toca '🎮 Jugar' al terminar.",
                tableRect.left + 20f, tableRect.top - 40f, paintHint
            )
        }
    }

    private fun drawPockets(canvas: Canvas) {
        val pockets = getPocketsList()
        val cornerRadius = ballRadius * 1.7f
        val sideRadius = ballRadius * 1.4f

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
     * Traza la línea de referencia extendida completa desde la Bola Blanca hasta la Bola Objetivo,
     * y desde ahí se proyecta hasta la buchaca correspondiente o calcula los rebotes en banda.
     */
    private fun drawExtendedAimTrajectory(canvas: Canvas) {
        val dx = targetBall.x - cueBall.x
        val dy = targetBall.y - cueBall.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist < 1f) return

        val dirX = dx / dist
        val dirY = dy / dist

        // Posición de impacto: Bola Fantasma (Ghost Ball)
        val ghostX = targetBall.x - dirX * (ballRadius * 2f)
        val ghostY = targetBall.y - dirY * (ballRadius * 2f)

        // 1. Línea desde la Bola Blanca hasta la Bola Fantasma
        canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintCueShot)
        canvas.drawCircle(ghostX, ghostY, ballRadius, paintGhostBall)

        // 2. Línea de desvío de la bola blanca (Regla de 90 grados)
        val deflDirX = -dirY
        val deflDirY = dirX
        val cueDeflectHit = raycastToTable(PointF(ghostX, ghostY), deflDirX, deflDirY)
        canvas.drawLine(ghostX, ghostY, cueDeflectHit.point.x, cueDeflectHit.point.y, paintDeflection)

        // 3. Proyección extendida de la Bola Objetivo hacia la buchaca o banda
        val firstHit = raycastToTable(targetBall, dirX, dirY)

        if (firstHit.pocketIndex != -1) {
            // ¡ENTRA DIRECTO EN BUCHACA!
            canvas.drawLine(targetBall.x, targetBall.y, firstHit.point.x, firstHit.point.y, paintTargetDirectPot)
            drawPocketHighlight(canvas, firstHit.point, firstHit.pocketIndex, "★ BUCHACA", true)
        } else {
            // Choca con banda -> calcular REBOTE en espejo
            canvas.drawLine(targetBall.x, targetBall.y, firstHit.point.x, firstHit.point.y, paintTargetDirectPot)

            val refDirX = if (firstHit.cushionSide == CushionSide.LEFT || firstHit.cushionSide == CushionSide.RIGHT) -dirX else dirX
            val refDirY = if (firstHit.cushionSide == CushionSide.TOP || firstHit.cushionSide == CushionSide.BOTTOM) -dirY else dirY

            val secondHit = raycastToTable(firstHit.point, refDirX, refDirY)
            canvas.drawLine(firstHit.point.x, firstHit.point.y, secondHit.point.x, secondHit.point.y, paintTargetBankShot)

            if (secondHit.pocketIndex != -1) {
                drawPocketHighlight(canvas, secondHit.point, secondHit.pocketIndex, "★ REBOTE A BUCHACA", false)
            }
        }

        // 4. Dibujar Bola Blanca y Bola Objetivo
        drawBalls(canvas)
    }

    private fun drawBalls(canvas: Canvas) {
        val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val cueStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E5FF"); style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cuePaint)
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cueStroke)
        canvas.drawCircle(cueBall.x, cueBall.y, 4f, Paint().apply { color = Color.RED })
        canvas.drawText("BLANCA", cueBall.x - 42f, cueBall.y - 32f, paintText)

        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E53935"); style = Paint.Style.FILL }
        val targetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetPaint)
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetStroke)
        canvas.drawText("OBJETIVO", targetBall.x - 48f, targetBall.y - 32f, paintText)
    }

    private fun drawPocketHighlight(canvas: Canvas, p: PointF, pocketIdx: Int, label: String, isDirect: Boolean) {
        paintPocketHalo.color = if (isDirect) Color.parseColor("#00E676") else Color.parseColor("#FFD600")
        canvas.drawCircle(p.x, p.y, ballRadius * 2.2f, paintPocketHalo)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDirect) Color.parseColor("#00E676") else Color.parseColor("#FFD600")
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 2f, 2f, Color.BLACK)
        }
        val offsetY = if (p.y < tableRect.centerY()) 55f else -40f
        canvas.drawText(label, p.x - 65f, p.y + offsetY, labelPaint)
    }

    /**
     * Raycasting que detecta la colisión exacta contra las bandas y si ingresa a las buchacas.
     */
    private fun raycastToTable(origin: PointF, dirX: Float, dirY: Float): RayHit {
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

        // Comprobar si choca cerca de una buchaca (tronera)
        val pockets = getPocketsList()
        var pocketIdx = -1
        val pocketTol = ballRadius * 2.3f

        for (i in pockets.indices) {
            val p = pockets[i]
            if (hypot((hitX - p.x).toDouble(), (hitY - p.y).toDouble()) < pocketTol) {
                pocketIdx = i
                hitX = p.x
                hitY = p.y
                break
            }
        }

        return RayHit(PointF(hitX, hitY), hitSide, pocketIdx)
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
            "📐 Arrastra las esquinas a las troneras de tu mesa. Luego toca '🎮 Jugar'.",
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
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 75f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 75f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 75f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 75f -> 3
                    mode == Mode.TARGET_AIM && hypot(x - cueBall.x, y - cueBall.y) < 70f -> 4
                    mode == Mode.TARGET_AIM && hypot(x - targetBall.x, y - targetBall.y) < 70f -> 5
                    else -> -1
                }

                // Si en modo TARGET_AIM se toca cerca de una buchaca, apuntar automáticamente a esa buchaca
                if (mode == Mode.TARGET_AIM && activeDragItem == -1) {
                    val pockets = getPocketsList()
                    for (p in pockets) {
                        if (hypot((x - p.x).toDouble(), (y - p.y).toDouble()) < 75f) {
                            // Coloca la bola objetivo en dirección a esa buchaca
                            val toPocketX = p.x - cueBall.x
                            val toPocketY = p.y - cueBall.y
                            val d = hypot(toPocketX.toDouble(), toPocketY.toDouble()).toFloat()
                            if (d > 10f) {
                                targetBall.set(cueBall.x + (toPocketX / d) * (d * 0.5f), cueBall.y + (toPocketY / d) * (d * 0.5f))
                                invalidate()
                            }
                            return true
                        }
                    }
                    // O si toca en cualquier parte del tapete, colocar la bola objetivo ahí
                    if (tableRect.contains(x, y)) {
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