package com.pixel9.signalsurvey.radio.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.pixel9.signalsurvey.model.GeoFix
import com.pixel9.signalsurvey.model.SatelliteFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GNSS satellites in view.
 *
 * Unlike every other radio here, GNSS hands you a *direction*: azimuth and elevation per
 * satellite. With a heading fix from [com.pixel9.signalsurvey.ar.HeadingResolver] that makes
 * satellites the only emitters the app can place on a photo exactly, with no inference at
 * all — mark the sky and the marks are correct.
 *
 * The Pixel 9 is dual-frequency (L1 + L5), so expect two entries per satellite: 1575.42 MHz
 * and 1176.45 MHz. Most people have never seen that made visible.
 */
class GnssScanner(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun isAvailable(): Boolean =
        locationManager != null &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS) &&
            hasPermission()

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun observe(): Flow<List<SatelliteFix>> = callbackFlow {
        val lm = locationManager
        if (lm == null || !hasPermission()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val fixes = (0 until status.satelliteCount).map { i ->
                    SatelliteFix(
                        constellation = constellationName(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        carrierFreqHz = if (status.hasCarrierFrequencyHz(i)) {
                            status.getCarrierFrequencyHz(i).toLong()
                        } else null,
                        cn0DbHz = status.getCn0DbHz(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        elevationDeg = status.getElevationDegrees(i),
                        usedInFix = status.usedInFix(i),
                    )
                }
                trySend(fixes)
            }
        }

        runCatching { lm.registerGnssStatusCallback(context.mainExecutor, callback) }

        // GNSS status only updates while something is actually requesting location.
        val locationListener = android.location.LocationListener { }
        runCatching {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1_000L, 0f,
                context.mainExecutor, locationListener,
            )
        }

        awaitClose {
            runCatching { lm.unregisterGnssStatusCallback(callback) }
            runCatching { lm.removeUpdates(locationListener) }
        }
    }

    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): GeoFix? {
        if (!hasPermission()) return null
        val lm = locationManager ?: return null
        val best = listOfNotNull(
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
        ).minByOrNull { it.accuracy } ?: return null

        return GeoFix(
            lat = best.latitude,
            lon = best.longitude,
            altM = if (best.hasAltitude()) best.altitude else null,
            accuracyM = best.accuracy,
        )
    }

    companion object {
        fun constellationName(type: Int): String = when (type) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_IRNSS -> "NavIC"
            else -> "Unknown"
        }
    }
}
