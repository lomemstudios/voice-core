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
- No model and no model runtime since 0.3.0 (Petros, 28 October: the speaker models did not work on short audio and
  were removed, "Super big cleanup"). A model brought back is never committed: pinned by SHA-256 and fetched.
- `tools/check_public.sh` fails on any of these in the tracked tree. Run it before every commit that adds files; CI runs it.
- No test here needs a recording since 0.3.0 (the ones that read `VOICE_BENCH_DIR` went with the gate). Voice Next's
  `FerryAudioTest`, which plays the ferry recordings
  through this detector, stays in claude-voice with the recordings.

## What each module owns

- `core` (`com.lomemstudios.voice`, Kotlin on Android, no third-party runtime):
  - `turn`: `TurnDetector`, `TurnConfig`, `TurnEvent`, `TurnStats`, `Words`. When a turn starts and ends, from levels
    and the recognizer's words.
  - `call`: `CallRoute` (which communication device a session uses; never the earpiece), `EchoMeter` (how far over the
    floor an answer came back while it played), `MicKind` (phone, earbuds, car, wired, other: the kind of a turn's
    microphone, from its AudioDeviceInfo type and the app's word on whether a Bluetooth device is a car).
  - `engine`: `VoiceEngine` (the microphone, the call path and the frame fan-out; see below), `audio`: `FrameFanOut`,
    `call`: `RouteSteps` and `CallPlayer` (what plays is call audio on the call path).
- Removed in 0.3.0 (Petros, 28 October: "Testing is done, please remove the model"): the voice print (`print`:
  `VoicePrint`, `SpeakerCheck`, `WordVoice`, `IslandCutter`), the voice gate (`pvad`, `gate`, the detector's gate
  inputs), enrolment (`enrol`), the voice profile (`profile`), the model store (`models`), the engine factory
  (`engines`), the phone speed test (`bench`), the `sherpa` module, ONNX Runtime, `tools/fetch_deps.sh` and
  `tools/export_ecapa.py`. Speaker models cannot judge 0.5 s of a word, so the short words of passers-by were exactly
  what they missed. Their history is in git before `v0.3.0`.
- Stays in each app: screens, sounds, the server protocol, the turn detector's host and the recognizer, and the
  players, which take their usage from `VoiceEngine.playUsage()`.
- `VoiceEngine` in `core` owns the microphone and call-path audio (since 0.2.0, plan step 0.7): an app never opens
  `AudioRecord` or switches the audio mode itself. It reads 50 ms frames on its "audio" thread, gives each to every
  tap (its own thread and bounded queue; a slow tap drops its own frames) and, with its level, to the listener on the
  main thread.
- `setMode(MODE_IN_COMMUNICATION)` needs `MODIFY_AUDIO_SETTINGS`, a normal permission granted at install with no
  prompt. `core`'s own manifest declares it (0.2.2, 21 October), so every app that uses `VoiceEngine` gets it through
  the manifest merge. Before 0.2.2 no manifest declared it and Android refused the mode with only a logcat warning
  (`Audio Settings Permission Denial: setMode()`): Voice Next's call path ran in `MODE_NORMAL` from its version 10 to
  25 (checked on the emulator, 21 October). The `route:` log line reads the mode back from the system ("mode in
  communication"), and a refused mode logs "Android refused communication mode".
- A device the user picks (0.2.1, Claude Voice Next's Audio page, 20 October): `VoiceEngine(preferredDevice = …)` gives
  the id in `availableCommunicationDevices` of the picked device, and `CallRoute.choose(preferredId = …)` takes it over
  the automatic order while it is listed, whatever its type or the Bluetooth setting, never the earpiece; when it is not listed the order decides. The app keeps
  the pick by its own key and resolves it to an id at each choice; a change is applied with `chooseRoute`, never by
  restarting the session. `VoiceEngine.inputDevice` is the recording's routed microphone, for the screens.
- A media output (0.2.3, Claude Voice's two audio choices, Petros, 22 October): with this phone's microphone the user
  may want the answers on a device at full quality (earbuds as music, A2DP). `VoiceEngine(mediaOutput = …)` gives that
  output's id in `getDevices(GET_DEVICES_OUTPUTS)`, and `CallRoute.choose(mediaOutput = …)` then returns a media route
  to it (`Route.output`) over every call route: normal mode, no communication device, the recognition microphone kept
  on the phone's own (`setPreferredDevice`), never a headset's. The app points its players at
  `VoiceEngine.outputDevice` and plays with `playUsage()`, `USAGE_MEDIA` there (`CallPlayer.usage(call, media)`). The
  app passes a media output only while its user keeps the phone's microphone: Android opens a Bluetooth or wired
  microphone only in call mode, which plays on that same device. Another media output is another route
  (`Route.sameAs` compares it) and is applied without restarting the recording.

## The detector is the reference

- `turn/TurnDetector.kt` is the reference turn detector. Claude Voice's Swift `TurnCore` (claude-voice,
  `listener/Sources/TurnCore`) is ported line for line from it: a change here goes there too, checked with
  `swift run turn-check` on the Mac. lomem's TypeScript detector gets no parity work.
- A change to the detector keeps Voice Next's `FerryAudioTest` passing. Run it from claude-voice's `android/` with
  `VOICE_CORE_DIR=~/projects/voice-core ./gradlew testNextDebugUnitTest`.
- A check that guards a fix must fail with the fix removed: paste both runs. The timeline in `TurnDetectorTest` has a
  recognizer `lag`, because one that answers at once hides timing bugs.
- The scenarios Voice Next and the Lo Mem apps rely on go into `testdata/turns/*.json` (`TurnScenarioTest`).
- The detector has no voice gate since 0.3.0: it never tells voices apart, and another voice's sound and words hold a
  turn open as the user's do (`talker-after-user.json`). Claude Voice filters another person's stray words with the
  server's judgement of the words, not by audio.

## Build and test

- Java 21 and the Android SDK. On main: `JAVA_HOME=$HOME/.local/jdk-21 ANDROID_HOME=$HOME/Android/Sdk`; on the Mac:
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` and the SDK in `~/Library/Android/sdk`.
- `./gradlew test assembleRelease`. No third-party library to fetch since 0.3.0.
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
  with `core`, LICENSE and NOTICE. The same tree gives
  the same zip. Nothing is uploaded by it.
- Publishing is Petros's step, never Claude's without his go: push `main`, tag, then
  `gh release create v<version> build/release/voice-core-<version>-maven.zip`. The repository is
  github.com/lomemstudios/voice-core (public); v0.1.0 is released, and v0.2.0 (20 October, SHA-256 `c9d1e2f2…`,
  published by Claude on the voice gate build's brief from Petros). v0.2.1 (20 October, SHA-256 `6cf0b592…`: the picked device in `CallRoute.choose(preferredId)` and `VoiceEngine(preferredDevice)`, `VoiceEngine.inputDevice`; no model changes), published by Claude on the UX round two brief from Petros. v0.2.2 (21 October, SHA-256 `2f60d8c7…`: `MODIFY_AUDIO_SETTINGS` in core's manifest, the mode read back in the `route:` line; no model changes), published by Claude on Petros's brief for Voice Next's call mode. v0.2.3 (22 October, SHA-256 `26ec6545…`: the media output, `Route.output`, `VoiceEngine(mediaOutput)`, `VoiceEngine.outputDevice` and `media`, `CallPlayer.usage(call, media)`; it also carries the NOTICE and ModelManifest licence corrections of ae456ae; no model changes), published by Claude on Petros's brief for the two audio choices. v0.2.4 (26 October, SHA-256 `d3a846dd…`: `VoicePrint.scoreSpeech` and `speechOf`, the turn check on its speech only; `SpeakerCheck.floor` 0.25 and `needed`; `MicKind`; no model changes), published by Claude on Petros's brief for Claude Voice's simpler voice design ("go ahead, full permissions granted"). v0.2.5 (26 October, SHA-256 `79acd3ce…`: `WordVoice`, `WordColour`, `WordRules`, `IslandCutter`, `Island`, `VoicePrint.vector`; no model changes), published by Claude on Petros's brief for Claude Voice's live word colours ("release voice-core 0.2.5 the way 0.2.4 was released"). v0.3.0 (28 October, SHA-256 `add87e42…`: the speaker models and everything only they used removed, see "What each module owns"; `TurnDetector` without the gate's inputs, behaviour unchanged without a gate; the `sherpa` module and ONNX Runtime gone, so the zip holds `core` alone; Claude Voice Next 0.61's cleanup), built and committed by Claude, published by the job's lead.
- Each consumer pins one version and its SHA-256, and upgrading is a commit there. Voice Next: `voiceCoreVersion` in
  `android/app/build.gradle.kts`, and `voice_core_version`, `voice_core_repo` and `voice_core_sha256` in
  `android/tools/fetch_voiceprint.sh`. lomem: `packages/voice-native` (Phase 4).
