package com.pixel9.signalsurvey.survey

import com.pixel9.signalsurvey.fusion.SessionResolver
import com.pixel9.signalsurvey.model.PathPoint
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.SatelliteFix
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import java.util.UUID

/**
 * Mutable accumulator for a survey in progress.
 *
 * The immutable [SurveySession] is produced only at [finalise], where cross-shot resolution
 * runs. Everything before that is deliberately provisional: an emitter's position can change
 * completely when the fourth range arrives, and publishing an early guess as fact is exactly
 * the failure mode this app exists to avoid.
 */
class SurveySessionBuilder(
    val label: String,
    val deviceProfile: String,
) {
    val id: String = UUID.randomUUID().toString()
    val startedAtEpochMs: Long = System.currentTimeMillis()
    val startedAtElapsedMs: Long = android.os.SystemClock.elapsedRealtime()

    private val shots = mutableListOf<Shot>()
    private val rttFixes = mutableListOf<RttFix>()
    private val path = mutableListOf<PathPoint>()
    private var nextTargetId = 1

    @Volatile var lastCameraWorld: Vec3? = null
        private set

    val shotCount: Int get() = shots.size

    fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime() - startedAtElapsedMs

    /** Called from the AR frame loop; throttled by distance rather than by time. */
    @Synchronized
    fun recordCameraPosition(world: Vec3) {
        lastCameraWorld = world
        val last = path.lastOrNull()
        if (last == null || last.world.distanceTo(world) > PATH_SPACING_M) {
            path += PathPoint(elapsedMs(), world)
        }
    }

    @Synchronized
    fun allocateTargetId(): Int = nextTargetId++

    @Synchronized
    fun addShot(shot: Shot) {
        shots += shot
        path += PathPoint(shot.elapsedMs, shot.camera.worldPosition, shot.index)
    }

    @Synchronized
    fun addRttFixes(fixes: List<RttFix>) {
        rttFixes += fixes
    }

    @Synchronized
    fun currentShots(): List<Shot> = shots.toList()

    @Synchronized
    fun rttFixesFor(key: String): List<RttFix> = rttFixes.filter { it.key == key }

    /** How much RTT evidence exists so far — drives the "keep walking" prompt. */
    @Synchronized
    fun trilaterationReadyCount(): Int =
        rttFixes.groupBy { it.key }.count { (_, fixes) ->
            fixes.distinctBy { fix ->
                // Count distinct standing positions, not distinct measurements.
                Triple(
                    (fix.cameraWorld.x * 2).toInt(),
                    (fix.cameraWorld.y * 2).toInt(),
                    (fix.cameraWorld.z * 2).toInt(),
                )
            }.size >= 3
        }

    fun pathLengthM(): Float {
        val points = synchronized(this) { path.toList() }
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) total += points[i].world.distanceTo(points[i - 1].world)
        return total
    }

    /**
     * Produce the finished session. This is where multi-shot stops being a list of photos:
     * targets are merged across shots, then every emitter is resolved using the whole
     * session's measurements rather than any single frame's.
     */
    @Synchronized
    fun finalise(
        observations: Map<String, RadioObservation>,
        rssiSamples: List<RssiSample>,
        satellites: List<SatelliteFix>,
        location: com.pixel9.signalsurvey.model.GeoFix?,
    ): SurveySession {

        val assignments = SessionResolver.mergeTargets(shots)

        // Re-key every target to its canonical id, and record which shots it appeared in.
        val appearances = HashMap<Int, MutableSet<Int>>()
        shots.forEach { shot ->
            shot.targets.forEach { target ->
                val canonicalId = assignments[target.id] ?: target.id
                appearances.getOrPut(canonicalId) { mutableSetOf() } += shot.index
            }
        }

        val remappedShots = shots.map { shot ->
            shot.copy(
                targets = shot.targets.map { target ->
                    val canonicalId = assignments[target.id] ?: target.id
                    target.copy(
                        id = canonicalId,
                        seenInShots = appearances[canonicalId]?.sorted().orEmpty(),
                    )
                }
            )
        }

        val canonicalTargets: List<VisualTarget> = remappedShots
            .flatMap { it.targets }
            .groupBy { it.id }
            // Keep the sighting with the best measured range: that is the one whose anchor
            // the emitter resolver should trust.
            .map { (_, group) ->
                group.minByOrNull { target ->
                    when {
                        target.rangeSource.isMeasured -> target.rangeM ?: Float.MAX_VALUE
                        else -> Float.MAX_VALUE
                    }
                } ?: group.first()
            }

        val emitters = SessionResolver.resolveEmitters(
            observations = observations,
            rttFixes = rttFixes,
            rssiSamples = rssiSamples,
            shots = remappedShots,
            canonicalTargets = canonicalTargets,
        )

        return SurveySession(
            id = id,
            label = label,
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = System.currentTimeMillis(),
            deviceProfile = deviceProfile,
            shots = remappedShots,
            observations = observations,
            rssiSamples = rssiSamples,
            rttFixes = rttFixes.toList(),
            emitters = emitters,
            cameraPath = path.toList(),
            satellites = satellites,
            location = location,
        )
    }

    private companion object {
        /** Below this the path is just tracking jitter. */
        const val PATH_SPACING_M = 0.25f
    }
}
