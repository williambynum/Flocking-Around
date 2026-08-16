package com.pixel9.signalsurvey.model

/**
 * Whether *this phone* can confirm a signal exists, or is only reasoning about it.
 *
 * This distinction is the honesty backbone of the app. A base Pixel 9 has receivers for
 * Wi-Fi, Bluetooth, cellular, GNSS and NFC — and nothing else. Everything from Zigbee to
 * garage-door remotes is a guess, and the UI must never let a guess look like a reading.
 */
enum class Observability(val badge: String) {
    /** Receiver present, public API, passive. Renders as MEASURED when matched. */
    DIRECT("measured"),

    /** Only visible over IP once you are on the same LAN (mDNS/SSDP). */
    NETWORK_SIDE("network"),

    /** We must initiate: Wi-Fi RTT, UWB ranging, an NFC tap. */
    ACTIVE_ONLY("on request"),

    /** No receiver on this hardware. Always renders greyed, with the reason. */
    INFERRED_ONLY("inferred"),
}

enum class EmitterRole { BEACONS_CONTINUOUSLY, RESPONDS_ONLY, SCANS_FOR_OTHERS, BIDIRECTIONAL }

data class SignalProfile(
    val id: String,
    val family: RadioFamily,
    val standard: String,
    val bandLabel: String,
    val freqLowHz: Long,
    val freqHighHz: Long,
    val typicalTxDbm: Int,
    val role: EmitterRole,
    val observability: Observability,
    /** Shown in the "how would I verify this?" sheet. Keep it concrete. */
    val apiHint: String,
)

/**
 * Physics and API facts, stated once. Device priors live separately in [DeviceOntology] so
 * they can be tuned without touching anything factual.
 */
object SignalCatalog {

    // ---------------------------------------------------------------- Wi-Fi

    val WIFI_BE = SignalProfile(
        "wifi.be", RadioFamily.WIFI, "802.11be (Wi-Fi 7)", "2.4 / 5 / 6 GHz",
        2_400_000_000L, 7_125_000_000L, 20, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "ScanResult.wifiStandard == WIFI_STANDARD_11BE; multi-link via getAffiliatedMloLinks()",
    )
    val WIFI_AX = SignalProfile(
        "wifi.ax", RadioFamily.WIFI, "802.11ax (Wi-Fi 6/6E)", "2.4 / 5 / 6 GHz",
        2_400_000_000L, 7_125_000_000L, 20, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "ScanResult.wifiStandard == WIFI_STANDARD_11AX",
    )
    val WIFI_AC = SignalProfile(
        "wifi.ac", RadioFamily.WIFI, "802.11ac (Wi-Fi 5)", "5 GHz",
        5_150_000_000L, 5_895_000_000L, 20, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "ScanResult.wifiStandard == WIFI_STANDARD_11AC",
    )
    val WIFI_N = SignalProfile(
        "wifi.n", RadioFamily.WIFI, "802.11n (Wi-Fi 4)", "2.4 GHz",
        2_400_000_000L, 2_483_500_000L, 17, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "The legacy 2.4 GHz SSID nearly every gateway still runs",
    )
    val WIFI_STA = SignalProfile(
        "wifi.sta", RadioFamily.WIFI, "802.11 client (STA)", "2.4 / 5 GHz",
        2_400_000_000L, 5_895_000_000L, 15, EmitterRole.BIDIRECTIONAL, Observability.NETWORK_SIDE,
        "Android has no monitor mode - clients are invisible to a scan. Find it via mDNS/SSDP instead",
    )
    val WIFI_RTT = SignalProfile(
        "wifi.rtt", RadioFamily.WIFI, "802.11mc FTM ranging", "5 GHz",
        5_150_000_000L, 5_895_000_000L, 20, EmitterRole.RESPONDS_ONLY, Observability.ACTIVE_ONLY,
        "WifiRttManager.startRanging() when ScanResult.is80211mcResponder - true distance, +/-1-2 m",
    )
    val WIFI_DIRECT = SignalProfile(
        "wifi.p2p", RadioFamily.WIFI, "Wi-Fi Direct (P2P)", "2.4 / 5 GHz",
        2_400_000_000L, 5_895_000_000L, 18, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "WifiP2pManager.discoverPeers() - P2P group owners also show up as normal APs",
    )
    val WIFI_AWARE = SignalProfile(
        "wifi.nan", RadioFamily.WIFI, "Wi-Fi Aware (NAN)", "2.4 / 5 GHz",
        2_400_000_000L, 5_895_000_000L, 18, EmitterRole.BIDIRECTIONAL, Observability.ACTIVE_ONLY,
        "WifiAwareManager - peer discovery and ranging with no AP involved",
    )

    // ------------------------------------------------------------ Bluetooth

    val BLE = SignalProfile(
        "bt.le", RadioFamily.BLUETOOTH, "Bluetooth LE 5.3", "2.4 GHz (40 channels)",
        2_402_000_000L, 2_480_000_000L, 4, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "BluetoothLeScanner with setLegacy(false) to catch extended advertising",
    )
    val BT_CLASSIC = SignalProfile(
        "bt.br", RadioFamily.BLUETOOTH, "Bluetooth BR/EDR", "2.4 GHz (79 ch FHSS)",
        2_402_000_000L, 2_480_000_000L, 4, EmitterRole.RESPONDS_ONLY, Observability.DIRECT,
        "BluetoothAdapter.startDiscovery() - only while the device is discoverable",
    )
    val AURACAST = SignalProfile(
        "bt.auracast", RadioFamily.BLUETOOTH, "LE Audio Broadcast (Auracast)", "2.4 GHz",
        2_402_000_000L, 2_480_000_000L, 4, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "Plain BLE scan filtered on service UUID 0x1852 (Broadcast Audio Announcement)",
    )

    // -------------------------------------------------------------- Cellular

    val NR_SUB6 = SignalProfile(
        "cell.nr.sub6", RadioFamily.CELLULAR, "5G NR (sub-6)", "600 MHz - 3.7 GHz",
        600_000_000L, 3_800_000_000L, 43, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "CellInfoNr -> nrarfcn, getBands(), ssRsrp/ssRsrq/ssSinr",
    )
    val NR_MMWAVE = SignalProfile(
        "cell.nr.mmw", RadioFamily.CELLULAR, "5G NR mmWave", "24 - 40 GHz",
        24_250_000_000L, 40_000_000_000L, 40, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "Bands n260/n261. SKU-dependent on Pixel 9 - read getBands() at runtime, never assume",
    )
    val LTE = SignalProfile(
        "cell.lte", RadioFamily.CELLULAR, "LTE / LTE-Advanced", "700 MHz - 2.6 GHz",
        700_000_000L, 2_700_000_000L, 43, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "CellInfoLte -> earfcn, bandwidth, RSRP/RSRQ/CQI/timing advance",
    )
    val UMTS = SignalProfile(
        "cell.wcdma", RadioFamily.CELLULAR, "UMTS / WCDMA", "850 / 1900 / 2100 MHz",
        824_000_000L, 2_170_000_000L, 43, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "CellInfoWcdma - largely refarmed, still appears in some markets",
    )
    val GSM = SignalProfile(
        "cell.gsm", RadioFamily.CELLULAR, "GSM", "850 / 900 / 1800 / 1900 MHz",
        824_000_000L, 1_990_000_000L, 43, EmitterRole.BIDIRECTIONAL, Observability.DIRECT,
        "CellInfoGsm",
    )

    // ------------------------------------------------------- Short range etc.

    val UWB = SignalProfile(
        "uwb", RadioFamily.UWB, "802.15.4z UWB", "6.5 / 8 GHz (ch 5/9)",
        6_240_000_000L, 8_240_000_000L, -14, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "No UWB radio on the base Pixel 9 (Pro models only), and UWB is never passively " +
            "sniffable - it needs a BLE out-of-band handshake with a cooperating device",
    )
    val NFC = SignalProfile(
        "nfc", RadioFamily.NFC, "NFC / ISO 14443", "13.56 MHz",
        13_560_000L, 13_560_000L, 0, EmitterRole.RESPONDS_ONLY, Observability.ACTIVE_ONLY,
        "NfcAdapter reader mode - about 4 cm, so effectively contact-only",
    )
    val GNSS_L1 = SignalProfile(
        "gnss.l1", RadioFamily.GNSS, "GNSS L1 / E1 / B1", "1575.42 MHz",
        1_570_000_000L, 1_580_000_000L, -130, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "GnssStatus.Callback - azimuth and elevation make satellites projectable onto the photo",
    )
    val GNSS_L5 = SignalProfile(
        "gnss.l5", RadioFamily.GNSS, "GNSS L5 / E5a / B2a", "1176.45 MHz",
        1_170_000_000L, 1_180_000_000L, -130, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "Pixel 9 is dual-frequency - you will see two entries per satellite",
    )

    // ------------------------------------------------- Beyond the phone's reach

    val ZIGBEE = SignalProfile(
        "x.zigbee", RadioFamily.IEEE_802_15_4, "Zigbee 3.0", "2.4 GHz (ch 11-26)",
        2_405_000_000L, 2_480_000_000L, 8, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "No 802.15.4 receiver on any Pixel phone - cannot be verified",
    )
    val THREAD = SignalProfile(
        "x.thread", RadioFamily.IEEE_802_15_4, "Thread / Matter-over-Thread", "2.4 GHz",
        2_405_000_000L, 2_480_000_000L, 8, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "No radio. Partial hint only: _matter._tcp / _meshcop._udp on the LAN",
    )
    val ZWAVE = SignalProfile(
        "x.zwave", RadioFamily.SUB_GHZ_ISM, "Z-Wave", "908.42 MHz (US)",
        908_000_000L, 916_000_000L, 0, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "No sub-GHz receiver",
    )
    val ISM_SUBGHZ = SignalProfile(
        "x.ism", RadioFamily.SUB_GHZ_ISM, "Sub-GHz ISM (OOK/FSK)", "315 / 433 / 868 / 915 MHz",
        300_000_000L, 930_000_000L, 10, EmitterRole.BEACONS_CONTINUOUSLY, Observability.INFERRED_ONLY,
        "Garage doors, TPMS, weather stations, key fobs. Needs an external SDR over USB-OTG",
    )
    val LORA = SignalProfile(
        "x.lora", RadioFamily.SUB_GHZ_ISM, "LoRa / LoRaWAN", "868 / 915 MHz",
        863_000_000L, 928_000_000L, 14, EmitterRole.BEACONS_CONTINUOUSLY, Observability.INFERRED_ONLY,
        "No sub-GHz receiver",
    )
    val DECT = SignalProfile(
        "x.dect", RadioFamily.DECT, "DECT cordless", "1.88 - 1.9 GHz",
        1_880_000_000L, 1_900_000_000L, 24, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "No receiver",
    )
    val PROPRIETARY_24 = SignalProfile(
        "x.prop24", RadioFamily.PROPRIETARY_24, "Proprietary 2.4 GHz", "2.4 GHz",
        2_400_000_000L, 2_483_500_000L, 0, EmitterRole.BIDIRECTIONAL, Observability.INFERRED_ONLY,
        "Logitech Unifying, wireless peripherals, RC links - not 802.11 or BT framing",
    )
    val INFRARED = SignalProfile(
        "x.ir", RadioFamily.INFRARED, "Infrared remote (940 nm)", "~319 THz",
        0L, 0L, 0, EmitterRole.BEACONS_CONTINUOUSLY, Observability.INFERRED_ONLY,
        "No IR receiver, and the rear camera has an IR-cut filter",
    )
    val ULTRASONIC = SignalProfile(
        "x.ultrasonic", RadioFamily.ULTRASONIC, "Near-ultrasonic beacon", "17 - 22 kHz acoustic",
        17_000L, 22_000L, 0, EmitterRole.BEACONS_CONTINUOUSLY, Observability.DIRECT,
        "AudioRecord at 48 kHz plus an FFT - not RF, but a real emission the phone can hear",
    )
    val SAT_DOWNLINK = SignalProfile(
        "x.satdl", RadioFamily.SATELLITE_DOWNLINK, "Ku/Ka satellite downlink", "10.7 - 14.5 GHz",
        10_700_000_000L, 14_500_000_000L, 50, EmitterRole.RESPONDS_ONLY, Observability.INFERRED_ONLY,
        "Receive-only dish. No receiver on the phone",
    )

    /** Everything, for the reference screen and the export legend. */
    val all: List<SignalProfile> = listOf(
        WIFI_BE, WIFI_AX, WIFI_AC, WIFI_N, WIFI_STA, WIFI_RTT, WIFI_DIRECT, WIFI_AWARE,
        BLE, BT_CLASSIC, AURACAST,
        NR_SUB6, NR_MMWAVE, LTE, UMTS, GSM,
        UWB, NFC, GNSS_L1, GNSS_L5,
        ZIGBEE, THREAD, ZWAVE, ISM_SUBGHZ, LORA, DECT, PROPRIETARY_24,
        INFRARED, ULTRASONIC, SAT_DOWNLINK,
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: String): SignalProfile? = byId[id]

    /** Profiles this hardware can actually confirm. Used to badge the reference screen. */
    val observable: List<SignalProfile> = all.filter {
        it.observability == Observability.DIRECT || it.observability == Observability.ACTIVE_ONLY
    }
}
