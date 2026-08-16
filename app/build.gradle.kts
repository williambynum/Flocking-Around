plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.pixel9.signalsurvey"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pixel9.signalsurvey"
        // 33 (Android 13) is the floor for NEARBY_WIFI_DEVICES, ScanResult.getWifiSsid()
        // and WIFI_STANDARD_11BE. Target hardware is a Pixel 9 on 14/15, so dropping
        // below 33 buys nothing and costs a lot of version branching.
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Required, not cosmetic. The Anthropic SDK pulls in Jackson, which is enormous
            // and almost entirely unused here — unminified it adds ~28 MB of dex. R8 strips it
            // back down. The SDK, ML Kit and ARCore all ship their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // ML Kit's libmlkitcommonpipeline.so is 6-12 MB per ABI and ships for four of them,
    // which is ~30 MB of dead weight in any APK destined for a Pixel. Splitting produces a
    // lean arm64 APK for the target hardware and keeps a universal one for emulators.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(project(":survey"))
    implementation(project(":model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
