import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.jetbrains.navigation.compose)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.jetbrains.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.coil3.compose)
    implementation(libs.coil3.network.okhttp)
    implementation(libs.material.color.utilities)
    implementation(libs.jaudiotagger.jvm)
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    testImplementation(libs.junit)
}

val nativeSource = rootProject.layout.projectDirectory.dir("native")
val nativeBuildDir = layout.buildDirectory.dir("native")
val appResources = layout.buildDirectory.dir("appResources")

val configureNative by tasks.registering(Exec::class) {
    inputs.file(nativeSource.file("CMakeLists.txt"))
    outputs.file(nativeBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake", "-S", nativeSource.asFile.path, "-B", nativeBuildDir.get().asFile.path,
        "-G", "Visual Studio 17 2022", "-A", "x64",
    )
}

val buildNative by tasks.registering(Exec::class) {
    dependsOn(configureNative)
    inputs.dir(nativeSource)
    inputs.dir(rootProject.layout.projectDirectory.dir("app/src/main/cpp"))
    outputs.dir(nativeBuildDir.map { it.dir("bin") })
    commandLine("cmake", "--build", nativeBuildDir.get().asFile.path, "--config", "Release", "--parallel")
}

val syncNative by tasks.registering(Sync::class) {
    dependsOn(buildNative)
    from(nativeBuildDir.map { it.dir("bin") }) { include("*.dll") }
    into(appResources.map { it.dir("windows-x64") })
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(syncNative) }

tasks.test {
    dependsOn(syncNative)
    systemProperty("compose.application.resources.dir", appResources.get().dir("windows-x64").asFile.path)
}

compose.desktop {
    application {
        mainClass = "com.aurora.music.desktop.MainKt"
        jvmArgs += listOf("-XX:+UseZGC", "-XX:+ZGenerational", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Aurora"
            packageVersion = appVersion.getProperty("versionName")
            description = "Aurora music player"
            vendor = "Aurora"
            appResourcesRootDir.set(appResources)
            modules("java.management", "java.naming", "java.sql", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                menuGroup = "Aurora"
                upgradeUuid = "67dc3d5a-e732-4a4a-b9a6-d1126d48bca5"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
            }
        }
    }
}
