# R1b: portable presets, sample measurements and precision foundation

Implemented 2026-09-08 as the next reviewable R1 delivery. This adds usable preset transfer and measurement features to normal playback, and a separately validated high-precision engine prototype. It does not yet enable the new rack as a playback engine or complete R1/R2.

## Portable processing presets

Open **Settings → Equalizer & effects → Saved processing presets**. **Import preset** opens Android's document picker. A preset's **Export** action saves `<name>.aurorapreset.zip` through the system save picker. Imports retain the name, receive a new identity, and require an explicit **Apply** action. Repeated imports cannot overwrite an existing preset or change the current sound.

Each bundle contains `manifest.json` and, when referenced, `ir.wav`. The metadata contains the same complete legacy processing snapshot as R1a, with no original preset UUID, creation timestamp or local IR path. IR names and correction labels remain descriptive metadata. Other account, library, theme and alarm settings do not enter the bundle.

Validation precedes the library transaction:

- Fixed format/version and complete supported settings; strict UTF-8/JSON, duplicate-field rejection, bounded nesting and scalar sizes.
- At most two allowlisted ZIP entries; path traversal, duplicate entries, unsupported compression, excess size and CRC mismatches are rejected.
- A 128 KiB manifest, 64 MiB IR and 65 MiB archive ceiling; both declared sizes and streamed bytes are checked.
- IR dependency presence and SHA-256. WAV validation checks chunk/file bounds, PCM/float format, mono/stereo, sample rate, alignment, frame count and finite float samples before the allocating decoder can receive the file.
- Imported assets are copied to a new private immutable file, then the preset is appended in one DataStore edit. Temporary import files are removed on success and failure. File access and parsing run off the UI thread; cancelling a picker is silent.

The existing generic app backup retains its original JSON format and still does not embed IR files. Portable bundles are the supported transfer path for processing presets. Durable unused IR cleanup remains deferred: playback loads IRs asynchronously, so deletion needs ownership acknowledgements from the service as well as stored-reference checks. Deleting a preset currently preserves potentially live/shared assets.

Sources: [bundle parser/writer](../app/src/main/java/com/aurora/music/data/ProcessingPresetBundle.kt), [store transactions](../app/src/main/java/com/aurora/music/data/SettingsStore.kt), [screen](../app/src/main/java/com/aurora/music/ui/screens/settings/ProcessingPresetsScreen.kt).

## Digital sample measurements

**Signal Path** now shows independent left/right sample peak and RMS in dBFS before and after app processing on supported normal Android paths. Windows cover 100 ms of decoded audio. Full-scale and invalid-sample counters accumulate until flush/seek/format reset. Mono duplicates its reading for display purposes without counting the sample twice. PCM16/24/32 and float32 inputs are decoded with explicit little-endian scaling.

The input tap counts only newly consumed buffer ranges. A sink that retries a buffer therefore does not inflate measurements or feed duplicate samples to the visualizer. The post tap is the last app `AudioProcessor`, after convolution and before Media3 silence skipping/speed processing. It copies samples unchanged. Peak/RMS accumulation has fixed storage and performs no allocation, I/O or locking on the callback; the service creates display snapshots outside the callback. Decorative visualizer settings cannot alter the measurements.

These are **sample measurements**, not true peak, LUFS or an acoustic loudness reading. Full-scale endpoints are counted; that alone does not prove audible clipping. The two windows are not aligned, may be decoded ahead of audible output, and exclude player volume/ReplayGain/fades, Android system effects and hardware. Crossfade readings cover the current primary player, not the sum. Float bypass can provide input measurements but has no post-processor tap. Native/decoded USB, Mix and Cast expose no measurements from this implementation, preventing stale normal-player readings from leaking into those modes. Paused playback retains and labels its last readings.

The diagnostic report includes the same allowlisted numerical readings and limitations. Spectrum, aligned pre/post overlays, true peak, LUFS and final-bus metering remain later OBS work.

Sources: [meter](../app/src/main/java/com/aurora/music/playback/PcmLevelMeter.kt), [post processor](../app/src/main/java/com/aurora/music/playback/LevelMeterAudioProcessor.kt), [input sink](../app/src/main/java/com/aurora/music/playback/TappingAudioSink.kt), [Signal Path UI](../app/src/main/java/com/aurora/music/ui/screens/settings/SignalPathScreen.kt).

## Actual playback paths and ownership

This inventory describes the current code, including paths that do not use the new prototype. The Media3 dependency remains **1.5.1**, with Jellyfin's FFmpeg extension **1.5.0+1**; no dependency upgrade is part of this delivery.

| Path | Samples and processing | Gain and clock ownership | Observed output boundary |
|---|---|---|---|
| Normal Android PCM | Media3 decoder → input tap → PCM16 conversion/channel mapping → mono → Custom DSP → convolution → post tap → silence/Sonic | Service applies player gain for ReplayGain and fades; Media3/AudioTrack owns playback clock; Android effects can occur downstream | Media3 AudioTrack configuration, not hardware readback |
| High-resolution float | Supported high-resolution decoded PCM → input tap → Media3 float transport; app processors bypassed | Player/AudioTrack gain and platform playback parameters; Media3/AudioTrack clock | Configured float AudioTrack; downstream mixer remains unknown |
| Native USB FLAC | Native libFLAC → native integer transport, with native varispeed/crossfade paths | Native USB engine owns buffer/clock requests; ordinary player volume is not applied | Driver transport settings and request result; no independent physical-output proof |
| Decoded USB | Media3 decoded PCM → driver conversion/USB transport; app processing bypassed | Media3 handoff plus native transport; player gain/speed are not assumed applied | Driver-reported transport format |
| Crossfade | Two normal players and their separate chains; ownership swaps at transition | Service envelope gains once per player; independent Media3/AudioTrack clocks | Current primary player's output; combined sum is not tapped |
| Mix Studio | Per-deck stereo conversion → clip DSP → mono/global DSP/convolution → timeline gain | Mix timeline assigns player gains; each deck has its own decoder/AudioTrack | Existing Mix report; per-deck/final-bus evidence is not added here |
| Cast | Direct source handed to receiver; local DSP does not travel with it | Receiver owns decode, gain and output clock | Remote session only; receiver format/processing unknown |

The post-tap location follows the [Media3 1.5.1 DefaultAudioProcessorChain implementation](https://github.com/androidx/media/blob/1.5.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java), which appends silence skipping and Sonic after app processors. Its float-output path is a separate chain. Merely enabling float output cannot activate the new rack.

## Precision prototype and measured language choice

[The engine contract](r1b-precision-engine-contract.md) defines versioned reusable audio blocks, source/internal/output precision, presentation/frame timing, channel layout, output capabilities, and per-node latency/tail information. The prototype preserves supported integer24/32 and float decoder values in binary64, executes gain/mono/biquad nodes without intermediate PCM16 conversion, and converts once at the output boundary. It supports 64 biquads, bounded stable node identities, compiled bypass/wet-dry and smoothed gain targets, with per-node taps.

The native comparison is a **test-only** block-JNI kernel. It is packaged in the instrumentation APK, not the app. Kotlin/native recurrence parity and complete Kotlin rack timing are measured separately from real-time playback. The production audio route remains the established engine until the full legacy chain, convolution, output adapters and graph changes pass their integration gates.

Nothing Phone (3a) Pro / A059P, Android 16, arm64: 256 stereo frames, 64 bands, 256 warmup and 1,000 timed calls per case. Values below are microseconds; [captured measurements](../_artifacts/r1b/phone-precision-benchmark.json).

| Rate | Block duration | Kotlin kernel p95 | Native block JNI p95 | Complete Kotlin rack p95 | Complete rack maximum |
|---|---:|---:|---:|---:|---:|
| 48 kHz | 5,333.3 | 106.198 | 94.063 | 125.000 | 149.375 |
| 96 kHz | 2,666.7 | 102.084 | 94.063 | 123.073 | 146.875 |
| 192 kHz | 1,333.3 | 102.552 | 94.114 | 122.969 | 181.146 |

No case exceeded its block duration in these samples. The complete rack includes PCM24 decoding, preamp, 64 biquads, taps and final float32 conversion. Native kernel median was approximately 8% lower than the equivalent Kotlin reference, including JNI copies. **Provisional decision:** retain Kotlin scheduling/conversion/rack work and measure convolution/FFT separately before introducing a native production boundary. This small kernel benefit alone does not justify migrating the whole engine. The two test-only native libraries total 9,936 uncompressed bytes across arm64/x86_64 and are absent from the production APK. These short worker-thread benchmarks do not establish sustained real-time performance, thermal behavior or IR processing deadlines.

## Verification

| Check | Result and evidence |
|---|---|
| Wrapper build, explicit JDK 21 | Debug Kotlin/resources, release JVM tests, signed release and release instrumentation builds passed. [Verified build](../_artifacts/r1b/build-verified.log), [final UI-only message build](../_artifacts/r1b/build-ui-final.log), [resource build](../_artifacts/r1b/compile.log). |
| JVM | **83 passed**, including 14 bundle, 17 precision and 7 meter tests added in this delivery. [XML results](../_artifacts/r1b/unit-results). |
| Phone, Android 16 | **13 distinct checks passed**: 5 bundle/precision checks and 8 meter/lifecycle/preset/browse/transport checks. [Core](../_artifacts/r1b/phone-core-tests.log), [playback](../_artifacts/r1b/phone-playback-verified.log). |
| Emulator, Android 15 | **17 distinct checks passed across runs**: bundle/precision, meter/formats, prior presets, normal browsing/transport, crossfade, Mix return, collection controls and alarms. [First run](../_artifacts/r1b/emulator-verified.log), [foreground rerun and regressions](../_artifacts/r1b/emulator-foreground-tests.log), [harness diagnosis](../_artifacts/r1b/emulator-foreground-validation.json). |
| Live measurement | A known stereo WAV measured unity before/after flat processing and the expected gain after a −12 dB preamp change. Retry tests verify exact consumed-frame counts, timestamps and untouched PCM. Format tests cover pending configuration, flush activation, reset and shared empty drain buffers. |
| Phone UI | System-picker export/reimport, cancellation of both pickers, malformed-file rejection, deletion and normal/150% text layouts inspected. Import did not apply sound settings. Test presets/files removed and font scale restored to 100%. |

The first live checks exposed a shared-empty-buffer copy in the meter processor; the processor now ignores empty drain calls, and a dedicated test covers it. A separate emulator rerun initially hit Android 15 background audio-focus denial. Bringing Aurora to the foreground after instrumentation startup resolved all three playback timeouts without code changes. The emulator regression log also contains a source error for an already-deleted older QA WAV while restoring its previous test queue; all requested checks passed and no new meter/renderer exception occurred.

The final release was installed on the phone with `install -r`, preserving app data. Emulator builds use its existing debug signature; the physical phone retains the release signature. No wipe or uninstall was used. Existing output preferences and device-correction policies are preserved.

Visual evidence: [preset screen](../_artifacts/r1b/presets-empty.png), [export action](../_artifacts/r1b/export-menu.png), [save picker](../_artifacts/r1b/export-picker.png), [export result](../_artifacts/r1b/export-complete.png), [import result](../_artifacts/r1b/import-complete.png), [invalid-file rejection](../_artifacts/r1b/invalid-rejected-final.png), [preset text at 150%](../_artifacts/r1b/presets-large.png), [Signal Path measurements](../_artifacts/r1b/signal-levels.png), [measurements at 150%](../_artifacts/r1b/signal-levels-large.png).

## Remaining work in order

**2026-09-09 update:** [R1c](r1c-production-effects-and-convolution.md) completes the arithmetic/state port of the existing effects and convolution in their production PCM16 wrappers, with emulator playback coverage. The high-resolution adapter and persisted/editable rack integration below remain pending.

1. Port the remaining legacy effects and convolution to the chosen precision architecture. Record mixed precision explicitly until every active node is migrated; preserve the existing order and sound.
2. Build the production adapter at a boundary that receives high-resolution decoder samples. Preserve all bypass/output modes and test normal, crossfade, Mix and USB routes separately.
3. Persist/migrate named rack nodes and add the serial rack editor, state-preserving smooth changes, final-bus gain/limiter placement and output taps. Complete portable graph assets and app backup integration, plus safe asset ownership/cleanup.
4. Meet full R1 acceptance with representative IRs and 64 PEQ bands on the real audio thread, sustained callback/thermal tests, graph-swap listening/numerical checks and playback regressions.
5. Then proceed to R2's 64-band editor, tuning projects, FIR manager, route-confirmed preset bindings and headroom tools. The prototype's 64-band capacity is not yet a user-facing 64-band editor.
