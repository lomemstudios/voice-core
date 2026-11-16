# voice-core progress: 0.4.0 adds a speech detector, a microphone that recovers and the detector's end cause

## Summary of 0.4.0 (16 November 2026, job voicecore-1-vad)

RELEASE_LINE

voice-core 0.4.0 only adds. An app that uses none of the new parts builds and behaves as with 0.3.1. lomem's
voice-native, copied outside lomem, compiles against 0.4.0 unchanged, and its 91 JVM tests pass with 0.3.1 and with
0.4.0.

What it adds:

1. A pluggable speech detector: `SpeechProbability`, given to `TurnDetector` (`speech`) with each frame
   (`audio(frame, levelDB, duration, t)`). A turn starts on 0.25 s of speech at 0.5 or more, above half the margin. Its
   silence counts non-speech frames. The fallback, from sound alone, ends a turn once the person's own voice has been
   gone for its silence plus 3 s (`FALLBACK`), so a radio under the user cannot hold it to `maxTurn`.
2. `VoiceEngine` hardening: open and start failures are reported, never thrown; a read error reopens the recorder
   after 0.5 s doubling to 8 s; the audio thread runs at urgent-audio priority; `audioGeneration` is `@Volatile`; only
   the audio thread releases its recorder, after its last read.
3. Microphone health: `MicHealth.Healthy`, `Blank(ms)`, `Dead(why)`, `Taken(why)` through
   `VoiceEngineListener.onMicHealth` (a default method, so existing listeners compile). `Taken` also comes from
   Android silencing the recording (Android 10 and later).
4. The end cause: `speechEndAt` and `cause` (`SILENCE`, `STILL_WORDS`, `FALLBACK`, `MAX_TURN`, `FLUSH`, `NOISE`) on every
   `Ended` and `Dropped`.
5. A pre-roll: `TurnDetector.preRoll()` and `preRollFrom`, the last 2.5 s (`preRollSeconds`) of the frames given.
6. `freezeFloorInTurn` (default off): the floor does not rise while a turn is open.
7. Barge-in from sound: with a speech detector and `playing`, 0.3 s at 0.8 or more raises `BargeIn` with `bySound`.

Nothing waits on an event, and nothing needs Petros.

## Tests

`./gradlew test assembleRelease` passes: 81 tests, none failing. The new ones:

| Class | Tests | What |
|---|---|---|
| `TurnScenarioTest` | 1 (13 scenarios, 9 new) | door slam, coughs, "wait wait" over playback, unsure speech over playback, soft words, radio under speech, radio starting with speech, a loud turn that ends softly with the floor rising and frozen |
| `TurnSpeechTest` | 13 | frames without a speech detector change nothing (same events, stats and floor as levels alone), pre-roll, every end cause with its `speechEndAt`, speech start, sound barge-in, field equality |
| `MicHealthTest` | 6 | `MicWatch`, `Backoff`, `allZero` |

The scenario runner now reports every failing scenario, one line each. Runs of `TurnScenarioTest` alone:

Before any 0.4.0 logic, with only the new config fields in place. `loud-turn-ends-softly-floor-rises.json` already
passes: it pins today's rule, and so confirms the hypothesis that the floor rises in long loud speech until the soft
last words count as silence. `unsure-speech-over-playback.json` also passes before, because it only checks that no
barge-in from sound comes.

```
TurnScenarioTest tests 1 failures 1 errors 0
cough-with-speech-detector.json: turns started expected:<0> but was:<2>
door-slam-with-speech-detector.json: turns started expected:<0> but was:<2>
loud-turn-ends-softly-floor-frozen.json expected:<... of the long thought[ then the rest]> but was:<... of the long thought[]>
radio-starts-with-speech.json: cause expected:<FALLBACK> but was:<null>
radio-under-speech.json: turns started expected:<1> but was:<2>
soft-words-with-speech-detector.json expected:<check the build[ and the tests]> but was:<check the build[]>
wait-wait-over-playback.json: first barge-in expected:<[sound]> but was:<[words]>
```

After the floor freeze (`loud-turn-ends-softly-floor-frozen.json` passes):

```
TurnScenarioTest tests 1 failures 1 errors 0
cough-with-speech-detector.json: turns started expected:<0> but was:<2>
door-slam-with-speech-detector.json: turns started expected:<0> but was:<2>
radio-starts-with-speech.json: cause expected:<FALLBACK> but was:<null>
radio-under-speech.json: turns started expected:<1> but was:<2>
soft-words-with-speech-detector.json expected:<check the build[ and the tests]> but was:<check the build[]>
wait-wait-over-playback.json: first barge-in expected:<[sound]> but was:<[words]>
```

After the speech path without the fallback: the radio holds both turns for its whole 20 s, and they end only when it
stops:

```
TurnScenarioTest tests 1 failures 1 errors 0
radio-starts-with-speech.json: cause expected:<[FALLBACK]> but was:<[SILENCE]>
radio-under-speech.json: cause expected:<[FALLBACK]> but was:<[SILENCE]>
```

After the fallback:

```
TurnScenarioTest tests 1 failures 0 errors 0
```

lomem's voice-native JVM tests (`packages/voice-native/android/jvm-tests/run.sh`, from a `git archive` copy in a temp
folder, with `voice-core.gradle` pointed at this build's local Maven folder for 0.4.0): 91 tests, 0 failed, with 0.3.1
and with 0.4.0.

The engine's AudioRecord paths have no device test: no device is plugged into main. They compile, and their decisions
(`MicWatch`, `Backoff`) are unit-tested. The first phone run of an app on 0.4.0 shows them in its log
(`microphone: …` lines).

## Decisions made without Petros

None needed a panel: the prompt and the repository settle each.

- The sound barge-in is `TurnEvent.BargeIn` with `bySound = true`, not a new event type. The prompt asks for "a new
  optional event", and also that every consumer builds unchanged. A new subclass of the sealed `TurnEvent` breaks
  lomem's `VoiceService`, whose `when` lists every subclass. The build rule wins.
- The new event fields (`speechEndAt`, `cause`, `bySound`, `speechFrom`) are body properties, outside `equals` and
  `copy`. As constructor parameters they would change equality: `TurnDetectorTest` compares a `Dropped` by value, and
  so may a consumer.
- With a speech detector, a turn's start also needs sound above half the margin, and the sound barge-in too. Without
  it, a radio under the floor opens a turn every few seconds, and the answer leaking faintly past the echo canceller
  cuts itself.
- With a speech detector, the "held by noise" rule (4 s of sound with no words) is off. Claude Voice is to run no
  recognizer, so every turn would have no words and be dropped at 4 s.
- The cause for `finish()` is `FLUSH`, the prompt's word.
- The Swift `TurnCore` gets no port in this job: the default path is unchanged, so parity holds. The speech path needs a
  speech detector the Swift side does not have. CLAUDE.md says so.
- Claude Voice's `FerryAudioTest` was not run: the claude-voice repository is no longer checked out on main or the Mac,
  and Claude Voice's Android code now lives in lomem's voice-native. That module's 91 JVM tests ran instead, above.

## Needs Petros

Nothing.

## Earlier: Phase 0 extracted the pure logic, and Claude Voice builds against it

The steps are claude-voice's `docs/voice-gate-plan.md`, Phase 0.

## Done on 19 October 2026

| Step | State | Evidence |
|---|---|---|
| 0.1 research out of `/tmp` | done, on main | `~/.local/state/voice-bench/tse-research` holds the set, outputs, downloads, scripts and REPORT.md (4.4 GB; the 4.5 GB virtual envs were left out and recorded in `envs-frozen/<env>.txt` with `uv pip freeze`, and their Python version) |
| 0.2 the repository | done locally; no GitHub repository, no push, CI never run | `./gradlew test` 60 tests green; `tools/check_public.sh` clean |
| 0.3 the pure logic moved | done | below |
| 0.4 release | the zip and the consumer's install path work locally; nothing published | `tools/release.sh` gives the same SHA-256 after a clean rebuild; Claude Voice built from the installed zip with no `VOICE_CORE_DIR` |
| 0.5 `ModelStore`, 0.6 `VoiceProfileStore`, 0.7 `VoiceEngine` | not started | each changes what Claude Voice does (a licence list in Settings, where the print is stored, who owns the microphone); 0.7 needs Petros on the phone |

What moved from claude-voice's `android/app` (code unchanged; packages and comments only):

- `TurnDetector.kt` → `core/turn/TurnDetector.kt`; `VoicePrint.kt` → `core/print/`; `Enrolment.kt` →
  `core/enrol/EnrolmentGuide.kt`; `CallRoute.kt`, `EchoMeter.kt` → `core/call/`; `Engines.kt`'s interfaces →
  `core/engines/`, with the stub as `NoEngines`; the sherpa engines (`src/voiceprint/EnginesImpl.kt`) →
  `sherpa/` (`SherpaEngines`, `print/SherpaEmbedder`, `kws/SherpaKeywords`), with the keyword file, the model paths
  and the log as parameters instead of `Bus.log` and fixed paths.
- `SpeakerGate.turn()` and `bargeIn()`'s thresholds → `core/print/SpeakerCheck`; `SpeakerGate` keeps the commands and
  delegates to it.
- Tests: `TurnDetectorTest` (25 of 26), `CallRouteTest` 12, `EchoMeterTest` 6, `EnrolmentGuideTest` 6,
  `VoicePrintTest` 6, and a new `SpeakerCheckTest` 5. Claude Voice keeps `FerryAudioTest` and the recordings, the
  `afterSpent` check (`AfterSpentTest`), and the scan of its network code (`VoicePrintPrivacyTest`).
- Proof of no change: Claude Voice's 119 unit tests before, 65 in the app and 60 here after, all green in both flavors;
  `javap -c -p` of the 28 moved classes equals the old ones once package names and constant-pool numbering are
  normalised, and so do all 66 app classes that stayed, except `SpeakerGate` and `Engines` (changed on purpose) and
  `MainActivity` and `SettingsActivity` (the version number). The Next APK from the release zip is 8 bytes larger
  than before the move and holds the same native libraries and models.

## Decisions taken while building it (the plan left them open)

- Packages: `com.lomemstudios.voice.{turn,print,enrol,call,engines}` in `core`, `com.lomemstudios.voice.sherpa` (with
  `.print`, `.kws`) in `sherpa`; Maven group `com.lomemstudios.voice`, as the plan names the coordinates.
- The copyright line names Petros Makris, as claude-voice's NOTICE does.
- `minSdk` 24 for both modules (Claude Voice needs 33; an Expo app may go lower).
- `FerryAudioTest` and the ferry recordings stay in claude-voice: this repository is public.
- `afterSpent` (cutting an ended turn's words off the recognizer's open segment) stays in Claude Voice's `VoiceService`
  with the recognizer, which moves at plan step 1.18.
- Only `EnrolmentGuide` moved. The enrolment state in `VoiceService` (`enrolSample`, `enrolTick`, …) becomes
  `EnrolmentSession` at step 1.11, which rewrites it; moving it twice was not worth the risk.
- `startAudio`, `levelDB`, `playUsage()` and the call-audio mode switch are not pure logic: they move with
  `VoiceEngine` at 0.7, after a field run.
- The sherpa-onnx AAR lives in a local Maven repository (`~/.cache/voice-core/deps`, filled by `tools/fetch_deps.sh`)
  under `com.lomemstudios.voice.deps`, so the `sherpa` module builds in a checkout and in a release alike.
- Claude Voice unzips a release into `~/.cache/claude-voice/voice-core/maven`, not `~/.cache/claude-voice/voiceprint/maven`
  as the plan said: the emulator build points `VOICEPRINT_DIR` at `/nonexistent` to get the stub, and `core` must still
  resolve then.
- The release zip leaves out `maven-metadata.xml`, which carries the build time, so the same tree gives the same zip.

### Needed Petros on 19 October (done since: the repository exists, v0.1.0 to v0.3.1 are released)

- Create the GitHub repository (public, plan answer Q2), push `main`, and say where it lives. Then: tag `v0.1.0`,
  `tools/release.sh`, `gh release create`, and pin `voice_core_repo` and `voice_core_sha256` in claude-voice's
  `fetch_voiceprint.sh`. Until then Claude Voice builds only with `VOICE_CORE_DIR` (or a zip installed with
  `VOICE_CORE_ZIP`), and the Mac needs a clone of this repository from main.

## The voice filter is proven here before any Lo Mem app uses this library

Petros, 19 October evening (road-talk decision 011): the Lo Mem apps take `voice-core` only after Phase 1's field
validation in Claude Voice reports go (plan steps 1.16 and 1.17) and 0.5 to 0.7 are done. Only the engineering is
shared; each app keeps its own screens, sounds and AI stack.

## Done on 20 October 2026: 0.5 to 0.7 and the phone bench, for 0.2.0 (not released)

| Step | State | Evidence |
|---|---|---|
| 0.5 `ModelStore` | done | `ModelStoreTest` 7 tests; with the SHA-256 check taken out of `bytes()` (and, separately, of `fetch()`) one test fails each time, and passes with it back. Claude Voice lists each model's licence in Settings → About and logs `models: … ok` at start |
| 0.6 `VoiceProfileStore` | done | `VoiceProfileStoreTest` 5 tests: swap, a swap cut between its renames, delete-all, migration of `print.bin`, no audio kept by `PrintFile`; without `recover()`, and with the old `print.bin` kept after a new enrolment, one test fails each time. On main's emulator a pushed `print.bin` migrated at start and Delete my voice left only `voice/models` |
| 0.7 `VoiceEngine` | built; the field check waits for Petros | `EngineLogicTest` 8 tests (`RouteSteps`, `CallPlayer`, `FrameFanOut`; a blocking offer fails the slow-tap test). On the emulator a session logged its route, effects and the microphone check as before. Still to do: one drive or walk, and its trace compared with the last (turn counts, drops, `echo:` lines) |

Phone bench (`bench/PhoneBench`, Claude Voice's Settings → Tools → Phone speed test):

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
Model changes: pVAD and GTCRN pinned in the manifest. Claude Voice 0.21 builds against this checkout (`VOICE_CORE_DIR`).
