package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { ADJUST_TABLE, MOVE_BALLS, LOCKED_PLAY }
    enum class GridFilter { ALL, MAIN_ANGLES, OFF }

    var mode = Mode.LOCKED_PLAY
    var gridFilter = GridFilter.ALL
    var showThreeCushion = false
    var gridAlpha = 195

    // Rectángulo del tapete de la mesa
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(650f, 700f)      // Bola Blanca
    var targetBall = PointF(1550f, 520f)  // Bola Objetivo
    var ballRadius = 24f

    private var activeDragCorner = -1 // 0=TopLeft, 1=TopRight, 2=BottomLeft, 3=BottomRight, 4=CueBall, 5=TargetBall
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

    // Pinceles optimizados para dibujo a 120Hz
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
        color = Color.parseColor("#151515")
        style = Paint.Style.FILL
    }
    private val paintPocketRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
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
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(5f, 2f, 2f, Color.BLACK)
    }
    private val paintHint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }
    private val paintGhostBall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 255, 128)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintThreeCushion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 224, 64, 251)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        buildDiamondGrid()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!loadTableRect()) {
            // Posicionamiento inicial centrado optimizado para 8 Ball Pool en horizontal
            val marginX = w * 0.10f
            val marginY = h * 0.16f
            tableRect.set(marginX, marginY, w - marginX, h - marginY)
            cueBall.set(tableRect.left + tableRect.width() * 0.25f, tableRect.centerY())
            targetBall.set(tableRect.left + tableRect.width() * 0.70f, tableRect.centerY())
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

    /**
     * Construye y pre-calcula la cuadrícula de diamantes completa con la lógica:
     * "Mismo color = líneas paralelas (mismo ángulo de rebote)" de las imágenes de referencia.
     */
    private fun buildDiamondGrid() {
        diamondLines.clear()
        val allPoints = mutableListOf<Point>()

        // 1. Puntos de la banda superior (0 a 8) e inferior (0 a 8)
        for (x in 0..8) allPoints.add(Point(x, 0))
        for (x in 0..8) allPoints.add(Point(x, 4))
        // 2. Puntos intermedios de bandas cortas izquierda (1 a 3) y derecha (1 a 3)
        for (y in 1..3) allPoints.add(Point(0, y))
        for (y in 1..3) allPoints.add(Point(8, y))

        val seenPairs = mutableSetOf<String>()

        for (p1 in allPoints) {
            for (p2 in allPoints) {
                if (p1.x == p2.x && p1.y == p2.y) continue

                // Evitar conectar diamantes adyacentes de la misma banda
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
                (redDx == 1 && redDy == 1) || // 45° (1:1)
                (redDx == 2 && redDy == 1) || // 1:2
                (redDx == 1 && redDy == 2) || // 2:1
                (redDx == 3 && redDy == 1) || // 1:3
                (redDx == 1 && redDy == 3) || // 3:1
                (redDx == 4 && redDy == 1) || // 1:4
                (redDx == 3 && redDy == 2) || // 2:3
                (redDx == 2 && redDy == 3))   // 3:2
    }

    /**
     * Asigna un color exacto para cada inclinación / ángulo.
     * Líneas paralelas (mismo ángulo de rebote) comparten el mismo color.
     */
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Dibujar contorno exterior de madera y marco interior
        canvas.drawRect(tableRect, paintTableBorder)
        canvas.drawRect(tableRect, paintTableInnerRim)

        // 2. Dibujar las 6 troneras (4 esquinas y 2 laterales)
        drawPockets(canvas)

        // 3. Dibujar la cuadrícula de diamantes con colores por ángulo (Imágenes 3 y 4)
        if (gridFilter != GridFilter.OFF) {
            drawDiamondGridLines(canvas)
        }

        // 4. Dibujar los puntos de diamantes y sus números en las bandas (0..8 y 0..4)
        drawDiamondsAndLabels(canvas)

        // 5. Si estamos en modo ajustar mesa, dibujar puntos de arrastre en las 4 esquinas
        if (mode == Mode.ADJUST_TABLE) {
            drawAdjustmentHandles(canvas)
        } else if (mode == Mode.MOVE_BALLS) {
            drawBallsAndTrajectories(canvas)
        }

        // 6. Sistema de 3 bandas si está activo
        if (showThreeCushion) {
            drawThreeCushionSystem(canvas)
        }
    }

    private fun drawPockets(canvas: Canvas) {
        val cx = tableRect.centerX()
        val cornerRadius = ballRadius * 1.8f
        val sideRadius = ballRadius * 1.5f

        val pockets = listOf(
            PointF(tableRect.left, tableRect.top) to cornerRadius,
            PointF(cx, tableRect.top) to sideRadius,
            PointF(tableRect.right, tableRect.top) to cornerRadius,
            PointF(tableRect.left, tableRect.bottom) to cornerRadius,
            PointF(cx, tableRect.bottom) to sideRadius,
            PointF(tableRect.right, tableRect.bottom) to cornerRadius
        )

        for ((p, r) in pockets) {
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

        // Banda Superior e Inferior (0 a 8)
        for (i in 0..8) {
            val x = tableRect.left + i * wSeg

            // Diamante superior y etiqueta
            canvas.drawCircle(x, tableRect.top, 6f, paintDiamondDot)
            canvas.drawText("$i", x - 6f, tableRect.top - 18f, paintText)

            // Diamante inferior y etiqueta
            canvas.drawCircle(x, tableRect.bottom, 6f, paintDiamondDot)
            canvas.drawText("$i", x - 6f, tableRect.bottom + 36f, paintText)
        }

        // Banda Izquierda y Derecha (0 a 4)
        for (j in 0..4) {
            val y = tableRect.top + j * hSeg

            // Diamante izquierdo y etiqueta
            if (j in 1..3) canvas.drawCircle(tableRect.left, y, 6f, paintDiamondDot)
            canvas.drawText("$j", tableRect.left - 34f, y + 8f, paintText)

            // Diamante derecho y etiqueta
            if (j in 1..3) canvas.drawCircle(tableRect.right, y, 6f, paintDiamondDot)
            canvas.drawText("$j", tableRect.right + 18f, y + 8f, paintText)
        }
    }

    private fun drawAdjustmentHandles(canvas: Canvas) {
        val paintCorner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // 4 esquinas con colores llamativos para arrastre
        paintCorner.color = Color.RED
        canvas.drawCircle(tableRect.left, tableRect.top, 36f, paintCorner)
        paintCorner.color = Color.YELLOW
        canvas.drawCircle(tableRect.right, tableRect.top, 36f, paintCorner)
        paintCorner.color = Color.CYAN
        canvas.drawCircle(tableRect.left, tableRect.bottom, 36f, paintCorner)
        paintCorner.color = Color.GREEN
        canvas.drawCircle(tableRect.right, tableRect.bottom, 36f, paintCorner)

        canvas.drawText(
            "📐 Arrastra las esquinas de color a las troneras de tu mesa. Luego toca '🎮 Jugar'.",
            tableRect.left + 20f, tableRect.top - 50f, paintHint
        )
    }

    private fun drawBallsAndTrajectories(canvas: Canvas) {
        // Bola Blanca
        val paintWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, paintWhite)
        canvas.drawCircle(cueBall.x, cueBall.y, 4f, Paint().apply { color = Color.WHITE })
        canvas.drawText("BLANCA", cueBall.x - 45f, cueBall.y - 32f, paintText)

        // Bola Objetivo
        val paintTarget = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, paintTarget)
        canvas.drawText("OBJETIVO", targetBall.x - 52f, targetBall.y - 32f, paintText)

        // Línea de tiro directo con Ghost Ball
        canvas.drawLine(cueBall.x, cueBall.y, targetBall.x, targetBall.y, paintGhostBall)
    }

    private fun drawThreeCushionSystem(canvas: Canvas) {
        val w = tableRect.width()
        val llegadaNorm = ((targetBall.x - tableRect.left) / w).coerceIn(0.1f, 0.9f)
        val salidaNorm = ((cueBall.x - tableRect.left) / w).coerceIn(0.0f, 1.0f)
        val salidaVal = 50f - (salidaNorm * 40f)
        val llegadaVal = llegadaNorm * 80f
        val ataqueVal = (salidaVal - (llegadaVal * 0.5f)).coerceIn(5f, 75f)

        val b1 = PointF(tableRect.left + (ataqueVal / 80f) * w, tableRect.top)
        val b2 = PointF(tableRect.right, tableRect.top + tableRect.height() * 0.45f)
        val b3 = PointF(tableRect.left + llegadaNorm * w, tableRect.bottom)

        canvas.drawLine(cueBall.x, cueBall.y, b1.x, b1.y, paintThreeCushion)
        canvas.drawLine(b1.x, b1.y, b2.x, b2.y, paintThreeCushion)
        canvas.drawLine(b2.x, b2.y, b3.x, b3.y, paintThreeCushion)
        canvas.drawLine(b3.x, b3.y, targetBall.x, targetBall.y, paintThreeCushion)
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
                activeDragCorner = when {
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 80f -> 0
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.top) < 80f -> 1
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.bottom) < 80f -> 2
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 80f -> 3
                    mode == Mode.MOVE_BALLS && hypot(x - cueBall.x, y - cueBall.y) < 70f -> 4
                    mode == Mode.MOVE_BALLS && hypot(x - targetBall.x, y - targetBall.y) < 70f -> 5
                    else -> -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeDragCorner) {
                    0 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                    1 -> { tableRect.right = x; tableRect.top = y; invalidate() }
                    2 -> { tableRect.left = x; tableRect.bottom = y; invalidate() }
                    3 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                    4 -> { cueBall.set(x, y); invalidate() }
                    5 -> { targetBall.set(x, y); invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeDragCorner in 0..3) {
                    saveTableRect()
                }
                activeDragCorner = -1
            }
        }
        return true
    }
}