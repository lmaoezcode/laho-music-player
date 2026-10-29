// Vendored from decent-player (MIT, see ../LICENSE and ../README.md).
// LaHo changes: ported to Media3 1.11 (AudioSinkConfig), SFTP data source and
// its JSch / cache dependencies removed, version-catalog deps.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.decent.usbaudio.media3"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":decent-usb-audio-driver"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    // LaHo: UsbAudioSink.signalPath is a StateFlow
    api(libs.org.jetbrains.kotlinx.coroutines.core)
}
