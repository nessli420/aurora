# R2a: strict EQ text interchange and calculated response

Implementation and validation notes for the continuation of [R1e](r1e-serial-rack-and-backups.md), 2026-09-09. The [master roadmap](music-player-completeness-plan.md) remains the scope reference; R2 is still in progress.

## Implemented user flow

Settings → Audio → Processing rack now offers **Import EQ text**. The dialog accepts pasted text or a UTF-8 file from Android's document picker. A selected file supplies an editable name. Parsing produces a preview of the preamp, every imported parametric band and the stages that will be added; malformed input displays a line-numbered error.

**Append stages** adds an optional Gain stage for a nonzero preamp, followed by one dedicated Equalizer stage. Both enter the rack in one validated settings write. The append preserves existing stages, their order and IDs, and the current Standard/Rack selection. An inactive rack stays inactive; an active rack applies the appended stages to playback. New stages receive persistent UUIDs, and the imported EQ has no graphic-band payload. Capacity is checked again against the current graph before writing, so an import cannot partially add its Gain stage and then fail to add its EQ stage.

Stages are appended at the end. Import does not move an existing limiter or rewrite a recommended ordering; users can arrange the new stages with the rack's existing move controls.

Dedicated Equalizer stage editors now expose **Export parametric EQ text** and **Calculated response**. These actions are not offered as complete exports or response plots of a Legacy DSP stage, which also contains other effects.

## Text format and limits

`RackEqTextCodec` is separate from the existing AutoEq repository/parser. Its supported subset follows the [Equalizer APO configuration reference](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/) and maps directly to Aurora's existing peak and center-frequency shelf filters:

```text
Preamp: -6.25 dB
Filter 1: ON PK Fc 1000 Hz Gain -3 dB Q 1
Filter 2: ON LSC Fc 85.5 Hz Gain 2.25 dB Q 0.707
Filter 3: ON HSC Fc 12000 Hz Gain -1.5 dB Q 0.9
```

| Field or resource | Accepted bound |
|---|---|
| Input size | 256 KiB of UTF-8 text |
| Lines / characters per line | 2,048 / 4,096 |
| Numeric token length | 64 characters |
| Active parametric filters | Up to 64 per imported profile |
| Rack capacity after append | 64 total parametric bands and 16 total stages |
| Frequency | 10–24,000 Hz |
| Filter gain | −30 to +30 dB |
| Q | 0.1–100 |
| Preamp | −60 to +24 dB |

The rack-wide parametric budget includes dedicated EQ and Legacy DSP stages, including bypassed stages. The existing Legacy DSP limit remains 12 parametric bands; imported profiles use a dedicated EQ stage. Graphic bands do not count toward the parametric budget, but each EQ stage retains its separate existing graphic-band limit.

Commands and units are case-insensitive. Decimal and scientific notation, spaces/tabs, CRLF/LF line endings, a leading UTF-8 BOM and `#` comments are accepted. Filter numbers are optional labels and are not interpreted, as in APO; repeated labels do not remove or replace filters. File order is retained. A missing Preamp means 0 dB. An explicit preamp-only or neutral profile is valid; blank/comment-only input is rejected.

The importer rejects unsupported or malformed commands as a whole. It does not silently drop channel/device selectors, includes, expressions, convolution, graphic EQ, delay, other filter families, bandwidth-in-octaves parameters or shelf slope/corner variants. Only PK, LSC and HSC with explicit frequency, gain and Q are accepted. Multiple Preamp commands are rejected with a request for one combined value; APO can sum multiple preamps, so keeping only the last value would change the profile.

`OFF` filter rows are rejected with an instruction to remove or comment them out. The current `ParsedEq` model cannot retain disabled-band metadata, so treating these rows as successfully imported would lose that information. Per-band bypass remains separate work.

Numeric values must be finite and within the supported ranges. Values that underflow to zero are rejected. The Q floor is deliberately **0.1**: current playback clamps smaller Q values, so accepting or exporting those values as exact text interchange would misrepresent the applied response. Older full processing presets may retain Q values below 0.1, and the response view reflects the existing playback floor. This batch does not change those playback semantics.

Text export uses locale-independent `Float.toString()` representations. Parsing an exported accepted profile preserves its Float values, including scientific notation and signed zero; UI display rounding does not change the exported values. This is numeric round-trip preservation, not preservation of original comments, whitespace or filter labels.

## Parametric-only export boundary

The export dialog clearly names the operation **Export parametric EQ text**. It writes the selected stage's parametric filters with `Preamp: 0 dB` through Android's Create Document flow.

The text omits graphic EQ, stage wet/dry, stage bypass, separate Gain stages, all other rack stages, convolution assets and transport/output settings. A stage with nonzero graphic gains receives an additional omission warning; export remains an explicit parametric-only operation. A profile that cannot be represented by the strict codec, including Q below 0.1, displays the error and disables saving.

The dialog links to full processing presets for users who need to preserve the complete graph and its sound settings. A parametric export must not be described as a full-rack backup or as the complete effective response of the selected stage.

## Calculated magnitude, phase and group delay

The dedicated EQ response view uses the same prepared coefficient bank as the production EQ node. It includes that node's graphic and parametric filters, same-input wet/dry mix and bypass setting. It is a calculation for **one EQ stage**, not a measurement of the complete rack, speakers/headphones, Android mixer or output hardware. Separate gain, convolution, nonlinear effects and downstream processing are outside its scope.

The card provides Magnitude, Phase and Group delay tabs, a logarithmic frequency graph, tap selection and a frequency slider with a numerical readout. It uses the currently reported decoder sample rate when available; otherwise it explicitly labels a 48 kHz preview. An inactive rack is marked as preview-only.

The default curve has 512 logarithmically spaced points from 20 Hz to 20 kHz, with its upper end capped strictly below the selected sample rate's Nyquist frequency. The calculation API supports 32–4,096 points and stream rates from 8–768 kHz. Filters outside the current stream's usable frequency range follow the production coefficient builder's bypass behavior.

Magnitude is shown in dB with a −180 dB numerical floor. Phase is unwrapped separately over contiguous valid sections. Phase and group delay are undefined below −120 dB response magnitude and appear as gaps, rather than misleading infinities or interpolated values through a null. Very narrow features can require denser sampling than the default display grid.

Group delay uses the analytic derivative of the complex transfer function and is reported in milliseconds. Negative values are retained. It is the EQ transfer function's group delay, **not device, Bluetooth, buffering or end-to-end playback latency**.

## Playback and persistence fixes

Crossfade now carries Aurora's shuffle flag to the incoming player while assigning an identity native shuffle order. Aurora still physically reorders the queue. Previously the incoming player's false flag could make the next toggle overwrite the saved original order, so turning shuffle off no longer restored the expected queue. A service regression exercises a pre-reordered queue, the handoff, and restoration of the original order without changing the current track.

Queue persistence now orders the snapshot and atomic file write under the same lock. An awaited account restore can no longer be overwritten by an older ordinary flush that finishes late. Restore publishes the in-memory replacement only after the durable write succeeds and preserves other accounts. Three device tests cover write ordering, originally absent accounts and failed-write rollback.

Signal Path now shows Media3's reported primary-player AudioTrack underruns since that player was created. The counter is not a total across crossfade players, does not measure the analog output, and remains unavailable for unobserved paths.

Playback fixtures now suppress external scrobbling while running and restore saved settings, the original queue, its durable account snapshot and local listening history. Precision fixtures skip a pre-existing shuffled session, and relevant playback fixtures guard active exclusive/remote/Mix sessions. Playback meter windows intentionally clear during seek/format flushes; the sustained test allows up to two seconds of seek recovery and requires fresh output afterward.

## Automated validation

The wrapper build uses JDK 21. `compileDebugKotlin`, `processDebugResources`, `testReleaseUnitTest`, `assembleRelease` and `assembleReleaseAndroidTest` completed successfully. All **187 JVM tests passed**, with no failures, errors or skips: the prior 164 plus 15 strict-codec cases and 8 response cases. Build and runtime logs are under `_artifacts/r2a/`.

The added codec coverage includes 64/65-band boundaries, peak/shelf parsing, exact Float and locale round trips, malformed and unsupported commands, OFF rows, duplicate preamps, non-finite/underflow/out-of-range numbers, input bounds, complete node/band capacity checks, unchanged existing graphs and detached immutable snapshots.

The response tests cover neutral/bypassed/dry stages, sample-rate/Nyquist bounds, the sixty-fourth filter, wet mixing, known delay/phase cases, undefined response nulls, production impulse-response comparison and stepped-sine comparison. These are software-response checks; even when they pass, they do not establish hardware response or latency.

| Validation item | Result / evidence |
|---|---|
| Kotlin/resource compilation | Passed; wrapper/JDK 21 |
| JVM suite, including codec and response tests | 187 passed, zero failures/errors/skips |
| Release APK and instrumentation APK | Signed release variants built and installed with `adb install -r` |
| Instrumentation regressions | 87 covered phone scenarios have passing results; no remaining failures or skips |

| Phone batch | Passing scenarios | Log |
|---|---:|---|
| Rack/sink adapters, rack and backup persistence, queue durability, synthetic rack timing | 33 | `phone-final-core.log` |
| Rack through float output | 6 | `phone-rack-float.log` |
| Rack through PCM16 compatibility output | 5 | `phone-rack-pcm16.log` |
| Standard processing through float output | 5 | `phone-standard-float.log` |
| Standard processing through PCM16 | 4 | `phone-standard-pcm16.log` |
| Sustained dense rack, two rates | 1 | `phone-sustained-final.log` |
| Presets/bundles, meters and 11 existing Mix scenarios | 20 | `phone-regressions.log` |
| R0 service/Signal Path rerun plus effects/convolution buffer contracts | 13 | `phone-r0-and-effects-final.log` |

The original 21-case regression batch had 20 passes and one R0 test failure: its wait matched the word “Custom” in the requested-but-unapplied list before DSP preparation completed. It now waits for the observed active-processing reason, then retains its original MODIFIED assertion. That case passed in the final 13-case batch. The table counts successful scenarios, excludes the failed attempt and does not double-count its rerun. The separate initial sustained-test failure is documented below.

Existing Mix coverage included collection playback in the normal queue, bounded decks, seek/pause/live controls, remote buffering, error propagation, saved-project account isolation, service/session transitions, streamed library analysis and complementary vocal/backing stems. The phone separated the 12-second synthetic fixture in 44.353 seconds and played its resulting stems. This is a functional inference/output check, not a vocal-isolation quality review.

## Phone validation

The connected **Nothing Phone (3a) Pro / A059P, Android 16 (API 36)** was used throughout this pass. Its online state was checked before installing the signed release APK and instrumentation APK with `adb install -r`; app data was retained. The real Navidrome Home library loaded, the current track remained available, and Alarm and Signal Path remained separate settings destinations.

Phone UI checks used a 54-filter file with a −6 dB preamp alongside the existing 10-band Legacy stage. Preview showed the preamp and two new stages, and append produced **4 stages / 64 total parametric bands** while remaining in Standard mode. A pasted unsupported HP filter was rejected with a line-specific error. The imported stage showed magnitude, phase and group delay at the reported 44.1 kHz decoder rate; graph taps updated the numerical readout and negative group delay remained visible. Native phone screenshots were inspected for layout and legibility.

The export disclosure explicitly listed the omitted settings. Android's document picker saved the file; pulling it back confirmed all 54 filter types/frequencies/gains/Q values in their original order and the disclosed 0 dB export preamp. Both temporary stages were then removed, restoring the original inactive two-stage / ten-band rack. Boundary, malformed, OFF and Q-floor combinations beyond the manual phone case are covered by the codec tests, not claimed as separate UI executions.

| Phone check | Result / evidence |
|---|---|
| Device identity, online status, APK installation | A059P / Android 16, successful data-preserving release install |
| Import/preview/append/error flows | Passed; `phone-import-preview.png`, `phone-import-added.png`, `phone-import-error.png` |
| Parametric export disclosure and file contents | Passed; `phone-eq-export-preview.png`, `phone-eq-export.txt` |
| Magnitude/phase/group-delay display and point inspection | Passed; `phone-eq-response.png`, `phone-eq-phase.png`, `phone-eq-group-delay.png` |
| Active playback and retained settings after validation | Real streaming track advanced from 0:13 to 0:17, then paused; original rack restored; `phone-final-player-before.png`, `phone-final-player-after.png`, `phone-final-rack-restored.png` |

The final build's group-delay formatting and Signal Path underrun row were checked again (`phone-final-group-delay.png`, `phone-final-signal-path.png`). Real-library playback reported zero underruns. Its existing user sound settings produced three digital full-scale samples since reset; these are endpoint counts, not proof of audible clipping, and the user's processing settings were retained.

Installed release APK SHA-256: `24BA8320F0F02DCC99EBCDB9FA93653FAA6C7EC4BA419266FF83A418D8926C57`. Machine-readable counts, build identities and validation boundaries are in `_artifacts/r2a/validation-summary.json`; JVM result XML is retained in `_artifacts/r2a/jvm-results/`.

### Sustained real playback

`RealPlaybackRackDeviceTest` played a nine-stage rack through the actual Media3 service and AudioTrack: input gain, 64-band EQ, saturation, stereo width, crossfeed, compression, channel delay, a 4,096-frame convolution IR and a final limiter. Each sample rate ran for 60 seconds after warmup. During each run the activity went to the background, the EQ wet amount changed to 70%, the activity returned, and playback sought forward. The test required the actual prepared graph description, fresh positive output, correct rate, no renderer error, no invalid/full-scale samples and the live 70% wet state.

| Observation | 48 kHz | 96 kHz |
|---|---:|---:|
| Measured playback window | 60.085 s | 60.042 s |
| Reported new AudioTrack underruns | 0 | 0 |
| Observed clock stalls at 250 ms polling | 0 | 0 |
| Maximum observed meter age | 564 ms | 576 ms |
| Brief empty meter polls during seek | 0 | 1 |
| AudioTrack boundary | Stereo float32 / 48 kHz | Stereo float32 / 96 kHz |
| Thermal status samples | All 0 | All 0 |
| Background/resume, live edit and seek | Passed | Passed |

Evidence: `phone-sustained-final.log`, `phone-sustained-final.json`, `phone-path-48000.txt`, and `phone-path-96000.txt`. An earlier test attempt stopped because it required a non-null meter during the 96 kHz seek flush; the retained initial log documents that failure. The revised test accepts only a bounded seek gap and then requires output recovery. The rerun passed both complete windows.

AudioFlinger snapshots matched Aurora's active stereo float track to the speaker mixer, which ran at 48 kHz, including during the 96 kHz stream test. This confirms a functioning Android speaker path, not a 96 kHz DAC or bit-perfect route. Two minutes of measured playback is not a long-term thermal/battery test or an acoustic listening certification.

## Remaining scope

This slice adds strict parametric text interchange and calculated response for dedicated EQ stages. It does not add arbitrary APO execution, response measurement, automatic curve fitting, independent left/right correction, new filter families, per-band bypass, impulse-response management, route-bound preset automation or full-rack response modeling. External USB DAC, Bluetooth, Cast, car-head-unit behavior, acoustic quality and extended thermal/battery acceptance remain unverified by this pass. The test results establish the covered phone scenarios, not a guarantee that every app path is free of bugs.
