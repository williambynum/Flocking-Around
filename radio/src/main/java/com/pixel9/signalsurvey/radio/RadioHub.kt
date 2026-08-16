package com.pixel9.signalsurvey.radio

import android.content.Context
import android.os.SystemClock
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.RadioSummary
import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.SatelliteFix
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.radio.bluetooth.BleScanner
import com.pixel9.signalsurvey.radio.bluetooth.ClassicBtScanner
import com.pixel9.signalsurvey.radio.cellular.CellularScanner
import com.pixel9.signalsurvey.radio.gnss.GnssScanner
import com.pixel9.signalsurvey.radio.net.NetworkDiscovery
import com.pixel9.signalsurvey.radio.wifi.RttRanger
import com.pixel9.signalsurvey.radio.wifi.WifiScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * One place that owns every receiver.
 *
 * Beyond merging streams, the hub does the thing that makes multi-shot localisation
 * possible: every time an observation arrives it records an [RssiSample] tagged with where
 * the phone was standing, using [positionProvider]. Walk around for thirty seconds and you
 * have an RSSI field sampled over a known trajectory — which is enough to solve for where an
 * emitter is, even one the camera never saw.
 */
class RadioHub(
    context: Context,
    /** Current ARCore camera position, or null while tracking is not established. */
    private val positionProvider: () -> Vec3?,
) {
    private val appContext = context.applicationContext

    val capabilities = DeviceCapabilities(appContext)
    val wifi = WifiScanner(appContext)
    val rtt = RttRanger(appContext)
    val ble = BleScanner(appContext)
    val classicBt = ClassicBtScanner(appContext)
    val cellular = CellularScanner(appContext)
    val gnss = GnssScanner(appContext)
    val network = NetworkDiscovery(appContext)

    private val merged = ConcurrentHashMap<String, RadioObservation>()
    private val samples = ArrayList<RssiSample>()
    private val samplesLock = Any()

    private val _observations = MutableStateFlow<Map<String, RadioObservation>>(emptyMap())
    val observations: StateFlow<Map<String, RadioObservation>> = _observations.asStateFlow()

    private val _summary = MutableStateFlow(RadioSummary.EMPTY)
    val summary: StateFlow<RadioSummary> = _summary.asStateFlow()

    private val _satellites = MutableStateFlow<List<SatelliteFix>>(emptyList())
    val satellites: StateFlow<List<SatelliteFix>> = _satellites.asStateFlow()

    private var jobs = mutableListOf<Job>()

    /** Start every available receiver. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return

        jobs += scope.launch {
            wifi.observe().onEach { ingest(it) }.collect { }
        }
        jobs += scope.launch {
            ble.observe().onEach { ingest(it) }.collect { }
        }
        jobs += scope.launch {
            network.observe().onEach { ingest(it) }.collect { }
        }
        jobs += scope.launch {
            gnss.observe().collect { _satellites.value = it }
        }
        // The modem is polled rather than streamed: requestCellInfoUpdate wakes the radio,
        // so hammering it costs real battery for data that changes slowly.
        jobs += scope.launch {
            while (isActive) {
                ingest(cellular.snapshot())
                delay(CELL_POLL_MS)
            }
        }
        // Publish on a ticker rather than per-advertisement. A busy office produces hundreds
        // of BLE callbacks a second and recomposing the HUD on each is pure waste.
        jobs += scope.launch {
            while (isActive) {
                publish()
                delay(PUBLISH_MS)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun ingest(batch: List<RadioObservation>) {
        if (batch.isEmpty()) return
        val where = positionProvider()
        val now = SystemClock.elapsedRealtime()

        batch.forEach { fresh ->
            merged[fresh.key] = fresh.mergeWith(merged[fresh.key])

            val rssi = fresh.rssiDbm
            if (where != null && rssi != null) {
                synchronized(samplesLock) {
                    // Only keep a sample when the phone has actually moved since the last
                    // one for this key. Standing still adds rows without adding information,
                    // and it biases the gradient solver toward wherever you loitered.
                    val previous = samples.lastOrNull { it.key == fresh.key }
                    if (previous == null || previous.cameraWorld.distanceTo(where) > MIN_SAMPLE_SPACING_M) {
                        samples += RssiSample(fresh.key, now, rssi, where)
                    }
                }
            }
        }
    }

    private fun publish() {
        val snapshot = merged.toMap()
        _observations.value = snapshot
        _summary.value = RadioSummary(
            perFamily = snapshot.values.groupingBy { it.family }.eachCount(),
            rttCapableAps = snapshot.values.count { it.extras["rttResponder"] == "true" },
            strongestRssi = snapshot.values.mapNotNull { it.rssiDbm }.maxOrNull(),
        )
    }

    fun currentObservations(): List<RadioObservation> = merged.values.toList()

    fun rssiSamples(): List<RssiSample> = synchronized(samplesLock) { samples.toList() }

    fun reset() {
        merged.clear()
        synchronized(samplesLock) { samples.clear() }
        _observations.value = emptyMap()
        _summary.value = RadioSummary.EMPTY
    }

    private companion object {
        const val PUBLISH_MS = 400L
        const val CELL_POLL_MS = 10_000L
        /** Samples closer together than this add noise, not information. */
        const val MIN_SAMPLE_SPACING_M = 0.35f
    }
}
