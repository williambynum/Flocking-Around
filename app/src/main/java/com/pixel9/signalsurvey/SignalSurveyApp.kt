package com.pixel9.signalsurvey

import android.app.Application
import com.pixel9.signalsurvey.radio.OuiLookup

/**
 * No DI framework here on purpose.
 *
 * The dependency graph is a handful of singletons owned by one ViewModel, and Hilt would add
 * a KSP round to every module build for no structural benefit at this size. If the graph
 * grows a second entry point, swapping this for Hilt is mechanical.
 */
class SignalSurveyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Optional: overlays the full IEEE registry if assets/oui.csv is shipped.
        OuiLookup.loadFromAssets(this)
    }
}
