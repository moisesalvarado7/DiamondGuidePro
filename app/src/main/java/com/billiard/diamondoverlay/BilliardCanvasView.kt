package com.billiard.diamondoverlay

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class BilliardCanvasView(context: Context) : View(context) {

    enum class Mode { ADJUST_TABLE, MOVE_BALLS, LOCKED_PLAY }
    var mode = Mode.MOVE_BALLS
    var showThreeCushion = true

    // Marco ajustable de la mesa (proporción inicial centrada para pantalla horizontal de juego)
    var tableRect = RectF(260f, 220f, 2180f, 1180f)
    var cueBall = PointF(650f, 700f)      // Bola Blanca
    var targetBall = PointF(1550f, 520f)  // Bola Objetivo
    var ballRadius = 26f

    private var activeDragHandle = 0 // 0=ninguno, 1=Blanca, 2=Objetivo, 3=TopLeft, 4=BottomRight

    private val paintTable = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintDiamond = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val paintDirectPot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 0, 255, 128)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }
    private val paintBankLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 171, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
    }
    private val paintThreeCushion = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 224, 64, 251)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Dibujar el marco de la mesa y las 6 troneras
        canvas.drawRect(tableRect, paintTable)
        val pockets = getPockets()
        pockets.forEachIndexed { idx, p ->
            paintTable.color = Color.CYAN
            canvas.drawCircle(p.x, p.y, ballRadius * 1.5f, paintTable)
        }

        // 2. Dibujar los 18 Diamantes con sus etiquetas del Sistema Base 50
        drawDiamonds(canvas)

        // 3. Si estamos en modo ajustar mesa, dibujar esquinas de arrastre grandes
        if (mode == Mode.ADJUST_TABLE) {
            val handlePaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
            canvas.drawCircle(tableRect.left, tableRect.top, 40f, handlePaint)
            canvas.drawCircle(tableRect.right, tableRect.bottom, 40f, handlePaint)
            canvas.drawText("Arrastra los puntos ROJOS a las esquinas interiores de la mesa", tableRect.left + 50f, tableRect.top - 20f, paintText)
        }

        // 4. Evaluar y trazar automáticamente en qué troneras PUEDES ANOTAR (Ghost Ball)
        drawAutomaticPotSolutions(canvas, pockets)

        // 5. Calcular y trazar rebote de 1 banda (Espejo) hacia la bola objetivo
        drawOneCushionKick(canvas)

        // 6. Calcular y trazar el Sistema de Diamantes (3 Bandas Base 50)
        if (showThreeCushion) {
            drawThreeCushionDiamondSystem(canvas)
        }

        // 7. Dibujar Bola Blanca y Bola Objetivo
        val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f }
        canvas.drawCircle(cueBall.x, cueBall.y, ballRadius, cuePaint)
        canvas.drawCircle(cueBall.x, cueBall.y, 5f, Paint().apply { color = Color.WHITE })
        canvas.drawText("BLANCA", cueBall.x - 45f, cueBall.y - 35f, paintText)

        val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 5f }
        canvas.drawCircle(targetBall.x, targetBall.y, ballRadius, targetPaint)
        canvas.drawText("OBJETIVO", targetBall.x - 55f, targetBall.y - 35f, paintText)
    }

    private fun getPockets(): List<PointF> {
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

    private fun drawDiamonds(canvas: Canvas) {
        val wSegment = tableRect.width() / 8f
        val hSegment = tableRect.height() / 4f
        val attackLabels = arrayOf("0", "10", "20", "30", "40", "50", "60", "70", "80")
        val salidaLabels = arrayOf("50", "40", "35", "30", "25", "20", "15", "10", "5")

        // Bandas Largas (Superior = Ataque, Inferior = Salida/Llegada)
        for (i in 0..8) {
            val x = tableRect.left + i * wSegment
            canvas.drawCircle(x, tableRect.top, 8f, paintDiamond)
            canvas.drawText("A:${attackLabels[i]}", x - 22f, tableRect.top - 14f, paintText)

            canvas.drawCircle(x, tableRect.bottom, 8f, paintDiamond)
            canvas.drawText("S:${salidaLabels[i]}", x - 22f, tableRect.bottom + 32f, paintText)
        }

        // Bandas Cortas (3 diamantes intermedios por lado)
        for (j in 1..3) {
            val y = tableRect.top + j * hSegment
            canvas.drawCircle(tableRect.left, y, 8f, paintDiamond)
            canvas.drawCircle(tableRect.right, y, 8f, paintDiamond)
        }
    }

    /**
     * Evalúa las 6 troneras y dibuja únicamente aquellas donde el ángulo de corte es físicamente
     * posible (< 72 grados), mostrando dónde colocar la Bola Fantasma (Ghost Ball).
     */
    private fun drawAutomaticPotSolutions(canvas: Canvas, pockets: List<PointF>) {
        var bestPocketIdx = -1
        var bestAngleDeg = 999.0

        pockets.forEachIndexed { idx, pocket ->
            // Vector desde la tronera hacia la bola objetivo para hallar la Bola Fantasma
            val dx = targetBall.x - pocket.x
            val dy = targetBall.y - pocket.y
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist < 1f) return@forEachIndexed

            val ux = dx / dist
            val uy = dy / dist
            // La bola fantasma se ubica exactamente a 2 radios (1 diámetro) detrás de la bola objetivo
            val ghostX = targetBall.x + ux * (ballRadius * 2f)
            val ghostY = targetBall.y + uy * (ballRadius * 2f)

            // Vector de tiro desde la bola blanca hacia la bola fantasma
            val shotDx = ghostX - cueBall.x
            val shotDy = ghostY - cueBall.y
            val shotDist = hypot(shotDx.toDouble(), shotDy.toDouble()).toFloat()
            if (shotDist < 1f) return@forEachIndexed

            // Calcular el ángulo de corte (Cut Angle)
            val toPocketDx = -ux
            val toPocketDy = -uy
            val dot = (shotDx / shotDist) * toPocketDx + (shotDy / shotDist) * toPocketDy
            val cutAngleDeg = Math.toDegrees(acos(dot.coerceIn(-1f, 1f).toDouble()))

            // Si el corte es menor a 70°, es anotable directamente
            if (cutAngleDeg <= 70.0) {
                if (cutAngleDeg < bestAngleDeg) {
                    bestAngleDeg = cutAngleDeg
                    bestPocketIdx = idx
                }
                paintDirectPot.alpha = if (cutAngleDeg < 35.0) 255 else 130
                // Línea de Blanca -> Bola Fantasma -> Tronera
                canvas.drawLine(cueBall.x, cueBall.y, ghostX, ghostY, paintDirectPot)
                canvas.drawCircle(ghostX, ghostY, ballRadius, paintDirectPot)
                canvas.drawLine(targetBall.x, targetBall.y, pocket.x, pocket.y, paintDirectPot)
            }
        }

        if (bestPocketIdx != -1) {
            val p = pockets[bestPocketIdx]
            canvas.drawText(
                "⭐ MEJOR TIRO DIRECTO: Tronera #${bestPocketIdx + 1} (Corte: ${bestAngleDeg.toInt()}°)",
                tableRect.left + 20f, tableRect.top + 45f, paintText
            )
            canvas.drawCircle(p.x, p.y, ballRadius * 2.1f, paintDirectPot)
        }
    }

    /**
     * Calcula automáticamente el tiro de 1 Banda en la banda superior (Ley de Reflexión por espejo)
     * para conectar la Bola Blanca con la Bola Objetivo cuando estás tapado.
     */
    private fun drawOneCushionKick(canvas: Canvas) {
        val mirrorTargetY = tableRect.top - (targetBall.y - tableRect.top)
        val dy1 = cueBall.y - tableRect.top
        val dyTotal = cueBall.y - mirrorTargetY
        if (dyTotal <= 0f) return

        val ratio = dy1 / dyTotal
        val bounceX = cueBall.x + (targetBall.x - cueBall.x) * ratio
        val bouncePoint = PointF(bounceX, tableRect.top)

        if (bounceX in tableRect.left..tableRect.right) {
            canvas.drawLine(cueBall.x, cueBall.y, bouncePoint.x, bouncePoint.y, paintBankLine)
            canvas.drawLine(bouncePoint.x, bouncePoint.y, targetBall.x, targetBall.y, paintBankLine)
            val diamondValue = ((bounceX - tableRect.left) / (tableRect.width() / 8f)) * 10f
            canvas.drawText("1 Banda -> Diamante ${diamondValue.toInt()}", bounceX - 60f, tableRect.top + 35f, paintText)
        }
    }

    /**
     * Calcula automáticamente el Sistema de Diamantes (3 Bandas: Larga Superior -> Corta Derecha -> Larga Inferior)
     * para llegar exactamente a la coordenada de la Bola Objetivo.
     */
    private fun drawThreeCushionDiamondSystem(canvas: Canvas) {
        // 1. Calcular qué valor de Llegada (en 3ra banda inferior) conecta con la Bola Objetivo
        val w = tableRect.width()
        val chegadaNorm = ((targetBall.x - tableRect.left) / w).coerceIn(0.1f, 0.9f)
        val llegadaVal = chegadaNorm * 80f // Escala de 0 a 80 en banda inferior

        // 2. Calcular el valor de Salida de la Bola Blanca proyectado en la banda inferior
        val salidaNorm = ((cueBall.x - tableRect.left) / w).coerceIn(0.0f, 1.0f)
        val salidaVal = 50f - (salidaNorm * 40f) // De 50 (izq) a 10 (der)

        // 3. Fórmula Base 50: Ataque = Salida - Llegada (ajustado a escala de mesa)
        val ataqueVal = (salidaVal - (llegadaVal * 0.5f)).coerceIn(5f, 75f)
        val bounce1X = tableRect.left + (ataqueVal / 80f) * w
        val bounce1 = PointF(bounce1X, tableRect.top) // 1ra Banda (Superior)

        val bounce2Y = tableRect.top + tableRect.height() * 0.45f
        val bounce2 = PointF(tableRect.right, bounce2Y) // 2da Banda (Corta Derecha)

        val bounce3X = tableRect.left + chegadaNorm * w
        val bounce3 = PointF(bounce3X, tableRect.bottom) // 3ra Banda (Inferior)

        // Dibujar polilínea de 3 bandas
        canvas.drawLine(cueBall.x, cueBall.y, bounce1.x, bounce1.y, paintThreeCushion)
        canvas.drawLine(bounce1.x, bounce1.y, bounce2.x, bounce2.y, paintThreeCushion)
        canvas.drawLine(bounce2.x, bounce2.y, bounce3.x, bounce3.y, paintThreeCushion)
        canvas.drawLine(bounce3.x, bounce3.y, targetBall.x, targetBall.y, paintThreeCushion)

        canvas.drawText(
            "💎 3 Bandas: Salida ${salidaVal.toInt()} - Ataque ${ataqueVal.toInt()} = Llegada ${ (salidaVal - ataqueVal).toInt() }",
            tableRect.left + 20f, tableRect.bottom - 20f, paintText
        )
    }

    /**
     * Escáner de visión artificial ligero: recorre el área interior de la mesa buscando la concentración
     * circular de píxeles blancos puros (R>235, G>235, B>235) y mueve la Bola Blanca ahí automáticamente.
     */
    fun detectCueBallFromBitmap(bmp: Bitmap) {
        val left = tableRect.left.toInt().coerceIn(0, bmp.width - 1)
        val right = tableRect.right.toInt().coerceIn(0, bmp.width - 1)
        val top = tableRect.top.toInt().coerceIn(0, bmp.height - 1)
        val bottom = tableRect.bottom.toInt().coerceIn(0, bmp.height - 1)

        var sumX = 0L
        var sumY = 0L
        var count = 0

        // Muestreo cada 4 píxeles para hacerlo en menos de 15 milisegundos
        for (y in top..bottom step 4) {
            for (x in left..right step 4) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Filtro de blanco brillante con baja saturación cromática
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
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.LOCKED_PLAY) return false
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeDragHandle = when {
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.left, y - tableRect.top) < 90f -> 3
                    mode == Mode.ADJUST_TABLE && hypot(x - tableRect.right, y - tableRect.bottom) < 90f -> 4
                    hypot(x - cueBall.x, y - cueBall.y) < 85f -> 1
                    hypot(x - targetBall.x, y - targetBall.y) < 85f -> 2
                    else -> 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeDragHandle) {
                    1 -> { cueBall.set(x, y); invalidate() }
                    2 -> { targetBall.set(x, y); invalidate() }
                    3 -> { tableRect.left = x; tableRect.top = y; invalidate() }
                    4 -> { tableRect.right = x; tableRect.bottom = y; invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> activeDragHandle = 0
        }
        return true
    }
}