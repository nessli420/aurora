pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Aurora"
include(":app")

// Vendored decent-player USB bit-perfect driver + Media3 wrapper (MIT). Experimental.
include(":decent-usb-audio-driver")
include(":decent-usb-audio-wrapper-media3")
project(":decent-usb-audio-driver").projectDir = file("decent/decent-usb-audio-driver")
project(":decent-usb-audio-wrapper-media3").projectDir = file("decent/decent-usb-audio-wrapper-media3")
