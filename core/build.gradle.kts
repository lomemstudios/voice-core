// core: the turn detector, the voice print over any Embedder, the voice profile and model store, the enrolment
// guide, the voice engine (microphone, call-audio route, echo meter, frame fan-out), and the pVAD runner with the
// phone bench on ONNX Runtime for Android (plan "pVAD needs its own ONNX Runtime on Android").
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.lomemstudios.voice"
    compileSdk = 36
    defaultConfig {
        // Low enough for an Expo app; Claude Voice Next itself needs 33.
        minSdk = 24
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // MIT. Version chosen 20 October 2026: 18.2 MB of arm64 native library, against 25.8 MB for 1.24.1 and 34.2 MB
    // for 1.31.0, the newest; it runs pVAD (PROGRESS.md, "ONNX Runtime").
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
    // ONNX Runtime for the JVM, so the parity tests run the Kotlin runners on this machine (PvadParityTest,
    // EcapaParityTest); the Android AAR is left off the test classpath below.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    // The real org.json for the JVM tests: Android's is a stub there.
    testImplementation("org.json:json:20260814")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "core"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        // tools/release.sh publishes here and zips it; nothing is uploaded from Gradle.
        maven {
            name = "release"
            url = uri(rootProject.layout.buildDirectory.dir("release/maven"))
        }
    }
}

// The JVM tests load ONNX Runtime's desktop jar; the Android AAR's classes would shadow it and fail to load their
// native library.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

// Tests that need recordings read them from here (CLAUDE.md, "This repository is public").
tasks.withType<Test>().configureEach {
    environment("VOICE_BENCH_DIR", System.getenv("VOICE_BENCH_DIR") ?: "")
}
