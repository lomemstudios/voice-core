# voice-core progress: Phase 0 extracted the pure logic, and Claude Voice Next builds against it

The steps are claude-voice's `docs/voice-gate-plan.md`, Phase 0.

## Done on 19 October 2026

| Step | State | Evidence |
|---|---|---|
| 0.1 research out of `/tmp` | done, on main | `~/.local/state/voice-bench/tse-research` holds the set, outputs, downloads, scripts and REPORT.md (4.4 GB; the 4.5 GB virtual envs were left out and recorded in `envs-frozen/<env>.txt` with `uv pip freeze`, and their Python version) |
| 0.2 the repository | done locally; no GitHub repository, no push, CI never run | `./gradlew test` 60 tests green; `tools/check_public.sh` clean |
| 0.3 the pure logic moved | done | below |
| 0.4 release | the zip and the consumer's install path work locally; nothing published | `tools/release.sh` gives the same SHA-256 after a clean rebuild; Voice Next built from the installed zip with no `VOICE_CORE_DIR` |
| 0.5 `ModelStore`, 0.6 `VoiceProfileStore`, 0.7 `VoiceEngine` | not started | each changes what Voice Next does (a licence list in Settings, where the print is stored, who owns the microphone); 0.7 needs Petros on the phone |

What moved from claude-voice's `android/app` (code unchanged; packages and comments only):

- `TurnDetector.kt` → `core/turn/TurnDetector.kt`; `VoicePrint.kt` → `core/print/`; `Enrolment.kt` →
  `core/enrol/EnrolmentGuide.kt`; `CallRoute.kt`, `EchoMeter.kt` → `core/call/`; `Engines.kt`'s interfaces →
  `core/engines/`, with the stub as `NoEngines`; the sherpa engines (`src/voiceprint/EnginesImpl.kt`) →
  `sherpa/` (`SherpaEngines`, `print/SherpaEmbedder`, `kws/SherpaKeywords`), with the keyword file, the model paths
  and the log as parameters instead of `Bus.log` and fixed paths.
- `SpeakerGate.turn()` and `bargeIn()`'s thresholds → `core/print/SpeakerCheck`; `SpeakerGate` keeps the commands and
  delegates to it.
- Tests: `TurnDetectorTest` (25 of 26), `CallRouteTest` 12, `EchoMeterTest` 6, `EnrolmentGuideTest` 6,
  `VoicePrintTest` 6, and a new `SpeakerCheckTest` 5. Voice Next keeps `FerryAudioTest` and the recordings, the
  `afterSpent` check (`AfterSpentTest`), and the scan of its network code (`VoicePrintPrivacyTest`).
- Proof of no change: Voice Next's 119 unit tests before, 65 in the app and 60 here after, all green in both flavors;
  `javap -c -p` of the 28 moved classes equals the old ones once package names and constant-pool numbering are
  normalised, and so do all 66 app classes that stayed, except `SpeakerGate` and `Engines` (changed on purpose) and
  `MainActivity` and `SettingsActivity` (the version number). The Next APK from the release zip is 8 bytes larger
  than before the move and holds the same native libraries and models.

## Decisions taken while building it (the plan left them open)

- Packages: `com.lomemstudios.voice.{turn,print,enrol,call,engines}` in `core`, `com.lomemstudios.voice.sherpa` (with
  `.print`, `.kws`) in `sherpa`; Maven group `com.lomemstudios.voice`, as the plan names the coordinates.
- The copyright line names Petros Makris, as claude-voice's NOTICE does.
- `minSdk` 24 for both modules (Voice Next needs 33; an Expo app may go lower).
- `FerryAudioTest` and the ferry recordings stay in claude-voice: this repository is public.
- `afterSpent` (cutting an ended turn's words off the recognizer's open segment) stays in Voice Next's `VoiceService`
  with the recognizer, which moves at plan step 1.18.
- Only `EnrolmentGuide` moved. The enrolment state in `VoiceService` (`enrolSample`, `enrolTick`, …) becomes
  `EnrolmentSession` at step 1.11, which rewrites it; moving it twice was not worth the risk.
- `startAudio`, `levelDB`, `playUsage()` and the call-audio mode switch are not pure logic: they move with
  `VoiceEngine` at 0.7, after a field run.
- The sherpa-onnx AAR lives in a local Maven repository (`~/.cache/voice-core/deps`, filled by `tools/fetch_deps.sh`)
  under `com.lomemstudios.voice.deps`, so the `sherpa` module builds in a checkout and in a release alike.
- Voice Next unzips a release into `~/.cache/claude-voice/voice-core/maven`, not `~/.cache/claude-voice/voiceprint/maven`
  as the plan said: the emulator build points `VOICEPRINT_DIR` at `/nonexistent` to get the stub, and `core` must still
  resolve then.
- The release zip leaves out `maven-metadata.xml`, which carries the build time, so the same tree gives the same zip.

## Needs Petros

- Create the GitHub repository (public, plan answer Q2), push `main`, and say where it lives. Then: tag `v0.1.0`,
  `tools/release.sh`, `gh release create`, and pin `voice_core_repo` and `voice_core_sha256` in claude-voice's
  `fetch_voiceprint.sh`. Until then Voice Next builds only with `VOICE_CORE_DIR` (or a zip installed with
  `VOICE_CORE_ZIP`), and the Mac needs a clone of this repository from main.

## Done on 20 October 2026: 0.5 to 0.7 and the phone bench, for 0.2.0 (not released)

| Step | State | Evidence |
|---|---|---|
| 0.5 `ModelStore` | done | `ModelStoreTest` 7 tests; with the SHA-256 check taken out of `bytes()` (and, separately, of `fetch()`) one test fails each time, and passes with it back. Voice Next lists each model's licence in Settings → About and logs `models: … ok` at start |
| 0.6 `VoiceProfileStore` | done | `VoiceProfileStoreTest` 5 tests: swap, a swap cut between its renames, delete-all, migration of `print.bin`, no audio kept by `PrintFile`; without `recover()`, and with the old `print.bin` kept after a new enrolment, one test fails each time. On main's emulator a pushed `print.bin` migrated at start and Delete my voice left only `voice/models` |
| 0.7 `VoiceEngine` | built; the field check waits for Petros | `EngineLogicTest` 8 tests (`RouteSteps`, `CallPlayer`, `FrameFanOut`; a blocking offer fails the slow-tap test). On the emulator a session logged its route, effects and the microphone check as before. Still to do: one drive or walk, and its trace compared with the last (turn counts, drops, `echo:` lines) |

Phone bench (`bench/PhoneBench`, Voice Next's Settings → Tools → Phone speed test):

- It lives here, not in the app: it times `Pvad`, the runner the voice gate will use (plan 1.7), on the thread layout
  Phase 1 will use, so the numbers are the gate's own; any app of this library can run it; it holds no data of
  anyone's (generated audio, a fixed random embedding). The app adds only its screen and where the lines go.
- `SyntheticAudio` equals a Python reference sample for sample (`BenchTest` pins its first samples and RMS). pVAD's
  mean p over the 60 s clip is 0.1389 with onnxruntime 1.22.0 in Python on main and 0.1389 in the bench on main's
  x86-64 emulator, so the Kotlin runner feeds the model as the research did.
- On the emulator (4 x86-64 cores of the 5950X): pVAD paced, per 10 ms step mean 1.23 ms, p95 2.23, worst 6.61; per
  50 ms chunk p95 11.3 ms; flat out 0.92 ms a step (RTF 0.092); TitaNet small 8.8 ms per 1 s window; GTCRN 2.3 ms
  per 50 ms chunk. The phone's numbers come from Petros's run.

ONNX Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` (MIT), AAR SHA-256
`04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2`. Its arm64 library is 18.2 MB; 1.24.1's is 25.8 MB
and 1.31.0's (the newest) 34.2 MB, for no gain pVAD needs. It runs beside the ONNX Runtime linked into sherpa-onnx in
one process (the emulator run above). sherpa-onnx's AAR also carries `lib/x86/libonnxruntime.so`, which clashes at
packaging: the app picks one (`pickFirsts`).

What 0.2.0 would carry, against 0.1.0: `models`, `profile`, `engine`, `audio`, `pvad`, `bench`; `call.RouteSteps` and
`CallPlayer`; `VoicePrint(PrintStore, …)` beside the old constructor, `add(kind, pcm, text)`, `reload()`, and `save()`
false when the store could not write; `levelDb` public; `core` depends on onnxruntime-android 1.22.0; `SherpaBench`.
Model changes: pVAD and GTCRN pinned in the manifest. Voice Next 0.21 builds against this checkout (`VOICE_CORE_DIR`).
