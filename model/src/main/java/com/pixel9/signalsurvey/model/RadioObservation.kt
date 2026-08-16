package com.pixel9.signalsurvey.model

/**
 * One thing heard on the air (or on the local network), normalized across every radio.
 *
 * Deliberately flat and immutable: the whole pipeline — merge, fuse, trilaterate, render,
 * export — operates on this single type, so adding a radio means adding a producer and
 * nothing else.
 */
data class RadioObservation(
    /**
     * Stable identity within a session. BSSID for Wi-Fi, device address for Bluetooth,
     * "nr:<nci>" / "lte:<eci>" for cells, "mdns:<host>:<type>" for network services.
     *
     * Bluetooth addresses rotate roughly every 15 minutes on privacy-aware devices, so BLE
     * keys are not durable across a long session. [fingerprint] is the stable-ish fallback.
     */
    val key: String,
    val family: RadioFamily,
    val displayName: String,
    val vendor: String? = null,
    val rssiDbm: Int? = null,
    /** Real ranging only — Wi-Fi RTT. Never populate this from RSSI. */
    val measuredRangeM: Float? = null,
    val measuredRangeStdDevM: Float? = null,
    /** Log-distance path-loss guess. Order of magnitude at best; always render with "~". */
    val estimatedRangeM: Float? = null,
    val standard: String,
    val bandLabel: String,
    val freqHz: Long? = null,
    val firstSeenElapsedMs: Long = 0L,
    val lastSeenElapsedMs: Long = 0L,
    val sightings: Int = 1,
    /** Free-form per-family detail; rendered verbatim in the detail sheet and the JSON. */
    val extras: Map<String, String> = emptyMap(),
) {
    /**
     * Identity that survives MAC rotation: vendor + advertised services + name.
     * Not unique, but good enough to stop one BLE tracker becoming four session entries.
     */
    val fingerprint: String
        get() = listOfNotNull(
            family.name,
            vendor,
            extras["services"],
            displayName.takeIf { !it.startsWith("<") && it.isNotBlank() },
        ).joinToString("|")

    val isRandomAddress: Boolean get() = extras["addressRandom"] == "true"

    /** Merge a fresh sighting into an accumulated one, keeping the strongest RSSI. */
    fun mergeWith(prior: RadioObservation?): RadioObservation {
        if (prior == null) return this
        return copy(
            displayName = displayName.takeIf { it.isNotBlank() && !it.startsWith("<") }
                ?: prior.displayName,
            vendor = vendor ?: prior.vendor,
            rssiDbm = listOfNotNull(rssiDbm, prior.rssiDbm).maxOrNull(),
            measuredRangeM = measuredRangeM ?: prior.measuredRangeM,
            measuredRangeStdDevM = measuredRangeStdDevM ?: prior.measuredRangeStdDevM,
            firstSeenElapsedMs = minOf(firstSeenElapsedMs, prior.firstSeenElapsedMs),
            lastSeenElapsedMs = maxOf(lastSeenElapsedMs, prior.lastSeenElapsedMs),
            sightings = prior.sightings + 1,
            extras = prior.extras + extras,
        )
    }

    /**
     * What this emitter is actually doing on the air, in words. This is the answer to
     * "mark what it's doing" — the standard name alone does not say anything.
     */
    fun activityDescription(): String = when (family) {
        RadioFamily.WIFI -> when {
            extras["mloLinks"] != null ->
                "beaconing across ${extras["mloLinks"]} (Wi-Fi 7 multi-link)"
            extras["role"] == "p2p" -> "advertising Wi-Fi Direct"
            displayName.startsWith("<hidden") ->
                "beaconing with SSID suppressed - ${extras["security"] ?: "unknown security"}"
            else -> "beaconing \"$displayName\" - ${extras["channelWidth"] ?: "?"} " +
                "ch ${extras["channel"] ?: "?"} - ${extras["security"] ?: "?"}"
        }

        RadioFamily.BLUETOOTH -> when {
            extras["auracast"] == "true" -> "broadcasting LE Audio (Auracast)"
            extras["periodicAdvMs"] != null ->
                "periodic advertising every ${extras["periodicAdvMs"]} ms on ${extras["phy"]}"
            extras["btClass"] != null -> "discoverable BR/EDR - ${extras["btClass"]}"
            extras["connectable"] == "false" -> "broadcast-only advertising on ${extras["phy"]}"
            else -> "advertising, connectable - ${extras["phy"] ?: "LE 1M"}"
        }

        RadioFamily.CELLULAR -> buildString {
            append(if (extras["registered"] == "true") "serving cell" else "neighbour cell")
            extras["band"]?.let { append(" - band $it") }
            extras["pci"]?.let { append(" - PCI $it") }
            extras["arfcn"]?.let { append(" - ARFCN $it") }
        }

        RadioFamily.GNSS ->
            "downlink ${extras["constellation"] ?: ""} SV ${extras["svid"] ?: "?"} " +
                "at ${extras["cn0"] ?: "?"} dB-Hz"

        RadioFamily.NETWORK_SERVICE ->
            "advertising ${extras["serviceType"] ?: "a service"} on the local network"

        else -> standard
    }
}

enum class RadioFamily(val label: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    CELLULAR("Cellular"),
    UWB("UWB"),
    NFC("NFC"),
    GNSS("GNSS"),
    /** Discovered over IP (mDNS/SSDP), not off the air. */
    NETWORK_SERVICE("Network service"),
    IEEE_802_15_4("802.15.4"),
    SUB_GHZ_ISM("Sub-GHz ISM"),
    DECT("DECT"),
    PROPRIETARY_24("Proprietary 2.4 GHz"),
    INFRARED("Infrared"),
    ULTRASONIC("Ultrasonic"),
    SATELLITE_DOWNLINK("Satellite downlink"),
}

/** A single RSSI reading tagged with where the phone was standing when it was taken. */
data class RssiSample(
    val key: String,
    val elapsedMs: Long,
    val rssiDbm: Int,
    val cameraWorld: Vec3,
)

/** A Wi-Fi RTT range measured from a known world position. The basis of trilateration. */
data class RttFix(
    val key: String,
    val elapsedMs: Long,
    val distanceM: Float,
    val stdDevM: Float,
    val rssiDbm: Int,
    val cameraWorld: Vec3,
    val shotIndex: Int?,
)

/** A GNSS satellite as reported by GnssStatus — azimuth/elevation make it projectable. */
data class SatelliteFix(
    val constellation: String,
    val svid: Int,
    val carrierFreqHz: Long?,
    val cn0DbHz: Float,
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val usedInFix: Boolean,
) {
    val bandLabel: String get() = when (carrierFreqHz) {
        null -> "unknown"
        in 1_570_000_000..1_580_000_000 -> "L1 / E1 / B1 (1575 MHz)"
        in 1_170_000_000..1_180_000_000 -> "L5 / E5a / B2a (1176 MHz)"
        in 1_200_000_000..1_250_000_000 -> "L2 / G2 (1227 MHz)"
        else -> "%.1f MHz".format(carrierFreqHz / 1e6)
    }
}

data class GeoFix(val lat: Double, val lon: Double, val altM: Double?, val accuracyM: Float)
