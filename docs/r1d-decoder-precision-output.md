# R1d: decoder precision through Android output

Implementation date: 2026-09-09. This continues R1 after the production kernel migration in [R1c](r1c-production-effects-and-convolution.md). The release build is installed on the emulator with its existing signing key and app data preserved.

## Scope

The existing **Prefer hi-res Android output** setting now selects a decoder-side processing sink for normal Android playback and both normal crossfade players. The default remains the existing compatibility mode. Restart Aurora after changing this output preference; effect and impulse-response controls remain live. Saved presets retain the existing preference and schema.

For supported stereo PCM16, PCM24, PCM32 and float32 decoder output, the new path is:

`decoder PCM → binary64 effects → binary64 convolution → one float32 conversion → Android AudioTrack`

There is no intermediate PCM16 conversion between effects and convolution. Promoting decoded samples cannot recover precision already lost in a decoder. Float32 output has 24 significant bits; this is binary64 internal processing, not a 64-bit hardware-output claim. Android remains responsible for the output clock, system mixing and any downstream sample-rate conversion.

## Compatibility and boundaries

Media3 1.5.1's float branch omits its channel mapper, gapless trimmer and silence-skipping processor. This implementation therefore chooses a PCM16 compatibility path for streams with encoder delay/padding, channel mapping, unsupported channel layouts, or silence skipping. The existing processors and Media3 trimming remain available there. Once a padded stream uses the sink, compatibility remains until that sink resets, protecting the old trimmer's padding when subsequent tracks drain. Enabling silence skipping during float playback drains the current precision block and switches to PCM16; disabling it permits float again on the next eligible stream. Signal Path reports the actual choice and reason; requesting high-resolution output alone is not evidence of high-resolution processing.

The precision sink uses platform AudioTrack speed/pitch controls. It retains pending output across partial writes and drains valid convolution frames before a format change or end of stream. Seeking discards retained samples and histories. A convolution partition collects up to 1,023 frames before producing output; this is not a total-latency measurement. Tails remain truncated at the source duration for normal queue timing.

The new block processor prepares immutable effect coefficients and complete replacement impulse-response engines outside sample processing. The callback uses bounded reusable blocks. Existing effect order, impulse-response limits and linear IR resampling remain as described in R1c. Convolution still follows the existing limiter; this change does not add a final true-peak limiter.

Native USB, direct Cast and Mix Studio retain their existing paths. Normal collection Mix uses the normal player and can use the new sink; the separate multi-deck Studio engine still exchanges PCM16 between its processors. The app does not request Android bit-perfect mixer behavior for the new processed output.

## Observability

Signal Path distinguishes retained decoder precision, binary64 processing, the float32 output boundary, and compatibility fallback. Pre/post meters describe actual consumed buffers, before player volume and Android effects. Separate post meters prevent old PCM16 output from being interpreted as float during a pending format change. Crossfade readings describe the incoming primary player, not the sum of overlapping outputs. No hardware route or DAC precision is inferred from an enabled preference.

## Verification

| Check | Result |
|---|---|
| Gradle wrapper / JDK 21 | Debug Kotlin/resources, release unit tests, release APK and instrumentation APK passed. [Build](../_artifacts/r1d/build-verified.log), [final test-only rebuild](../_artifacts/r1d/build-test-observation.log). |
| JVM | **125 passed**, including nine new block-processor tests for input ownership, double processing, IR preparation/replacement/failure, timestamps, exact EOS and resets. [XML results](../_artifacts/r1d/unit-results). |
| Decoder-side sink | **9 passed** on the emulator: quiet PCM24 below one PCM16 LSB, DSP plus convolution, retained output identity/PTS, partial writes, exact frame counts, pending format, seek, encoded rejection, padded-stream fallback, and live silence toggles during backpressure. [Log](../_artifacts/r1d/emulator-sink.log). |
| Live precision playback | **4 passed**: measured asymmetric preamp/IR gain, live IR replacement, 44.1/48 kHz format transitions and seeking, observed 96 kHz float32 AudioTrack output, 1.25× platform speed, silence fallback, processed crossfade, and Studio Mix returning to ordinary precision playback. [Log](../_artifacts/r1d/emulator-precision-playback-verified.log). |
| Live compatibility playback | **3 passed** with the output preference disabled: IR replacement, format/seek lifecycle, crossfade, and both Studio Mix decks returning to normal playback. [Log](../_artifacts/r1d/emulator-compatibility-playback.log). |
| Existing regressions | **9 passed**: meters and format transitions, saved preset application, browse/session transport, crossfade, Mix return and collection controls. [Log](../_artifacts/r1d/emulator-regressions.log). |
| UI | Audio output description inspected at normal text size; text and controls fit. Emulator left paused on the existing local QA library track, with its original output preference restored. [Screenshot](../_artifacts/r1d/ui-output-final.png). |

**25 emulator checks passed** across precision and compatibility configurations on ResetGuardApi35 / Android 15 / x86_64. Three live scenarios run in both configurations. [Validation summary, logs and APK hashes](../_artifacts/r1d/validation-summary.json).

Captured Signal Path evidence: [96 kHz PCM24 → binary64 → float32](../_artifacts/r1d/signal-precision-96k.txt), [silence-skipping PCM16 fallback](../_artifacts/r1d/signal-skip-fallback.txt), [format change and seek](../_artifacts/r1d/signal-precision-format-change.txt), and [crossfade primary](../_artifacts/r1d/signal-precision-crossfade.txt).

The initial live test could observe valid PCM measurements before the new AudioTrack initialization notification arrived. Its output-format assertion now waits for that independent observation; the final run passed all four cases. This did not require weakening the required float32 format or changing production processing. The emulator run is not a listening test, hardware-route proof, or sustained underrun/performance measurement.

The older Mix regression fixtures again restored a deleted `mix-qa-*.wav` after their checks, producing a source-file error in logcat. The final UI check selected the persistent local QA library track and paused it. This cleanup does not count as an additional automated playback check.

## Remaining R1 work

Update: [R1e](r1e-serial-rack-and-backups.md) subsequently implements the saved serial rack/editor, graph migration, app-backup IR assets and unified binary64 global processing for compatibility/Studio paths. The list below records what remained at the R1d boundary; the R1e report and master roadmap track the current remaining work.

1. Persisted named rack nodes, schema migration, editing, node transitions and explicit final gain/limiter/tap placement.
2. Precision processing for Mix Studio, additional channel layouts, and a validated float-compatible gapless/silence path.
3. App-backup IR assets and safe asset ownership/cleanup.
4. Sustained callback, long-IR, thermal, underrun and physical output-route measurements. Emulator evidence cannot establish phone/DAC behavior.

The relevant upstream contracts are versioned: [DefaultAudioSink](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java), [AudioSink](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/AudioSink.java), [MediaCodecAudioRenderer](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java), and [TrimmingAudioProcessor](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TrimmingAudioProcessor.java).
