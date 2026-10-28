// core: the turn detector and the voice engine (microphone, call-audio route, echo meter, frame fan-out).
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
    testImplementation("junit:junit:4.13.2")
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


