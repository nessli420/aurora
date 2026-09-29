import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseTag = providers.gradleProperty("releaseTag").orNull
require(releaseTag == null || releaseTag.removePrefix("V").removePrefix("v") == appVersion.getProperty("versionName")) {
    "The release tag must match versionName in version.properties."
}

val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProperties.getProperty(key) ?: System.getenv(env)
val releaseKeystore = rootProject.file(signingValue("storeFile", "AURORA_KEYSTORE_FILE") ?: "keystore/aurora-release.jks")
val releaseStorePassword = signingValue("storePassword", "AURORA_KEYSTORE_PASSWORD")
val canSignRelease = releaseKeystore.exists() && releaseStorePassword != null

android {
    namespace = "com.aurora.music"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.aurora.music"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Native AcoustID/Chromaprint fingerprinter (4.4c). arm64 for the phone, x86_64 for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (canSignRelease) create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePassword
            keyAlias = signingValue("keyAlias", "AURORA_KEY_ALIAS") ?: "aurora"
            keyPassword = signingValue("keyPassword", "AURORA_KEY_PASSWORD") ?: releaseStorePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (canSignRelease) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    testBuildType = "release"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val composeStability = rootProject.layout.projectDirectory.file("compose-stability.conf")
composeCompiler {
    stabilityConfigurationFiles.add(composeStability)
}
// the compose plugin does not track this file, so a change would otherwise leave stale incremental output
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    inputs.file(composeStability).withPropertyName("composeStability").withPathSensitivity(PathSensitivity.NONE)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":extension-sdk"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation(libs.lottie.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation("androidx.media3:media3-exoplayer-dash:${libs.versions.media3.get()}")
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.newpipeextractor)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.jaudiotagger)
    // Experimental USB bit-perfect audio driver (vendored decent-player, MIT).
    implementation(project(":decent-usb-audio-wrapper-media3"))
    // FFmpeg decoder (Jellyfin build) — float32 output for all formats, required for bit-perfect
    // non-local / non-FLAC content through the USB driver. Picked up by EXTENSION_RENDERER_MODE_PREFER.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
    debugImplementation(libs.androidx.ui.tooling)
}

// A tiny block-JNI comparison belongs to instrumentation, not the shipped audio engine.
val precisionBenchmarkJni = layout.buildDirectory.dir("generated/precisionBenchmarkJniLibs")
android.sourceSets.getByName("androidTest").jniLibs.srcDir(precisionBenchmarkJni)
val compilePrecisionBenchmarkJni by tasks.registering {
    val source = layout.projectDirectory.file("src/androidTest/cpp/precision_benchmark.cpp")
    inputs.file(source)
    outputs.dir(precisionBenchmarkJni)
    doLast {
        val host = when {
            System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
            System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
            else -> "linux-x86_64"
        }
        val llvm = android.sdkDirectory.resolve("ndk/${android.ndkVersion}/toolchains/llvm/prebuilt/$host")
        val compiler = llvm.resolve("bin/clang++" + if (host.startsWith("windows")) ".exe" else "")
        mapOf("arm64-v8a" to "aarch64-linux-android26", "x86_64" to "x86_64-linux-android26").forEach { (abi, target) ->
            val output = precisionBenchmarkJni.get().dir(abi).asFile.apply { mkdirs() }
                .resolve("libaurora_precision_benchmark.so")
            exec {
                commandLine(compiler.absolutePath, "--target=$target", "--sysroot=${llvm.resolve("sysroot")}",
                    "-shared", "-fPIC", "-O3", "-fno-fast-math", "-ffp-contract=off", "-static-libstdc++",
                    source.asFile.absolutePath, "-o", output.absolutePath)
            }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestJniLibFolders") }
    .configureEach { dependsOn(compilePrecisionBenchmarkJni) }
