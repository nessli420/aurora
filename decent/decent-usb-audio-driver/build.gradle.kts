// Vendored from github.com/Ma145/decent-player (MIT). Build settings realigned to Aurora's
// toolchain (compileSdk 35 / minSdk 26 / NDK 27 / JDK 17) and the Kotlin plugin applied explicitly.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.decent.usbaudio"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        externalNativeBuild { cmake { cppFlags("") } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake { path("src/main/jni/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
