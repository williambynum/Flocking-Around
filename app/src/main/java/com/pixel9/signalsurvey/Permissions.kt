package com.pixel9.signalsurvey

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * What the app asks for, and what it does without.
 *
 * Only the camera is genuinely required — ARCore is the spine of the whole app. Everything
 * else degrades a specific capability, and the UI says which, rather than nagging.
 */
object Permissions {

    /** Without this there is no AR session and therefore no app. */
    const val REQUIRED_CAMERA = Manifest.permission.CAMERA

    /**
     * Fine location is not optional in practice: the framework redacts Wi-Fi scan results,
     * BLE results used for positioning, and cell identity without it.
     */
    val CORE = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.READ_PHONE_STATE,
    )

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> = CORE.filterNot { granted(context, it) }

    fun hasCamera(context: Context): Boolean = granted(context, REQUIRED_CAMERA)

    /** Human-readable consequences, shown next to the request rather than after a denial. */
    fun consequenceOf(permission: String): String = when (permission) {
        Manifest.permission.CAMERA -> "Required - the AR session cannot start without it"
        Manifest.permission.ACCESS_FINE_LOCATION ->
            "Wi-Fi and Bluetooth results are redacted by the OS without it"
        Manifest.permission.NEARBY_WIFI_DEVICES -> "No Wi-Fi access point survey"
        Manifest.permission.BLUETOOTH_SCAN -> "No Bluetooth LE or BR/EDR detection"
        Manifest.permission.BLUETOOTH_CONNECT -> "Reduced Bluetooth device detail"
        Manifest.permission.READ_PHONE_STATE -> "No cellular cell information"
        else -> ""
    }
}
