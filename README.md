# voice-core keeps an Android voice app's turn taking and call audio in one tested library

voice-core is the Android voice layer shared by Claude Voice Next and the Lo Mem apps. It decides when a spoken turn
starts and ends, and picks where a voice session's audio goes so the platform echo canceller hears the answers.
Screens, sounds, the recognizer and the server protocol stay in each app.

Android only. Web is out of scope and iOS is deferred.

## Modules

| Module | Maven coordinates | Holds | Depends on |
|---|---|---|---|
| `core` | `com.lomemstudios.voice:core` | `turn` (TurnDetector, TurnConfig, TurnEvent, TurnStats, Words), `engine` (VoiceEngine), `audio` (FrameFanOut), `call` (CallRoute, EchoMeter, MicKind, RouteSteps, CallPlayer) | Kotlin stdlib |

Since 0.3.0 there are no speaker models: the voice print, the voice gate (pVAD), enrolment, the voice profile, the
model store, the phone bench and the `sherpa` module were removed, with ONNX Runtime.

## Build and test

Java 21 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew test               # the unit tests
./gradlew assembleRelease    # the AAR
tools/check_public.sh        # no recording, model, token or private value is tracked
```

## Use it from an app

From a checkout, as a Gradle included build: `includeBuild("<voice-core folder>")` in the app's
`settings.gradle.kts`. Gradle then uses the checkout for `com.lomemstudios.voice:core`.

From a release: unzip `voice-core-<version>-maven.zip` into a folder, add that folder as a `maven { url }`
repository, and depend on `com.lomemstudios.voice:core:<version>`. Pin the version and the zip's SHA-256.

## Licence

Apache 2.0: [LICENSE](LICENSE). Third-party parts and their licences: [NOTICE](NOTICE).
