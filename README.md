# voice-core keeps an Android voice app's turn taking and call audio in one tested library

voice-core is the Android voice layer shared by Claude Voice and the Lo Mem apps. It decides when a spoken turn
starts and ends, and picks where a voice session's audio goes so the platform echo canceller hears the answers.
Screens, sounds, the recognizer and the server protocol stay in each app.

Android only. Web is out of scope and iOS is deferred.

## Modules

| Module | Maven coordinates | Holds | Depends on |
|---|---|---|---|
| `core` | `com.lomemstudios.voice:core` | `turn` (TurnDetector, TurnConfig, TurnEvent, TurnStats, Words, SpeechProbability, EndCause), `engine` (VoiceEngine, MicHealth, MicWatch, Backoff), `audio` (FrameFanOut, PreRoll), `call` (CallRoute, EchoMeter, MicKind, RouteSteps, CallPlayer) | Kotlin stdlib |

Since 0.3.0 there are no speaker models: the voice print, the voice gate (pVAD), enrolment, the voice profile, the
model store, the phone bench and the `sherpa` module were removed, with ONNX Runtime.

## What 0.4.0 adds

Every addition is optional. An app that uses none of them builds and behaves as with 0.3.1.

**A speech detector.** Give the detector a `SpeechProbability` (the app's own model, for example Silero through
sherpa-onnx) and pass it each frame:

```kotlin
val detector = TurnDetector(config, SpeechProbability { frame -> silero.probability(frame) })
// on each microphone frame:
detector.audio(pcm, levelDB, duration, t)
```

With a speech detector, these rules apply:

- A turn starts after `speechStart` (0.25 s) of frames at or above `speechThreshold` (0.5). Each frame must also be
  above half the margin over the noise floor, so a radio under the floor opens no turn. A door slam or a cough opens
  none.
- A turn's silence counts the frames the detector calls non-speech. Soft words under the margin keep it open.
- The fallback ends a turn when speech goes on but the person's own voice has stopped. Own voice is sound above half
  the margin, and within `voiceDropDB` (15 dB) of the turn's voice level. Once own voice has been gone for the turn's
  silence plus `fallbackSilence` (3 s), the turn ends with cause `FALLBACK`. This works from sound alone, with no
  recognizer: a car radio, a passenger or the navigation voice cannot hold a turn open until `maxTurn`. A radio as loud
  as the user still can.
- Sound with no words no longer drops a turn as "held by noise". Judging that is the speech detector's job.
- While `playing` is set, `bargeInSpeech` (0.3 s) of frames at or above `bargeInProbability` (0.8), above half the
  margin, raise a `TurnEvent.BargeIn` with `bySound = true` and empty text. It comes once a turn, before any words.

An app with no recognizer still calls `caughtUp` on each `Flush`. It sets `noWordsSilence` to its pause, because a
turn with no words ends on that silence and arrives as `Dropped` with why "no words".

**The end cause.** Every `Ended` and `Dropped` carries `speechEndAt` (where the speech ended) and `cause`: `SILENCE`,
`STILL_WORDS`, `FALLBACK`, `MAX_TURN`, `FLUSH` (the app called `finish`) or `NOISE`. These fields stay out of
`equals` and `copy`.

**A pre-roll.** When the app passes frames to `audio(frame, …)`, `detector.preRoll()` returns the last
`preRollSeconds` (2.5 s) of audio, and `preRollFrom` gives the time of its first sample. Read it on `Started` to keep
the first word.

**The noise floor frozen in a turn.** Dense loud speech lifts the floor until soft last words count as silence
(`testdata/turns/loud-turn-ends-softly-floor-rises.json`). `freezeFloorInTurn = true` stops the floor rising while a
turn is open. It is off by default.

**A microphone that recovers.** `VoiceEngine` reports a recorder that will not open, start or read as
`MicHealth.Dead` or `MicHealth.Taken`, through `VoiceEngineListener.onMicHealth`, instead of throwing. It then opens
the recorder again after 0.5 s, 1 s, 2 s and so on, up to 8 s. `Blank(ms)` reports exact zeros, and `Healthy`
reports that sound came back. The audio thread runs at urgent-audio priority. Stopping never frees a recorder while a
read is inside it.

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
