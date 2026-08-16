package com.pixel9.signalsurvey.ar

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.util.EnumSet

/**
 * Owns the ARCore [Session] lifecycle.
 *
 * Two things here matter more than they look:
 *
 * 1. **CPU image resolution.** ARCore defaults to a small CPU stream (often 640x480), which
 *    starves the classifier. The config has to be chosen *before* the first resume.
 * 2. **Depth mode.** The base Pixel 9 has no ToF sensor, so depth comes from motion and
 *    needs parallax before it converges. That is why the capture flow makes the operator
 *    sweep first — see [ArmingState].
 */
class ArSessionController(private val appContext: Context) {

    var session: Session? = null
        private set

    var lastFailure: String? = null
        private set

    var depthSupported: Boolean = false
        private set

    /** Call from the Activity. Returns false when ARCore asked to install/update itself. */
    fun createSession(activity: Activity, userRequestedInstall: Boolean): Boolean {
        if (session != null) return true
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, userRequestedInstall)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return false
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val s = Session(activity)
            configure(s)
            selectHighestCpuImageConfig(s)
            session = s
            lastFailure = null
            return true
        } catch (e: UnavailableArcoreNotInstalledException) {
            lastFailure = "Google Play Services for AR is not installed."
        } catch (e: UnavailableApkTooOldException) {
            lastFailure = "Google Play Services for AR is out of date."
        } catch (e: UnavailableSdkTooOldException) {
            lastFailure = "This app is built against an ARCore SDK that is too old."
        } catch (e: UnavailableDeviceNotCompatibleException) {
            lastFailure = "This device does not support ARCore."
        } catch (e: Exception) {
            lastFailure = "Failed to create the AR session: ${e.message}"
        }
        Log.e(TAG, lastFailure ?: "unknown AR failure")
        return false
    }

    private fun configure(s: Session) {
        depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        val config = s.config.apply {
            // Depth gives metric distance to every annotated device. Without it we fall
            // back to plane hits and RSSI estimates, which are far weaker.
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED

            // Ceiling APs and wall-mounted cameras rarely land on a detected plane, so
            // instant placement is the difference between an anchor and no anchor.
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP

            // Nothing is lit by the scene — every annotation is 2D. Turning estimation off
            // is free GPU headroom for the classifier.
            lightEstimationMode = Config.LightEstimationMode.DISABLED

            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL

            // Never hand back a stale frame: a capture must correspond to what was on screen.
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }
        s.configure(config)
    }

    /**
     * Pick the camera config with the largest CPU image. On a Pixel 9 this moves the
     * classifier input from 640x480 to 1280x960, which is the difference between reading a
     * router across a room and not.
     */
    private fun selectHighestCpuImageConfig(s: Session) {
        runCatching {
            val filter = CameraConfigFilter(s)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            val best = s.getSupportedCameraConfigs(filter)
                .maxByOrNull { it.imageSize.width * it.imageSize.height }
            if (best != null) {
                s.cameraConfig = best
                Log.i(TAG, "CPU image stream: ${best.imageSize.width}x${best.imageSize.height}")
            }
        }.onFailure { Log.w(TAG, "Could not raise the CPU image resolution: ${it.message}") }
    }

    /** @return true when the session actually resumed. */
    fun resume(): Boolean {
        val s = session ?: return false
        return try {
            s.resume(); true
        } catch (e: CameraNotAvailableException) {
            lastFailure = "The camera is in use by another app."
            session = null
            false
        }
    }

    fun pause() { session?.pause() }

    fun close() {
        session?.close()
        session = null
    }

    private companion object { const val TAG = "ArSessionController" }
}
