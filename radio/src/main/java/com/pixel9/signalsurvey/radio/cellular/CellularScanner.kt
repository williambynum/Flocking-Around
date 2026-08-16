package com.pixel9.signalsurvey.radio.cellular

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Serving and neighbouring cells.
 *
 * Cell identity is location-sensitive: without ACCESS_FINE_LOCATION the framework redacts
 * CI/TAC even when READ_PHONE_STATE is granted, so both are required for anything useful.
 *
 * No bearing is available — a modem reports signal strength, not angle of arrival — so cells
 * are placed in the unmatched rail rather than pinned to a pixel, unless the operator has
 * visually identified a tower to associate them with.
 */
class CellularScanner(private val context: Context) {

    private val telephony = context.getSystemService(TelephonyManager::class.java)

    fun isAvailable(): Boolean =
        telephony != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            hasPermission()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Force a fresh modem read rather than using the cached list. */
    @SuppressLint("MissingPermission")
    suspend fun snapshot(): List<RadioObservation> {
        val tm = telephony ?: return emptyList()
        if (!hasPermission()) return emptyList()

        val cells: List<CellInfo> = suspendCancellableCoroutine { cont ->
            runCatching {
                tm.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            @Suppress("DEPRECATION")
                            if (cont.isActive) cont.resume(tm.allCellInfo.orEmpty())
                        }
                    },
                )
            }.onFailure {
                @Suppress("DEPRECATION")
                if (cont.isActive) cont.resume(runCatching { tm.allCellInfo }.getOrNull().orEmpty())
            }
        }

        val now = SystemClock.elapsedRealtime()
        return cells.mapNotNull { it.toObservation(now) }
    }

    private fun CellInfo.toObservation(nowMs: Long): RadioObservation? = when (this) {
        is CellInfoNr -> nrObservation(this, nowMs)
        is CellInfoLte -> lteObservation(this, nowMs)
        is CellInfoWcdma -> {
            val id = cellIdentity
            val s = cellSignalStrength
            RadioObservation(
                key = "wcdma:${id.cid}",
                family = RadioFamily.CELLULAR,
                displayName = id.operatorAlphaLong?.toString() ?: "UMTS cell ${id.cid}",
                rssiDbm = s.dbm,
                standard = "UMTS / WCDMA",
                bandLabel = "850 / 1900 / 2100 MHz",
                firstSeenElapsedMs = nowMs, lastSeenElapsedMs = nowMs,
                extras = mapOf(
                    "registered" to isRegistered.toString(),
                    "psc" to id.psc.toString(),
                    "arfcn" to id.uarfcn.toString(),
                    "lac" to id.lac.toString(),
                ),
            )
        }
        is CellInfoGsm -> {
            val id = cellIdentity
            RadioObservation(
                key = "gsm:${id.cid}",
                family = RadioFamily.CELLULAR,
                displayName = id.operatorAlphaLong?.toString() ?: "GSM cell ${id.cid}",
                rssiDbm = cellSignalStrength.dbm,
                standard = "GSM",
                bandLabel = "850 / 900 / 1800 / 1900 MHz",
                firstSeenElapsedMs = nowMs, lastSeenElapsedMs = nowMs,
                extras = mapOf(
                    "registered" to isRegistered.toString(),
                    "arfcn" to id.arfcn.toString(),
                    "lac" to id.lac.toString(),
                ),
            )
        }
        else -> null
    }

    private fun nrObservation(info: CellInfoNr, nowMs: Long): RadioObservation? {
        val id = info.cellIdentity as? CellIdentityNr ?: return null
        val signal = info.cellSignalStrength as? android.telephony.CellSignalStrengthNr
        val bands = runCatching { id.bands.toList() }.getOrDefault(emptyList())
        val isMmWave = bands.any { it >= 257 }
        val freqHz = nrarfcnToHz(id.nrarfcn)

        return RadioObservation(
            key = "nr:${id.nci}",
            family = RadioFamily.CELLULAR,
            displayName = id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                ?: "5G NR cell",
            rssiDbm = signal?.ssRsrp,
            standard = if (isMmWave) "5G NR mmWave" else "5G NR (sub-6)",
            bandLabel = if (bands.isEmpty()) "unknown band"
            else bands.joinToString(", ") { "n$it" },
            freqHz = freqHz,
            firstSeenElapsedMs = nowMs, lastSeenElapsedMs = nowMs,
            extras = buildMap {
                put("registered", info.isRegistered.toString())
                put("nci", id.nci.toString())
                put("pci", id.pci.toString())
                put("tac", id.tac.toString())
                put("arfcn", id.nrarfcn.toString())
                if (bands.isNotEmpty()) put("band", bands.joinToString(",") { "n$it" })
                freqHz?.let { put("downlinkMhz", "%.2f".format(it / 1e6)) }
                signal?.let {
                    put("ssRsrp", it.ssRsrp.toString())
                    put("ssRsrq", it.ssRsrq.toString())
                    put("ssSinr", it.ssSinr.toString())
                }
            },
        )
    }

    private fun lteObservation(info: CellInfoLte, nowMs: Long): RadioObservation? {
        val id = info.cellIdentity as? CellIdentityLte ?: return null
        val s = info.cellSignalStrength
        val bands = runCatching { id.bands.toList() }.getOrDefault(emptyList())

        return RadioObservation(
            key = "lte:${id.ci}",
            family = RadioFamily.CELLULAR,
            displayName = id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() }
                ?: "LTE cell ${id.ci}",
            rssiDbm = s.rsrp,
            standard = "LTE / LTE-Advanced",
            bandLabel = if (bands.isEmpty()) "700 MHz - 2.6 GHz"
            else bands.joinToString(", ") { "B$it" },
            firstSeenElapsedMs = nowMs, lastSeenElapsedMs = nowMs,
            extras = buildMap {
                put("registered", info.isRegistered.toString())
                put("ci", id.ci.toString())
                put("pci", id.pci.toString())
                put("tac", id.tac.toString())
                put("arfcn", id.earfcn.toString())
                put("bandwidthKhz", id.bandwidth.toString())
                if (bands.isNotEmpty()) put("band", bands.joinToString(",") { "B$it" })
                put("rsrp", s.rsrp.toString())
                put("rsrq", s.rsrq.toString())
                put("rssnr", s.rssnr.toString())
                if (s.timingAdvance != Int.MAX_VALUE) {
                    // Each TA step is ~78 m of round-trip; coarse, but a genuine distance bound.
                    put("timingAdvance", s.timingAdvance.toString())
                    put("taRangeKm", "%.2f".format(s.timingAdvance * 0.078))
                }
            },
        )
    }

    companion object {
        /**
         * NR-ARFCN to downlink frequency, per the 3GPP TS 38.104 global frequency raster.
         * Three ranges with different step sizes; getting this right is what lets the app
         * say "3.71 GHz" instead of "ARFCN 646667".
         */
        fun nrarfcnToHz(nrarfcn: Int): Long? = when {
            nrarfcn <= 0 -> null
            nrarfcn < 600_000 -> (nrarfcn * 5_000L)                                  // 5 kHz raster
            nrarfcn < 2_016_667 -> 3_000_000_000L + (nrarfcn - 600_000) * 15_000L    // 15 kHz
            nrarfcn < 3_279_166 -> 24_250_080_000L + (nrarfcn - 2_016_667) * 60_000L // 60 kHz
            else -> null
        }
    }
}
