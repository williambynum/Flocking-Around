package com.pixel9.signalsurvey.radio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pixel9.signalsurvey.model.RadioObservation
import kotlinx.coroutines.flow.Flow
import kotlin.math.log10
import kotlin.math.pow

/** Anything that can hear something. One interface so [RadioHub] does not care which. */
interface RadioSource {
    val name: String
    /** True when this hardware exists and the required permissions are granted. */
    fun isAvailable(): Boolean
    /** Hot stream of observations. Cancelling the collection must release the receiver. */
    fun observe(): Flow<List<RadioObservation>>
}

/**
 * Log-distance path loss.
 *
 * This is an *estimate* and the UI must always render it as one. Indoors the exponent
 * varies between about 1.8 (a corridor acting as a waveguide) and 4 (through walls), so a
 * single number is routinely off by a factor of two. It exists to rank candidates during
 * fusion and to seed instant placement — never to state a distance as fact.
 *
 * @param exponent 2.0 free space, 2.7 typical indoor, 3.5+ through walls
 */
fun pathLossRangeM(
    rssiDbm: Int,
    txPowerDbm: Int,
    freqMhz: Int,
    exponent: Double = 2.7,
): Float {
    if (freqMhz <= 0) return 0f
    // Free-space path loss at one metre, in dB.
    val fsplAt1m = 20.0 * log10(freqMhz.toDouble()) - 27.55
    val metres = 10.0.pow((txPowerDbm - fsplAt1m - rssiDbm) / (10.0 * exponent))
    return metres.toFloat().coerceIn(0.3f, 300f)
}

/** What this specific handset can and cannot hear. Drives the "inferred" reasons in the UI. */
class DeviceCapabilities(context: Context) {

    private val pm: PackageManager = context.packageManager

    val hasWifiRtt: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
    val hasWifiAware: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    val hasBle: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val hasTelephony: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val hasNfc: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
    val hasGps: Boolean = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)

    /** Base Pixel 9: false. Pixel 9 Pro / Pro XL / Pro Fold: true. */
    val hasUwb: Boolean = pm.hasSystemFeature("android.hardware.uwb")

    fun describe(): String = buildString {
        append(Build.MODEL).append(" (").append(Build.DEVICE).append(", Android ")
        append(Build.VERSION.RELEASE).append(") - ")
        append(
            listOfNotNull(
                "Wi-Fi".takeIf { true },
                "Wi-Fi RTT".takeIf { hasWifiRtt },
                "Wi-Fi Aware".takeIf { hasWifiAware },
                "BLE".takeIf { hasBle },
                "Cellular".takeIf { hasTelephony },
                "GNSS".takeIf { hasGps },
                "NFC".takeIf { hasNfc },
                if (hasUwb) "UWB" else "no UWB",
            ).joinToString(", ")
        )
    }

    /**
     * Why a signal family cannot be confirmed on this hardware. Surfaced verbatim on the
     * annotation cards, because "we didn't detect it" and "we physically can't" are very
     * different claims.
     */
    fun unavailabilityReason(profileId: String): String? = when {
        profileId == "uwb" && !hasUwb ->
            "No UWB radio on this device, and UWB is never passively observable"
        profileId == "wifi.rtt" && !hasWifiRtt -> "No Wi-Fi RTT support on this device"
        profileId == "nfc" && !hasNfc -> "No NFC hardware"
        profileId.startsWith("cell.") && !hasTelephony -> "No cellular modem"
        else -> null
    }
}
