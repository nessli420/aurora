# R1a: saved processing presets

Implemented 2026-09-08 as the first, shortened slice of phase 2 / R1. The complete high-precision engine, preset portability and serial rack exceed the requested approximately 30-minute scope; this delivery establishes useful, tested preset storage for the existing engine.

## Delivered

Open **Settings → Equalizer & effects → Saved processing presets**. Save current settings with a name, apply a saved preset, rename it, duplicate it, or delete it after confirmation. Saving and renaming do not change the current sound. Later adjustments do not overwrite saved presets.

Each card shows the saved engine, ReplayGain mode, requested output mode, independent output, default speed and enabled playback options. The screen links to Signal Path and explains restart requirements and automatic device correction. It is a child of Equalizer; the existing device-correction catalog remains separate.

| Captured together | Details |
|---|---|
| All 29 current `AudioPrefs` fields | System EQ, graphic layout/gains, parametric frequencies/gains/Q/filter types, preamp, stereo controls, compressor/limiter, convolution, saturation, channel delays/trims and ReplayGain. |
| 10 playback processing/output preferences | Mono, silence skipping, crossfade duration/curve/headroom, gapless, default speed, hi-res preference, direct USB preference and independent output. |
| Correction metadata | Existing AutoEQ profile label; convolution name, immutable local file reference and SHA-256. |

Accounts, library/source selection, streaming/download bitrate, theme, alarms and device binding rules are not part of a processing preset. The session-only preferred Android device ID is not persisted into a preset. Default speed retains its existing default-setting behavior; it is not a command to change the currently playing track's speed.

## Consistency and storage

- Save reads one DataStore snapshot. Apply writes the captured settings and correction label in one transaction, preserving unrelated preferences and existing keys/defaults.
- Playback now consumes audio and playback preferences from the same DataStore emission, preventing a preset's new mono/output preferences from being combined with its previous EQ settings. Device AutoEQ corrections also use one guarded transaction instead of four separate writes. The existing automatic switching policy remains active and can replace the EQ when output devices change.
- Presets use UUIDs and schema version 1. The nullable storage envelope is checked before constructing Kotlin domain objects. Missing fields, malformed bands/types, non-finite/out-of-range values, duplicate IDs and unsupported versions produce a visible error; a damaged library is not silently discarded or overwritten.
- Names allow 1–80 characters; the local library allows 100 presets. JSON size and band arrays are bounded. Unchanged preset JSON is not decoded again on every preference change; parsing and preset I/O run off the main thread.
- The existing IR importer overwrites its working file. Saving a preset therefore copies a readable IR to an independent file under `filesDir/processing-presets`, bounded to 64 MiB, and records its hash. Importing another IR cannot silently change an old preset. Applying enabled convolution checks the saved file and hash before writing any settings. Disabled convolution can retain a dormant unavailable reference.
- Immutable IR files are retained when a preset is deleted or a save's commit status is uncertain. This protects duplicates and currently applied processing; reference-aware asset cleanup is a follow-up.

Relevant implementation: [ProcessingPreset model/codec](../app/src/main/java/com/aurora/music/data/ProcessingPreset.kt), [SettingsStore](../app/src/main/java/com/aurora/music/data/SettingsStore.kt), [preset screen](../app/src/main/java/com/aurora/music/ui/screens/settings/ProcessingPresetsScreen.kt), [PlaybackService](../app/src/main/java/com/aurora/music/playback/PlaybackService.kt), [AutoEqController](../app/src/main/java/com/aurora/music/data/AutoEqController.kt).

## Validation

| Check | Result |
|---|---|
| Build | JDK 21 + Gradle wrapper: debug Kotlin/resources, signed release and instrumentation APKs passed. [Final build](../_artifacts/r1a/build-final.log), [resource check](../_artifacts/r1a/build.log). |
| JVM | **45 passed**, including 10 new preset tests covering complete round trips, missing/null fields, unsupported schema versions, malformed lists/bands, invalid types/numbers, IDs, names and excluded non-processing settings. [Saved results](../_artifacts/r1a/unit-results). |
| Emulator, Android 15 / API 35 | **8 checks passed**: 2 preset DataStore/IR tests; live preset playback; R0 browse/transport/Signal Path; crossfade pause/queue; Mix session return; collection playback controls; alarm scheduling. [Preset/playback tests](../_artifacts/r1a/emulator-tests.log), [regressions](../_artifacts/r1a/regression-tests.log). |
| Phone, Android 16 / API 36 | **3 preset tests passed** on the Nothing Phone (3a) Pro. Full settings and aggregate-observer round trip, unrelated preference preservation, immutable/missing/corrupt IR behavior, live Custom mono restoration and applying while paused. [Results](../_artifacts/r1a/phone-tests.log). |
| Phone UI | Save, apply, rename, actions menu, delete confirmation, Back and persistence after app restart inspected. Normal and 150% font layouts inspected; font scale restored to 100%. Temporary test presets removed. |

The live service test verifies that applying a preset reaches processing without replacing the queue or starting paused music. Tests restore original processing settings, existing preset entries and generated fixtures. The updated release was installed with `install -r`, preserving app data. Emulator copies use its existing debug certificate; the phone uses the normal release certificate.

Visual evidence: [EQ entry](../_artifacts/r1a/equalizer-entry.png), [empty screen](../_artifacts/r1a/presets-empty.png), [saved preset](../_artifacts/r1a/preset-saved.png), [applied](../_artifacts/r1a/preset-applied.png), [actions](../_artifacts/r1a/preset-menu.png), [150% font](../_artifacts/r1a/presets-large.png), [after restart](../_artifacts/r1a/preset-after-restart.png), [delete confirmation](../_artifacts/r1a/delete-dialog.png).

## Remaining R1 work

This is **partial PRESET-01**, not completion of the full R1 phase. Next slices should address:

1. Portable preset import/export with IR asset bundles and dependency validation; reference-aware local asset cleanup. Existing generic backup includes preset JSON but does not bundle the IR audio files.
2. ENG-01 contracts: versioned audio blocks, explicit channel/precision/timing/capability data, output adapters and migration from these legacy snapshots into named graph nodes.
3. ENG-02 precision prototype and measured Kotlin/native comparison. Preserve supported high-resolution decoded input and remove intermediate PCM16 conversions before claiming a higher-precision engine.
4. ENG-03 serial rack, immutable schedules, smooth changes and deliberate placement of gains/limiting; essential OBS measurement taps and hardware/performance gates.

Preset transactions do not establish sample-accurate or click-free graph swaps. IR loading and current effect updates retain their existing behavior, and some output/engine changes require a restart. USB/Cast fidelity, new precision processing and graph routing are not claimed by this delivery.
