package com.pixel9.signalsurvey.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import com.pixel9.signalsurvey.model.PositionMethod
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Top-down plan of the whole survey.
 *
 * The deliverable a single annotated photo cannot be. A photo shows what one viewpoint saw;
 * this shows the space — where the operator walked, where each shot was taken and which way
 * it looked, and where every emitter the session managed to locate actually sits, including
 * the ones no camera ever pointed at.
 *
 * Position error is drawn as a ring rather than reported as a number, because an access
 * point trilaterated to ±0.8 m and one guessed from RSSI to ±6 m should not look alike.
 */
class PlanViewRenderer {

    fun render(session: SurveySession, sizePx: Int = 2048): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val theme = PlanTheme(sizePx)
        canvas.drawColor(BACKGROUND)

        val points = buildList {
            addAll(session.cameraPath.map { it.world })
            addAll(session.locatedEmitters.mapNotNull { it.worldPosition })
        }
        if (points.isEmpty()) {
            drawEmpty(canvas, theme, sizePx)
            return bitmap
        }

        val projection = Projection(points, sizePx, theme.margin)

        drawGrid(canvas, projection, theme, sizePx)
        drawPath(canvas, session, projection, theme)
        drawShots(canvas, session, projection, theme)
        drawEmitters(canvas, session, projection, theme)
        drawLegend(canvas, session, theme, sizePx)
        drawScaleBar(canvas, projection, theme, sizePx)
        drawCompass(canvas, session, theme, sizePx)
        return bitmap
    }

    /**
     * Compass rose, drawn only when a heading was actually resolved.
     *
     * The wedge is the heading's 1-sigma uncertainty, so a survey taken next to a steel
     * staircase shows a fat wedge and one taken in a wooden house shows a thin one. Drawing
     * a crisp north arrow in both cases would be the easy lie.
     */
    private fun drawCompass(canvas: Canvas, session: SurveySession, theme: PlanTheme, size: Int) {
        val northYaw = session.shots.firstNotNullOfOrNull { it.camera.trueNorthYawRad }
        val uncertainty = session.shots.firstNotNullOfOrNull { it.camera.trueNorthUncertaintyRad }

        val cx = size - theme.margin * 0.75f
        val cy = theme.margin * 0.9f
        val radius = theme.margin * 0.42f

        if (northYaw == null) {
            canvas.drawCircle(cx, cy, radius, theme.compassRing)
            canvas.drawText("no heading", cx, cy + theme.compassLabelOffset, theme.compassUnknown)
            canvas.drawText(
                "orientation is arbitrary",
                cx, cy + theme.compassLabelOffset + theme.compassSubGap, theme.compassUnknown,
            )
            return
        }

        // North in world yaw terms: worldYaw = trueBearing - offset, with trueBearing = 0.
        val worldYawOfNorth = -northYaw
        // Canvas up is world -Z, and screen Y grows downward.
        val dx = sin(worldYawOfNorth.toDouble()).toFloat()
        val dy = cos(worldYawOfNorth.toDouble()).toFloat()

        uncertainty?.let { sigma ->
            val wedge = Path().apply {
                moveTo(cx, cy)
                val steps = 12
                for (i in 0..steps) {
                    val t = i / steps.toFloat() * 2f - 1f
                    val a = worldYawOfNorth + t * sigma
                    lineTo(cx + sin(a.toDouble()).toFloat() * radius,
                        cy + cos(a.toDouble()).toFloat() * radius)
                }
                close()
            }
            canvas.drawPath(wedge, theme.compassWedge)
        }

        canvas.drawCircle(cx, cy, radius, theme.compassRing)
        canvas.drawLine(cx, cy, cx + dx * radius, cy + dy * radius, theme.compassNeedle)
        canvas.drawText("N", cx + dx * radius * 1.28f, cy + dy * radius * 1.28f + theme.tick,
            theme.compassLetter)

        uncertainty?.let {
            canvas.drawText(
                "+/-%.0f deg".format(Math.toDegrees(it.toDouble())),
                cx, cy + radius + theme.compassLabelOffset, theme.compassUnknown,
            )
        }
    }

    // --------------------------------------------------------------- projection

    /**
     * World X-Z to canvas pixels, uniform scale.
     *
     * Up on the canvas is world -Z, which is wherever the camera happened to be pointing
     * when the AR session started — *not* north. A compass rose is drawn separately when a
     * heading was resolved; without one, the plan has no absolute orientation and does not
     * pretend otherwise.
     */
    private class Projection(points: List<Vec3>, canvasSize: Int, margin: Float) {
        private val minX = points.minOf { it.x }
        private val maxX = points.maxOf { it.x }
        private val minZ = points.minOf { it.z }
        private val maxZ = points.maxOf { it.z }

        private val spanX = max(maxX - minX, 1f)
        private val spanZ = max(maxZ - minZ, 1f)
        private val usable = canvasSize - margin * 2

        /** Pixels per metre. */
        val scale: Float = (usable / max(spanX, spanZ)) * 0.92f

        private val offsetX = margin + (usable - spanX * scale) / 2f
        private val offsetY = margin + (usable - spanZ * scale) / 2f

        fun map(p: Vec3) = PointF(
            offsetX + (p.x - minX) * scale,
            offsetY + (p.z - minZ) * scale,
        )

        fun metresToPx(m: Float) = m * scale
    }

    // ------------------------------------------------------------------ drawing

    private fun drawGrid(canvas: Canvas, projection: Projection, theme: PlanTheme, size: Int) {
        // One line per metre, emphasised every five.
        val step = projection.metresToPx(1f)
        if (step < 8f) return

        var i = 0
        var x = 0f
        while (x < size) {
            canvas.drawLine(x, 0f, x, size.toFloat(), if (i % 5 == 0) theme.gridMajor else theme.grid)
            canvas.drawLine(0f, x, size.toFloat(), x, if (i % 5 == 0) theme.gridMajor else theme.grid)
            x += step
            i++
        }
    }

    private fun drawPath(canvas: Canvas, session: SurveySession, projection: Projection, theme: PlanTheme) {
        val path = session.cameraPath
        if (path.size < 2) return

        val line = Path()
        path.forEachIndexed { i, point ->
            val p = projection.map(point.world)
            if (i == 0) line.moveTo(p.x, p.y) else line.lineTo(p.x, p.y)
        }
        canvas.drawPath(line, theme.pathStroke)
    }

    private fun drawShots(canvas: Canvas, session: SurveySession, projection: Projection, theme: PlanTheme) {
        session.shots.forEach { shot ->
            val origin = projection.map(shot.camera.worldPosition)
            val forward = shot.camera.forward()
            val heading = kotlin.math.atan2(forward.x.toDouble(), forward.z.toDouble()).toFloat()
            val halfFov = Math.toRadians(shot.camera.horizontalFovDeg / 2.0).toFloat()
            val reach = projection.metresToPx(SHOT_WEDGE_M)

            // A wedge showing which way this shot looked; overlapping wedges are exactly
            // where cross-shot emitter resolution has the most evidence.
            val wedge = Path().apply {
                moveTo(origin.x, origin.y)
                lineTo(
                    origin.x + sin(heading - halfFov) * reach,
                    origin.y + cos(heading - halfFov) * reach,
                )
                lineTo(
                    origin.x + sin(heading + halfFov) * reach,
                    origin.y + cos(heading + halfFov) * reach,
                )
                close()
            }
            canvas.drawPath(wedge, theme.wedge)

            canvas.drawCircle(origin.x, origin.y, theme.shotRadius, theme.shotFill)
            canvas.drawCircle(origin.x, origin.y, theme.shotRadius, theme.shotStroke)
            canvas.drawText("#${shot.index}", origin.x, origin.y + theme.shotTextOffset, theme.shotText)
        }
    }

    private fun drawEmitters(
        canvas: Canvas,
        session: SurveySession,
        projection: Projection,
        theme: PlanTheme,
    ) {
        session.locatedEmitters.forEach { emitter ->
            val world = emitter.worldPosition ?: return@forEach
            val p = projection.map(world)
            val paint = theme.familyPaint(emitter.observation.family)

            // Error ring, so an RTT fix and an RSSI guess never look equally certain.
            emitter.positionErrorM?.let { error ->
                val radius = projection.metresToPx(error).coerceIn(theme.emitterRadius, 400f)
                canvas.drawCircle(p.x, p.y, radius, theme.errorRing(paint.color))
            }

            canvas.drawCircle(p.x, p.y, theme.emitterRadius, paint)
            if (!emitter.method.isMeasured) {
                // Hollow outline marks an inferred position, matching the photo annotations.
                canvas.drawCircle(p.x, p.y, theme.emitterRadius * 1.7f, theme.inferredRing)
            }

            val label = emitter.observation.displayName.take(24)
            canvas.drawText(label, p.x + theme.emitterRadius * 2f, p.y + theme.labelOffset, theme.emitterLabel)
            canvas.drawText(
                "${emitter.observation.standard} · ${emitter.method.label}",
                p.x + theme.emitterRadius * 2f,
                p.y + theme.labelOffset + theme.subLabelGap,
                theme.emitterSubLabel,
            )
        }
    }

    private fun drawLegend(canvas: Canvas, session: SurveySession, theme: PlanTheme, size: Int) {
        var y = theme.margin * 0.6f
        val x = theme.margin * 0.5f

        canvas.drawText(session.label, x, y, theme.heading)
        y += theme.headingGap

        val located = session.locatedEmitters
        val byMethod = located.groupingBy { it.method }.eachCount()

        listOf(
            "${session.shots.size} shots · %.1f m walked · %d s"
                .format(session.pathLengthM(), session.durationMs / 1000),
            "${session.observations.size} distinct emitters heard, ${located.size} located",
            byMethod.entries.joinToString(" · ") { "${it.value} by ${it.key.label}" },
            session.deviceProfile,
        ).forEach {
            if (it.isNotBlank()) {
                canvas.drawText(it, x, y, theme.legend)
                y += theme.legendGap
            }
        }

        // Family colour key.
        y += theme.legendGap * 0.5f
        RadioFamily.entries.filter { family ->
            session.observations.values.any { it.family == family }
        }.forEach { family ->
            canvas.drawCircle(x + theme.emitterRadius, y - theme.emitterRadius, theme.emitterRadius, theme.familyPaint(family))
            canvas.drawText(family.label, x + theme.emitterRadius * 3f, y, theme.legend)
            y += theme.legendGap
        }
    }

    private fun drawScaleBar(canvas: Canvas, projection: Projection, theme: PlanTheme, size: Int) {
        val metres = listOf(1f, 2f, 5f, 10f, 20f, 50f)
            .lastOrNull { projection.metresToPx(it) < size * 0.25f } ?: 1f
        val lengthPx = projection.metresToPx(metres)
        val y = size - theme.margin * 0.6f
        val x = size - theme.margin * 0.5f - lengthPx

        canvas.drawLine(x, y, x + lengthPx, y, theme.scaleBar)
        canvas.drawLine(x, y - theme.tick, x, y + theme.tick, theme.scaleBar)
        canvas.drawLine(x + lengthPx, y - theme.tick, x + lengthPx, y + theme.tick, theme.scaleBar)
        canvas.drawText("${metres.toInt()} m", x + lengthPx / 2f, y - theme.tick * 2f, theme.scaleText)
    }

    private fun drawEmpty(canvas: Canvas, theme: PlanTheme, size: Int) {
        canvas.drawText(
            "No positions resolved - walk further between shots",
            size / 2f, size / 2f, theme.emptyText,
        )
    }

    private companion object {
        const val BACKGROUND = 0xFF0B0F14.toInt()
        /** How far the shot direction wedges reach, in metres. */
        const val SHOT_WEDGE_M = 4f
    }
}

private class PlanTheme(canvasSize: Int) {

    private val unit = canvasSize / 2048f

    val margin = 150f * unit
    val emitterRadius = 11f * unit
    val shotRadius = 15f * unit
    val shotTextOffset = 7f * unit
    val labelOffset = 6f * unit
    val subLabelGap = 24f * unit
    val headingGap = 46f * unit
    val legendGap = 32f * unit
    val tick = 10f * unit

    private fun paint(size: Float, color: Int, bold: Boolean = false, center: Boolean = false) =
        Paint().apply {
            isAntiAlias = true
            textSize = size * unit
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            if (center) textAlign = Paint.Align.CENTER
        }

    val heading = paint(34f, Color.WHITE, bold = true)
    val legend = paint(22f, 0xFFB9C4D0.toInt())
    val emitterLabel = paint(21f, 0xFFE4EAF0.toInt())
    val emitterSubLabel = paint(17f, 0xFF8B94A0.toInt())
    val shotText = paint(19f, Color.BLACK, bold = true, center = true)
    val scaleText = paint(20f, 0xFFB9C4D0.toInt(), center = true)
    val emptyText = paint(28f, 0xFF8B94A0.toInt(), center = true)

    val grid = Paint().apply { color = 0x14FFFFFF; strokeWidth = 1f * unit }
    val gridMajor = Paint().apply { color = 0x28FFFFFF; strokeWidth = 1.5f * unit }

    val pathStroke = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f * unit
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xAA5CC8FF.toInt()
    }

    val wedge = Paint().apply { isAntiAlias = true; color = 0x225CC8FF }
    val shotFill = Paint().apply { isAntiAlias = true; color = 0xFF5CC8FF.toInt() }
    val shotStroke = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * unit
        color = 0xFF0B0F14.toInt()
    }

    val inferredRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * unit, 5f * unit), 0f)
        color = 0xFF8B94A0.toInt()
    }

    val scaleBar = Paint().apply {
        isAntiAlias = true
        strokeWidth = 3f * unit
        color = 0xFFB9C4D0.toInt()
    }

    val compassLabelOffset = 26f * unit
    val compassSubGap = 24f * unit
    val compassRing = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        color = 0x44FFFFFF
    }
    val compassWedge = Paint().apply { isAntiAlias = true; color = 0x33FFE066 }
    val compassNeedle = Paint().apply {
        isAntiAlias = true
        strokeWidth = 4f * unit
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFE066.toInt()
    }
    val compassLetter = paint(24f, 0xFFFFE066.toInt(), bold = true, center = true)
    val compassUnknown = paint(18f, 0xFF8B94A0.toInt(), center = true)

    fun errorRing(color: Int) = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * unit
        this.color = (color and 0x00FFFFFF) or 0x33000000
    }

    fun familyPaint(family: RadioFamily) = Paint().apply {
        isAntiAlias = true
        color = when (family) {
            RadioFamily.WIFI -> 0xFF5CC8FF.toInt()
            RadioFamily.BLUETOOTH -> 0xFF9B8CFF.toInt()
            RadioFamily.CELLULAR -> 0xFFFFA65C.toInt()
            RadioFamily.GNSS -> 0xFFFFE066.toInt()
            RadioFamily.NETWORK_SERVICE -> 0xFF7FD4A8.toInt()
            else -> 0xFF8B94A0.toInt()
        }
    }
}
