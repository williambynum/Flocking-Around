package com.pixel9.signalsurvey.radio.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.Vec3
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * IEEE 802.11mc Fine Timing Measurement.
 *
 * With no UWB on a base Pixel 9, this is the *only* source of true distance to another
 * radio. It reports millimetres with a stated standard deviation, typically landing within
 * one to two metres in practice — an order of magnitude better than anything RSSI can do.
 *
 * Combined with ARCore poses from several standing positions across a multi-shot session,
 * three or more ranges trilaterate an access point's actual 3D location, whether or not the
 * camera ever saw it.
 *
 * The catch: only APs advertising `is80211mcResponder` will answer. That is common on
 * enterprise gear and Google/Nest hardware, uncommon on budget consumer routers.
 */
class RttRanger(private val context: Context) {

    private val rtt: WifiRttManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            context.getSystemService(WifiRttManager::class.java)
        } else null

    val isSupported: Boolean get() = rtt != null && rtt.isAvailable

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Range against up to [RangingRequest.getMaxPeers] responders and tag each result with
     * where the phone was standing.
     *
     * @param cameraWorld the ARCore camera position at the moment of the request. This is
     *   what makes the result trilaterable later; without it a range is just a sphere.
     */
    @SuppressLint("MissingPermission")
    suspend fun range(
        responders: List<ScanResult>,
        cameraWorld: Vec3,
        shotIndex: Int?,
    ): List<RttFix> {
        val manager = rtt
        if (manager == null || !manager.isAvailable || !hasPermission()) return emptyList()

        val targets = responders.filter { it.is80211mcResponder }
            .sortedByDescending { it.level }
            .take(RangingRequest.getMaxPeers())
        if (targets.isEmpty()) return emptyList()

        val request = try {
            RangingRequest.Builder().addAccessPoints(targets).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not build a ranging request", e)
            return emptyList()
        }

        val now = SystemClock.elapsedRealtime()

        return suspendCancellableCoroutine { cont ->
            try {
                manager.startRanging(
                    request,
                    context.mainExecutor,
                    object : RangingResultCallback() {
                        override fun onRangingResults(results: List<RangingResult>) {
                            val fixes = results
                                .filter { it.status == RangingResult.STATUS_SUCCESS }
                                .mapNotNull { r ->
                                    @Suppress("DEPRECATION")
                                    val mac = r.macAddress?.toString()?.uppercase()
                                        ?: return@mapNotNull null
                                    RttFix(
                                        key = mac,
                                        elapsedMs = now,
                                        distanceM = r.distanceMm / 1000f,
                                        stdDevM = r.distanceStdDevMm / 1000f,
                                        rssiDbm = r.rssi,
                                        cameraWorld = cameraWorld,
                                        shotIndex = shotIndex,
                                    )
                                }
                            if (cont.isActive) cont.resume(fixes)
                        }

                        override fun onRangingFailure(code: Int) {
                            Log.w(TAG, "Ranging failed, code $code")
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "startRanging threw", e)
                if (cont.isActive) cont.resume(emptyList())
            }
        }
    }

    private companion object { const val TAG = "RttRanger" }
}
