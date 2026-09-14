# R2b: saved measurement projects and independent channel correction

Continuation of [the implementation roadmap](music-player-completeness-plan.md), started 2026-09-10. This implements a substantial part of TUNE-01/02/03 and dedicated-EQ channel routing. R2 is still in progress.

## User flow

Settings → Audio → **Measurement tuning** opens a dedicated workspace. The Processing rack also links to it. Projects keep independent left and right measurements, a custom imported target or an explicit relative-flat target, source/rig/notes and generation settings. Save, reopen, rename, delete and portable project JSON work locally. Full app backups include the project library, original source text and optional phase.

Import previews show the interpreted units, frequency coverage and magnitude. A third unlabeled column requires explicit confirmation that it represents phase in degrees; it is never treated as the other channel. Raw imports remain unchanged by smoothing, normalization, averaging or fitting. External project imports receive new project IDs and require regeneration before their saved correction can be applied.

The fitting dialog exposes design sample rate, 1–64 total bands, frequency range, maximum boost/cut, minimum/maximum Q, smoothing and normalization. Select left only, right only, independent available channels, or explicit linked averaging. Independent fitting splits the total budget across available channels. Linked mode averages magnitudes in dB only for the fitting copy and preserves both originals.

The result shows measured, target, fitted EQ and predicted response, with per-channel inspection and before/after RMS error. Desired correction values are retained in the saved result for verification. The proposed shared preamp derives from the largest calculated correction boost with a 0.5 dB margin. This estimate covers the fitted EQ response, not other rack stages, transients or hardware clipping.

**Apply to rack** previews the additions. One optional common Gain stage and channel-specific EQ stages are inserted immediately before a final Limiter stage when one is present; otherwise they append at the end. Existing stage order, IDs and Standard/Rack selection remain unchanged. The latest rack capacity and project validity are checked in the same DataStore transaction as saving the project and updating the rack, preventing partial changes. An inactive rack stays inactive. The complete rack remains limited to 16 stages and 64 total parametric bands, including bypassed stages.

## Import, persistence and fitting boundaries

- Numeric UTF-8 CSV/TXT/FRD and the REW frequency-response text-export subset: Hz, dB, optional degrees; whitespace, comma or semicolon columns; decimal/scientific numbers and commented metadata. The supported REW column order and units follow its [official export documentation](https://www.roomeqwizard.com/help/help_en-GB/html/file.html). Unsupported units, extra/mixed columns, duplicate or descending frequencies, malformed/nonfinite numbers and oversized data fail with actionable errors.
- Each import: at most 512 KiB, 16,384 points, 20,000 lines and 4,096 characters per line. At most 32 saved projects; 4 MiB per encoded project and 8 MiB for the encoded library. Input is rejected rather than truncated.
- Strict versioned JSON rejects unknown/missing fields, duplicate keys, invalid IDs/types, mismatched raw source and parsed points, stale fits and oversized payloads. Imported/generated fit evidence is validated against recomputed response mathematics before persistence or backup restoration.
- The fitter uses production binary64 peaking-filter coefficients and the explicit design sample rate. Frequency controls cover 10–24,000 Hz, including at higher sample rates. It fits 512 logarithmic points within shared measured/target coverage below Nyquist; it does not extrapolate. Optional normalization uses a common measured offset over available 200–2000 Hz coverage, preserving channel level differences, and a separate target offset. Missing shared reference coverage requires choosing no normalization or importing wider measurements. The UI requires at least two total bands when fitting two channels independently.
- Optimization is bounded and cancellable. Combined boost/cut limits are checked on the fitting grid and a denser full response grid. A correction that worsens initial RMS error falls back to zero filters. This is a numerical magnitude fit, not a claim of globally optimal tuning or acoustic verification.
- Source phase is retained but not fitted. Predicted curves exclude the proposed common preamp. Playback recalculates coefficients at the actual stream rate; the UI identifies the design rate used for the preview.

## Playback and compatibility

Dedicated Equalizer stages now select **Both / Left / Right**. The selected routing applies to graphic and parametric filters together. Unselected samples pass unchanged, including at partial wet mix. Stable channel/filter histories remain separate; changing routing resets the stage history and uses the existing prepared graph transition.

Rack schema 2 records channel routing. Schema 1 racks migrate to Both without altering parameters, order, bypass or wet values. Full processing presets and backups retain routing; parametric text export explicitly omits routing along with its existing omissions. Signal Path identifies left/right stages and calculated response views name their channel scope.

## Validation

Completed 2026-09-10–11 on the connected **Nothing A059P, Android 16 / API 36**, serial `0016615BM001167`. The signed release was installed with `adb install -r`; application data was preserved. Builds used the Gradle wrapper and the required JDK 21.

`testDebugUnitTest`, `assembleRelease` and `assembleReleaseAndroidTest` completed successfully. **236 JVM tests passed, zero failures or skips**, including 49 new tests for measurement parsing, strict project/library storage, saved-fit verification, fitting mathematics, routing, rack migration, atomic planning and backup inclusion. Coefficient comparisons cover 44.1/48/96 kHz; fitter tests cover independent/linked channels, normalization, coverage/Nyquist, more than 12 bands, limits, zero fallback and cancellation.

The accepted physical-device runs contain **42 passing scenarios, zero skipped or failed**:

| Run | Passed | Coverage |
|---|---:|---|
| `final-core-store-engine.log` | 28 | Project save/rename/delete/portable roundtrip/backup restore; atomic fit append and unchanged mode; invalid fit/backup rollback; rack/preset/IR backup compatibility; PCM adapter and precision sink handling |
| `final-rack-float.log` | 7 | Independent L/R EQ at 48/96 kHz; wet/bypass/routing edits and seek; crossfade, shuffle restoration, both Mix decks, speed/skip-silence fallback, convolution replacement and format changes |
| `final-rack-pcm16.log` | 6 | Corresponding channel/rack/crossfade/Mix/shuffle/convolution checks through PCM16 compatibility output |
| `final-dense-rack.log` | 1 | 64 PEQ bands, nine serial stages and 4,096-frame convolution; 60 seconds each at 48/96 kHz with background/resume, live wet edit and seek |

The dense run observed **zero new AudioTrack underrun notifications and zero playback-clock stalls** at either rate. Both configurations reported stereo float32 AudioTrack output. Maximum meter age outside the bounded seek interval was 593 ms at 48 kHz and 597 ms at 96 kHz; one expected transient meter gap occurred during the 96 kHz seek. Thermal status samples remained 0. These are measurements from this bounded run, not an acoustic or long-term endurance guarantee.

The first playback attempt skipped because the user had shuffle enabled. Those seven skips are excluded from the accepted count. The test fixture now captures both the physical queue and the service's unshuffled order through ordinary session commands, waits for the shuffle-state update, and restores both orders and the original flag after testing. Restoration assertions passed. No production shuffle behavior changed in R2b.

### Physical UI walkthrough

The native phone walkthrough covered the new settings dock and project creation; separate CSV L/R imports with phase; a custom target through the multiline text field; changing the budget to 24; generation; left/right chart selection; fitted-curve visibility and point inspection; append review/application; routed EQ editor/response view; JSON export; import as a new project requiring regeneration; and project deletion. Screenshots and fresh UI hierarchies are under `_artifacts/r2b/`.

The synthetic pair had 121 points per channel over 20 Hz–20 kHz, plus a two-point custom relative-flat target. The resulting 12 filters per channel reduced calculated RMS error **1.52736 → 0.04943 dB left** and **1.63566 → 0.01601 dB right**. The common proposed preamp was **−4.96044 dB**. The exported 122,508-byte project preserved both original source texts exactly and retained phase; `phone-project-summary.json` records the numerical results. These values describe synthetic imported data and its calculated correction.

The append review accurately predicted five total rack stages and 34 total PEQ bands after adding the shared gain and 24 fitted bands to the original rack. Standard mode stayed active. The routed stage response explicitly displayed “Left channel only · right passes unchanged.” The imported project remained separate from the original saved project and discarded its saved fit until regeneration.

After UI testing, an app-private checkpoint restored all original preference values with an equality assertion, including the original rack and tuning library; the checkpoint was then deleted. Temporary CSV/JSON files in Downloads were removed. The original song **Unabomber II — The Crying Nudes**, its queue/shuffle state and paused **0:54** position were preserved. The crash buffer queried for the validation interval was empty. The two checkpoint helper invocations and manual UI actions are excluded from the 42-scenario count.

### Artifacts

- Release APK: `app/build/outputs/apk/release/app-release.apk`
- Release SHA-256: `580ADB3EC59A6829861EBE7F0B4D0287D940A59F642317ACD1866010012AC1BC`
- Instrumentation APK SHA-256: `41F332F99125B1E98325ACEF8548F55C4D3B00DA008ECD242BA38F5E60E03ECA`
- Build and JVM evidence: `_artifacts/r2b/final-build.log`, `jvm-summary.json`, `jvm-results/`
- Runtime evidence: the four logs listed above, `dense-playback.json`, `ui-preferences-restored.log`, `crash-log.txt`
- UI/project evidence: `audio-settings.png`, `left-measurement-preview.png`, `left-fit-result.png`, `right-fit-result.png`, `append-review.png`, `left-channel-response.png`, `two-projects.png`, `original-player-restored.png`, `phone-exported-project.json`

## Remaining work

This does not add branded target redistribution, a measurement-system compatibility database, bass/tilt/ear-gain target shaping, phase/delay correction, independent per-channel fitting control sets, integrated fit undo/A-B, dedicated third-party adapters, FIR library management, reliable device/output preset bindings or additional filter families. Existing online Squig/AutoEQ browsing continues through its existing fitter. USB DAC, Bluetooth, Cast and acoustic/microphone verification require separate coverage.
