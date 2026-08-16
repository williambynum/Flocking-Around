package com.pixel9.signalsurvey.export

import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.VisualTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Human-readable renderings of a survey.
 *
 * The JSON is complete but nobody reads JSON. These are the formats you can actually open:
 * an HTML report that renders in any browser with the shot images inline, two CSVs that drop
 * straight into Excel or pandas, and a plain-text summary that works over SSH or in a text
 * message.
 *
 * All of them state provenance the same way the images do — measured versus inferred is
 * never collapsed, because a spreadsheet gets filtered and sorted and pivoted, and a column
 * that quietly mixes a millimetre-accurate FTM range with an RSSI guess will produce
 * confident nonsense.
 */
object ReportBuilder {

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ==================================================================== HTML

    /**
     * Self-contained report. Images are referenced by relative filename, so the folder works
     * as a unit — open `report.html` and everything is there.
     */
    fun html(session: SurveySession, shotImageNames: Map<Int, String>, planImageName: String?): String {
        val sb = StringBuilder(64 * 1024)

        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        sb.append("<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(session.label)).append(" - Signal Survey</title>\n")
        sb.append("<style>\n").append(CSS).append("\n</style>\n</head>\n<body>\n")

        // ---- header ----
        sb.append("<header>\n")
        sb.append("<h1>").append(esc(session.label)).append("</h1>\n")
        sb.append("<p class=\"sub\">")
            .append(timestampFormat.format(Date(session.startedAtEpochMs)))
            .append(" &middot; ").append(esc(session.deviceProfile)).append("</p>\n")
        sb.append("</header>\n")

        // ---- key numbers ----
        sb.append("<section class=\"stats\">\n")
        stat(sb, session.shots.size.toString(), "shots")
        stat(sb, "%.1f m".format(session.pathLengthM()), "walked")
        stat(sb, "%d s".format(session.durationMs / 1000), "duration")
        stat(sb, session.observations.size.toString(), "emitters heard")
        stat(sb, session.locatedEmitters.size.toString(), "located")
        stat(sb, session.shots.sumOf { it.targets.size }.toString(), "devices identified")
        sb.append("</section>\n")

        // ---- legend ----
        sb.append("<section class=\"legend\">\n<h2>How to read this</h2>\n")
        sb.append("<p><span class=\"pill measured\">measured</span> ")
        sb.append("This signal was actually received by the phone. Ranges marked ")
        sb.append("<em>802.11mc FTM</em> or <em>multi-shot RTT</em> are true distance ")
        sb.append("measurements; <em>AR depth</em> comes from the camera.</p>\n")
        sb.append("<p><span class=\"pill inferred\">inferred</span> ")
        sb.append("This signal was <strong>never observed</strong>. It is what a device of ")
        sb.append("this type usually emits. Each one carries the reason it could not be ")
        sb.append("confirmed &mdash; usually that this phone has no receiver for it.</p>\n")
        sb.append("<p class=\"note\">").append(esc(CAPABILITY_NOTE)).append("</p>\n")
        sb.append("</section>\n")

        // ---- plan view ----
        if (planImageName != null) {
            sb.append("<section>\n<h2>Plan view</h2>\n")
            sb.append("<p class=\"caption\">Top-down. Blue line is the path walked, numbered ")
            sb.append("circles are shot positions with their field of view, dots are located ")
            sb.append("emitters. Rings show position uncertainty; dashed outlines mark ")
            sb.append("positions that were estimated rather than measured.</p>\n")
            sb.append("<img class=\"plan\" src=\"").append(esc(planImageName))
                .append("\" alt=\"Plan view\">\n</section>\n")
        }

        // ---- per shot ----
        session.shots.forEach { shot ->
            sb.append("<section class=\"shot\">\n")
            sb.append("<h2>Shot ").append(shot.index).append("</h2>\n")
            sb.append("<p class=\"caption\">")
                .append(clockFormat.format(Date(shot.capturedAtEpochMs)))
                .append(" &middot; ").append(shot.targets.size).append(" devices identified")
                .append(" &middot; depth coverage ")
                .append("%.0f%%".format(shot.depthCoverage * 100))
                .append("</p>\n")

            shotImageNames[shot.index]?.let {
                sb.append("<img class=\"shot-img\" src=\"").append(esc(it))
                    .append("\" alt=\"Shot ").append(shot.index).append("\">\n")
            }

            if (shot.targets.isEmpty()) {
                sb.append("<p class=\"empty\">No devices recognised in this frame.</p>\n")
            } else {
                shot.targets.forEach { target -> appendTargetCard(sb, target) }
            }
            sb.append("</section>\n")
        }

        // ---- located emitters ----
        val located = session.locatedEmitters
        if (located.isNotEmpty()) {
            sb.append("<section>\n<h2>Located emitters</h2>\n")
            sb.append("<p class=\"caption\">Positions solved from measurements taken across ")
            sb.append("the whole survey, not from any single shot.</p>\n")
            sb.append("<div class=\"scroll\"><table>\n<thead><tr>")
            listOf("Name", "Type", "Standard", "Vendor", "RSSI", "How located", "Error", "Fixes", "Shots")
                .forEach { sb.append("<th>").append(it).append("</th>") }
            sb.append("</tr></thead>\n<tbody>\n")
            located.forEach { emitter ->
                val o = emitter.observation
                sb.append("<tr>")
                sb.append("<td>").append(esc(o.displayName)).append("</td>")
                sb.append("<td>").append(familyChip(o.family)).append("</td>")
                sb.append("<td>").append(esc(o.standard)).append("</td>")
                sb.append("<td>").append(esc(o.vendor ?: "-")).append("</td>")
                sb.append("<td class=\"num\">").append(o.rssiDbm?.let { "$it dBm" } ?: "-").append("</td>")
                sb.append("<td>").append(
                    if (emitter.method.isMeasured) "<span class=\"pill measured\">${esc(emitter.method.label)}</span>"
                    else "<span class=\"pill inferred\">${esc(emitter.method.label)}</span>"
                ).append("</td>")
                sb.append("<td class=\"num\">")
                    .append(emitter.positionErrorM?.let { "&plusmn;%.1f m".format(it) } ?: "-")
                    .append("</td>")
                sb.append("<td class=\"num\">").append(emitter.fixCount).append("</td>")
                sb.append("<td class=\"num\">")
                    .append(emitter.seenInShots.joinToString(", ") { "#$it" }.ifBlank { "-" })
                    .append("</td>")
                sb.append("</tr>\n")
            }
            sb.append("</tbody></table></div>\n</section>\n")
        }

        // ---- everything heard ----
        sb.append("<section>\n<h2>Everything heard (").append(session.observations.size).append(")</h2>\n")
        sb.append("<p class=\"caption\">Every distinct emitter detected during the survey, ")
        sb.append("including those that could not be placed.</p>\n")
        sb.append("<div class=\"scroll\"><table>\n<thead><tr>")
        listOf("Name", "Type", "Standard", "Band", "RSSI", "Range", "Vendor", "What it was doing")
            .forEach { sb.append("<th>").append(it).append("</th>") }
        sb.append("</tr></thead>\n<tbody>\n")
        session.observations.values
            .sortedWith(compareBy({ it.family.ordinal }, { -(it.rssiDbm ?: -999) }))
            .forEach { o ->
                sb.append("<tr>")
                sb.append("<td>").append(esc(o.displayName)).append("</td>")
                sb.append("<td>").append(familyChip(o.family)).append("</td>")
                sb.append("<td>").append(esc(o.standard)).append("</td>")
                sb.append("<td>").append(esc(o.bandLabel)).append("</td>")
                sb.append("<td class=\"num\">").append(o.rssiDbm?.let { "$it" } ?: "-").append("</td>")
                sb.append("<td class=\"num\">").append(rangeCell(o)).append("</td>")
                sb.append("<td>").append(esc(o.vendor ?: "-")).append("</td>")
                sb.append("<td class=\"activity\">").append(esc(o.activityDescription())).append("</td>")
                sb.append("</tr>\n")
            }
        sb.append("</tbody></table></div>\n</section>\n")

        // ---- GNSS ----
        if (session.satellites.isNotEmpty()) {
            sb.append("<section>\n<h2>GNSS satellites in view</h2>\n")
            sb.append("<p class=\"caption\">The Pixel 9 is dual-frequency, so most satellites ")
            sb.append("appear twice &mdash; once on L1 (1575 MHz) and once on L5 (1176 MHz).</p>\n")
            sb.append("<div class=\"scroll\"><table>\n<thead><tr>")
            listOf("Constellation", "SV", "Band", "C/N0", "Azimuth", "Elevation", "Used in fix")
                .forEach { sb.append("<th>").append(it).append("</th>") }
            sb.append("</tr></thead>\n<tbody>\n")
            session.satellites.sortedByDescending { it.cn0DbHz }.forEach { sat ->
                sb.append("<tr>")
                sb.append("<td>").append(esc(sat.constellation)).append("</td>")
                sb.append("<td class=\"num\">").append(sat.svid).append("</td>")
                sb.append("<td>").append(esc(sat.bandLabel)).append("</td>")
                sb.append("<td class=\"num\">").append("%.1f dB-Hz".format(sat.cn0DbHz)).append("</td>")
                sb.append("<td class=\"num\">").append("%.0f&deg;".format(sat.azimuthDeg)).append("</td>")
                sb.append("<td class=\"num\">").append("%.0f&deg;".format(sat.elevationDeg)).append("</td>")
                sb.append("<td>").append(if (sat.usedInFix) "yes" else "no").append("</td>")
                sb.append("</tr>\n")
            }
            sb.append("</tbody></table></div>\n</section>\n")
        }

        sb.append("<footer><p>Generated by Signal Survey. ")
        sb.append("Files in this folder: report.html (this page), summary.txt, ")
        sb.append("emitters.csv, devices.csv, survey.json (complete machine-readable record), ")
        sb.append("and the shot images.</p></footer>\n")
        sb.append("</body>\n</html>\n")
        return sb.toString()
    }

    private fun appendTargetCard(sb: StringBuilder, target: VisualTarget) {
        sb.append("<div class=\"card\">\n")
        sb.append("<h3>").append(esc(target.displayName)).append("</h3>\n")
        sb.append("<p class=\"meta\">visual match ")
            .append((target.visualConfidence * 100).toInt()).append("%")
        target.rangeM?.let {
            sb.append(" &middot; ")
            if (target.rangeSource.isMeasured) {
                sb.append("%.1f m (%s)".format(it, esc(target.rangeSource.shortLabel)))
            } else {
                sb.append("~%.0f m (%s, low confidence)".format(it, esc(target.rangeSource.shortLabel)))
            }
        }
        sb.append(" &middot; %+.0f&deg; %s".format(
            target.bearingDeg, if (target.bearingDeg >= 0) "right" else "left"
        ))
        sb.append("</p>\n")

        if (target.confirmed.isNotEmpty()) {
            sb.append("<ul class=\"signals\">\n")
            target.confirmed.forEach { signal ->
                val o = signal.observation
                sb.append("<li class=\"measured\">")
                sb.append("<strong>").append(esc(o.standard)).append("</strong>")
                o.rssiDbm?.let { sb.append(" <span class=\"num\">").append(it).append(" dBm</span>") }
                sb.append("<br><span class=\"detail\">").append(esc(o.activityDescription())).append("</span>")
                if (signal.evidence.isNotEmpty()) {
                    sb.append("<br><span class=\"evidence\">why: ")
                        .append(esc(signal.evidence.joinToString("; "))).append("</span>")
                }
                sb.append("</li>\n")
            }
            sb.append("</ul>\n")
        }

        if (target.inferred.isNotEmpty()) {
            sb.append("<ul class=\"signals\">\n")
            target.inferred.forEach { signal ->
                sb.append("<li class=\"inferred\">")
                sb.append(esc(signal.profile.standard))
                sb.append(" <span class=\"num\">").append((signal.prior * 100).toInt()).append("% likely</span>")
                sb.append("<br><span class=\"detail\">").append(esc(signal.reason)).append("</span>")
                sb.append("</li>\n")
            }
            sb.append("</ul>\n")
        }
        sb.append("</div>\n")
    }

    private fun rangeCell(o: com.pixel9.signalsurvey.model.RadioObservation): String = when {
        o.measuredRangeM != null -> "%.1f m".format(o.measuredRangeM)
        o.estimatedRangeM != null -> "~%.0f m".format(o.estimatedRangeM)
        else -> "-"
    }

    private fun stat(sb: StringBuilder, value: String, label: String) {
        sb.append("<div class=\"stat\"><span class=\"v\">").append(esc(value))
            .append("</span><span class=\"l\">").append(esc(label)).append("</span></div>\n")
    }

    private fun familyChip(family: RadioFamily): String =
        "<span class=\"fam f-${family.name.lowercase()}\">${esc(family.label)}</span>"

    // ===================================================================== CSV

    /** One row per emitter. The sheet you open when you want to sort by signal strength. */
    fun emittersCsv(session: SurveySession): String {
        val sb = StringBuilder(16 * 1024)
        sb.appendRow(
            "key", "family", "name", "vendor", "standard", "band", "frequency_hz",
            "rssi_dbm", "measured_range_m", "measured_range_stddev_m", "estimated_range_m",
            "range_is_measured", "position_method", "position_is_measured",
            "world_x_m", "world_y_m", "world_z_m", "position_error_m", "fix_count",
            "seen_in_shots", "sightings", "first_seen_s", "last_seen_s", "activity", "details",
        )

        val emittersByKey = session.emitters.associateBy { it.key }
        session.observations.values
            .sortedWith(compareBy({ it.family.ordinal }, { -(it.rssiDbm ?: -999) }))
            .forEach { o ->
                val emitter: ResolvedEmitter? = emittersByKey[o.key]
                val position = emitter?.worldPosition
                sb.appendRow(
                    o.key,
                    o.family.label,
                    o.displayName,
                    o.vendor ?: "",
                    o.standard,
                    o.bandLabel,
                    o.freqHz?.toString() ?: "",
                    o.rssiDbm?.toString() ?: "",
                    o.measuredRangeM?.let { "%.2f".format(it) } ?: "",
                    o.measuredRangeStdDevM?.let { "%.2f".format(it) } ?: "",
                    o.estimatedRangeM?.let { "%.1f".format(it) } ?: "",
                    // Explicit, so a spreadsheet filter can drop the guesses in one click.
                    if (o.measuredRangeM != null) "yes" else "no",
                    emitter?.method?.label ?: "not located",
                    if (emitter?.method?.isMeasured == true) "yes" else "no",
                    position?.let { "%.3f".format(it.x) } ?: "",
                    position?.let { "%.3f".format(it.y) } ?: "",
                    position?.let { "%.3f".format(it.z) } ?: "",
                    emitter?.positionErrorM?.let { "%.2f".format(it) } ?: "",
                    emitter?.fixCount?.toString() ?: "0",
                    emitter?.seenInShots?.joinToString(" ") ?: "",
                    o.sightings.toString(),
                    "%.1f".format(o.firstSeenElapsedMs / 1000f),
                    "%.1f".format(o.lastSeenElapsedMs / 1000f),
                    o.activityDescription(),
                    o.extras.entries.joinToString("; ") { "${it.key}=${it.value}" },
                )
            }
        return sb.toString()
    }

    /** One row per signal claim against an identified device — measured and inferred alike. */
    fun devicesCsv(session: SurveySession): String {
        val sb = StringBuilder(16 * 1024)
        sb.appendRow(
            "shot", "target_id", "device_class", "device_name", "visual_confidence",
            "range_m", "range_source", "range_is_measured", "bearing_deg", "elevation_deg",
            "world_x_m", "world_y_m", "world_z_m",
            "claim_status", "signal_standard", "signal_family", "signal_band",
            "signal_rssi_dbm", "match_score", "likelihood", "evidence_or_reason",
        )

        session.shots.forEach { shot ->
            shot.targets.forEach { t ->
                val common = arrayOf(
                    shot.index.toString(),
                    t.id.toString(),
                    t.label,
                    t.displayName,
                    "%.2f".format(t.visualConfidence),
                    t.rangeM?.let { "%.2f".format(it) } ?: "",
                    t.rangeSource.shortLabel,
                    if (t.rangeSource.isMeasured) "yes" else "no",
                    "%.1f".format(t.bearingDeg),
                    "%.1f".format(t.elevationDeg),
                    "%.3f".format(t.anchorWorld.x),
                    "%.3f".format(t.anchorWorld.y),
                    "%.3f".format(t.anchorWorld.z),
                )

                if (t.confirmed.isEmpty() && t.inferred.isEmpty()) {
                    sb.appendRow(*common, "none", "", "", "", "", "", "", "")
                }

                t.confirmed.forEach { signal ->
                    val o = signal.observation
                    sb.appendRow(
                        *common,
                        "MEASURED",
                        o.standard,
                        o.family.label,
                        o.bandLabel,
                        o.rssiDbm?.toString() ?: "",
                        "%.2f".format(signal.score),
                        "",
                        signal.evidence.joinToString("; "),
                    )
                }

                t.inferred.forEach { signal ->
                    sb.appendRow(
                        *common,
                        "INFERRED",
                        signal.profile.standard,
                        signal.profile.family.label,
                        signal.profile.bandLabel,
                        "",
                        "",
                        "%.2f".format(signal.prior),
                        signal.reason,
                    )
                }
            }
        }
        return sb.toString()
    }

    // ==================================================================== TEXT

    /** Plain text. Readable anywhere, including places HTML and CSV are not. */
    fun summaryText(session: SurveySession): String {
        val sb = StringBuilder(8 * 1024)
        fun rule() = sb.append("=".repeat(72)).append('\n')
        fun thin() = sb.append("-".repeat(72)).append('\n')

        rule()
        sb.append("SIGNAL SURVEY  -  ").append(session.label).append('\n')
        rule()
        sb.append("Started   : ").append(timestampFormat.format(Date(session.startedAtEpochMs))).append('\n')
        sb.append("Duration  : ").append(session.durationMs / 1000).append(" s\n")
        sb.append("Device    : ").append(session.deviceProfile).append('\n')
        sb.append("Walked    : ").append("%.1f m".format(session.pathLengthM())).append('\n')
        sb.append("Shots     : ").append(session.shots.size).append('\n')
        sb.append("Heard     : ").append(session.observations.size).append(" distinct emitters\n")
        sb.append("Located   : ").append(session.locatedEmitters.size).append('\n')
        session.location?.let {
            sb.append("Position  : %.6f, %.6f (+/-%.0f m)\n".format(it.lat, it.lon, it.accuracyM))
        }
        sb.append('\n')

        sb.append("HOW TO READ THIS\n")
        thin()
        sb.append("[MEASURED] the phone actually received this signal.\n")
        sb.append("[INFERRED] never observed - it is what this kind of device usually emits.\n")
        sb.append("           The reason it could not be confirmed is given on each line.\n\n")
        sb.append(wrap(CAPABILITY_NOTE, 72)).append("\n\n")

        // Counts by family.
        sb.append("WHAT WAS HEARD\n")
        thin()
        session.observations.values.groupingBy { it.family }.eachCount()
            .entries.sortedByDescending { it.value }
            .forEach { (family, count) ->
                sb.append("  %-22s %4d\n".format(family.label, count))
            }
        sb.append('\n')

        // Per shot.
        session.shots.forEach { shot ->
            sb.append("SHOT ").append(shot.index).append("  (")
                .append(clockFormat.format(Date(shot.capturedAtEpochMs))).append(")\n")
            thin()
            if (shot.targets.isEmpty()) {
                sb.append("  No devices recognised in this frame.\n\n")
                return@forEach
            }
            shot.targets.forEach { t ->
                sb.append("  ").append(t.displayName)
                    .append("  (").append((t.visualConfidence * 100).toInt()).append("% visual)\n")
                t.rangeM?.let {
                    sb.append("    distance : ")
                    if (t.rangeSource.isMeasured) sb.append("%.1f m  [%s]".format(it, t.rangeSource.shortLabel))
                    else sb.append("~%.0f m  [%s - estimate]".format(it, t.rangeSource.shortLabel))
                    sb.append('\n')
                }
                sb.append("    bearing  : %+.0f deg %s, %+.0f deg elevation\n".format(
                    t.bearingDeg, if (t.bearingDeg >= 0) "right" else "left", t.elevationDeg
                ))
                t.confirmed.forEach { signal ->
                    val o = signal.observation
                    sb.append("    [MEASURED] ").append(o.standard)
                    o.rssiDbm?.let { sb.append("  ").append(it).append(" dBm") }
                    sb.append('\n')
                    sb.append("               ").append(o.activityDescription()).append('\n')
                }
                t.inferred.forEach { signal ->
                    sb.append("    [INFERRED] ").append(signal.profile.standard)
                        .append("  (").append((signal.prior * 100).toInt()).append("% likely)\n")
                    sb.append("               ").append(signal.reason).append('\n')
                }
                sb.append('\n')
            }
        }

        val located = session.locatedEmitters
        if (located.isNotEmpty()) {
            sb.append("LOCATED EMITTERS\n")
            thin()
            located.forEach { emitter ->
                val o = emitter.observation
                sb.append("  ").append(o.displayName).append('\n')
                sb.append("    ").append(o.standard)
                o.rssiDbm?.let { sb.append("  ").append(it).append(" dBm") }
                sb.append('\n')
                sb.append("    located by ").append(emitter.method.label)
                emitter.positionErrorM?.let { sb.append(" (+/-%.1f m)".format(it)) }
                sb.append(" from ").append(emitter.fixCount).append(" measurements\n")
                if (emitter.seenInShots.isNotEmpty()) {
                    sb.append("    seen in shots ")
                        .append(emitter.seenInShots.joinToString(", ")).append('\n')
                }
                sb.append('\n')
            }
        }

        val unlocated = session.unlocatedEmitters
        if (unlocated.isNotEmpty()) {
            sb.append("HEARD BUT NOT LOCATED (").append(unlocated.size).append(")\n")
            thin()
            unlocated.take(60).forEach { emitter ->
                val o = emitter.observation
                sb.append("  %-34s %-26s %s\n".format(
                    o.displayName.take(34),
                    o.standard.take(26),
                    o.rssiDbm?.let { "$it dBm" } ?: "",
                ))
            }
            if (unlocated.size > 60) {
                sb.append("  ... and ").append(unlocated.size - 60)
                    .append(" more - see emitters.csv for the full list\n")
            }
            sb.append('\n')
        }

        rule()
        sb.append("Files in this folder:\n")
        sb.append("  report.html    this survey as a web page, with the images inline\n")
        sb.append("  summary.txt    this file\n")
        sb.append("  emitters.csv   one row per emitter - opens in Excel or Sheets\n")
        sb.append("  devices.csv    one row per signal claim against each device\n")
        sb.append("  survey.json    complete machine-readable record\n")
        sb.append("  plan_view.png  top-down map of the survey\n")
        sb.append("  shot_NN.jpg    annotated photographs\n")
        rule()
        return sb.toString()
    }

    // ================================================================== helpers

    /** RFC 4180: quote anything containing a comma, quote, CR or LF; double inner quotes. */
    private fun StringBuilder.appendRow(vararg cells: String): StringBuilder {
        cells.forEachIndexed { i, cell ->
            if (i > 0) append(',')
            val needsQuoting = cell.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
            if (needsQuoting) {
                append('"').append(cell.replace("\"", "\"\"")).append('"')
            } else {
                append(cell)
            }
        }
        return append("\r\n")
    }

    private fun esc(raw: String): String = buildString(raw.length + 16) {
        raw.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun wrap(text: String, width: Int): String {
        val out = StringBuilder()
        var lineLength = 0
        text.split(' ').forEach { word ->
            if (lineLength + word.length + 1 > width) {
                out.append('\n')
                lineLength = 0
            } else if (lineLength > 0) {
                out.append(' ')
                lineLength++
            }
            out.append(word)
            lineLength += word.length
        }
        return out.toString()
    }

    private const val CAPABILITY_NOTE =
        "This phone has receivers only for Wi-Fi, Bluetooth, cellular, GNSS and NFC. " +
            "Zigbee, Thread, Z-Wave, sub-GHz ISM, DECT and proprietary 2.4 GHz links cannot " +
            "be detected by any Pixel phone. Wi-Fi client devices are also invisible because " +
            "Android has no monitor mode, so those are found over mDNS instead."

    private val CSS = """
        :root {
          --bg: #0b0f14; --panel: #141a22; --panel2: #11161d; --line: #232c38;
          --text: #e4eaf0; --dim: #8b94a0; --meta: #b9c4d0;
          --accent: #5cc8ff; --measured: #7fd4a8; --inferred: #8b94a0;
        }
        * { box-sizing: border-box; }
        body {
          margin: 0; padding: 0 20px 60px;
          background: var(--bg); color: var(--text);
          font: 15px/1.6 -apple-system, "Segoe UI", Roboto, system-ui, sans-serif;
          max-width: 1100px; margin-inline: auto;
        }
        header { padding: 36px 0 20px; border-bottom: 1px solid var(--line); }
        h1 { margin: 0; font-size: 30px; letter-spacing: -0.02em; }
        h2 { margin: 40px 0 8px; font-size: 20px; letter-spacing: -0.01em; }
        h3 { margin: 0 0 4px; font-size: 16px; }
        .sub { margin: 6px 0 0; color: var(--dim); font-size: 14px; }
        .caption { color: var(--dim); font-size: 13px; margin: 0 0 14px; }
        .empty { color: var(--dim); font-style: italic; }
        .note { color: var(--dim); font-size: 13px; border-left: 3px solid var(--line);
                padding-left: 12px; margin-top: 14px; }

        .stats { display: flex; flex-wrap: wrap; gap: 10px; margin: 22px 0; }
        .stat { background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
                padding: 12px 16px; min-width: 108px; }
        .stat .v { display: block; font-size: 22px; font-weight: 600; }
        .stat .l { display: block; font-size: 12px; color: var(--dim); margin-top: 2px; }

        .legend { background: var(--panel2); border: 1px solid var(--line);
                  border-radius: 12px; padding: 4px 20px 18px; margin-top: 26px; }
        .legend p { font-size: 14px; }

        .pill { display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 8px;
                border-radius: 20px; text-transform: uppercase; letter-spacing: 0.04em; }
        .pill.measured { background: rgba(127,212,168,0.16); color: var(--measured);
                         border: 1px solid rgba(127,212,168,0.35); }
        .pill.inferred { background: rgba(139,148,160,0.14); color: var(--inferred);
                         border: 1px dashed rgba(139,148,160,0.5); }

        img { max-width: 100%; height: auto; border-radius: 10px;
              border: 1px solid var(--line); display: block; }
        .plan { background: #0b0f14; }
        .shot-img { margin-bottom: 16px; }
        .shot { border-top: 1px solid var(--line); padding-top: 8px; margin-top: 34px; }

        .card { background: var(--panel); border: 1px solid var(--line); border-radius: 10px;
                padding: 14px 16px; margin: 12px 0; }
        .meta { color: var(--meta); font-size: 13px; margin: 0 0 10px; }
        .detail { color: var(--meta); font-size: 13px; }
        .evidence { color: var(--dim); font-size: 12px; font-style: italic; }

        ul.signals { list-style: none; margin: 8px 0 0; padding: 0; }
        ul.signals li { padding: 8px 0 8px 20px; position: relative;
                        border-top: 1px solid var(--line); font-size: 14px; }
        ul.signals li::before { position: absolute; left: 0; top: 14px; width: 9px; height: 9px;
                                border-radius: 50%; content: ""; }
        li.measured::before { background: var(--measured); }
        li.inferred::before { border: 1.5px solid var(--inferred); }
        li.inferred { color: var(--dim); }

        .scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
        table { border-collapse: collapse; width: 100%; font-size: 13px; min-width: 720px; }
        th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--line);
                 vertical-align: top; }
        th { color: var(--dim); font-size: 11px; text-transform: uppercase;
             letter-spacing: 0.06em; font-weight: 600; white-space: nowrap; }
        td.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
        td.activity { color: var(--meta); }
        tbody tr:hover { background: rgba(92,200,255,0.05); }

        .fam { font-size: 11px; padding: 2px 7px; border-radius: 5px; white-space: nowrap;
               background: rgba(139,148,160,0.15); color: var(--dim); }
        .f-wifi { background: rgba(92,200,255,0.16); color: #5cc8ff; }
        .f-bluetooth { background: rgba(155,140,255,0.16); color: #9b8cff; }
        .f-cellular { background: rgba(255,166,92,0.16); color: #ffa65c; }
        .f-gnss { background: rgba(255,224,102,0.16); color: #ffe066; }
        .f-network_service { background: rgba(127,212,168,0.16); color: #7fd4a8; }

        footer { margin-top: 50px; padding-top: 18px; border-top: 1px solid var(--line);
                 color: var(--dim); font-size: 12px; }

        @media print {
          body { background: #fff; color: #000; max-width: none; }
          .stat, .card, .legend { border-color: #ccc; background: #fff; }
          th, td, ul.signals li { border-color: #ddd; }
          li.inferred, .detail, .caption, .sub { color: #555; }
        }
    """.trimIndent()
}
