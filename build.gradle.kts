// The same Android Gradle Plugin as Claude Voice Next's android/ build: a consumer that includes this build
// (VOICE_CORE_DIR) must not see two versions. AGP 9 builds Kotlin itself; do not apply the Kotlin plugin.
plugins {
    id("com.android.library") version "9.4.1" apply false
}

// One version for both modules; a release tags v<this> (CLAUDE.md, "Releases and pinning").
allprojects {
    group = "com.lomemstudios.voice"
    version = providers.gradleProperty("voiceCoreVersion").get()
}
