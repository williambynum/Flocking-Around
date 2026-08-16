package com.pixel9.signalsurvey.radio

import android.content.Context
import android.util.Log

/**
 * MAC prefix to vendor.
 *
 * An OUI match is the single strongest piece of evidence the fusion engine has: a class
 * prior says "routers usually do Wi-Fi", but an OUI says "*this* BSSID is made by the
 * company that makes the router you are looking at".
 *
 * The built-in table is curated for networking and IoT hardware. [loadFromAssets] will
 * overlay the full IEEE registry if `assets/oui.csv` is present — the file is about 1.5 MB
 * as `prefix,vendor` lines, which is worth shipping for a survey tool.
 */
object OuiLookup {

    private val builtIn: Map<String, String> = mapOf(
        // Networking
        "00:1A:11" to "Google",
        "3C:37:86" to "Netgear",
        "A0:63:91" to "Netgear",
        "94:9F:3E" to "Sonos",
        "F8:1A:67" to "TP-Link",
        "50:C7:BF" to "TP-Link",
        "AC:84:C6" to "TP-Link",
        "00:0B:86" to "HPE Aruba",
        "6C:F3:7F" to "HPE Aruba",
        "00:1B:54" to "Cisco",
        "F4:B5:2F" to "Juniper",
        "18:64:72" to "HPE Aruba",
        "E0:CB:BC" to "Ubiquiti",
        "24:5A:4C" to "Ubiquiti",
        "78:8A:20" to "Ubiquiti",
        "B4:FB:E4" to "Ubiquiti",
        "00:18:0A" to "Cisco Meraki",
        "88:15:44" to "Cisco Meraki",
        "2C:C8:1B" to "Ruckus",
        "C4:01:7C" to "Ruckus",
        "00:05:CA" to "Hitron",
        "9C:34:26" to "Arris / CommScope",
        "00:1D:CF" to "Arris / CommScope",
        "D4:04:CD" to "Arris / CommScope",
        "00:26:F2" to "Netgear",
        "20:E5:2A" to "Netgear",
        "44:94:FC" to "Netgear",
        "60:38:E0" to "Belkin / Linksys",
        "C0:56:27" to "Belkin / Linksys",
        "00:14:BF" to "Cisco-Linksys",
        "70:3A:CB" to "Google (Nest Wifi)",
        "F4:F5:D8" to "Google",
        "1C:F2:9A" to "Google",
        "30:FD:38" to "Google",
        "54:60:09" to "Google",
        "00:9A:CD" to "Huawei",
        "48:3C:0C" to "Huawei",
        "34:00:A3" to "Xiaomi",
        "64:09:80" to "Xiaomi",

        // IoT / consumer
        "B8:27:EB" to "Raspberry Pi Foundation",
        "DC:A6:32" to "Raspberry Pi Trading",
        "E4:5F:01" to "Raspberry Pi Trading",
        "24:0A:C4" to "Espressif (ESP32)",
        "8C:AA:B5" to "Espressif (ESP32)",
        "A4:CF:12" to "Espressif (ESP32)",
        "EC:FA:BC" to "Espressif (ESP32)",
        "50:02:91" to "Espressif (ESP8266)",
        "00:17:88" to "Signify (Philips Hue)",
        "EC:B5:FA" to "Signify (Philips Hue)",
        "18:B4:30" to "Google Nest",
        "64:16:66" to "Google Nest",
        "44:65:0D" to "Amazon",
        "68:37:E9" to "Amazon",
        "FC:65:DE" to "Amazon",
        "0C:47:C9" to "Amazon",
        "00:04:4B" to "NVIDIA",
        "48:B0:2D" to "NVIDIA",
        "AC:BC:32" to "Apple",
        "F0:18:98" to "Apple",
        "3C:15:C2" to "Apple",
        "00:16:6C" to "Samsung",
        "78:BD:BC" to "Samsung",
        "8C:79:F5" to "Samsung",
        "B0:72:BF" to "Murata (Wi-Fi modules)",
        "00:1E:C0" to "Microchip",
        "00:0D:6F" to "Ember / Silicon Labs",
        "00:12:4B" to "Texas Instruments",
        "54:2A:1B" to "Wyze",
        "2C:AA:8E" to "Wyze",
        "D0:3F:27" to "Ring",
        "00:62:6E" to "Ring",
        "9C:8E:CD" to "Ring / Amazon",
        "B0:C5:54" to "D-Link",
        "34:08:04" to "D-Link",
        "00:40:8C" to "Axis Communications",
        "AC:CC:8E" to "Axis Communications",
        "00:1C:14" to "Hikvision",
        "C0:56:E3" to "Hikvision",
        "00:12:12" to "Dahua",
    )

    @Volatile
    private var extended: Map<String, String>? = null

    /** Optional: overlay the full IEEE OUI registry from `assets/oui.csv`. */
    fun loadFromAssets(context: Context, assetName: String = "oui.csv") {
        if (extended != null) return
        runCatching {
            val map = HashMap<String, String>(40_000)
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val comma = line.indexOf(',')
                    if (comma in 1..line.lastIndex) {
                        map[normalize(line.substring(0, comma))] = line.substring(comma + 1).trim()
                    }
                }
            }
            extended = map
        }.onFailure {
            // Entirely expected when the asset is not shipped.
            Log.d(TAG, "No $assetName in assets; using the built-in OUI table")
        }
    }

    fun vendorFor(macAddress: String?): String? {
        val mac = macAddress ?: return null
        if (mac.length < 8) return null
        val prefix = normalize(mac.substring(0, 8))

        // A locally administered address (bit 1 of the first octet) is randomised, not a
        // manufacturer prefix — saying "Xerox" for a randomised MAC would be worse than
        // saying nothing.
        val firstOctet = mac.substringBefore(':').toIntOrNull(16)
        if (firstOctet != null && (firstOctet and 0x02) != 0) return null

        return extended?.get(prefix) ?: builtIn[prefix]
    }

    private fun normalize(prefix: String): String =
        prefix.uppercase().replace("-", ":").replace(".", ":").take(8)

    private const val TAG = "OuiLookup"
}
