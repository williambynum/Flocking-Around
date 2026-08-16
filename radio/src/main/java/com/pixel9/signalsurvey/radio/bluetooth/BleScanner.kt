package com.pixel9.signalsurvey.radio.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.radio.RadioSource
import com.pixel9.signalsurvey.radio.pathLossRangeM
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Bluetooth LE advertisement sweep.
 *
 * Unfiltered and in low-latency mode, because the point is to hear everything. In a typical
 * home this returns 20-60 distinct advertisers; in an office, hundreds.
 *
 * The awkward part is identity. Privacy-aware devices rotate their address roughly every
 * fifteen minutes, so the MAC is not a durable key across a long survey. Service UUIDs plus
 * the manufacturer company ID are — see [RadioObservation.fingerprint], which the session
 * merger keys off instead.
 */
class BleScanner(private val context: Context) : RadioSource {

    override val name = "Bluetooth LE"

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    override fun isAvailable(): Boolean =
        adapter != null &&
            adapter.isEnabled &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
            hasPermission()

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun observe(): Flow<List<RadioObservation>> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !isAvailable()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            // setLegacy(false) is what surfaces Bluetooth 5 extended advertising, including
            // Auracast broadcast sources and long-range Coded PHY beacons.
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .setReportDelay(0)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(listOf(result.toObservation()))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                trySend(results.map { it.toObservation() })
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed, code $errorCode")
            }
        }

        runCatching { scanner.startScan(emptyList(), settings, callback) }
            .onFailure { Log.w(TAG, "startScan threw", it) }

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toObservation(): RadioObservation {
        val record = scanRecord
        val now = SystemClock.elapsedRealtime()

        val companyId: Int? = record?.manufacturerSpecificData?.let {
            if (it.size() > 0) it.keyAt(0) else null
        }
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()
        val serviceDataUuids = record?.serviceData?.keys?.map {
            it.uuid.toString().lowercase()
        }.orEmpty()
        val allUuids = (serviceUuids + serviceDataUuids).distinct()

        val vendor = companyId?.let { BtAssignedNumbers.companyName(it) }
        val guessedRole = BtAssignedNumbers.identify(companyId, allUuids)

        val phy = buildString {
            append(BtAssignedNumbers.phyLabel(primaryPhy))
            if (secondaryPhy != ScanResult.PHY_UNUSED) {
                append(" + ").append(BtAssignedNumbers.phyLabel(secondaryPhy))
            }
        }

        val txPower = if (txPower != ScanResult.TX_POWER_NOT_PRESENT) txPower else null

        val extras = buildMap {
            put("phy", phy)
            put("connectable", isConnectable.toString())
            put("legacy", isLegacy.toString())
            put("extendedAdv", (!isLegacy).toString())
            put("dataStatus", dataStatusLabel(dataStatus))
            txPower?.let { put("txPowerDbm", it.toString()) }
            if (advertisingSid != ScanResult.SID_NOT_PRESENT) {
                put("advertisingSid", advertisingSid.toString())
            }
            if (periodicAdvertisingInterval != ScanResult.PERIODIC_INTERVAL_NOT_PRESENT) {
                put("periodicAdvMs", "%.2f".format(periodicAdvertisingInterval * 1.25))
            }
            companyId?.let { put("companyId", "0x%04X".format(it)) }
            if (allUuids.isNotEmpty()) put("services", allUuids.joinToString(","))
            if (allUuids.any { it.startsWith(BtAssignedNumbers.UUID_BROADCAST_AUDIO) }) {
                put("auracast", "true")
            }
            guessedRole?.let { put("identifiedAs", it) }
            put("addressRandom", isRandomAddress(device.address).toString())
            record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
                ?.let { put("advTxPowerDbm", it.toString()) }
        }

        val displayName = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: guessedRole
            ?: vendor?.let { "$it device" }
            ?: "BLE advertiser"

        return RadioObservation(
            key = device.address,
            family = RadioFamily.BLUETOOTH,
            displayName = displayName,
            vendor = vendor,
            rssiDbm = rssi,
            estimatedRangeM = pathLossRangeM(rssi, txPower ?: BLE_ASSUMED_TX_DBM, BLE_FREQ_MHZ),
            standard = if (isLegacy) "Bluetooth LE (legacy adv)" else "Bluetooth LE 5.x (extended adv)",
            bandLabel = "2.4 GHz",
            freqHz = 2_440_000_000L,
            firstSeenElapsedMs = now,
            lastSeenElapsedMs = now,
            extras = extras,
        )
    }

    /**
     * Address type from the top two bits of the most significant octet, per Core Spec Vol 6.
     * `BluetoothDevice.getAddressType()` is not public API, so this heuristic is the
     * available option: 0b11 static random, 0b01 resolvable private, 0b00 non-resolvable.
     */
    private fun isRandomAddress(address: String?): Boolean {
        val firstOctet = address?.substringBefore(':')?.toIntOrNull(16) ?: return false
        return (firstOctet and 0xC0) != 0x00 || (firstOctet and 0xC0) == 0xC0
    }

    private fun dataStatusLabel(status: Int): String = when (status) {
        ScanResult.DATA_COMPLETE -> "complete"
        ScanResult.DATA_TRUNCATED -> "truncated"
        else -> "unknown"
    }

    private companion object {
        const val TAG = "BleScanner"
        const val BLE_FREQ_MHZ = 2440
        /** Typical beacon output when the advertisement omits tx power. */
        const val BLE_ASSUMED_TX_DBM = -12
    }
}
