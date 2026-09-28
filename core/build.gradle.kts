import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    api(libs.retrofit)
    api(libs.retrofit.gson)
    api(libs.gson)
    api(libs.androidx.datastore.preferences.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
