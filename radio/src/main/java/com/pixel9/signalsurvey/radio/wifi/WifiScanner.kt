package com.pixel9.signalsurvey.radio.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.radio.OuiLookup
import com.pixel9.signalsurvey.radio.RadioSource
import com.pixel9.signalsurvey.radio.pathLossRangeM
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wi-Fi access point survey.
 *
 * Two platform realities shape this:
 *
 * 1. `startScan()` is throttled to four calls per two minutes for foreground apps. Asking
 *    more often does not fail loudly — it silently returns stale results.
 * 2. The framework and other apps trigger scans too, and the broadcast fires for those as
 *    well. Listening to every SCAN_RESULTS_AVAILABLE is what actually keeps the survey dense.
 *
 * What this can never see: Wi-Fi *clients*. Android has no monitor mode, so a smart speaker
 * on your network is invisible here and must be found via mDNS instead.
 */
class WifiScanner(private val context: Context) : RadioSource {

    override val name = "Wi-Fi"

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    /** Kept for [RttRanger]: ranging needs the platform objects, not our model. */
    @Volatile
    var lastRawResults: List<ScanResult> = emptyList()
        private set

    override fun isAvailable(): Boolean = wifi != null && hasScanPermission()

    override fun observe(): Flow<List<RadioObservation>> = callbackFlow {
        if (!isAvailable()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(readResults())
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            // Protected system broadcast: NOT_EXPORTED still receives it and nothing can spoof it.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Emit whatever is already cached so the HUD is populated immediately.
        trySend(readResults())

        val pump = launch {
            while (isActive) {
                requestScan()
                delay(RESCAN_INTERVAL_MS)
            }
        }

        awaitClose {
            pump.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Fire a scan request. Safe to call at any time; the platform will throttle it. */
    fun requestScan() {
        if (!hasScanPermission()) return
        @Suppress("DEPRECATION")
        runCatching { wifi?.startScan() }
    }

    /** One-shot read of the current cache, for the capture listen window. */
    @SuppressLint("MissingPermission")
    fun readResults(): List<RadioObservation> {
        if (!hasScanPermission()) return emptyList()
        val results = runCatching { wifi?.scanResults }.getOrNull().orEmpty()
        lastRawResults = results
        val now = SystemClock.elapsedRealtime()
        return results.map { it.toObservation(now) }
    }

    /** APs that will answer an 802.11mc ranging request — the only true distances available. */
    fun rttCapableResults(): List<ScanResult> = lastRawResults.filter { it.is80211mcResponder }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toObservation(nowMs: Long): RadioObservation {
        val ssid = (wifiSsid?.toString()?.trim('"') ?: "").ifBlank { "<hidden SSID>" }
        val standard = standardLabel(wifiStandard)
        val band = bandLabel(frequency)

        val extras = buildMap {
            put("channel", channelOf(frequency).toString())
            put("channelWidth", widthLabel(channelWidth))
            put("security", securityLabel(capabilities.orEmpty()))
            put("capabilities", capabilities.orEmpty())
            put("centerFreq0", centerFreq0.toString())
            if (centerFreq1 != 0) put("centerFreq1", centerFreq1.toString())
            put("rttResponder", is80211mcResponder.toString())
            put("bssid", BSSID.orEmpty())

            // Wi-Fi 7 multi-link. One logical AP running several radios at once is the most
            // visually interesting thing a modern scan can show, so surface it prominently.
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    apMldMacAddress?.let { put("mldMac", it.toString()) }
                    val links = affiliatedMloLinks
                    if (links.isNotEmpty()) {
                        put("mloLinks", links.joinToString(", ") { link ->
                            "${mloBandLabel(link.band)} ch${link.channel}"
                        })
                        put("mloLinkCount", links.size.toString())
                    }
                }
            }
        }

        return RadioObservation(
            key = BSSID.orEmpty().uppercase(),
            family = RadioFamily.WIFI,
            displayName = ssid,
            vendor = OuiLookup.vendorFor(BSSID),
            rssiDbm = level,
            measuredRangeM = null,   // only RttRanger may fill this
            estimatedRangeM = pathLossRangeM(level, AP_TX_DBM, frequency),
            standard = standard,
            bandLabel = band,
            freqHz = frequency * 1_000_000L,
            firstSeenElapsedMs = nowMs,
            lastSeenElapsedMs = nowMs,
            extras = extras,
        )
    }

    private fun hasScanPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val nearby = ContextCompat.checkSelfPermission(
            context, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        return fine || nearby
    }

    companion object {
        /** Just outside the four-per-two-minutes throttle window. */
        const val RESCAN_INTERVAL_MS = 32_000L

        /** Typical consumer AP EIRP. Only used for the path-loss estimate. */
        private const val AP_TX_DBM = 20

        fun standardLabel(std: Int): String = when (std) {
            ScanResult.WIFI_STANDARD_11BE -> "802.11be (Wi-Fi 7)"
            ScanResult.WIFI_STANDARD_11AX -> "802.11ax (Wi-Fi 6/6E)"
            ScanResult.WIFI_STANDARD_11AC -> "802.11ac (Wi-Fi 5)"
            ScanResult.WIFI_STANDARD_11N -> "802.11n (Wi-Fi 4)"
            ScanResult.WIFI_STANDARD_11AD -> "802.11ad (60 GHz)"
            ScanResult.WIFI_STANDARD_LEGACY -> "802.11 a/b/g"
            else -> "802.11 (unknown)"
        }

        fun bandLabel(freqMhz: Int): String = when (freqMhz) {
            in 2401..2495 -> "2.4 GHz"
            in 5150..5895 -> "5 GHz"
            in 5925..7125 -> "6 GHz (Wi-Fi 6E/7)"
            in 57000..71000 -> "60 GHz"
            else -> "$freqMhz MHz"
        }

        fun channelOf(freqMhz: Int): Int = when {
            freqMhz == 2484 -> 14
            freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
            freqMhz in 5150..5895 -> (freqMhz - 5000) / 5
            freqMhz in 5925..7125 -> (freqMhz - 5950) / 5
            else -> -1
        }

        fun widthLabel(width: Int): String = when (width) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
            ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
            ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
            ScanResult.CHANNEL_WIDTH_320MHZ -> "320 MHz (Wi-Fi 7)"
            else -> "unknown"
        }

        fun securityLabel(caps: String): String = when {
            caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3 transition"
            caps.contains("SAE") -> "WPA3-SAE"
            caps.contains("OWE") -> "OWE (enhanced open)"
            caps.contains("EAP_SUITE_B") -> "WPA3-Enterprise 192-bit"
            caps.contains("EAP") -> "WPA2/3-Enterprise"
            caps.contains("RSN") || caps.contains("WPA2") -> "WPA2-PSK"
            caps.contains("WPA") -> "WPA-PSK"
            caps.contains("WEP") -> "WEP (broken)"
            else -> "Open"
        }

        private fun mloBandLabel(band: Int): String = when (band) {
            1 -> "2.4 GHz"
            2 -> "5 GHz"
            8 -> "6 GHz"
            else -> "band $band"
        }
    }
}
