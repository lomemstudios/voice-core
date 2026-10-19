# voice-core — Claude instructions

The Android voice core shared by Claude Voice Next (claude-voice, branch `agent-sdk-streaming`, `next` flavor) and the
Lo Mem apps (through lomem's `packages/voice-native` Expo module, from plan Phase 4). `README.md` says what it is; these
are the rules. The plan that drives it is claude-voice's `docs/voice-gate-plan.md`; `PROGRESS.md` here says what is done
and the decisions taken.

- No home machine, like lomem: main and the Mac both have the Android SDK. The checkout is `~/projects/voice-core`.
- Android only, at full quality. Web is out of scope and iOS deferred: nothing here is weakened to fit another platform.

## This repository is public

- Never in this repository, its history or a release: a recording of Petros's voice (the ferry recordings, enrolment
  samples, saved turns, the mixtures), a token or key, a tailnet address or a home path, a REAL-TSE checkpoint, its code,
  its weights, its URL or its fetch script. REAL-TSE stays Petros's own, on main, outside every repository.
- Models are not committed either: the app bundles them, or (from plan step 0.5) `ModelStore` fetches them by SHA-256.
- `tools/check_public.sh` fails on any of these in the tracked tree. Run it before every commit that adds files; CI runs it.
- Tests that need recordings read them from `VOICE_BENCH_DIR` (on main `~/.local/state/voice-bench`, outside every
  repository) and skip when it is unset or missing. Voice Next's `FerryAudioTest`, which plays the ferry recordings
  through this detector, stays in claude-voice with the recordings.

## What each module owns

- `core` (`com.lomemstudios.voice`, plain Kotlin on Android, no model runtime):
  - `turn`: `TurnDetector`, `TurnConfig`, `TurnEvent`, `TurnStats`, `Words`. When a turn starts and ends, from levels
    and the recognizer's words.
  - `print`: `VoicePrint` (the print over any `Embedder`, in the folder the app gives), `SpeakerCheck` (a turn's and a
    barge-in's thresholds, graded by the length of speech scored), `TurnVerdict`.
  - `enrol`: `EnrolmentGuide` (what to say next, and the tone, line and words after each sample).
  - `call`: `CallRoute` (which communication device a session uses; never the earpiece), `EchoMeter` (how far over the
    floor an answer came back while it played).
  - `engines`: `KeywordSpotting`, `EngineFactory`, and `NoEngines` for a build without a model runtime.
- `sherpa` (`com.lomemstudios.voice.sherpa`): `SherpaEngines.factory(context, keywordsFile, log, models)` on sherpa-onnx
  1.13.8: TitaNet small as the `Embedder`, the zipformer keyword spotter. The keyword list and the model files are the
  app's own assets; `SherpaModels` names their paths (defaults are where Voice Next bundles them).
- Stays in each app: screens, sounds, the server protocol, the command words and their thresholds (Voice Next's
  `SpeakerGate`), and until plan step 0.7 the microphone, the call-audio mode switch and playback (Voice Next's
  `VoiceService`).
- From 0.7, `VoiceEngine` in `core` owns the microphone and call-path audio, and an app never opens `AudioRecord` or
  switches the audio mode itself. Until then that rule is not in force: Voice Next's `VoiceService` still does both.

## The detector is the reference

- `turn/TurnDetector.kt` is the reference turn detector. Claude Voice's Swift `TurnCore` (claude-voice,
  `listener/Sources/TurnCore`) is ported line for line from it: a change here goes there too, checked with
  `swift run turn-check` on the Mac. lomem's TypeScript detector gets no parity work.
- A change to the detector keeps Voice Next's `FerryAudioTest` passing. Run it from claude-voice's `android/` with
  `VOICE_CORE_DIR=~/projects/voice-core ./gradlew testNextDebugUnitTest`.
- A check that guards a fix must fail with the fix removed: paste both runs. The timeline in `TurnDetectorTest` has a
  recognizer `lag`, because one that answers at once hides timing bugs.
- The scenarios Voice Next and the Lo Mem apps rely on go into `testdata/turns/*.json` (plan step 1.5).

## Enrolment rules bind every app

Each was a reason Petros could not finish enrolment (17 October): one sample per tone; every sample gets the `got_it`
or `ignored` tone at once, with its count or reason on screen; the guide speaks only between samples; the microphone
takes no sample while the guide or a tone plays; nothing else plays during enrolment. `EnrolmentGuide` gives the tone,
the line and the words; until `EnrolmentSession` (plan step 1.11) the app enforces the microphone side.

## Build and test

- Java 21 and the Android SDK. On main: `JAVA_HOME=$HOME/.local/jdk-21 ANDROID_HOME=$HOME/Android/Sdk`; on the Mac:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and the SDK in `~/Library/Android/sdk`.
- `tools/fetch_deps.sh` first: the sherpa-onnx AAR, pinned by SHA-256, into `VOICE_CORE_DEPS` or
  `~/.cache/voice-core/deps`, a local Maven repository. Then `./gradlew test assembleRelease`.
- The Android Gradle Plugin version (9.4.1) and Gradle (9.8.1) match claude-voice's `android/` build: Voice Next
  includes this build, and change them together. AGP 9 builds Kotlin itself; do not apply the Kotlin plugin.
- Without the Android SDK, the detector's tests run with a standalone Kotlin compiler and JUnit (not run since the
  move; main has the SDK now): compile `core/src/main/kotlin/com/lomemstudios/voice/turn/TurnDetector.kt` and
  `core/src/test/kotlin/com/lomemstudios/voice/turn/TurnDetectorTest.kt` with `kotlinc -cp junit.jar`, and run
  `org.junit.runner.JUnitCore com.lomemstudios.voice.turn.TurnDetectorTest`.

## Releases and pinning

- The version is `voiceCoreVersion` in `gradle.properties`; a release tags `v<version>`. While at `0.x` a minor bump
  may break the API, from `1.0` only a major. Each release notes its API and model changes.
- `tools/release.sh` builds `build/release/voice-core-<version>-maven.zip` and prints its SHA-256: a Maven repository
  with `core`, `sherpa`, the third-party AARs (`com.lomemstudios.voice.deps`), LICENSE and NOTICE. The same tree gives
  the same zip. Nothing is uploaded by it.
- Publishing is Petros's step, never Claude's without his go: create the GitHub repository, push `main`, tag, then
  `gh release create v<version> build/release/voice-core-<version>-maven.zip`.
- Each consumer pins one version and its SHA-256, and upgrading is a commit there. Voice Next: `voiceCoreVersion` in
  `android/app/build.gradle.kts`, and `voice_core_version`, `voice_core_repo` and `voice_core_sha256` in
  `android/tools/fetch_voiceprint.sh`. lomem: `packages/voice-native` (Phase 4).
- The sherpa-onnx AAR is republished unchanged as `com.lomemstudios.voice.deps:sherpa-onnx-static-link-onnxruntime`,
  because AGP refuses a local `.aar` file inside a library AAR (checked 19 October with `./gradlew :sherpa:bundleReleaseAar`:
  "Direct local .aar file dependencies are not supported when building an AAR").
