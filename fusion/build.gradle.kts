plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pixel9.signalsurvey.fusion"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // The solvers are pure arithmetic over pure-data models, so they run on the JVM. The
    // stubbed android.jar returns defaults for the platform types the models hold (Rect,
    // Matrix); tests here must not depend on projection through CameraSnapshot.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
}
