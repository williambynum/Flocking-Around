package com.pixel9.signalsurvey.fusion

import com.pixel9.signalsurvey.model.PositionMethod
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget

/**
 * Turns a pile of shots and measurements into one coherent picture of the space.
 *
 * Two jobs, both of which only exist because the app is multi-shot:
 *
 * 1. **Merge visual targets across shots.** The router in shot 1 and the router in shot 4
 *    are the same router. Without merging you get duplicate markers and the emitter
 *    resolution splits its evidence between them.
 *
 * 2. **Resolve emitter positions from every measurement in the session**, not just the ones
 *    taken during a single shot. This is what lets shot 5 be annotated with an AP that was
 *    only ever ranged during shots 1 and 2.
 */
object SessionResolver {

    /**
     * Union targets across shots by world proximity and label agreement.
     *
     * The distance threshold scales with range because anchor error grows with distance —
     * two detections 1.5 m apart at 20 m are almost certainly the same object, whereas at
     * 1 m they are two different things on a desk.
     */
    fun mergeTargets(shots: List<Shot>): Map<Int, Int> {
        val assignments = HashMap<Int, Int>()   // original target id -> canonical id
        val canonical = ArrayList<VisualTarget>()

        shots.forEach { shot ->
            shot.targets.forEach { target ->
                val match = canonical.firstOrNull { existing ->
                    val threshold = mergeThresholdM(target.rangeM ?: existing.rangeM ?: 5f)
                    val close = existing.anchorWorld.distanceTo(target.anchorWorld) < threshold
                    val compatible = existing.label == target.label ||
                        existing.label == UNKNOWN || target.label == UNKNOWN
                    close && compatible
                }

                if (match == null) {
                    canonical += target
                    assignments[target.id] = target.id
                } else {
                    assignments[target.id] = match.id
                }
            }
        }
        return assignments
    }

    /** Anchor error grows roughly linearly with distance; so does the merge radius. */
    private fun mergeThresholdM(rangeM: Float): Float =
        (0.35f + rangeM * 0.10f).coerceIn(0.35f, 3.0f)

    /**
     * Resolve every emitter heard during the session, using the best method its evidence
     * supports. The [PositionMethod] travels with the result so the UI can render a
     * trilaterated AP differently from an RSSI guess — they are not the same claim.
     */
    fun resolveEmitters(
        observations: Map<String, RadioObservation>,
        rttFixes: List<RttFix>,
        rssiSamples: List<RssiSample>,
        shots: List<Shot>,
        canonicalTargets: List<VisualTarget>,
    ): List<ResolvedEmitter> {

        val fixesByKey = rttFixes.groupBy { it.key }
        val samplesByKey = rssiSamples.groupBy { it.key }

        // Which shot indices each emitter was audible in — drives the "seen in" chips.
        val shotWindows = shots.associate { it.index to it.elapsedMs }

        return observations.values.map { obs ->
            val fixes = fixesByKey[obs.key].orEmpty()
            val samples = samplesByKey[obs.key].orEmpty()

            // A target the fusion engine already tied this emitter to wins outright: a
            // visual anchor with AR depth behind it beats anything RF can produce.
            val visualTarget = canonicalTargets.firstOrNull { target ->
                target.confirmed.any { it.observation.key == obs.key }
            }

            val seenIn = shotWindows.filterValues { shotElapsed ->
                obs.firstSeenElapsedMs <= shotElapsed + SHOT_WINDOW_MS &&
                    obs.lastSeenElapsedMs >= shotElapsed - SHOT_WINDOW_MS
            }.keys.sorted()

            when {
                visualTarget != null -> ResolvedEmitter(
                    key = obs.key,
                    observation = obs,
                    worldPosition = visualTarget.anchorWorld,
                    positionErrorM = visualTarget.rangeM?.let { it * 0.08f },
                    method = PositionMethod.VISUAL_ANCHOR,
                    seenInShots = seenIn,
                    visualTargetId = visualTarget.id,
                    fixCount = 1,
                )

                fixes.size >= 3 -> {
                    val solution = Trilateration.solveFromRtt(fixes)
                    if (solution != null && solution.residualM < MAX_ACCEPTABLE_RESIDUAL_M) {
                        ResolvedEmitter(
                            key = obs.key,
                            observation = obs,
                            worldPosition = solution.position,
                            positionErrorM = solution.residualM,
                            method = PositionMethod.RTT_TRILATERATION,
                            seenInShots = seenIn,
                            fixCount = solution.usedMeasurements,
                        )
                    } else {
                        // The geometry did not support a solve — say so rather than
                        // publishing a point with a metre of residual dressed up as a fix.
                        unlocated(obs, seenIn, fixes.size)
                    }
                }

                samples.size >= 8 -> {
                    val solution = Trilateration.solveFromRssi(samples)
                    if (solution != null) {
                        ResolvedEmitter(
                            key = obs.key,
                            observation = obs,
                            worldPosition = solution.position,
                            positionErrorM = solution.residualM,
                            method = PositionMethod.RSSI_GRADIENT,
                            seenInShots = seenIn,
                            fixCount = solution.usedMeasurements,
                        )
                    } else unlocated(obs, seenIn, samples.size)
                }

                fixes.size in 1..2 -> ResolvedEmitter(
                    key = obs.key,
                    observation = obs,
                    // A single range is a sphere. Refusing to place it is the honest answer;
                    // the UI shows it as a range ring in the plan view instead.
                    worldPosition = null,
                    positionErrorM = fixes.minOf { it.stdDevM },
                    method = PositionMethod.SINGLE_RANGE_RING,
                    seenInShots = seenIn,
                    fixCount = fixes.size,
                )

                else -> unlocated(obs, seenIn, 0)
            }
        }.sortedWith(
            compareByDescending<ResolvedEmitter> { it.isLocated }
                .thenByDescending { it.observation.rssiDbm ?: Int.MIN_VALUE }
        )
    }

    private fun unlocated(obs: RadioObservation, seenIn: List<Int>, fixCount: Int) =
        ResolvedEmitter(
            key = obs.key,
            observation = obs,
            worldPosition = null,
            positionErrorM = null,
            method = PositionMethod.UNLOCATED,
            seenInShots = seenIn,
            fixCount = fixCount,
        )

    /**
     * Emitters a given shot should be annotated with: everything located and inside the
     * frame, whether or not the camera recognised it. This is the multi-shot payoff made
     * concrete — an AP trilaterated during shots 1-2 appears correctly placed on shot 5.
     */
    fun emittersVisibleIn(shot: Shot, emitters: List<ResolvedEmitter>): List<ResolvedEmitter> =
        emitters.filter { emitter ->
            val position = emitter.worldPosition ?: return@filter false
            shot.camera.isInFrame(position, shot.widthPx, shot.heightPx) &&
                shot.camera.geometryTo(position).distanceM < MAX_ANNOTATION_RANGE_M
        }

    /** Emitters heard but never placed — these belong in the margin rail. */
    fun unlocatedFor(session: SurveySession): List<ResolvedEmitter> =
        session.emitters.filterNot { it.isLocated }
            .sortedByDescending { it.observation.rssiDbm ?: Int.MIN_VALUE }

    /** Anchor a resolved emitter's world point into a shot's pixel space. */
    fun projectInto(shot: Shot, emitter: ResolvedEmitter): android.graphics.PointF? {
        val position = emitter.worldPosition ?: return null
        return shot.camera.projectToImage(position, shot.widthPx, shot.heightPx)
    }

    private const val UNKNOWN = "unknown_device"
    private const val SHOT_WINDOW_MS = 15_000L
    private const val MAX_ACCEPTABLE_RESIDUAL_M = 4.0f
    /** Past this, a marker on a photo is meaningless even if the maths converged. */
    private const val MAX_ANNOTATION_RANGE_M = 60f
}
