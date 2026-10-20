# voice-core keeps an Android voice app's turn taking, voice print and call audio in one tested library

voice-core is the Android voice layer shared by Claude Voice Next and the Lo Mem apps. It decides
when a spoken turn starts and ends, checks whether speech is the enrolled user's, guides enrolment, and picks where a
voice session's audio goes so the platform echo canceller hears the answers. Screens, sounds and the server protocol stay
in each app.

Android only. Web is out of scope and iOS is deferred.

## Modules

| Module | Maven coordinates | Holds | Depends on |
|---|---|---|---|
| `core` | `com.lomemstudios.voice:core` | `turn` (TurnDetector, TurnConfig, TurnEvent, TurnStats, Words), `print` (VoicePrint, Embedder, SpeakerCheck, PrintStore), `profile` (VoiceProfileStore), `models` (ModelManifest, ModelStore), `engine` (VoiceEngine), `audio` (FrameFanOut), `enrol` (SpeechEnrolment), `call` (CallRoute, EchoMeter, RouteSteps, CallPlayer), `engines` (EngineFactory, NoEngines), `pvad` (Pvad), `bench` (PhoneBench, SyntheticAudio, StepStats) | Kotlin stdlib, ONNX Runtime for Android 1.22.0 |
| `sherpa` | `com.lomemstudios.voice:sherpa` | `SherpaEngines`: TitaNet small as the voice print's Embedder; `SherpaBench` | `core`, sherpa-onnx 1.13.8 |

`core` holds the logic, with unit tests, the microphone and call path (`VoiceEngine`) and the pVAD runner. `sherpa`
runs the sherpa-onnx models. The app bundles the model files in its own assets; `ModelManifest` pins each one.

## Build and test

Java 21 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
tools/fetch_deps.sh          # the sherpa-onnx AAR, pinned by SHA-256, into ~/.cache/voice-core/deps
./gradlew test               # the unit tests
./gradlew assembleRelease    # both AARs
tools/check_public.sh        # no recording, model, token or private value is tracked
```

## Use it from an app

From a checkout, as a Gradle included build: `includeBuild("<voice-core folder>")` in the app's
`settings.gradle.kts`, and add `~/.cache/voice-core/deps` as a `maven { url }` repository. Gradle then uses the checkout
for `com.lomemstudios.voice:core` and `:sherpa`.

From a release: unzip `voice-core-<version>-maven.zip` into a folder, add that folder as a `maven { url }`
repository, and depend on `com.lomemstudios.voice:core:<version>` (and `:sherpa:<version>`). The zip carries the
sherpa-onnx AAR too. Pin the version and the zip's SHA-256.

## Licence

Apache 2.0: [LICENSE](LICENSE). Third-party parts and their licences: [NOTICE](NOTICE).
