# R1e: saved serial racks, 64-band editing and portable app backups

Implementation date: 2026-09-09. This is a larger continuation of [R1d](r1d-decoder-precision-output.md), including the first 64-band EQ slice from R2. The master roadmap remains in [music-player-completeness-plan.md](music-player-completeness-plan.md).

Follow-up: [R2a](r2a-eq-import-response-and-phone-validation.md) adds strict EQ text import/export and calculated response views, fixes crossfade shuffle continuity, and records physical-phone validation of the current rack, backup and playback paths. The R1e results below retain the original batch's evidence and boundaries.

## User-facing changes

Settings → Audio now has a **Processing rack** dock. A rack holds up to 16 named stages in a persisted top-to-bottom order. Stages can be edited, renamed, moved, bypassed, duplicated or removed, with independent wet/dry controls. Available stages are legacy DSP, gain, equalizer, saturation, stereo width/balance/channel trim, crossfeed, compressor, limiter, delay and convolution. One convolution stage can occupy any position.

Equalizer stages support **64 parametric bands in total across the rack**, with frequency, gain, Q and peak/low-shelf/high-shelf controls. The existing legacy stage remains limited to 12 parametric bands. The compact list opens one numeric band editor at a time. Whole-stage bypass is available; per-band bypass, expanded filter families and response/phase/group-delay plots remain R2 work.

The Standard/Rack selector makes ownership explicit. An enabled rack owns EQ, channel and effect settings; ordinary mono/EQ controls do not silently rewrite its saved nodes. Equalizer & effects links to the active rack and provides an explicit return to standard settings. Choosing global Off or System disables the rack atomically. ReplayGain, transport and output preferences retain their separate roles.

Existing settings and V1 presets migrate to a **disabled Legacy chain**, preserving ordinary playback and the existing effects-before-convolution order. Copy standard settings explicitly snapshots current settings, including mono. The optional **Ordered processing** template puts the limiter after convolution; migration never silently changes limiter placement. A rack is saved automatically, and pending writes finish before its Back/preset/Signal Path navigation.

## Engine integration

The production graph compiles off the audio callback. Stable node IDs carry compatible EQ/effect history across edits. Once graph management is active, edits blend old and new schedules over 20 ms using matching source frames, with a bounded input split at the next convolution block boundary. Bypass, wet/dry and serial ordering run in binary64; there is no quantization between rack nodes. Convolution wet/dry uses an aligned dry FIFO rather than mixing delayed and current frames.

Output paths are now:

| Playback path | Global processing |
|---|---|
| Eligible hi-res Android normal playback/crossfade | Decoder PCM → binary64 rack or standard chain → float32 output |
| PCM16 compatibility normal playback/crossfade | Media3 PCM16 conversion/trim → binary64 rack or standard chain → one PCM16 output conversion |
| Studio Mix | Per-clip stereo/DSP → PCM16 boundary → binary64 global rack or standard chain → PCM16 output → timeline gains |

The PCM16 compatibility adapter replaces the separate global mono/DSP/convolution processors, so effects and IR no longer quantize between those global stages. Dry PCM16 identity is retained; enabled legacy effects can differ by output least-significant bits from the earlier separately quantized wrappers. Studio still has a PCM16 boundary after per-clip DSP. Exclusive USB and direct Cast continue to bypass the local rack. Android's mixer, output clock and downstream hardware remain outside these precision guarantees.

Signal Path reports the actually active technical node order, bypass/wet information and output conversion. Mix reports active deck processing without pretending to measure the summed output. Preparing or failed edits retain the previous ready graph where available; diagnostics distinguish this from a new dry-convolution fallback.

Lifecycle limits: the first live activation from the pre-rack legacy adapter drains its accepted partial convolution block before starting the rack; the 20 ms graph blend applies thereafter. Large FFT histories are not copied into a replacement convolver, so long reverb tails are not fully preserved across an IR/graph edit. Track EOS truncates the IR tail at source duration. The existing linear IR resampler and stereo-only topology remain; parallel branches and true-stereo convolution are separate roadmap items.

## Presets and backups

Preset schema and portable bundles advance to V2 and read V1. Complete rack snapshots retain stable node IDs, order, effect values, bypass and wet/dry. Preset IR assets remain immutable and include content hashes. The selected IR is shared with the rack's single convolution stage; it need not have the standard convolution switch enabled.

Backup & restore exports **aurora-backup.zip**, containing settings, racks, saved presets, on-device playlists/likes, history and all referenced IR files. Music downloads are excluded. Identical IR content is stored once, and local paths become content references. Restore stages and validates metadata and all required assets before changing preferences, then installs fresh immutable assets and remaps both current and saved references.

Validation rejects malformed/duplicate JSON fields, wrong types, unsupported versions, non-finite values, oversized inputs, invalid graphs, missing required IRs, unknown ZIP entries, path traversal, duplicate entries and incorrect entry size/CRC/content hash. Limits are 16 MiB metadata, 64 MiB per WAV asset, 101 distinct IR assets and 512 MiB total archive with header headroom. Older JSON backups are accepted; their old absolute IR paths are removed and convolution is bypassed until an IR is selected again.

Restore awaits atomic, fsynced file replacement for on-device playlists and history before committing preferences. Recoverable failures roll back completed writes; cancellation waits for the commit or rollback to finish. Existing IR assets are retained so active players and older snapshots cannot lose a file during restore. Unreferenced-file garbage collection is deferred. There is no cross-store crash journal; this is not a guarantee against power loss midway through replacing multiple stores.

## Validation

The Gradle wrapper with JDK 21 completed `compileDebugKotlin`, `processDebugResources`, `testReleaseUnitTest`, `assembleRelease` and `assembleReleaseAndroidTest`. **164 JVM tests passed**. The release app was installed on `emulator-5554` (ResetGuardApi35, Android 15/API 35, x86_64), re-signed with the emulator's existing debug key; app data was retained. No phone was used.

**59 distinct emulator test scenarios passed** during this batch:

| Coverage | Passed |
|---|---:|
| PCM16 rack adapter: exhaustive identity, backpressure, quiet signals, convolution, live edits, EOS/seek/format lifecycle | 8 |
| Rack DataStore/preset migration, atomic mode changes and malformed input | 5 |
| Precision sink buffer identity, timestamps, padding, fallback and format lifecycle | 9 |
| Complete app-backup/IR roundtrip, missing dependencies, legacy JSON, forced disk failures and rollback | 7 |
| Dense production rack reference/timing probe | 1 |
| Rack through normal/crossfade/Studio, live IR replacement, sample-rate/seek changes and wet/bypass/disable: PCM16 / float | 4 / 5 |
| Standard processing through the same service paths: PCM16 / float | 3 / 4 |
| Existing presets/bundles, meters, MediaLibrary/session transport, Collection Mix and crossfade regressions | 13 |

The first dense-rack reset assertion incorrectly assumed silence must produce zero after asymmetric saturation. It now compares every output sample against independently compiled fresh racks after flush and reset, retaining exact duration checks. A speed check initially sampled the asynchronous AudioTrack rate-change edge; it now requires a full measured interval at 1.10–1.40× for a requested 1.25× speed. Both corrected checks passed. The final backup-ownership adjustment was followed by all JVM tests, rebuild/install and all seven backup device checks.

The synthetic probe ran nine stages, 64 PEQ bands and a 4096-frame stereo IR for four measured seconds per rate. It recorded 48 kHz p50/p95 = **141.8/373.1 µs**, and 96 kHz p50/p95 = **142.1/339.6 µs** per accepted 256-frame input. Maximum intervals were **36.34/40.76 ms**, with **43/1** intervals exceeding their equivalent block budgets. These include process scheduling pauses; this worker-thread probe has no AudioTrack and proves neither absence of underruns nor sustained phone performance. Process-wide memory snapshots include the app/UI and are not allocation/leak measurements. Frame counts, finite output and reset references passed.

Visual checks covered the new dock, adding/editing/reordering/removing an EQ stage, a persisted −3.5 dB low shelf, the band dialog at 100% and 130% font scale, and Backup & restore's system picker and successful ZIP export. The font scale and temporary EQ stage were restored. After older Mix fixtures restored a deleted temporary media item, the persistent local QA track was selected and paused; that fixture cleanup is not an additional automated check.

Evidence: [validation summary and APK hashes](../_artifacts/r1e/validation-summary.json), [build](../_artifacts/r1e/final-build.log), [JVM totals](../_artifacts/r1e/jvm-summary.json), [dense-rack report](../_artifacts/r1e/engine-stress.json), [rack screen](../_artifacts/r1e/rack-initial.png), [large-font band editor](../_artifacts/r1e/rack-band-large-font.png), [backup export](../_artifacts/r1e/backup-exported.png). The summary identifies the successful log for each scenario, including reruns of corrected tests.

## Remaining roadmap work

This delivers the serial rack/editor and graph-aware preset/app-backup scope, plus 64-band storage/editing/processing. Full R1 hardware acceptance remains open: physical speaker/wired/Bluetooth/USB runs, confirmed route behavior, sustained underrun/thermal/battery evidence, actual Android Auto UI and the remaining playback matrix. R2 still includes broader import/fitting, response views, independent L/R correction, IR management and reliable route-bound presets. No hardware-output or universal bit-perfect claim is made by this batch.
