package com.pixel9.signalsurvey.model

/** P(device emits this | device is of this class). Tune freely; nothing factual lives here. */
data class SignalExpectation(
    val profile: SignalProfile,
    val prior: Float,
    val note: String = "",
)

/**
 * Maps a classifier output label to what that kind of device is expected to be doing on
 * the air, plus the identity hooks the fusion engine uses to *confirm* it.
 *
 * [vendorOuis], [bleCompanyIds], [bleServiceUuids] and [mdnsTypes] are the whole reason
 * fusion works — a class prior alone can never distinguish two routers on one shelf.
 */
data class DeviceClassProfile(
    /** Must match the TFLite classifier's label exactly. */
    val label: String,
    val displayName: String,
    val expectations: List<SignalExpectation>,
    /** Wi-Fi / Bluetooth MAC prefixes, upper case, colon separated. */
    val vendorOuis: List<String> = emptyList(),
    /** Bluetooth SIG company identifiers from the manufacturer-data header. */
    val bleCompanyIds: List<Int> = emptyList(),
    /** Lower-case 128-bit UUID strings, or the 16-bit short form. */
    val bleServiceUuids: List<String> = emptyList(),
    val mdnsTypes: List<String> = emptyList(),
    /** android.bluetooth.BluetoothClass.Device.Major constant, when characteristic. */
    val btClassMajor: Int? = null,
)

object DeviceOntology {

    private fun e(p: SignalProfile, prior: Float, note: String = "") = SignalExpectation(p, prior, note)

    // BluetoothClass.Device.Major values, inlined so :model needs no bluetooth import.
    private const val MAJOR_COMPUTER = 0x0100
    private const val MAJOR_PHONE = 0x0200
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_PERIPHERAL = 0x0500
    private const val MAJOR_IMAGING = 0x0600
    private const val MAJOR_WEARABLE = 0x0700

    val entries: List<DeviceClassProfile> = listOf(

        DeviceClassProfile(
            label = "wireless_router",
            displayName = "Wi-Fi Router / Gateway",
            expectations = listOf(
                e(SignalCatalog.WIFI_BE, 0.45f, "Wi-Fi 7 on 2023+ hardware; look for 6 GHz and MLO"),
                e(SignalCatalog.WIFI_AX, 0.85f),
                e(SignalCatalog.WIFI_N, 0.95f, "The legacy 2.4 GHz SSID is almost always still up"),
                e(SignalCatalog.WIFI_RTT, 0.30f, "Tap to range if it is an 802.11mc responder"),
                e(SignalCatalog.BLE, 0.35f, "Setup and onboarding beacon"),
                e(SignalCatalog.ZIGBEE, 0.15f, "ISP gateways increasingly bundle a smart-home hub"),
                e(SignalCatalog.DECT, 0.10f, "European ISP gateways often include a DECT base"),
            ),
            vendorOuis = listOf("00:1A:11", "3C:37:86", "A0:63:91", "94:9F:3E", "F8:1A:67", "50:C7:BF"),
            mdnsTypes = listOf("_http._tcp", "_workstation._tcp"),
        ),

        DeviceClassProfile(
            label = "mesh_node",
            displayName = "Mesh Wi-Fi Node",
            expectations = listOf(
                e(SignalCatalog.WIFI_BE, 0.40f),
                e(SignalCatalog.WIFI_AX, 0.90f, "Backhaul SSID is often hidden - expect a <hidden> entry"),
                e(SignalCatalog.BLE, 0.80f, "Nearly every mesh kit onboards over BLE"),
                e(SignalCatalog.THREAD, 0.45f, "Nest Wifi Pro and eero act as Thread border routers"),
                e(SignalCatalog.WIFI_RTT, 0.25f),
            ),
            bleServiceUuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb"),
            mdnsTypes = listOf("_googlecast._tcp", "_meshcop._udp", "_matter._tcp"),
        ),

        DeviceClassProfile(
            label = "ceiling_access_point",
            displayName = "Enterprise Access Point",
            expectations = listOf(
                e(SignalCatalog.WIFI_BE, 0.35f),
                e(SignalCatalog.WIFI_AX, 0.90f),
                e(SignalCatalog.WIFI_RTT, 0.55f, "Enterprise APs are the likeliest 802.11mc responders"),
                e(SignalCatalog.BLE, 0.75f, "iBeacon/Eddystone wayfinding and BLE asset tracking"),
                e(SignalCatalog.ZIGBEE, 0.20f, "Some Aruba and Cisco APs carry an 802.15.4 radio"),
            ),
            vendorOuis = listOf("00:0B:86", "6C:F3:7F", "00:1B:54", "F4:B5:2F", "18:64:72", "E0:CB:BC"),
        ),

        DeviceClassProfile(
            label = "smart_speaker",
            displayName = "Smart Speaker / Display",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.98f, "Client only - find it via mDNS, never a Wi-Fi scan"),
                e(SignalCatalog.BLE, 0.90f, "Fast Pair, setup, proximity"),
                e(SignalCatalog.BT_CLASSIC, 0.55f, "A2DP sink while in pairing mode"),
                e(SignalCatalog.THREAD, 0.35f, "Nest Hub 2nd gen is a Thread border router"),
                e(SignalCatalog.ULTRASONIC, 0.10f, "Some ecosystems use near-ultrasonic pairing tones"),
            ),
            bleCompanyIds = listOf(0x00E0, 0x0171, 0x004C, 0x0075),
            bleServiceUuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb"),
            mdnsTypes = listOf(
                "_googlecast._tcp", "_spotify-connect._tcp", "_raop._tcp",
                "_matter._tcp", "_sonos._tcp",
            ),
        ),

        DeviceClassProfile(
            label = "security_camera",
            displayName = "IP / Security Camera",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.85f),
                e(SignalCatalog.BLE, 0.70f, "Onboarding beacon; some keep advertising for presence"),
                e(SignalCatalog.PROPRIETARY_24, 0.20f, "Battery cams often use a proprietary base-station link"),
                e(SignalCatalog.INFRARED, 0.75f, "940 nm night-vision illuminator - not verifiable"),
            ),
            mdnsTypes = listOf("_rtsp._tcp", "_axis-video._tcp", "_hap._tcp", "_onvif._tcp"),
        ),

        DeviceClassProfile(
            label = "smart_tv",
            displayName = "Smart TV / Streaming Device",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.95f),
                e(SignalCatalog.BT_CLASSIC, 0.70f, "Remote and soundbar pairing"),
                e(SignalCatalog.BLE, 0.85f),
                e(SignalCatalog.WIFI_DIRECT, 0.60f, "Cast/Miracast group owner - this one IS scannable"),
                e(SignalCatalog.AURACAST, 0.15f, "LE Audio broadcast on 2024+ sets"),
                e(SignalCatalog.INFRARED, 0.60f, "IR receiver - the phone cannot verify it"),
            ),
            btClassMajor = MAJOR_AUDIO_VIDEO,
            mdnsTypes = listOf("_googlecast._tcp", "_airplay._tcp", "_dial._tcp", "_viziocast._tcp"),
        ),

        DeviceClassProfile(
            label = "cell_tower",
            displayName = "Cell Site / Macro Tower",
            expectations = listOf(
                e(SignalCatalog.NR_SUB6, 0.90f, "Check CellInfoNr.getBands() for what is actually live"),
                e(SignalCatalog.LTE, 0.98f, "The LTE anchor is near-universal in NSA deployments"),
                e(SignalCatalog.UMTS, 0.20f),
                e(SignalCatalog.NR_MMWAVE, 0.15f, "Only if you can make out small panel radomes"),
                e(SignalCatalog.SAT_DOWNLINK, 0.25f, "Backhaul dishes on the headframe"),
            ),
        ),

        DeviceClassProfile(
            label = "small_cell",
            displayName = "Small Cell / DAS Node",
            expectations = listOf(
                e(SignalCatalog.NR_SUB6, 0.60f),
                e(SignalCatalog.NR_MMWAVE, 0.45f, "Street-level mmWave nodes - very short range, very directional"),
                e(SignalCatalog.LTE, 0.85f),
            ),
        ),

        DeviceClassProfile(
            label = "smart_lock",
            displayName = "Smart Lock / Video Doorbell",
            expectations = listOf(
                e(SignalCatalog.BLE, 0.95f, "Primary control channel"),
                e(SignalCatalog.WIFI_STA, 0.40f),
                e(SignalCatalog.ZIGBEE, 0.30f),
                e(SignalCatalog.THREAD, 0.30f),
                e(SignalCatalog.UWB, 0.20f, "Newer locks support UWB unlock - base Pixel 9 has no UWB radio"),
                e(SignalCatalog.NFC, 0.25f, "Tap-to-unlock"),
            ),
            mdnsTypes = listOf("_hap._tcp", "_matter._tcp"),
        ),

        DeviceClassProfile(
            label = "ble_tracker",
            displayName = "Item Tracker (Tile / AirTag / SmartTag)",
            expectations = listOf(
                e(SignalCatalog.BLE, 0.99f, "Rotating address - identify by service data, not by MAC"),
                e(SignalCatalog.UWB, 0.40f, "Precision finding - not observable on this hardware"),
                e(SignalCatalog.NFC, 0.50f, "Tap-to-identify in lost mode"),
            ),
            bleCompanyIds = listOf(0x004C, 0x0075, 0x00E0),
            bleServiceUuids = listOf(
                "0000feed-0000-1000-8000-00805f9b34fb", // Tile
                "0000feaa-0000-1000-8000-00805f9b34fb", // Eddystone / Google FMDN
                "0000fd5a-0000-1000-8000-00805f9b34fb", // Samsung SmartTag
                "0000fd44-0000-1000-8000-00805f9b34fb", // Apple continuity
            ),
        ),

        DeviceClassProfile(
            label = "printer",
            displayName = "Network Printer",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.90f),
                e(SignalCatalog.WIFI_DIRECT, 0.75f, "The Wi-Fi Direct SSID IS visible to a scan"),
                e(SignalCatalog.BLE, 0.40f),
                e(SignalCatalog.NFC, 0.35f, "Tap-to-print"),
            ),
            btClassMajor = MAJOR_IMAGING,
            mdnsTypes = listOf("_ipp._tcp", "_ipps._tcp", "_pdl-datastream._tcp", "_printer._tcp"),
        ),

        DeviceClassProfile(
            label = "laptop",
            displayName = "Laptop / Workstation",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.98f),
                e(SignalCatalog.BLE, 0.90f, "Swift Pair, Continuity, Nearby Share"),
                e(SignalCatalog.BT_CLASSIC, 0.85f),
                e(SignalCatalog.PROPRIETARY_24, 0.35f, "USB dongle mouse/keyboard link"),
                e(SignalCatalog.WIFI_AWARE, 0.20f),
            ),
            bleCompanyIds = listOf(0x0006, 0x004C, 0x00E0),
            btClassMajor = MAJOR_COMPUTER,
            mdnsTypes = listOf("_companion-link._tcp", "_ssh._tcp", "_workstation._tcp", "_smb._tcp"),
        ),

        DeviceClassProfile(
            label = "smartphone",
            displayName = "Smartphone",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.95f),
                e(SignalCatalog.BLE, 0.95f),
                e(SignalCatalog.BT_CLASSIC, 0.80f),
                e(SignalCatalog.LTE, 0.85f, "Uplink only - the phone cannot hear another handset's uplink"),
                e(SignalCatalog.NR_SUB6, 0.70f),
                e(SignalCatalog.NFC, 0.80f),
                e(SignalCatalog.UWB, 0.25f),
            ),
            bleCompanyIds = listOf(0x004C, 0x00E0, 0x0075),
            btClassMajor = MAJOR_PHONE,
        ),

        DeviceClassProfile(
            label = "smartwatch",
            displayName = "Smartwatch / Wearable",
            expectations = listOf(
                e(SignalCatalog.BLE, 0.98f),
                e(SignalCatalog.BT_CLASSIC, 0.40f),
                e(SignalCatalog.WIFI_STA, 0.55f),
                e(SignalCatalog.LTE, 0.25f, "Cellular-model watches only"),
                e(SignalCatalog.NFC, 0.60f, "Contactless payment"),
            ),
            bleCompanyIds = listOf(0x004C, 0x00E0, 0x0075, 0x0157),
            btClassMajor = MAJOR_WEARABLE,
        ),

        DeviceClassProfile(
            label = "smart_thermostat",
            displayName = "Thermostat / HVAC Controller",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.85f),
                e(SignalCatalog.BLE, 0.80f),
                e(SignalCatalog.THREAD, 0.50f),
                e(SignalCatalog.ZIGBEE, 0.25f),
                e(SignalCatalog.PROPRIETARY_24, 0.20f, "Some systems use a proprietary link to the air handler"),
            ),
            mdnsTypes = listOf("_hap._tcp", "_matter._tcp"),
        ),

        DeviceClassProfile(
            label = "smart_bulb",
            displayName = "Smart Bulb / Light Fixture",
            expectations = listOf(
                e(SignalCatalog.ZIGBEE, 0.55f, "Hue and most hub-based lighting - not verifiable"),
                e(SignalCatalog.BLE, 0.60f),
                e(SignalCatalog.WIFI_STA, 0.35f),
                e(SignalCatalog.THREAD, 0.30f),
            ),
        ),

        DeviceClassProfile(
            label = "iot_hub",
            displayName = "Smart Home Hub / Bridge",
            expectations = listOf(
                e(SignalCatalog.WIFI_STA, 0.80f),
                e(SignalCatalog.ZIGBEE, 0.75f, "The whole point of the device - and invisible to this phone"),
                e(SignalCatalog.THREAD, 0.60f),
                e(SignalCatalog.ZWAVE, 0.45f),
                e(SignalCatalog.BLE, 0.85f),
            ),
            mdnsTypes = listOf("_hue._tcp", "_hap._tcp", "_matter._tcp", "_smartthings._tcp"),
        ),

        DeviceClassProfile(
            label = "vehicle",
            displayName = "Vehicle",
            expectations = listOf(
                e(SignalCatalog.BT_CLASSIC, 0.90f, "Hands-free and A2DP head unit"),
                e(SignalCatalog.BLE, 0.85f, "Phone-as-key"),
                e(SignalCatalog.ISM_SUBGHZ, 0.95f, "TPMS at 315/433 MHz plus the key fob - not verifiable"),
                e(SignalCatalog.LTE, 0.60f, "Embedded telematics modem"),
                e(SignalCatalog.WIFI_STA, 0.45f, "In-car hotspot, sometimes a full AP"),
                e(SignalCatalog.UWB, 0.30f, "CCC Digital Key 3.0"),
            ),
            btClassMajor = MAJOR_AUDIO_VIDEO,
        ),

        DeviceClassProfile(
            label = "satellite_dish",
            displayName = "Satellite Dish / VSAT",
            expectations = listOf(
                e(SignalCatalog.SAT_DOWNLINK, 0.95f, "Receive-only 10-14 GHz - not verifiable"),
                e(SignalCatalog.WIFI_BE, 0.55f, "Starlink routers are Wi-Fi 6/7 APs - that part IS verifiable"),
                e(SignalCatalog.WIFI_AX, 0.60f),
            ),
        ),

        DeviceClassProfile(
            label = "antenna_mast",
            displayName = "Antenna / Mast (unclassified)",
            expectations = listOf(
                e(SignalCatalog.LTE, 0.50f),
                e(SignalCatalog.NR_SUB6, 0.40f),
                e(SignalCatalog.ISM_SUBGHZ, 0.30f, "Could be anything from LoRa to public-safety - not verifiable"),
                e(SignalCatalog.SAT_DOWNLINK, 0.20f),
            ),
        ),
    )

    private val byLabel: Map<String, DeviceClassProfile> = entries.associateBy { it.label }

    fun forLabel(label: String): DeviceClassProfile? = byLabel[label]

    /** Every label the classifier is expected to emit — handy for validating a new model. */
    val labels: List<String> = entries.map { it.label }
}
