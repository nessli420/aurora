# R1c: production effects and convolution

Date: **2026-09-09**. Continues [R1b](r1b-portability-measurements-and-engine.md) and the [completion plan](music-player-completeness-plan.md). Validation in this delivery uses the Android 15 emulator; the phone is disconnected.

## Scope and precision boundary

The existing player now runs Custom DSP and convolution with binary64 coefficients, arithmetic and signal histories. Normal playback, the incoming crossfade player, and Mix's clip/global processors use the upgraded wrappers through their existing construction paths. Saved preferences, preset schema, effect order and endpoint quantization conventions are retained.

This is **not yet the complete R1 high-resolution path**. Media3's ordinary processor chain still supplies stereo PCM16 and each wrapper still outputs PCM16. Samples can be narrowed before Custom DSP and again between it and convolution. The separately tested serial rack remains a prototype; the reusable effects/convolution kernels are not yet persisted or exposed as editable rack nodes. Android float bypass, native USB and direct Cast keep their existing routes.

Signal Path names the binary64 work and PCM16 boundaries separately. Mix describes the processing schedule without inventing per-deck measurements. Convolution preparation and failure are visible; a preparation failure uses dry audio.

## Implemented behavior

### Existing effects

`PrecisionDspCoeffBuilder` prepares immutable binary64 graphic/parametric EQ coefficients, including peaking and both shelf types. `PrecisionEffectsKernel` preserves the existing order:

1. Graphic and parametric EQ.
2. Smoothed preamp/balance and channel trim.
3. Asymmetric saturation, width and crossfeed.
4. Stereo-linked compressor, then limiter.
5. Independent channel delays.

The 31 graphic plus 12 parametric slots, smoothing time, parameter ranges and integer delay-frame mapping remain compatible. The existing limiter is an attack/release gain stage, not a new brick-wall or true-peak limiter. Convolution still follows it; final-bus limiting remains future work. Improved arithmetic can change numerical rounding, so this is not a promise of bit-identical enabled processing.

`AuroraDspProcessor` keeps its disabled byte-copy path and legacy PCM16 conversion convention. Pending format negotiation no longer resets or retunes audio still draining in the old format. Flush/seek clears EQ, crossfeed, dynamics, smoothing and channel-delay histories. Runtime evidence follows actual processed buffers rather than Media3's pending `isActive` format.

The legacy comparison exposed accumulated float filter roundoff rather than an effect-order change. For the fixed rich-effects fixture, the full old/new difference is 0.000426 peak and 0.000146 RMS. The new EQ agrees with an independent transposed-form double recurrence within 1.65e-12 peak; non-EQ old/new difference is 1.54e-6 peak. Separate tests retain strict independent-reference and non-EQ bounds. [Diagnostic source and measurements](../_artifacts/r1c/effects-analysis/comparison.txt).

### Convolution

`PrecisionConvolver` provides stereo binary64 partitioned overlap-save processing with fixed work/output storage. `ConvolutionProcessor` prepares complete replacement stereo engines on a shared worker and adopts them on the playback thread. The latest requested IR wins; callbacks apply backpressure while preparation is pending. Makeup-only updates preserve the filter history. Valid buffered frames from an old IR are drained before replacing it or entering bypass.

- Production EOS emits exactly the number of input frames, including a final partial FFT block. It does not append the old padding or overwrite a full input FIFO.
- Reverb beyond the track's input duration remains truncated to preserve queue/gapless timing. The reusable kernel separately supports an explicit full-tail mode for future graph/output integration.
- A 1,024-frame partition collects up to 1,023 input frames before output. It does not insert leading silence. This is buffering information, not a measurement of total output latency.
- Flush invalidates all prior convolution history. Preparation, PCM decoding and buffer storage are bounded; oversized IRs fail instead of being silently shortened.
- WAV integer samples are decoded directly into doubles, including PCM32; float32 IRs retain their original precision. WAV structure, alignment and finite samples are checked. The existing linear IR resampling model is retained and is not a new high-quality sample-rate converter.
- The prepared filter limit is **262,144 frames per channel at the playback rate**, approximately 32 MiB for stereo FFT spectra/history per instance. Streaming WAV loading permits at most **1,048,576 source frames per channel** (16 MiB of decoded stereo doubles), uses a 64 KiB I/O buffer and skips metadata chunks without loading the whole file. WAV containers remain limited to 64 MiB. Valid extensible PCM/IEEE-float WAVs are supported with validated subformat GUID and valid-bit fields. These are resource bounds, not real-time performance guarantees for every permitted IR.

The preparation/control work is outside sample processing. DSP format setup still allocates/prepares its small fixed bank during Media3 configuration. Full sustained callback, underrun, thermal and representative long-IR measurements remain an R1 acceptance gate.

## Verification

| Check | Result |
|---|---|
| JDK 21 Gradle wrapper | Debug Kotlin/resources, release JVM tests, release APK and release instrumentation APK passed. [Full build](../_artifacts/r1c/build-verified.log), [final build after the Signal Path fix](../_artifacts/r1c/build-signal-path-fix.log). |
| JVM | **116 passed**, including 16 effects, 10 convolution and 7 WAV checks added in this slice. [XML results](../_artifacts/r1c/unit-results). |
| Emulator processor checks | **12 passed**: effects wrapper, convolution wrapper, and the complete Media3 processor pump with an oversized input buffer and pending-format/EOS transitions. [Log](../_artifacts/r1c/emulator-core.log). |
| Emulator live playback | **3 passed**: measured asymmetric IR/preamp gain, live IR replacement, 44.1→48 kHz transition, seeking, pause/resume, processed crossfade handoff, and two processed Mix decks returning to normal playback. [Verified log](../_artifacts/r1c/emulator-playback-verified.log). |
| Existing emulator regressions | **9 passed**: meters, meter format lifecycle, saved preset application, browse/session transport, crossfade, Mix return and collection controls. [Log](../_artifacts/r1c/emulator-regressions.log). |
| Emulator UI | Settings and Signal Path inspected at 100% text size with the existing local QA track. The mono track correctly reports Custom DSP bypass; the longer precision description wraps within its card. [Settings](../_artifacts/r1c/settings-audio.png), [levels](../_artifacts/r1c/signal-path-final.png), [processing](../_artifacts/r1c/signal-processing-final.png). |

**24 distinct emulator checks passed**, on ResetGuardApi35 / Android 15 / x86_64. The release build was copied and signed with the emulator's existing debug key for `install -r`; app data was preserved. The production release APK retains its release signature. [Validation summary and hashes](../_artifacts/r1c/validation-summary.json).

Captured live evidence: [initial processing](../_artifacts/r1c/signal-initial.txt), [after format change and seek](../_artifacts/r1c/signal-format-change.txt), [crossfade primary](../_artifacts/r1c/signal-crossfade.txt). The crossfade readings still describe the primary player before player-volume fade gain, not the sum of both outputs.

The regression logcat contains a source error when an older Mix test restored an already-deleted QA WAV; all nine checks passed. No new processor/renderer exception was found. The emulator was subsequently left paused on its persistent local QA library track. This pass does not validate phone/DAC routes, listening quality, high-resolution end-to-end preservation or sustained hardware performance.

The focused checks cover analytic EQ/effects behavior, comparison with frozen legacy float math, low-level samples, block partitioning, independent channels, direct convolution references, exact EOS counts, reset, pending formats, background preparation and the actual Media3 processor pump. End-to-end tests use known stereo tones and attenuation IRs to measure before/after gains during live replacement, seeking, sample-rate changes and crossfade handoff.

The first emulator playback run found an existing evidence race: a delayed `onAudioDisabled` analytics event could clear a newer sink configuration after a track change. Audio continued, but Signal Path lost its input format and measurements. The sink now exclusively owns its decoded-format evidence through configure/reset callbacks; the unchanged playback tests serve as regressions for that race.

Media3 lifecycle decisions were checked against its versioned [BaseAudioProcessor](https://github.com/androidx/media/blob/1.5.1/libraries/common/src/main/java/androidx/media3/common/audio/BaseAudioProcessor.java) and [AudioProcessingPipeline](https://github.com/androidx/media/blob/1.5.1/libraries/common/src/main/java/androidx/media3/common/audio/AudioProcessingPipeline.java) contracts: configuration remains pending until flush, and processing may consume zero input under backpressure.

## Next implementation batch

1. Delivered for eligible normal Android playback in [R1d: decoder precision and output](r1d-decoder-precision-output.md), with explicit compatibility fallbacks. Mix Studio and broader float gapless/channel support remain future work.
2. Add persisted named rack nodes, schema migration, graph editing, state-preserving transitions, and intentional final gain/limiter/tap placement.
3. Complete app-backup IR assets and safe asset ownership/cleanup.
4. Measure representative 64-band and IR chains on a physical device's real audio thread, including sustained performance and output-route checks. Then advance to R2 tuning/editor features.
