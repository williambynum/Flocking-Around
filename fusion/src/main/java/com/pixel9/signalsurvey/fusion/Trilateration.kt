package com.pixel9.signalsurvey.fusion

import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.Vec3
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class PositionSolution(
    val position: Vec3,
    /** RMS residual in metres — the honest error bar, not a confidence percentage. */
    val residualM: Float,
    val usedMeasurements: Int,
    /** True when the geometry only supported a horizontal solve. */
    val planarOnly: Boolean,
)

/**
 * Solving for where an emitter is, from ranges taken at known positions.
 *
 * This is the whole reason the app is multi-shot. A single Wi-Fi RTT range is a sphere
 * around the phone — it says "3.4 m away" and nothing about direction. Take the same
 * measurement from three or four places along a walk, with ARCore supplying centimetre-
 * accurate positions for each, and the spheres intersect at a point.
 *
 * The result is an access point pinned in 3D whether or not the camera ever saw it, which
 * can then be drawn on *every* shot in the session that happens to look its way.
 */
object Trilateration {

    /** Minimum spread between measurement positions for the geometry to be worth solving. */
    private const val MIN_BASELINE_M = 0.8f

    /**
     * Least squares over Wi-Fi RTT ranges.
     *
     * Linearised by differencing against a reference measurement, which turns the sphere
     * intersection into a linear system, then refined by Gauss-Newton to remove the bias
     * that differencing introduces.
     */
    fun solveFromRtt(fixes: List<RttFix>): PositionSolution? {
        val usable = dedupeByPosition(fixes.map { it.cameraWorld to it.distanceM })
        if (usable.size < 3) return null
        if (baseline(usable.map { it.first }) < MIN_BASELINE_M) return null

        val planar = !hasVerticalSpread(usable.map { it.first }) || usable.size < 4
        val seed = linearLeastSquares(usable, planar) ?: centroidSeed(usable)
        val refined = gaussNewton(usable, seed, planar)

        return PositionSolution(
            position = refined,
            residualM = rmsResidual(usable, refined),
            usedMeasurements = usable.size,
            planarOnly = planar,
        )
    }

    /**
     * Coarse-to-fine grid search over the RSSI field.
     *
     * Much weaker than RTT — expect several metres of error indoors — but it works for every
     * emitter, not just the minority of APs that answer FTM, and it is the only way to place
     * a BLE beacon at all.
     *
     * The reference power is eliminated analytically: for any candidate position it enters
     * linearly, so the best possible value is the mean offset and the cost reduces to the
     * variance of the residuals. That removes an unknown and makes the search behave.
     */
    fun solveFromRssi(
        samples: List<RssiSample>,
        pathLossExponent: Double = 2.7,
    ): PositionSolution? {
        if (samples.size < MIN_RSSI_SAMPLES) return null
        val positions = samples.map { it.cameraWorld }
        if (baseline(positions) < MIN_RSSI_BASELINE_M) return null

        var centre = Vec3.centroid(positions)
        var halfSpan = (baseline(positions) * 1.5f).coerceIn(4f, 30f)
        var best = centre
        var bestCost = Float.MAX_VALUE

        repeat(REFINEMENT_PASSES) { pass ->
            val step = halfSpan / GRID_STEPS
            var localBest = best
            var localCost = Float.MAX_VALUE

            for (ix in -GRID_STEPS..GRID_STEPS) {
                for (iy in -GRID_STEPS / 2..GRID_STEPS / 2) {   // less vertical extent indoors
                    for (iz in -GRID_STEPS..GRID_STEPS) {
                        val candidate = Vec3(
                            centre.x + ix * step,
                            centre.y + iy * step,
                            centre.z + iz * step,
                        )
                        val cost = rssiCost(samples, candidate, pathLossExponent)
                        if (cost < localCost) {
                            localCost = cost
                            localBest = candidate
                        }
                    }
                }
            }

            best = localBest
            bestCost = localCost
            centre = localBest
            halfSpan /= 3f
            if (pass == REFINEMENT_PASSES - 1) return@repeat
        }

        // bestCost is the variance of the log-distance residual in dB. Convert to a rough
        // metric error at the median observed distance so the UI has something meaningful.
        val medianRange = samples.map { best.distanceTo(it.cameraWorld) }.sorted()
            .let { it[it.size / 2] }
        val dbError = sqrt(bestCost)
        val metricError = medianRange * (10f.pow(dbError / (10f * pathLossExponent.toFloat())) - 1f)

        return PositionSolution(
            position = best,
            residualM = metricError.coerceIn(0.5f, 50f),
            usedMeasurements = samples.size,
            planarOnly = false,
        )
    }

    // ---------------------------------------------------------------- internals

    private fun rssiCost(samples: List<RssiSample>, candidate: Vec3, n: Double): Float {
        // For a candidate position the optimal reference power is the mean of
        // (rssi + pathLoss), so the remaining cost is just the variance of that quantity.
        var sum = 0.0
        var sumSq = 0.0
        samples.forEach { s ->
            val d = candidate.distanceTo(s.cameraWorld).coerceAtLeast(0.3f)
            val implied = s.rssiDbm + 10.0 * n * log10(d.toDouble())
            sum += implied
            sumSq += implied * implied
        }
        val count = samples.size
        val mean = sum / count
        return ((sumSq / count) - mean * mean).toFloat()
    }

    /** Differenced linear system, solved by normal equations. */
    private fun linearLeastSquares(
        measurements: List<Pair<Vec3, Float>>,
        planar: Boolean,
    ): Vec3? {
        val (p0, r0) = measurements.first()
        val rows = measurements.drop(1)
        if (rows.size < (if (planar) 2 else 3)) return null

        val dim = if (planar) 2 else 3
        val ata = Array(dim) { DoubleArray(dim) }
        val atb = DoubleArray(dim)

        rows.forEach { (pi, ri) ->
            val a = doubleArrayOf(
                2.0 * (p0.x - pi.x),
                if (planar) 2.0 * (p0.z - pi.z) else 2.0 * (p0.y - pi.y),
                if (planar) 0.0 else 2.0 * (p0.z - pi.z),
            )
            val b = (ri * ri - r0 * r0).toDouble() -
                (pi.x * pi.x + pi.y * pi.y + pi.z * pi.z).toDouble() +
                (p0.x * p0.x + p0.y * p0.y + p0.z * p0.z).toDouble()

            for (r in 0 until dim) {
                for (c in 0 until dim) ata[r][c] += a[r] * a[c]
                atb[r] += a[r] * b
            }
        }

        val solution = solveSymmetric(ata, atb, dim) ?: return null
        return if (planar) {
            Vec3(solution[0].toFloat(), p0.y, solution[1].toFloat())
        } else {
            Vec3(solution[0].toFloat(), solution[1].toFloat(), solution[2].toFloat())
        }
    }

    /**
     * Gauss-Newton on the true (non-differenced) residuals. Ten iterations is far more than
     * enough from a least-squares seed and costs nothing at this problem size.
     */
    private fun gaussNewton(
        measurements: List<Pair<Vec3, Float>>,
        seed: Vec3,
        planar: Boolean,
    ): Vec3 {
        var x = seed
        repeat(10) {
            val dim = if (planar) 2 else 3
            val jtj = Array(dim) { DoubleArray(dim) }
            val jtr = DoubleArray(dim)

            measurements.forEach { (p, r) ->
                val d = x.distanceTo(p).coerceAtLeast(0.05f)
                val residual = (d - r).toDouble()
                val grad = doubleArrayOf(
                    ((x.x - p.x) / d).toDouble(),
                    if (planar) ((x.z - p.z) / d).toDouble() else ((x.y - p.y) / d).toDouble(),
                    if (planar) 0.0 else ((x.z - p.z) / d).toDouble(),
                )
                for (a in 0 until dim) {
                    for (b in 0 until dim) jtj[a][b] += grad[a] * grad[b]
                    jtr[a] += grad[a] * residual
                }
            }

            // Levenberg-style damping keeps a degenerate geometry from exploding.
            for (a in 0 until dim) jtj[a][a] *= 1.05

            val delta = solveSymmetric(jtj, jtr, dim) ?: return x
            x = if (planar) {
                Vec3(x.x - delta[0].toFloat(), x.y, x.z - delta[1].toFloat())
            } else {
                Vec3(
                    x.x - delta[0].toFloat(),
                    x.y - delta[1].toFloat(),
                    x.z - delta[2].toFloat(),
                )
            }
        }
        return x
    }

    /** Gaussian elimination with partial pivoting for a 2x2 or 3x3 system. */
    private fun solveSymmetric(a: Array<DoubleArray>, b: DoubleArray, n: Int): DoubleArray? {
        val m = Array(n) { r -> DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] } }

        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-9) return null      // singular: degenerate geometry
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp

            for (r in 0 until n) {
                if (r == col) continue
                val factor = m[r][col] / m[col][col]
                for (c in col..n) m[r][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    private fun rmsResidual(measurements: List<Pair<Vec3, Float>>, x: Vec3): Float {
        var sum = 0.0
        measurements.forEach { (p, r) ->
            val e = (x.distanceTo(p) - r).toDouble()
            sum += e * e
        }
        return sqrt(sum / measurements.size).toFloat()
    }

    private fun centroidSeed(measurements: List<Pair<Vec3, Float>>): Vec3 =
        Vec3.centroid(measurements.map { it.first })

    /**
     * Collapse measurements taken from effectively the same place. Ten ranges from one
     * standing position look like ten constraints to the solver but carry the information
     * of one, which makes the residual look far better than it is.
     */
    private fun dedupeByPosition(
        measurements: List<Pair<Vec3, Float>>,
        minSpacingM: Float = 0.4f,
    ): List<Pair<Vec3, Float>> {
        val kept = ArrayList<Pair<Vec3, Float>>()
        measurements.forEach { m ->
            if (kept.none { it.first.distanceTo(m.first) < minSpacingM }) kept += m
        }
        return kept
    }

    private fun baseline(positions: List<Vec3>): Float {
        if (positions.size < 2) return 0f
        var max = 0f
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                max = maxOf(max, positions[i].distanceTo(positions[j]))
            }
        }
        return max
    }

    /** Handheld surveys are almost planar; without vertical spread a 3D solve is fiction. */
    private fun hasVerticalSpread(positions: List<Vec3>): Boolean {
        val ys = positions.map { it.y }
        return (ys.max() - ys.min()) > 0.5f
    }

    private fun Float.pow(exp: Float): Float = Math.pow(this.toDouble(), exp.toDouble()).toFloat()

    private const val MIN_RSSI_SAMPLES = 8
    private const val MIN_RSSI_BASELINE_M = 1.5f
    private const val GRID_STEPS = 8
    private const val REFINEMENT_PASSES = 4
}
