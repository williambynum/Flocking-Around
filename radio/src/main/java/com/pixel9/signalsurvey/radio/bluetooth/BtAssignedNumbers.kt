package com.pixel9.signalsurvey.radio.bluetooth

import android.bluetooth.le.ScanResult

/**
 * The subset of the Bluetooth SIG Assigned Numbers registry that actually identifies
 * consumer hardware in the wild.
 *
 * This is what turns "BLE advertiser at -63 dBm" into "Google Fast Pair accessory" on an
 * annotation card, and it is the strongest evidence the fusion engine has short of a Wi-Fi
 * OUI match.
 *
 * For production, import the SIG's full company-identifier and 16-bit UUID CSVs into a Room
 * table at build time — this table is curated for recognisability, not coverage.
 */
object BtAssignedNumbers {

    // --- Service UUID prefixes (16-bit, expanded to the base UUID) --------------

    const val UUID_BROADCAST_AUDIO = "00001852"   // Auracast broadcast announcement
    private const val UUID_BASIC_AUDIO = "00001853"
    private const val UUID_FAST_PAIR = "0000fe2c" // Google Fast Pair
    private const val UUID_EDDYSTONE = "0000feaa" // Eddystone / Google Find My Device network
    private const val UUID_TILE = "0000feed"
    private const val UUID_SAMSUNG_TAG = "0000fd5a"
    private const val UUID_APPLE_CONTINUITY = "0000fd44"
    private const val UUID_MS_SWIFT_PAIR = "0000fe07"
    private const val UUID_NORDIC_DFU = "0000fe59"
    private const val UUID_XIAOMI = "0000fe95"
    private const val UUID_EXPOSURE_NOTIF = "0000fd6f"
    private const val UUID_MATTER = "0000fff6"    // Matter commissionable node
    private const val UUID_HEART_RATE = "0000180d"
    private const val UUID_BATTERY = "0000180f"
    private const val UUID_HID = "00001812"
    private const val UUID_MESH_PROV = "00001827"
    private const val UUID_MESH_PROXY = "00001828"

    private val serviceIdentities: List<Pair<String, String>> = listOf(
        UUID_BROADCAST_AUDIO to "Auracast broadcast source",
        UUID_BASIC_AUDIO to "LE Audio device",
        UUID_FAST_PAIR to "Google Fast Pair accessory",
        UUID_EDDYSTONE to "Eddystone / Find My Device beacon",
        UUID_TILE to "Tile tracker",
        UUID_SAMSUNG_TAG to "Samsung SmartTag",
        UUID_APPLE_CONTINUITY to "Apple Continuity device",
        UUID_MS_SWIFT_PAIR to "Microsoft Swift Pair accessory",
        UUID_NORDIC_DFU to "Nordic-based device (DFU mode)",
        UUID_XIAOMI to "Xiaomi / Mijia device",
        UUID_EXPOSURE_NOTIF to "Exposure Notification beacon",
        UUID_MATTER to "Matter commissionable device",
        UUID_HEART_RATE to "Heart rate monitor",
        UUID_HID to "Bluetooth HID (keyboard / mouse / controller)",
        UUID_MESH_PROV to "Bluetooth Mesh node (unprovisioned)",
        UUID_MESH_PROXY to "Bluetooth Mesh proxy",
        UUID_BATTERY to "Battery-reporting peripheral",
    )

    // --- Company identifiers ----------------------------------------------------

    private val companies: Map<Int, String> = mapOf(
        0x0006 to "Microsoft",
        0x000F to "Broadcom",
        0x0075 to "Samsung",
        0x004C to "Apple",
        0x00E0 to "Google",
        0x0087 to "Garmin",
        0x00D2 to "Dialog Semiconductor",
        0x0117 to "Fitbit",
        0x0157 to "Huami / Amazfit",
        0x0171 to "Amazon",
        0x0180 to "Bose",
        0x01D7 to "Anker",
        0x02E5 to "Espressif",
        0x0499 to "Ruuvi",
        0x0059 to "Nordic Semiconductor",
        0x000D to "Texas Instruments",
        0x0131 to "Cypress / Infineon",
        0x0310 to "SGL Italia",
        0x0118 to "Sony",
        0x001D to "Qualcomm",
        0x0072 to "Logitech",
        0x038F to "Xiaomi",
        0x0644 to "Tile",
        0x02FF to "Silicon Labs",
        0x004F to "Bang & Olufsen",
        0x0107 to "Polar",
        0x0154 to "Sonos",
        0x0201 to "Gopro",
        0x0822 to "Adafruit",
    )

    fun companyName(id: Int): String? = companies[id]

    /** Best-effort human identity from the advertisement alone. */
    fun identify(companyId: Int?, serviceUuids: List<String>): String? {
        serviceIdentities.firstOrNull { (prefix, _) ->
            serviceUuids.any { it.startsWith(prefix) }
        }?.let { return it.second }

        return when (companyId) {
            0x004C -> "Apple device (Continuity advertisement)"
            0x00E0 -> "Google device"
            0x0075 -> "Samsung device"
            0x0006 -> "Microsoft device"
            0x0171 -> "Amazon device"
            else -> null
        }
    }

    fun phyLabel(phy: Int): String = when (phy) {
        ScanResult.PHY_UNUSED -> "unused"
        1 -> "LE 1M"      // BluetoothDevice.PHY_LE_1M
        2 -> "LE 2M"      // BluetoothDevice.PHY_LE_2M
        3 -> "LE Coded"   // BluetoothDevice.PHY_LE_CODED (long range)
        else -> "PHY $phy"
    }

    /**
     * Bluetooth Class of Device major class. Unlike BLE advertisements, BR/EDR devices state
     * their category outright, which makes classic discovery unusually informative when a
     * device happens to be discoverable.
     */
    fun majorClassLabel(major: Int): String = when (major) {
        0x0000 -> "Miscellaneous"
        0x0100 -> "Computer"
        0x0200 -> "Phone"
        0x0300 -> "Network access point"
        0x0400 -> "Audio / video"
        0x0500 -> "Peripheral (keyboard, mouse, controller)"
        0x0600 -> "Imaging (printer, scanner, camera)"
        0x0700 -> "Wearable"
        0x0800 -> "Toy"
        0x0900 -> "Health"
        else -> "Uncategorised"
    }
}
