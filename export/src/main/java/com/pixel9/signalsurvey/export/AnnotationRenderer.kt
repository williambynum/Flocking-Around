package com.pixel9.signalsurvey.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.VisualTarget
import kotlin.math.max
import kotlin.math.min

enum class LineStyle { TITLE, META, MEASURED, INFERRED, DETAIL }

data class CardLine(val text: String, val style: LineStyle)

data class CardContent(val index: Int, val anchor: PointF, val lines: List<CardLine>)

private enum class Side { LEFT, RIGHT }

private class Callout(
    val content: CardContent,
    val side: Side,
    var box: RectF = RectF(),
    var wrapped: List<Pair<String, LineStyle>> = emptyList(),
)

/**
 * Burns annotations into a captured shot.
 *
 * Deliberately *not* boxes drawn over the devices. A box obscures the thing being
 * documented, and three devices near each other produce three overlapping boxes with
 * unreadable text. Survey imagery has solved this for a century: a precise marker at the
 * point, the description in a margin, and a leader line joining them.
 *
 * Every claim is badged by how it was arrived at — a filled dot for something measured, a
 * hollow one for something inferred — because a photo gets shared without its context and
 * has to defend itself.
 */
class AnnotationRenderer {

    fun render(
        shot: Shot,
        base: Bitmap,
        targets: List<VisualTarget>,
        /** Emitters located anywhere in the session that fall inside this frame. */
        visibleEmitters: List<ResolvedEmitter>,
        /** Heard but never located — summarised along the bottom edge. */
        unlocated: List<ResolvedEmitter>,
        sessionLabel: String,
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width
        val h = out.height
        val theme = Theme(w)

        // Rails darkened so light text survives a bright wall behind them.
        canvas.drawRect(0f, 0f, w * RAIL_FRACTION, h.toFloat(), theme.scrim)
        canvas.drawRect(w * (1f - RAIL_FRACTION), 0f, w.toFloat(), h.toFloat(), theme.scrim)

        val cards = buildCards(shot, targets, visibleEmitters, w, h)
        val callouts = layout(cards, w, h, theme)

        callouts.forEach { drawLeader(canvas, it, theme) }
        callouts.forEach { drawMarker(canvas, it, theme) }
        callouts.forEach { drawCard(canvas, it, theme) }

        drawUnlocatedStrip(canvas, unlocated, w, h, theme)
        drawFooter(canvas, shot, sessionLabel, w, h, theme)
        return out
    }

    // ------------------------------------------------------------------ content

    private fun buildCards(
        shot: Shot,
        targets: List<VisualTarget>,
        emitters: List<ResolvedEmitter>,
        w: Int,
        h: Int,
    ): List<CardContent> {
        val cards = mutableListOf<CardContent>()
        var index = 1

        targets.forEach { target ->
            val point = shot.camera.projectToImage(target.anchorWorld, w, h)
                ?: PointF(target.boxImagePx.exactCenterX(), target.boxImagePx.exactCenterY())

            val lines = mutableListOf<CardLine>()
            lines += CardLine(target.displayName, LineStyle.TITLE)
            lines += CardLine(
                "visual match ${(target.visualConfidence * 100).toInt()}%",
                LineStyle.META,
            )
            target.rangeM?.let {
                lines += CardLine(rangeText(it, target.rangeSource), LineStyle.META)
            }
            lines += CardLine(
                "%+.0f° %s · %+.0f° elevation".format(
                    target.bearingDeg,
                    if (target.bearingDeg >= 0) "right" else "left",
                    target.elevationDeg,
                ),
                LineStyle.META,
            )

            target.confirmed.forEach { signal ->
                val o = signal.observation
                lines += CardLine(
                    buildString {
                        append(o.standard)
                        o.rssiDbm?.let { append("  ").append(it).append(" dBm") }
                    },
                    LineStyle.MEASURED,
                )
                lines += CardLine(o.activityDescription(), LineStyle.DETAIL)
            }

            target.inferred.take(MAX_INFERRED_LINES).forEach {
                lines += CardLine("${it.profile.standard} — ${it.reason}", LineStyle.INFERRED)
            }

            cards += CardContent(index++, point, lines)
        }

        // Emitters located by the session but with no visual match in this frame. These are
        // the ones a single-shot app simply could not draw.
        emitters.filter { it.visualTargetId == null }.forEach { emitter ->
            val point = shot.camera.projectToImage(emitter.worldPosition!!, w, h) ?: return@forEach
            val geometry = shot.camera.geometryTo(emitter.worldPosition!!)
            val o = emitter.observation

            cards += CardContent(
                index++,
                point,
                listOf(
                    CardLine(o.displayName, LineStyle.TITLE),
                    CardLine("${o.family.label} · no visual identification", LineStyle.META),
                    CardLine(
                        "%.1f m · %s%s".format(
                            geometry.distanceM,
                            emitter.method.label,
                            emitter.positionErrorM?.let { " ±%.1f m".format(it) } ?: "",
                        ),
                        LineStyle.META,
                    ),
                    CardLine(o.standard, LineStyle.MEASURED),
                    CardLine(o.activityDescription(), LineStyle.DETAIL),
                    CardLine(
                        "located from ${emitter.fixCount} measurements across shots " +
                            emitter.seenInShots.joinToString(", ") { "#$it" },
                        LineStyle.DETAIL,
                    ),
                ),
            )
        }

        return cards
    }

    private fun rangeText(range: Float, source: com.pixel9.signalsurvey.model.RangeSource): String =
        if (source.isMeasured) "%.1f m  (%s)".format(range, source.shortLabel)
        else "~%.0f m  (%s, low confidence)".format(range, source.shortLabel)

    // ------------------------------------------------------------------- layout

    /**
     * Anchors never move; only the cards do. Cards go into left/right rails ordered by their
     * anchor's vertical position, then a single downward relaxation pass separates them.
     */
    private fun layout(cards: List<CardContent>, w: Int, h: Int, theme: Theme): List<Callout> {
        val margin = w * 0.025f
        val cardWidth = w * CARD_WIDTH_FRACTION
        val gap = h * 0.012f

        val callouts = cards.sortedBy { it.anchor.y }.map {
            Callout(it, if (it.anchor.x < w / 2f) Side.LEFT else Side.RIGHT)
        }

        Side.entries.forEach { side ->
            var cursorY = margin
            callouts.filter { it.side == side }.forEach { callout ->
                callout.wrapped = wrap(callout.content.lines, cardWidth - theme.cardPadding * 2, theme)
                val cardHeight = theme.cardPadding * 2 +
                    callout.wrapped.sumOf { theme.lineHeight(it.second).toDouble() }.toFloat()

                val top = max(cursorY, callout.content.anchor.y - cardHeight / 2f)
                    .coerceAtMost(h - cardHeight - margin)
                    .coerceAtLeast(margin)
                val left = if (side == Side.LEFT) margin else w - cardWidth - margin

                callout.box = RectF(left, top, left + cardWidth, top + cardHeight)
                cursorY = top + cardHeight + gap
            }
        }
        return callouts
    }

    private fun wrap(
        lines: List<CardLine>,
        maxWidth: Float,
        theme: Theme,
    ): List<Pair<String, LineStyle>> {
        val out = mutableListOf<Pair<String, LineStyle>>()
        lines.forEach { line ->
            val paint = theme.paintFor(line.style)
            val indent = if (line.style == LineStyle.DETAIL) theme.detailIndent else 0f
            val available = maxWidth - indent
            var current = StringBuilder()

            line.text.split(' ').forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= available) {
                    current = StringBuilder(candidate)
                } else {
                    if (current.isNotEmpty()) out += current.toString() to line.style
                    current = StringBuilder(word)
                }
            }
            if (current.isNotEmpty()) out += current.toString() to line.style
        }
        return out
    }

    // ------------------------------------------------------------------ drawing

    private fun drawLeader(canvas: Canvas, callout: Callout, theme: Theme) {
        val startX = if (callout.side == Side.LEFT) callout.box.right else callout.box.left
        val startY = callout.box.centerY()
        val end = callout.content.anchor
        val midX = (startX + end.x) / 2f

        canvas.drawPath(
            Path().apply {
                moveTo(startX, startY)
                cubicTo(midX, startY, midX, end.y, end.x, end.y)
            },
            theme.leader,
        )
    }

    private fun drawMarker(canvas: Canvas, callout: Callout, theme: Theme) {
        val p = callout.content.anchor
        canvas.drawCircle(p.x, p.y, theme.markerRadius * 1.9f, theme.markerHalo)
        canvas.drawCircle(p.x, p.y, theme.markerRadius, theme.markerFill)
        canvas.drawCircle(p.x, p.y, theme.markerRadius, theme.markerStroke)
        canvas.drawText(
            callout.content.index.toString(),
            p.x,
            p.y + theme.markerTextOffset,
            theme.markerText,
        )
    }

    private fun drawCard(canvas: Canvas, callout: Callout, theme: Theme) {
        canvas.drawRoundRect(callout.box, theme.corner, theme.corner, theme.cardBackground)
        canvas.drawRoundRect(callout.box, theme.corner, theme.corner, theme.cardBorder)

        var y = callout.box.top + theme.cardPadding
        val x = callout.box.left + theme.cardPadding

        callout.wrapped.forEachIndexed { i, (text, style) ->
            y += theme.lineHeight(style)
            val baseline = y - theme.lineDescent(style)

            when (style) {
                LineStyle.TITLE -> {
                    canvas.drawText("${callout.content.index}. $text", x, baseline, theme.title)
                }
                LineStyle.MEASURED -> {
                    // Filled dot: this was actually observed.
                    canvas.drawCircle(
                        x + theme.dotRadius, baseline - theme.dotRadius,
                        theme.dotRadius, theme.measuredDot,
                    )
                    canvas.drawText(text, x + theme.detailIndent, baseline, theme.measured)
                }
                LineStyle.INFERRED -> {
                    // Hollow dot: this is a guess, and the card says why.
                    canvas.drawCircle(
                        x + theme.dotRadius, baseline - theme.dotRadius,
                        theme.dotRadius, theme.inferredDot,
                    )
                    canvas.drawText(text, x + theme.detailIndent, baseline, theme.inferred)
                }
                LineStyle.DETAIL -> canvas.drawText(text, x + theme.detailIndent, baseline, theme.detail)
                LineStyle.META -> canvas.drawText(text, x, baseline, theme.meta)
            }
            if (i == 0) y += theme.titleGap
        }
    }

    /**
     * Everything heard but never placed. Without this the photo would quietly imply the only
     * signals present are the ones with markers, which in a typical office is off by a
     * hundred devices.
     */
    private fun drawUnlocatedStrip(
        canvas: Canvas,
        unlocated: List<ResolvedEmitter>,
        w: Int,
        h: Int,
        theme: Theme,
    ) {
        if (unlocated.isEmpty()) return

        val stripHeight = theme.stripHeight
        val top = h - stripHeight - theme.footerHeight
        canvas.drawRect(0f, top, w.toFloat(), top + stripHeight, theme.stripBackground)

        val byFamily = unlocated.groupingBy { it.observation.family }.eachCount()
        val header = "Heard but not located: " + byFamily.entries
            .sortedByDescending { it.value }
            .joinToString("  ·  ") { "${it.value} ${it.key.label}" }
        canvas.drawText(header, theme.cardPadding, top + theme.stripHeaderBaseline, theme.stripHeader)

        val strongest = unlocated.take(STRIP_ITEMS)
        val columnWidth = (w - theme.cardPadding * 2) / STRIP_COLUMNS
        strongest.forEachIndexed { i, emitter ->
            val col = i % STRIP_COLUMNS
            val row = i / STRIP_COLUMNS
            val x = theme.cardPadding + col * columnWidth
            val y = top + theme.stripItemBaseline + row * theme.stripRowHeight
            val o = emitter.observation
            val text = buildString {
                append(o.displayName.take(26))
                o.rssiDbm?.let { append("  ").append(it).append(" dBm") }
            }
            canvas.drawCircle(x + theme.dotRadius, y - theme.dotRadius, theme.dotRadius, theme.familyDot(o.family))
            canvas.drawText(text, x + theme.detailIndent, y, theme.stripItem)
        }
    }

    private fun drawFooter(
        canvas: Canvas,
        shot: Shot,
        sessionLabel: String,
        w: Int,
        h: Int,
        theme: Theme,
    ) {
        val top = h - theme.footerHeight
        canvas.drawRect(0f, top, w.toFloat(), h.toFloat(), theme.footerBackground)
        canvas.drawText(
            "$sessionLabel · shot #${shot.index} · " +
                "solid dot = measured, hollow = inferred (no receiver on this device)",
            theme.cardPadding,
            top + theme.footerBaseline,
            theme.footer,
        )
    }

    private companion object {
        const val RAIL_FRACTION = 0.38f
        const val CARD_WIDTH_FRACTION = 0.33f
        const val MAX_INFERRED_LINES = 3
        const val STRIP_ITEMS = 9
        const val STRIP_COLUMNS = 3
    }
}

/** All sizes scale off image width so a 1080 px shot and a 4000 px shot look the same. */
private class Theme(imageWidth: Int) {

    private val unit = imageWidth / 1080f

    val cardPadding = 14f * unit
    val corner = 12f * unit
    val markerRadius = 13f * unit
    val markerTextOffset = 6f * unit
    val dotRadius = 4.5f * unit
    val detailIndent = 20f * unit
    val titleGap = 4f * unit
    val stripHeight = 132f * unit
    val stripRowHeight = 30f * unit
    val stripHeaderBaseline = 32f * unit
    val stripItemBaseline = 66f * unit
    val footerHeight = 40f * unit
    val footerBaseline = 26f * unit

    private fun paint(size: Float, color: Int, bold: Boolean = false) = Paint().apply {
        isAntiAlias = true
        textSize = size * unit
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    val title = paint(21f, Color.WHITE, bold = true)
    val meta = paint(15f, 0xFFB9C4D0.toInt())
    val measured = paint(16f, 0xFF7FD4A8.toInt())
    val inferred = paint(15f, 0xFF8B94A0.toInt())
    val detail = paint(14f, 0xFF98A4B2.toInt())
    val stripHeader = paint(16f, 0xFFD5DEE8.toInt(), bold = true)
    val stripItem = paint(14f, 0xFFB9C4D0.toInt())
    val footer = paint(13f, 0xFF8B94A0.toInt())

    val markerText = paint(16f, Color.BLACK, bold = true).apply {
        textAlign = Paint.Align.CENTER
    }

    val scrim = Paint().apply { color = 0x99000000.toInt() }
    val stripBackground = Paint().apply { color = 0xCC0B0F14.toInt() }
    val footerBackground = Paint().apply { color = 0xE6070A0E.toInt() }

    val cardBackground = Paint().apply {
        isAntiAlias = true
        color = 0xE6141A22.toInt()
    }
    val cardBorder = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * unit
        color = 0x33FFFFFF
    }

    val leader = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        color = 0xCC5CC8FF.toInt()
    }

    val markerFill = Paint().apply { isAntiAlias = true; color = 0xFF5CC8FF.toInt() }
    val markerHalo = Paint().apply { isAntiAlias = true; color = 0x4D5CC8FF }
    val markerStroke = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        color = 0xFF0B0F14.toInt()
    }

    val measuredDot = Paint().apply { isAntiAlias = true; color = 0xFF7FD4A8.toInt() }
    val inferredDot = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * unit
        color = 0xFF8B94A0.toInt()
    }

    fun familyDot(family: RadioFamily) = Paint().apply {
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

    fun paintFor(style: LineStyle): Paint = when (style) {
        LineStyle.TITLE -> title
        LineStyle.META -> meta
        LineStyle.MEASURED -> measured
        LineStyle.INFERRED -> inferred
        LineStyle.DETAIL -> detail
    }

    fun lineHeight(style: LineStyle): Float = when (style) {
        LineStyle.TITLE -> 28f * unit
        LineStyle.META -> 20f * unit
        LineStyle.MEASURED -> 22f * unit
        LineStyle.INFERRED -> 20f * unit
        LineStyle.DETAIL -> 19f * unit
    }

    fun lineDescent(style: LineStyle): Float = min(lineHeight(style) * 0.25f, 6f * unit)
}
