plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pixel9.signalsurvey.vision"
    compileSdk = 35
    defaultConfig { minSdk = 33 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":model"))
    implementation(libs.mlkit.objectdetection)
    implementation(libs.mlkit.objectdetection.custom)
    implementation(libs.mlkit.imagelabeling)

    // Cloud enrichment — used only when the operator opts in at runtime.
    implementation(libs.anthropic.java)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
