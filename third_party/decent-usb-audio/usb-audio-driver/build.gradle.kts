// Vendored from decent-player (MIT, see ../LICENSE and ../README.md).
// LaHo changes: compileSdk/Java aligned with the app, version-catalog deps,
// libFLAC taken from the xiph/flac submodule, consumer keep rules for JNI.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild { cmake { cppFlags("") } }
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
