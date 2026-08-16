package com.pixel9.signalsurvey.fusion

import android.graphics.Rect
import com.pixel9.signalsurvey.model.CameraSnapshot
import com.pixel9.signalsurvey.model.ConfirmedSignal
import com.pixel9.signalsurvey.model.DeviceOntology
import com.pixel9.signalsurvey.model.IdentificationSource
import com.pixel9.signalsurvey.model.InferredSignal
import com.pixel9.signalsurvey.model.Observability
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.RangeSource
import com.pixel9.signalsurvey.model.SignalProfile
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import kotlin.math.abs

/** Everything the engine needs about one thing the camera saw. */
data class FusionRequest(
    val targetId: Int,
    val trackingId: Int?,
    val label: String,
    val visualConfidence: Float,
    val boxImagePx: Rect,
    val anchorWorld: Vec3,
    val rangeM: Float?,
    val rangeSource: RangeSource,
    val camera: CameraSnapshot,
    val shotIndex: Int,
    /** From the generic labeller when the ontology has no entry for [label]. */
    val displayNameOverride: String? = null,
    val identification: IdentificationSource = IdentificationSource.NONE,
)

/**
 * Decides which of the signals in the air belong to the device in the picture.
 *
 * The hard truth this encodes: association is inference, not measurement. A class prior
 * ("routers do Wi-Fi") is almost worthless on its own — it cannot tell two routers on one
 * shelf apart. What actually discriminates is identity evidence (an OUI, a BLE company ID,
 * an mDNS service type) and range agreement, and of those only a *measured* range carries
 * real weight.
 *
 * So the scoring is deliberately lopsided toward evidence, and every confirmation carries
 * the reasons that produced it so the detail sheet can show its working.
 */
class FusionEngine(
    /** Maps a signal profile id to why this hardware cannot observe it, if it cannot. */
    private val unavailabilityReason: (String) -> String? = { null },
) {

    fun annotate(
        request: FusionRequest,
        observations: List<RadioObservation>,
    ): VisualTarget {
        val profile = DeviceOntology.forLabel(request.label)
        val geometry = request.camera.geometryTo(request.anchorWorld)

        if (profile == null) {
            // No ontology entry. The object may still have been named by the generic labeller
            // ("Bookcase"), which is worth showing — it just carries no RF expectations, so
            // nothing is confirmed or inferred against it. Better an honestly bare marker
            // than a confident guess about a device we cannot name.
            return VisualTarget(
                id = request.targetId,
                trackingId = request.trackingId,
                label = request.label,
                displayName = request.displayNameOverride
                    ?: request.label.replace('_', ' ').replaceFirstChar { it.uppercase() },
                visualConfidence = request.visualConfidence,
                boxImagePx = request.boxImagePx,
                anchorWorld = request.anchorWorld,
                rangeM = request.rangeM,
                rangeSource = request.rangeSource,
                bearingDeg = geometry.bearingDeg,
                elevationDeg = geometry.elevationDeg,
                seenInShots = listOf(request.shotIndex),
                identification = request.identification,
            )
        }

        val scored = observations
            .map { obs -> obs to scoreWithEvidence(profile, obs, request) }
            .filter { it.second.first >= MATCH_THRESHOLD }
            .sortedByDescending { it.second.first }
            .take(MAX_CONFIRMED)

        val confirmed = scored.map { (obs, se) ->
            ConfirmedSignal(observation = obs, score = se.first, evidence = se.second)
        }
        val confirmedFamilies = confirmed.map { it.observation.family }.toSet()

        val inferred = profile.expectations
            .filter { it.profile.family !in confirmedFamilies }
            .sortedByDescending { it.prior }
            .map { expectation ->
                InferredSignal(
                    profile = expectation.profile,
                    prior = expectation.prior,
                    reason = reasonFor(expectation.profile, expectation.note),
                )
            }

        return VisualTarget(
            id = request.targetId,
            trackingId = request.trackingId,
            label = request.label,
            displayName = profile.displayName,
            visualConfidence = request.visualConfidence,
            boxImagePx = request.boxImagePx,
            anchorWorld = request.anchorWorld,
            rangeM = request.rangeM,
            rangeSource = request.rangeSource,
            bearingDeg = geometry.bearingDeg,
            elevationDeg = geometry.elevationDeg,
            confirmed = confirmed,
            inferred = inferred,
            seenInShots = listOf(request.shotIndex),
            identification = request.identification,
        )
    }

    private fun scoreWithEvidence(
        profile: com.pixel9.signalsurvey.model.DeviceClassProfile,
        obs: RadioObservation,
        request: FusionRequest,
    ): Pair<Float, List<String>> {
        var score = 0f
        val evidence = mutableListOf<String>()

        // 1. Class prior. Necessary but never sufficient.
        val prior = profile.expectations
            .filter { it.profile.family == obs.family }
            .maxOfOrNull { it.prior } ?: 0f
        if (prior <= 0f) return 0f to emptyList()
        score += prior * W_CLASS_PRIOR

        // 2. Identity. This is what actually distinguishes one device from its neighbour.
        profile.vendorOuis.firstOrNull { obs.key.startsWith(it, ignoreCase = true) }?.let {
            score += W_IDENTITY
            evidence += "MAC prefix $it matches this device class"
        }
        obs.extras["companyId"]?.let { raw ->
            val id = raw.removePrefix("0x").toIntOrNull(16)
            if (id != null && id in profile.bleCompanyIds) {
                score += W_IDENTITY
                evidence += "Bluetooth company ID $raw (${obs.vendor ?: "known vendor"})"
            }
        }
        obs.extras["services"]?.let { services ->
            profile.bleServiceUuids.firstOrNull { uuid ->
                services.contains(uuid.take(8), ignoreCase = true)
            }?.let {
                score += W_IDENTITY
                evidence += "Advertises service UUID ${it.take(8)}"
            }
        }
        obs.extras["serviceType"]?.let { type ->
            if (type in profile.mdnsTypes) {
                score += W_IDENTITY
                evidence += "Advertises $type on the local network"
            }
        }
        obs.extras["btClass"]?.let { btClass ->
            if (profile.btClassMajor != null) {
                score += W_IDENTITY * 0.6f
                evidence += "Bluetooth class of device: $btClass"
            }
        }

        // 3. Range agreement. A measured range is worth several times an estimated one,
        // because a path-loss number routinely disagrees with reality by a factor of two.
        val visualRange = request.rangeM
        if (visualRange != null) {
            val measured = obs.measuredRangeM
            val estimated = obs.estimatedRangeM
            when {
                measured != null -> {
                    val agreement = agreement(measured, visualRange, tolerance = 2.0f)
                    score += agreement * W_RANGE_MEASURED
                    if (agreement > 0.4f) {
                        evidence += "Measured range %.1f m agrees with the visual %.1f m"
                            .format(measured, visualRange)
                    }
                }
                estimated != null && request.rangeSource.isMeasured -> {
                    val agreement = agreement(estimated, visualRange, tolerance = 8.0f)
                    score += agreement * W_RANGE_ESTIMATED
                    if (agreement > 0.6f) {
                        evidence += "RSSI estimate ~%.0f m is consistent".format(estimated)
                    }
                }
            }
        }

        // 4. Very strong signal from something close by is weak corroboration on its own,
        // but it does help break ties between two otherwise identical candidates.
        obs.rssiDbm?.let { rssi ->
            if (rssi > -45 && (visualRange ?: 99f) < 4f) {
                score += 0.05f
            }
        }

        return score.coerceIn(0f, 1.5f) to evidence
    }

    /** 1.0 when the two agree exactly, falling linearly to 0 at [tolerance] metres apart. */
    private fun agreement(a: Float, b: Float, tolerance: Float): Float =
        (1f - abs(a - b) / tolerance).coerceIn(0f, 1f)

    private fun reasonFor(profile: SignalProfile, ontologyNote: String): String {
        unavailabilityReason(profile.id)?.let { return it }
        return when (profile.observability) {
            Observability.INFERRED_ONLY -> profile.apiHint
            Observability.NETWORK_SIDE ->
                "Client device - join the same network to confirm via mDNS"
            Observability.ACTIVE_ONLY -> ontologyNote.ifBlank { "Tap to attempt an active measurement" }
            Observability.DIRECT ->
                ontologyNote.ifBlank { "Expected for this device class, but not detected" }
        }
    }

    private companion object {
        const val W_CLASS_PRIOR = 0.35f
        const val W_IDENTITY = 0.40f
        const val W_RANGE_MEASURED = 0.35f
        const val W_RANGE_ESTIMATED = 0.12f
        const val MATCH_THRESHOLD = 0.45f
        const val MAX_CONFIRMED = 6
    }
}
