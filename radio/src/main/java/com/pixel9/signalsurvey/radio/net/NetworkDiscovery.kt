package com.pixel9.signalsurvey.radio.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * mDNS / DNS-SD service discovery.
 *
 * This is the answer to the app's biggest blind spot. Android has no Wi-Fi monitor mode, so
 * a smart speaker, TV or camera sitting on the same network is completely invisible to a
 * Wi-Fi scan — it is a client, not an AP. Service discovery is how those devices get
 * identified at all, and the service name usually carries the room name the owner chose,
 * which is far better identity than anything RF provides.
 *
 * Services are deliberately *not* resolved to host/port. Resolution serialises badly on
 * Android and the IP address adds nothing here: the service type plus the advertised name is
 * what the ontology matches on.
 */
class NetworkDiscovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    fun isAvailable(): Boolean = nsd != null

    /**
     * Discover the curated service types in parallel.
     *
     * Enumerating `_services._dns-sd._udp` first would be more complete, but it needs a
     * second discovery round per discovered type and routinely takes longer than a capture
     * window allows. A fixed list covers essentially everything consumer.
     */
    fun observe(): Flow<List<RadioObservation>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        SERVICE_TYPES.forEach { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    trySend(listOf(info.toObservation()))
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.d(TAG, "discovery start failed for $serviceType ($errorCode)")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            listeners += listener
            runCatching {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.d(TAG, "could not start discovery for $type") }
        }

        awaitClose {
            listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        }
    }

    private fun NsdServiceInfo.toObservation(): RadioObservation {
        val now = SystemClock.elapsedRealtime()
        val type = serviceType.orEmpty().trim('.')
        val name = serviceName.orEmpty()

        return RadioObservation(
            key = "mdns:$name:$type",
            family = RadioFamily.NETWORK_SERVICE,
            displayName = name.ifBlank { type },
            vendor = SERVICE_VENDORS[normalizeType(type)],
            standard = "mDNS / DNS-SD",
            bandLabel = "over Wi-Fi (client)",
            firstSeenElapsedMs = now,
            lastSeenElapsedMs = now,
            extras = mapOf(
                "serviceType" to normalizeType(type),
                "serviceName" to name,
                "note" to "Discovered over IP, not off the air - the device is a Wi-Fi client",
            ),
        )
    }

    private fun normalizeType(raw: String): String {
        // The framework hands back types in varying shapes: "_googlecast._tcp.", "_tcp.local."
        val cleaned = raw.removeSuffix(".local").trim('.')
        return SERVICE_TYPES.firstOrNull { cleaned.contains(it.trim('.').substringBefore("._")) }
            ?: cleaned
    }

    companion object {
        private const val TAG = "NetworkDiscovery"

        /** Curated for consumer and prosumer coverage. Add freely. */
        val SERVICE_TYPES = listOf(
            "_googlecast._tcp",       // Chromecast, Android TV, Nest speakers/displays
            "_airplay._tcp",          // Apple TV, AirPlay speakers, some smart TVs
            "_raop._tcp",             // AirPlay audio endpoints
            "_spotify-connect._tcp",
            "_sonos._tcp",
            "_hap._tcp",              // HomeKit accessory protocol
            "_matter._tcp",           // Matter operational
            "_matterc._udp",          // Matter commissionable
            "_meshcop._udp",          // Thread border router
            "_ipp._tcp",              // printers
            "_ipps._tcp",
            "_pdl-datastream._tcp",
            "_printer._tcp",
            "_rtsp._tcp",             // IP cameras, NVRs
            "_axis-video._tcp",
            "_dial._tcp",             // smart TV app launch
            "_viziocast._tcp",
            "_amzn-wplay._tcp",       // Fire TV
            "_companion-link._tcp",   // Apple Continuity
            "_ssh._tcp",
            "_smb._tcp",
            "_workstation._tcp",
            "_http._tcp",
            "_hue._tcp",              // Philips Hue bridge
            "_nvstream._tcp",         // NVIDIA GameStream
        )

        private val SERVICE_VENDORS = mapOf(
            "_googlecast._tcp" to "Google",
            "_airplay._tcp" to "Apple",
            "_raop._tcp" to "Apple",
            "_companion-link._tcp" to "Apple",
            "_amzn-wplay._tcp" to "Amazon",
            "_sonos._tcp" to "Sonos",
            "_hue._tcp" to "Signify (Philips Hue)",
            "_viziocast._tcp" to "Vizio",
            "_axis-video._tcp" to "Axis",
            "_nvstream._tcp" to "NVIDIA",
        )
    }
}
