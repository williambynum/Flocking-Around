plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pixel9.signalsurvey.ar"
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
    // ARCore only. The camera background is rendered by our own GLSurfaceView renderer
    // (see BackgroundRenderer.kt) rather than SceneView/Filament: this app draws zero 3D
    // content, so a full scene graph would add ~8 MB and a version-coupling headache for
    // one textured quad.
    api(libs.arcore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
