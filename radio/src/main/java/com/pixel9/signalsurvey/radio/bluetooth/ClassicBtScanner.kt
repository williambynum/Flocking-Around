package com.pixel9.signalsurvey.radio.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.radio.OuiLookup
import com.pixel9.signalsurvey.radio.pathLossRangeM
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Bluetooth BR/EDR inquiry.
 *
 * Worth running despite its limits, because unlike a BLE advertisement a classic device
 * states its Class of Device outright — "Audio/Video", "Imaging", "Peripheral" — which is a
 * category the ontology can match directly against a visual class.
 *
 * Two costs, which is why this only runs inside the capture listen window rather than
 * continuously:
 *
 * - It only finds devices that are currently *discoverable*, which most are not.
 * - Inquiry saturates the 2.4 GHz radio, degrading concurrent BLE scanning and Wi-Fi.
 */
class ClassicBtScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun isAvailable(): Boolean = adapter != null && adapter.isEnabled && hasPermission()

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun discover(): Flow<RadioObservation> = callbackFlow {
        val a = adapter
        if (a == null || !isAvailable()) {
            awaitClose { }
            return@callbackFlow
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_FOUND) return

                val device: BluetoothDevice = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                ) ?: return
                val rssi = intent.getShortExtra(
                    BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE
                ).toInt()
                val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)

                val btClass = device.bluetoothClass
                val majorLabel = btClass?.majorDeviceClass
                    ?.let { BtAssignedNumbers.majorClassLabel(it) }

                val now = SystemClock.elapsedRealtime()
                trySend(
                    RadioObservation(
                        key = device.address,
                        family = RadioFamily.BLUETOOTH,
                        displayName = name?.takeIf { it.isNotBlank() }
                            ?: majorLabel
                            ?: "BR/EDR device",
                        vendor = OuiLookup.vendorFor(device.address),
                        rssiDbm = rssi.takeIf { it != Short.MIN_VALUE.toInt() },
                        estimatedRangeM = rssi.takeIf { it != Short.MIN_VALUE.toInt() }
                            ?.let { pathLossRangeM(it, CLASSIC_TX_DBM, 2440) },
                        standard = "Bluetooth BR/EDR",
                        bandLabel = "2.4 GHz (79 ch FHSS)",
                        freqHz = 2_440_000_000L,
                        firstSeenElapsedMs = now,
                        lastSeenElapsedMs = now,
                        extras = buildMap {
                            majorLabel?.let { put("btClass", it) }
                            btClass?.deviceClass?.let { put("btDeviceClass", "0x%06X".format(it)) }
                            put("discoveryMode", "BR/EDR inquiry")
                        },
                    )
                )
            }
        }

        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        runCatching {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        }

        awaitClose {
            runCatching { a.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        /** Class 2 devices, by far the most common, are nominally +4 dBm. */
        const val CLASSIC_TX_DBM = 4
    }
}
