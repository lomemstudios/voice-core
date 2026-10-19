// sherpa: the engines on sherpa-onnx 1.13.8 (Apache 2.0): TitaNet small as the voice print's Embedder and the
// zipformer keyword spotter. The models stay in the app's assets; this module only names their paths.
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.lomemstudios.voice.sherpa"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":core"))
    // The k2-fsa release AAR, republished unchanged under these coordinates by tools/fetch_deps.sh (AGP refuses a
    // local .aar file inside a library that is itself an AAR) and shipped inside each voice-core release.
    implementation("com.lomemstudios.voice.deps:sherpa-onnx-static-link-onnxruntime:1.13.8@aar")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "sherpa"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "release"
            url = uri(rootProject.layout.buildDirectory.dir("release/maven"))
        }
    }
}
