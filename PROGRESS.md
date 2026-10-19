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
