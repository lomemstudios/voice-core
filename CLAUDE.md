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
- Models are not committed either: the app bundles them, or `ModelStore` fetches them on demand; either way each is
  pinned by SHA-256 in `models/ModelManifest.kt` and checked before use.
- `tools/check_public.sh` fails on any of these in the tracked tree. Run it before every commit that adds files; CI runs it.
- Tests that need recordings read them from `VOICE_BENCH_DIR` (on main `~/.local/state/voice-bench`, outside every
  repository) and skip when it is unset or missing. Voice Next's `FerryAudioTest`, which plays the ferry recordings
  through this detector, stays in claude-voice with the recordings.

## What each module owns

- `core` (`com.lomemstudios.voice`, Kotlin on Android, with ONNX Runtime for Android 1.22.0 for pVAD):
  - `turn`: `TurnDetector`, `TurnConfig`, `TurnEvent`, `TurnStats`, `Words`. When a turn starts and ends, from levels
    and the recognizer's words.
  - `print`: `VoicePrint` (the print over any `Embedder` from the accepted enrolment speech, embedded in 3 s windows,
    in the store the app gives), `SpeakerCheck` (a turn's and a barge-in's thresholds, graded by the length of speech
    scored), `TurnVerdict`.
  - `enrol`: `SpeechEnrolment` (free-speech enrolment: a room check, then the pieces of the user's speech that are
    clear enough, until 15 s of speech, ending by itself at 20 s; the progress and a plain-language line), `EnrolConfig`.
  - `call`: `CallRoute` (which communication device a session uses; never the earpiece), `EchoMeter` (how far over the
    floor an answer came back while it played).
  - `engines`: `EngineFactory` (the embedder), and `NoEngines` for a build without a model runtime.
  - `models`: `ModelManifest` (every model, pinned: name, SHA-256, size, licence, source) and `ModelStore` (bundled
    or on-demand; a bundled model is hashed once per app build, a mismatch is never used, a bad download deleted).
  - `profile`: `VoiceProfileStore` (`<filesDir>/voice/profile`: kept enrolment samples as WAV, one print per model
    version, swapped in whole, delete-all, migration of the old `print.bin`); `VoicePrint` keeps its print through a
    `PrintStore` (the profile, or `PrintFile`, the old single file).
  - `engine`: `VoiceEngine` (the microphone, the call path and the frame fan-out; see below), `audio`: `FrameFanOut`,
    `call`: `RouteSteps` and `CallPlayer` (what plays is call audio on the call path).
  - `pvad`: `Pvad`, FireRedChat pVAD on ONNX Runtime, one stream per microphone, its state carried between steps.
  - `bench`: `PhoneBench` (the phone speed test: pVAD paced as Phase 1 runs it, then flat out, and any `BenchPart`),
    `SyntheticAudio` (deterministic, equal to the Python reference sample for sample), `StepStats`, `Device`.
- `sherpa` (`com.lomemstudios.voice.sherpa`): `SherpaEngines.factory(context, log, models)` on sherpa-onnx 1.13.8:
  TitaNet small as the `Embedder`. The model files are the app's own assets; `SherpaModels` names their paths (the
  default is where Voice Next bundles it). `SherpaBench` gives the speed test's TitaNet and GTCRN parts. There is no
  keyword spotter since 0.2.0 (Petros, 20 October: Voice Next's spoken commands are understood by its server instead).
- An app with both modules has two ONNX Runtimes in one process (core's, and the one linked into sherpa-onnx's JNI
  library); they ran side by side in the speed test on main's x86-64 emulator. sherpa-onnx's AAR also ships
  `lib/x86/libonnxruntime.so`, so the app needs `packaging.jniLibs.pickFirsts += "lib/x86/libonnxruntime.so"`.
- Stays in each app: screens, sounds, the server protocol, the turn detector's host and the recognizer (until plan step
  1.18), and the players, which take their usage from `VoiceEngine.playUsage()`.
- `VoiceEngine` in `core` owns the microphone and call-path audio (since 0.2.0, plan step 0.7): an app never opens
  `AudioRecord` or switches the audio mode itself. It reads 50 ms frames on its "audio" thread, gives each to every
  tap (its own thread and bounded queue; a slow tap drops its own frames) and, with its level, to the listener on the
  main thread.

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

Enrolment is free speech since 0.2.0 (Petros, 20 October): the user reads a short paragraph aloud
(`SpeechEnrolment.PARAGRAPH`) or talks about anything, with a live level, a progress bar of accepted seconds and a
plain-language line, and finishes once 15 s of speech are in. The word-by-word guide before it refused speech 14 dB over
the floor as "too quiet" and a 2.65 s word as "too long" until he gave up (field log, 20 October 08:11), so the checks
stay lenient: speech from 6 dB over the floor, refused only when on average under 10 dB over it or clipped, never too
long. The rules that still bind every app: the microphone takes no sample while the guide speaks or a tone plays (the
app calls `hold()`, which drops an open piece); the guide speaks only before the speech and at its end; nothing else
plays during enrolment. `SpeechEnrolmentTest` pins the thresholds.

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
- Publishing is Petros's step, never Claude's without his go: push `main`, tag, then
  `gh release create v<version> build/release/voice-core-<version>-maven.zip`. The repository is
  github.com/lomemstudios/voice-core (public); v0.1.0 is released.
- Each consumer pins one version and its SHA-256, and upgrading is a commit there. Voice Next: `voiceCoreVersion` in
  `android/app/build.gradle.kts`, and `voice_core_version`, `voice_core_repo` and `voice_core_sha256` in
  `android/tools/fetch_voiceprint.sh`. lomem: `packages/voice-native` (Phase 4).
- The sherpa-onnx AAR is republished unchanged as `com.lomemstudios.voice.deps:sherpa-onnx-static-link-onnxruntime`,
  because AGP refuses a local `.aar` file inside a library AAR (checked 19 October with `./gradlew :sherpa:bundleReleaseAar`:
  "Direct local .aar file dependencies are not supported when building an AAR").
