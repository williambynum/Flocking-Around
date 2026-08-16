plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pixel9.signalsurvey.export"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ReportBuilder is pure string generation over pure-data models, so it can be exercised
    // on the JVM without a device. The stubbed android.jar returns defaults for the handful
    // of platform types the models hold (Rect, Matrix).
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    // JSON is org.json from the platform — no dependency, no serialization plugin.

    testImplementation("junit:junit:4.13.2")
    // org.json is stubbed in android.jar; the real implementation is needed for the
    // SessionJson round-trip in tests.
    testImplementation("org.json:json:20240303")
}
