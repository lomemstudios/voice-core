// voice-core: the Android voice core shared by Claude Voice Next and the Lo Mem apps (CLAUDE.md).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "voice-core"
include(":core")
