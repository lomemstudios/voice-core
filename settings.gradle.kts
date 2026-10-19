// voice-core: the Android voice core shared by Claude Voice Next and the Lo Mem apps (CLAUDE.md).
//
// Third-party libraries that are not on a public Maven repository (today the sherpa-onnx AAR) come from a local
// Maven directory that tools/fetch_deps.sh fills, pinned by SHA-256: VOICE_CORE_DEPS, else ~/.cache/voice-core/deps.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val deps = System.getenv("VOICE_CORE_DEPS")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.cache/voice-core/deps"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "voiceCoreDeps"
            url = uri(deps)
        }
    }
}

rootProject.name = "voice-core"
include(":core")
include(":sherpa")
