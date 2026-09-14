# Aurora music player: implementation audit and completion plan

Audit date: **2026-09-08**. Baseline: local working tree at commit `0a20314`, including the modifications and untracked Mix Studio files already present when this audit began.

This document turns the supplied 24-part feature proposal into work grounded in Aurora's current implementation. It also plans the requested Settings reorganization: **Alarm and Signal Path each get a separate dock and dedicated screen**. This pass produces a plan only.

**Implementation update, 2026-09-08:** R0 has since been implemented. See [the R0 report](r0-settings-and-signal-path.md) for changes, build/device results and remaining hardware validation. The audit findings below describe the original research baseline; the phase table and first-batch checklist now track implementation progress.

**Phase 2 update, 2026-09-08:** R1 began with [R1a: saved processing presets](r1a-processing-presets.md). The next delivery, [R1b: portability, measurements and engine foundation](r1b-portability-measurements-and-engine.md), adds portable preset/IR bundles, live sample peak/RMS readings and a separately tested binary64 serial-rack prototype with a phone Kotlin/native comparison. Production precision-engine integration, complete legacy-node migration and the rack editor remain pending; full R1 is not complete.

## 1. Scope and evidence

The audit inspected the Kotlin application, native USB integration, settings/navigation, models, stores, existing roadmap, and focused test files. Source links below refer to this checkout; symbols are more durable than line numbers as development continues.

- **Present** means implementation was found and its call sites inspected. It does not mean it was exercised on a device in this pass.
- **Partial** means useful implementation exists, with specific gaps against the supplied proposal.
- **New** means no integrated implementation was found in the inspected application and relevant native code. An unused dependency, filename extension, or old roadmap entry does not establish support.
- **Experimental** means an implementation exists but compatibility or behavior needs explicit hardware validation.

No app build, listening test, device interaction, or competitor benchmark was performed for this documentation pass. Existing Mix Studio validation notes are historical evidence, not new results. The working tree contains ongoing work, so implementation tasks must refresh the relevant diff before editing shared files.

The previous [roadmap][old-roadmap] remains useful background, but this document is the current baseline for these 24 proposals. In particular, its statement that DSD/DoP and hardware volume are in progress is not evidence of an integrated DSD feature. Likewise, the repository guidance saying there is no test suite is now stale: focused Mix JVM and release instrumentation tests are present in the working tree ([build configuration][build], [JVM tests][mix-tests], [device tests][mix-device-tests]).

## 2. Findings that change the proposed priorities

1. **Measurement-driven EQ already exists.** Squig measurements and a target curve feed an on-device fitter. It averages L/R and uses mostly fixed fitting constraints; the missing work is a complete import, visualization, fitting, and independent-channel workflow.
2. **Unified sources and duplicate collapsing already exist.** Local, Navidrome/Subsonic, and Jellyfin can be merged. The gap is durable identity, retained alternatives, stronger matching, quality-aware selection, and recovery when a selected source fails.
3. **Headroom assistance already exists.** There is an EQ response calculation and an Auto preamp action. Continuous maintenance, broader-chain analysis, and measured clipping protection are missing.
4. **Signal Path exists as a small card.** It has one format description, output name, Boolean bit-perfect flag, and explanatory text. It does not capture every actual stage or negotiated output format.
5. **The main processed path is constrained by PCM16 boundaries.** Both custom DSP and convolution accept stereo PCM16, calculate with float arrays, and write PCM16 again. Raising a band limit or changing coefficient calculations alone will not create a 64-bit signal path.
6. **The actual DSP order differs from the supplied diagram.** The service inserts mono, custom DSP, then convolution. Inside custom DSP, EQ precedes gain/trim, saturation, width, crossfeed, compressor, limiter, and channel delay. Convolution therefore runs after that limiter. ReplayGain is applied through player volume. Migration must be based on this order, with intentional behavior changes identified.
7. **The normal visualizer tap is before app DSP.** `TappingAudioSink.handleBuffer()` reads the decoder buffer before forwarding to `DefaultAudioSink`, where the processors run. Its FFT and RMS are not an established post-DSP measurement point. USB has separate taps and must be characterized independently.
8. **Audio analysis is already substantial.** R128-style track/album gain scanning, Sonic feature extraction and estimated BPM/key, Chromaprint identification, and the current Mix waveform/RMS/audible-boundary analysis provide reusable components. They are not yet a unified, validated library of all requested analysis fields.
9. **Settings needs an immediate navigation pass.** Alarm and Signal Path are embedded in Playback & quality; ReplayGain is under Equalizer & effects; output controls share the playback page. These should have clear owners before more features arrive.

Evidence: [DSP][dsp], [coefficients][coeff], [convolution][conv], [service][service], [visualizer tap][tap], [EQ UI][eq-ui], [Squig client][squig-client], [fitter][fitter], [merged backend][merged], [analysis][mix-analysis].

## 3. Coverage of all 24 requested feature areas

Numbers match the numbered sections in the supplied feature list. Workstream IDs below link the audit to the implementation plan.

| # | Feature | Current implementation | Actual remaining work | Workstream |
|---|---|---|---|---|
| 1 | Modular DSP graph | **New graph; present primitives.** Fixed service chain and fixed ordering inside `AuroraDspProcessor`; no user routing graph. | Serial rack first, then multiple instances, bypass, wet/dry, L/R and M/S branches, splitting/merging, node meters, subchains, and latency compensation. | ENG, DSP |
| 2 | 64-bit internal DSP | **New.** Sample state and coefficients are float32; PCM16 enters/leaves the main processors. Double math in fitting or analysis does not change playback precision. | Preserve decoder precision, introduce double-precision processing/summing, remove intermediate quantization, and quantize once at the actual output boundary. | ENG |
| 3 | IEM/headphone tuning lab | **Partial.** AutoEQ/Spinorama preset lookup, Squig FR/target fetching, and automatic PEQ generation exist. | General local measurement/target import, format validation, FR/target/result plots, editable fitting limits, saved projects, explicit target provenance and rig compatibility. | TUNE |
| 4 | Independent L/R correction | **Partial.** Independent trim/delay and convolution channels exist. Squig averages L/R; PEQ uses the same coefficients on both channels. | Retain separate measurements, independent PEQ banks, linked editing, separate headroom, mismatch correction, and channel-aware export. | ENG, TUNE |
| 5 | 64+ band PEQ and response views | **Partial.** Up to 12 parametric plus 31 graphic bands. Peak and low/high shelves work in DSP/import; the current band editor lacks a type selector. | At least 64 parametric bands in storage, import, editor, and engine; expanded filters; bypass/duplicate/reorder; actual magnitude, phase, and group delay views. | EQ |
| 6 | Deeper FIR/convolution | **Partial.** One mono/stereo WAV IR, partitioned convolution, makeup gain, and automatic linear IR resampling. | IR library, high-quality resampling, true-stereo matrix, bounded long-IR handling, full tails, latency, normalization/trimming, phase conversion, and multiple stages. | FIR |
| 7 | Resampling, oversampling, dither | **Partial primitives; new playback controls.** IR resampling is linear; native USB varispeed also uses a linear resampler. Stem preparation has a fixed-rate conversion path. | A measured playback SRC, source-family policy, negotiated rate limits, per-node oversampling, and final-output TPDF/noise-shaped dither. Existing conversions are not this feature. | ENG, RATE |
| 8 | DSD | **New integrated feature.** No app DSD mode, DoP/native transport, or PCM-to-DSD workflow found. `dsf`/`dff` in a quality list does not prove decoding/output. | Verify bundled decoder capabilities; add DSF/DFF metadata/seek and DSD-to-PCM first, then DoP/native USB; evaluate DST/SACD ISO and PCM-to-DSD later. | USB |
| 9 | Detailed Signal Path | **Partial.** `SignalPath` flow and private card in Playback settings. | Separate dock/screen now; subsequently stage-specific actual formats, decoder, gain, active graph, resampling, output negotiation, latency, and evidence-qualified verdicts. | SET, OBS |
| 10 | Live meters and analysis | **Partial.** FFT, waveform, RMS/level data, and visualizer styles exist. The normal sink tap is before app processors. | Synchronized stereo pre/post taps; calibrated peak/RMS, LUFS, true peak, spectrum/spectrogram, correlation, vectorscope and phase scope; distinguish presentation effects from measurements. | OBS |
| 11 | Automatic clipping/headroom | **Partial.** `eqPeakDb()` and one-shot Auto preamp exist; the UI estimates at default 48 kHz and limits the Auto attenuation action to 12 dB. | Active-rate response, continuous optional attenuation, convolution/matrix/summing gains, measured clipping/true peak, and clear limits for nonlinear/time-varying nodes. | OBS, RATE |
| 12 | Adaptive loudness compensation | **New.** System LoudnessEnhancer and ReplayGain are different functions. | Volume-dependent response with a user reference level, smoothing, route-specific calibration, and integration with preamp/headroom. | LISTEN |
| 13 | Estimated IEM SPL | **New.** No DAC transfer curve, impedance/sensitivity model, or calibrated SPL estimator found. | Hardware/headphone profiles, sensitivity units, calibration and uncertainty, volume-stage accounting, estimated level history; exposure estimates only after a separate validated model. | LISTEN |
| 14 | Level-matched A/B and ABX | **New.** No blind trial system or saved A/B graph snapshots. Existing crossfade/mix utilities are reusable, not an ABX implementation. | Immutable A/B states, shared sample timeline, latency/level matching, blind randomized trials, explicit trial rules/results, then aligned file/codec comparisons. | COMPARE |
| 15 | Preset rules engine | **Partial.** AutoEQ device bindings save bands/preamp and apply on device events. Device selection is based on connected-device priority and type/name. | Full DSP/output snapshots, confirmed active-route identity, ordered rules, conflicts/manual override, and source/context predicates. | PRESET |
| 16 | Studio effects | **Partial.** Compressor, limiter, saturation, crossfeed, width, trim/delay, mono and system effects exist. | Missing dynamics, modulation, tone, spatial, time, and utility processors as graph nodes with consistent controls and CPU/latency reporting. | DSP |
| 17 | Multiband processing/dynamic EQ | **New.** Current compressor is full-band; no detector-driven EQ or crossover bank found. | Validated crossovers, independently controlled dynamics bands, detector/filter separation, envelopes, solo/mute, and dynamic response display. | DSP |
| 18 | Universal source/provider layer | **Partial.** `MediaBackend` covers Local, Subsonic, Jellyfin, Spotify; merged mode currently includes Local/Subsonic/Jellyfin. Radio and podcasts already exist. | Broader capability model and indexing; Plex, SMB, WebDAV, SFTP, UPnP first; remaining sources evaluated individually. Vendored SFTP transport is not a wired Aurora library provider. | LIB |
| 19 | Cross-provider duplicate merging | **Partial.** Normalized artist/title + four-second duration clustering, quality ranking, and local/download/stream preferences already exist. | Stable recording/edition/source identities, retained copies, manual split/merge, identifier/fingerprint evidence, reliable paging, per-copy selection and fallback. | LIB |
| 20 | DSP-capable network output | **Partial base.** Direct Chromecast hands over the source URL and bypasses Aurora DSP. | Separate processed mode with decoding/DSP, receiver-compatible encoding/stream serving, receiver capability handling, seeking, buffering, transport recovery and diagnostics. Add DLNA/UPnP, then evaluate Sonos/Kodi. | NET |
| 21 | Aurora as a renderer | **New.** Local MediaLibraryService/Android Auto is not a remotely controllable renderer. | Discoverable paired endpoint, controller/renderer roles, queue/transport protocol, source authorization, session recovery, and DAC state reporting. | NET |
| 22 | Serious library analysis | **Partial.** Local track/album R128-style gain scan; Sonic similarity/BPM/key; Chromaprint; current shared Mix waveform, RMS, tempo confidence and audible boundaries. | Unified versioned records and jobs; validated LUFS-I/LRA/true peak/dynamic-range definitions; expose available metrics to smart playlists; avoid repeated decoding. | ANALYZE |
| 23 | Deep metadata | **Partial.** Basic domain tags, seven editable tag fields, local/Jellyfin editing and MusicBrainz/AcoustID workflows. | Multi-value credits/genres, recording/release IDs, classical hierarchy, editions/discs, extended tags, CUE, artwork/booklets and reviewed batch editing. | META |
| 24 | Plugin SDK | **New.** Backend and processor classes are internal abstractions, not a supported third-party SDK. | Versioned provider/metadata/audio contracts, permissions and isolation, compatibility, example extensions, packaging and controlled failure handling. | EXT |

### Preserve the existing base

Keep the measured headphone/earbud/speaker catalog and its category distinctions ([catalog implementation][autoeq], [catalog documentation][device-presets]). A speaker correction must continue to use its own measurement context rather than an IEM target.

Preserve current queue/shuffle semantics, Android Auto/session controls, offline localization, radio/podcasts, and Mix Studio. In particular, the current Mix implementation has its own multi-player processing path, and native USB has a separate crossfade/varispeed path. A new main DSP engine is incomplete until these paths are explicitly supported or visibly constrained ([Mix implementation][mix-player], [Mix design and prior verification][mix-doc]).

## 4. Settings organization: first delivery

### SET-01 — Give Alarm and Signal Path their own docks

Priority: **P0**. Size: **S**. Independent of the audio-engine rewrite.

Here, a dock means a separately visible, labeled card/row on the Settings landing page that opens its own destination. Alarm must not remain a small playback subsection, and Signal Path must not remain an embedded card in Playback & quality.

| Dock | Screen and route to add | Initial contents | Landing-page summary |
|---|---|---|---|
| **Alarm** | `AlarmSettingsScreen`, `Routes.SETTINGS_ALARM` | Existing enable/time controls, current daily liked-music behavior, next scheduled time, scheduling/permission status, link to relevant permissions. | Off, or next scheduled alarm. |
| **Signal Path** | `SignalPathScreen`, `Routes.SIGNAL_PATH` | Existing signal data and explanation, labeled according to what the service actually knows; useful inactive state when nothing is playing. | Active output and known format, or Nothing playing. |

Implementation boundaries:

- Add callbacks in [SettingsScreen][settings-ui], routes in [Destinations][routes], and composables in [AuroraApp][nav]. Extract the current sections from [PlaybackSettingsScreen][playback-ui].
- Reuse `SettingsTopBar`, `SettingsGroup`, and shared row/slider components. Keep portrait layout, font scaling, TalkBack labels, mini-player insets, and back-stack behavior.
- Keep the existing `AlarmPrefs` and DataStore keys. Moving the controls must not disable, duplicate, or reschedule an alarm merely because a screen is opened.
- Reuse [AlarmScheduler/AlarmReceiver][alarm] and [AlarmActivity][alarm-activity]. The current implementation schedules a daily alarm, prefers exact scheduling when available, and falls back to inexact scheduling; the screen must distinguish those outcomes. Implement an observable scheduling result if needed.
- Account for next-day rollover, local time changes and 12/24-hour formatting. Put schedule reliability work in its own small follow-up if it requires receiver changes.
- Signal Path stays reachable without playback. Replace the current disappearing-card behavior with a clear idle state.
- Add a Now Playing quality/source-indicator shortcut to the same Signal Path route; use one screen/model from both entry points.
- Do not invent output measurements to fill the new screen. Missing output rate, decoder name or latency remains Unknown until OBS supplies it.

**Acceptance:** both docks are visible from Settings without opening Playback; the existing alarm still fires/dismisses and retains its settings across restart; Signal Path opens from both entry points, updates during playback, and handles idle/disconnect; old embedded sections are removed.

### SET-02 — Establish a clear home for every existing setting

Priority: **P0**, after SET-01. Size: **M**.

Proposed landing-page structure:

| Group | Docks/screens | Ownership |
|---|---|---|
| Library & accounts | Servers & accounts; Library & sources; Downloads & storage; Library analysis & discovery | Authentication, source membership/priority, offline files, scans and Sonic automation. |
| Audio | Playback & quality; Audio output; Equalizer & effects; Volume & loudness; **Signal Path** | Playback behavior, routing/output mode, processing, normalization/gain policy, actual-path inspection. |
| Timers | **Alarm** | Wake scheduling and behavior. A shortcut to the existing player sleep timer can be added without duplicating its state. |
| Appearance & controls | Appearance; Visualizer; Gestures & behaviour | Visual presentation and interaction. Measurement meters remain under Signal Path/analysis tools. |
| Connections | Integrations | Last.fm, ListenBrainz, Discord, lyrics and other existing integrations; later network playback configuration. |
| App & data | Permissions; Backup & restore; About Aurora | App access, export/import, version and support information. |

Specific moves:

1. Keep streaming bitrate/data saver, crossfade/curve/overlap headroom, gapless, skip silence and default speed in Playback & quality.
2. Move hi-res, experimental direct USB, preferred device and independent-output behavior into **Audio output** (`SETTINGS_OUTPUT`). Keep contextual links from Playback and Signal Path.
3. Move ReplayGain from the bottom of Equalizer into **Volume & loudness** (`SETTINGS_LOUDNESS`). Keep graph preamp/limiter editing with the effects they control; show links and shared state rather than a second independent control implementation.
4. Keep mono/balance/channel processing discoverable in Equalizer & effects, with an accessibility shortcut if appropriate. Define one owner for each preference.
5. Rename Sonic discovery to **Library analysis & discovery** when it exposes the wider analysis work. Existing scan and automation controls move together.
6. Leave future tools out of the landing page until usable. Tuning Lab, convolution management, graph editing and presets can become dedicated destinations reached from Equalizer; Listening tests can be reached from tuning/presets. This avoids a landing page full of unavailable features.
7. Add a small shared settings destination registry for labels, descriptions and routes. Use it for Settings search as the page grows; avoid an unrelated navigation framework rewrite.

**Acceptance:** every current setting has one owning screen; no saved value changes as a consequence of navigation; existing entry points and Back work; all current settings remain discoverable; no empty future-feature docks ship. Make annotated phone screenshots of the landing page and the two requested docks part of review.

### Alarm depth after navigation

Treat snooze, weekdays/multiple alarms, explicit source/playlist selection, configurable fade/volume, and a reliable offline fallback as **separate optional backlog items**, not requirements for moving the current alarm. The first delivery should already show next trigger and whether required permissions/scheduling succeeded.

## 5. Engineering workstreams

Priority levels: **P0** discoverability/trust and prerequisites; **P1** core completion; **P2** advanced depth; **P3** later expansion. Sizes are relative engineering scope, not dates: S focused extraction; M one bounded feature; L several components; XL a subsystem spanning releases.

### ENG — Processing and output foundation

Priority **P1**. Size **XL**. Enables EQ, graph routing, meters, advanced FIR, comparison and processed network output.

**ENG-01: Record the actual playback paths and define shared contracts.**

- Inventory normal Android PCM processing, float output, direct USB native FLAC, direct USB decoded streams, crossfade players, Mix decks, and Cast. For each, capture decoder format, processing placement, volume owner, clock owner and output format.
- Introduce a versioned `AudioBlock` contract carrying sample rate, channel layout, frame count and presentation time, plus processor latency/tail/capability metadata.
- Define separate bypass and processed modes. Bit-perfect means the relevant samples are preserved through the supported path; it is not a synonym for exclusive USB, 64-bit arithmetic, or high sample rate.
- Define output adapters behind a common capability interface. Keep source/backend decisions in the data layer.

**ENG-02: Prove a high-precision processed path before building the graph editor.**

- Preserve supported high-resolution decoder output into a double-precision buffer. Integer 24/32-bit and float decoder outputs need explicit conversion rules; float32 decoder precision cannot be recovered by casting to double.
- Remove PCM16 round trips between EQ/convolution and other nodes. Use one final output conversion with optional dither. Do not claim a 64-bit chain while any active internal node silently falls back to float32 or PCM16.
- Compare a Kotlin prototype and a block-based native implementation on target hardware. Decide implementation language from measured CPU, memory, latency, APK cost and integration complexity; use block calls rather than per-sample JNI.
- Preserve the precision of the native bypass path. Upgrade the processed path without forcing every playback mode through a new converter.
- Keep the current Media3 version/FFmpeg extension compatibility visible in the design; a library upgrade is a separate change. Merely enabling float on the existing sink does not run Aurora's processors. [Media3 documents that float output disables audio processing](https://developer.android.com/reference/androidx/media3/exoplayer/audio/DefaultAudioSink.Builder#setEnableFloatOutput(boolean)).

**ENG-03: Serial rack, then bounded routing graph.**

- Adapt existing effects to named node instances; add reorder, per-node bypass, wet/dry, duplicate and saved subchains.
- Compile an immutable processing schedule away from the audio callback. Preallocate buffers; bound node count/memory; avoid callback locks, file/network work and unbounded allocation.
- Apply parameter changes smoothly and swap compiled graphs without clicks. Preserve state/tails where appropriate; serialize changes that alter sample rate or output mode.
- Add L/R and M/S splits, matrix/mix nodes and parallel branches with measured latency compensation. Validate compatible formats, reject cycles in the first graph format, and use explicit delay nodes for supported feedback effects later.
- Migrate old settings into a **Legacy chain** preserving the observed order. Offer an explicitly named recommended chain with headroom early and a final limiter after convolution/mix/SRC; do not silently change existing presets' sound.
- Define placement for ReplayGain, user volume, fades and Mix sums. Apply master gain once; meter both node output and the final bus.

**Acceptance:** reference signals survive bypass as specified; 24-bit low-level information survives the processed path; existing presets migrate without unexplained changes; 64 active PEQ bands and a representative IR meet recorded callback deadlines on the target phone; graph swaps do not click; delayed parallel branches null/recombine correctly; all supported player paths use the declared pipeline. Every unsupported mode reports the restriction.

Primary files: [service][service], [DSP][dsp], [coefficients][coeff], [convolution][conv], [preferences][prefs], [MixPlayer][mix-player], [USB sink][usb-sink], [native engine][usb-native].

### OBS — Signal Path, meters and trustworthy headroom

Priority **P0** for the model and existing-path corrections; **P1** for full instrumentation. Size **L**. Start after SET-01; expand alongside ENG.

**OBS-01: Replace a single format/Boolean with a structured snapshot.**

Represent source, decoder, processing, output negotiation, active device and evidence independently. Keep expected/requested formats separate from observed formats. Record source bitrate when available; do not derive it from the PCM sink format.

| Stage/indicator | Data to expose | Evidence requirement |
|---|---|---|
| Source | Provider/copy, codec, original rate/depth/channels/bitrate | Selected playable source and decoder metadata; account for server transcoding and local substitution. |
| Decoder | Implementation and emitted format | Renderer/decoder events; identify native FLAC separately. |
| Processing | Precision, actual active nodes/order, RG/preamp/fades/mix gains | Active processing schedule, not preferences alone. |
| Resampling | Input/output rates, reason, method | Actual converter/output negotiation. |
| Output | Backend, negotiated PCM/DSD format, grant/fallback state | Sink/native USB adapter; do not use `player.audioFormat` as proof of hardware rate. |
| Device | Active route, supported formats, hardware gain if readable | Actual routed output or claimed native USB device; supported capability is not current state. |
| Bit-perfect | Preserved / modified / unknown with reasons | Account for DSP, RG, volume, fades, crossfade, speed, conversion, Cast and actual output grant. |
| Latency | Known processor delay plus measured/estimated sink delay | Identify each component and unknown downstream latency. |

- Correct the current model's dependence on configured modes and Android mixer grants for paths that use the native USB driver. Capture native mode/format and crossfade/varispeed activity directly.
- Investigate startup-only float selection and live mode changes. An enabled EQ preference must not be displayed as processing audio when the active sink bypasses it.
- Revalidate Android mixer attributes against the intended format, and track route/grant changes. Android's bit-perfect mixer behavior describes sending data unchanged to the HAL; it is not proof of every subsequent hardware stage. [Android API reference](https://developer.android.com/reference/android/media/AudioMixerAttributes#MIXER_BEHAVIOR_BIT_PERFECT).
- Clear or replace local-path data during Cast and Mix transitions. Display unknown Bluetooth codec/rate or downstream mixer behavior when the platform cannot report it.
- Provide a user-exportable diagnostic report with credentials, authenticated URLs and private identifiers removed.

**OBS-02: Add real measurement taps.**

- Place timestamped stereo taps before DSP, after DSP, and at the final converted output where feasible. Avoid double-counting buffers retried by the sink.
- Build analysis on a bounded worker queue. Define units, windows, calibration and reset semantics before adding meters.
- First: sample peak/RMS, clip counters, spectrum and a synchronized pre/post overlay. Next: validated true peak, short-term/integrated LUFS, spectrogram, stereo correlation, vectorscope and phase scope.
- Decorative visualizer sensitivity/smoothing must not change measurement readings. Retain both channels for stereo analysis.

**OBS-03: Upgrade headroom.**

- Reuse `eqPeakDb`, but evaluate the actual sample rate and full enabled linear response with sufficiently dense/adaptive frequency sampling. Increase available attenuation where a valid chain requires it.
- Add an optional continuously maintained preamp, with smoothed attenuation and an explicit relationship to manual gain.
- For linear routing, account for FIR and matrix/parallel summing. Separate frequency-response estimates from conservative peak bounds.
- Nonlinear, adaptive and time-varying nodes cannot be certified clip-free by one static EQ curve. Show estimated headroom plus measured peaks; use a validated final limiter when requested.
- Review convolution after the existing limiter and the current attack/release limiter behavior. A UI label saying brick-wall does not establish true-peak limiting or protection after later processing.

**Acceptance:** disabling a node changes both audio and the reported path; known test signals read correctly; pre/post changes are measurable and aligned; native USB, Android PCM/float, Mix and Cast never inherit misleading stale data; UI never fabricates unknown rates or latency.

Primary files: [SignalPath/AppContainer][container], [service][service], [tap][tap], [visualizer][visualizer], [EQ headroom UI][eq-ui], [coefficients][coeff].

### PRESET — Saved processing states and rules

Priority **P1** for full presets; **P2** for rules. Size **L**. Preset schema precedes graph editing and ABX.

**Implemented slices:** [R1a](r1a-processing-presets.md) provides local versioned snapshots, atomic settings restore, immutable IR copies and named CRUD. [R1b](r1b-portability-measurements-and-engine.md) adds portable single-preset bundles with included IRs and dependency validation. PRESET-01 remains partial until graph-node migration and full app-backup asset integration are implemented. Existing automatic device-correction rules are preserved; PRESET-02/03 are not completed by these slices.

- **PRESET-01:** Save/load/duplicate/rename full processing states, with stable node IDs, schema version, output preferences and references to IR/measurement assets. Add explicit import/export of portable presets with dependency validation; extend Backup/restore.
- Import existing `AudioPrefs`, `ParamBand`, and `EqBinding` values without losing coefficients, shelf types, graphic layout, preamp or device associations. New persisted fields need nullable/default-safe migration following the repository's Gson constraint.
- **PRESET-02:** Bind presets to the confirmed active route. Type/name and connected-device priority alone are insufficient when two devices are attached or names collide. Provide manual headphone selection for passive headphones behind the same DAC; reconnecting must respect manual overrides.
- **PRESET-03:** Add ordered conditions for device/headphone, source/provider, album/genre/playlist, sample rate, codec/container and Android Auto/Cast context. Define priority, conflict resolution, preview/explanation and restore behavior before expanding predicates.
- Time/location modes are later opt-in conditions, dependent on reliable context and the permissions the user chooses to grant. Apply a full preset atomically rather than racing individual DataStore writes.

**Acceptance:** preset export/import reproduces the graph and assets; old bindings migrate; active-route changes select the expected preset; unbound devices preserve manual correction; rule conflicts and overrides are explainable; ABX can freeze rules during a trial.

Primary files: [preferences][prefs], [AutoEqController][bindings], [backup][backup], [output/service][service].

### EQ — 64-band PEQ and response editor

Priority **P1**. Size **L**. Requires ENG's precision/node contract and PRESET-01 schema.

- Support **64 parametric bands per EQ instance** throughout storage, importer, fitter, compiled processing and UI. Profile larger graphs before increasing that limit; do not promise unlimited realtime processing.
- Replace fixed slot assumptions and integer-only filter meanings with stable, versioned filter identifiers. Keep migration for peak/low shelf/high shelf.
- First expand to low/high pass, band pass, notch and all pass; then tilt and cascaded Butterworth/Linkwitz-Riley designs. Expose order/slope and relevant Q/shelf parameters. Custom coefficients belong in an expert control with stability/finite-value checks.
- Build response views from the same coefficient implementation used for playback, at the active rate. Include preamp, graphic EQ and channel selection; distinguish this linear response from measured nonlinear output.
- Add magnitude, unwrapped phase and group delay views with valid frequency/Nyquist bounds. Include numeric editing, type selector, per-band enable, duplicate, reorder and a usable 64-row list.
- Preserve strict import behavior: unsupported or malformed active filters must produce an actionable error, not silently yield an incomplete correction.

**Acceptance:** imported and manually created 64-band presets persist/reopen; reference sweeps match displayed response; shelf editing works; each channel can have different coefficients; invalid filters are rejected; measured performance supports the advertised cap.

Primary files: [coefficients][coeff], [DSP][dsp], [EQ UI][eq-ui], [parser][autoeq], [preferences][prefs].

### TUNE — Measurement-driven headphone, IEM and speaker tuning

Priority **P1**. Size **L**. Basic measurement storage/import can begin before ENG; independent L/R playback requires ENG/EQ.

- **TUNE-01, measurement projects:** store raw FR, source, units, channel, measurement rig, normalization, target and generation parameters. Refactor the private Squig parser into a tested importer; preserve both channels instead of averaging immediately.
- First support generic frequency/dB CSV/TXT/FRD, REW and Squig exports plus user targets. Validate units, finite values, sorting, duplicate frequencies, coverage and phase columns. Keep original data and show what was interpreted.
- Treat **measurement imports** and **filter imports** separately. Equalizer APO already has a supported subset; expose it through a file/paste workflow. Add AutoEQ JSON, Wavelet and Poweramp adapters only against documented formats and representative fixtures. Wavelet curves are not inherently parametric bands; label any conversion as a fit.
- **TUNE-02, fit controls:** expose band budget, boost/cut/Q/frequency limits, smoothing, bass preference, tilt, ear gain and conservative treble correction. The existing fitter defaults to 10 bands, hard-limits to 12, uses fixed smoothing/treble weighting and evaluates responses at the default DSP rate; remove those hidden assumptions deliberately.
- Target library: Harman IE, IEF Neutral, diffuse-field, JM-1, custom target and another measurement, subject to verified definitions/provenance and redistribution permission. Keep measurement-system compatibility visible rather than treating similarly named targets as interchangeable.
- Reuse and improve `AutoEqGenerator`; benchmark against an offline reference implementation. The upstream AutoEQ project provides configurable parametric fitting and useful reference behavior, not an Android playback engine. [AutoEQ project](https://github.com/jaakkopasanen/AutoEq).
- **TUNE-03, inspection/application:** show measured, target, correction and predicted corrected response; fit error; headroom; undo; explicit apply/save. Add independent L/R fits with linked or unlinked controls and trim/delay.
- Provide the same workflow for speakers with appropriate targets/measurement context. Retain current AutoEQ and Spinorama browsing as the quick preset path.

**Acceptance:** representative imports reproduce their curves; mismatched frequency grids are resampled rather than averaged by array index; constraints are honored; plots and applied filters agree; L/R remain separate; generation is cancellable; saved projects reopen without a network fetch.

Primary files: [AutoEqRepository][autoeq], [Squig client][squig-client], [Squig repository][squig-repo], [fitter][fitter], [EQ UI][eq-ui], [device preset notes][device-presets].

### FIR and RATE — Convolution, resampling and final conversion

Priority **P1** for correctness and basic management; **P2** for advanced phase/oversampling options. Size **L** each.

**FIR-01:** Add an IR library with metadata, channel mapping, sample rate, duration/tap count, normalization, trimming, delay and resource estimates. Support mono and stereo first, then true-stereo as a 2×2 convolution matrix (LL/LR/RL/RR), not two independent channels relabeled as true stereo.

- Load/prepare IRs away from playback; bound file size, channels and memory. Cache prepared variants by IR content and processing rate.
- Replace linear IR resampling with the selected validated SRC; review scaling and normalization so conversion does not silently change gain.
- Fix/verify stream lifecycle: current end-of-stream handling pads a remaining input block but does not explicitly drain the entire FIR tail. Test full tails, flush, seek, rate change and back-to-back tracks.
- Make multiple IR stages use graph latency accounting. Add minimum-phase conversion first after reference tests; linear/mixed-phase design/import needs explicit phase data and latency tradeoffs.

**RATE-01:** Define Follow source, fixed rate and compatible maximum policies. Preserve 44.1/48-kHz families when upsampling; intersect requests with supported output formats. Show chosen rate and fallback reason.

- Evaluate SRC candidates with measured passband/stopband response, alias rejection, latency, CPU and license/build constraints. Use descriptive quality settings backed by measurements rather than an unexplained Audiophile label.
- Keep output sample-rate conversion separate from speed/pitch and from oversampling within nonlinear nodes.
- Add 2×/4×/8× oversampling to supported nonlinear nodes, with anti-imaging/alias filters, latency compensation and CPU limits. It should not multiply the entire graph's cost by default.
- Add optional TPDF when reducing precision to integer output; later validate noise-shaped variants at each supported rate. Quantize once, after all processing. Bypass should not add dither to unchanged samples.

**Acceptance:** impulses and sweeps match reference convolution/SRC; entire tails are preserved; true-stereo routes all four paths correctly; unsupported DAC rates fall back visibly; dither/noise shaping meets reference tests; oversampling reduces the intended alias products without overrunning audio deadlines.

Primary files: [convolution][conv], [FFT][fft], [native conversion][usb-native], [service][service], future graph/output adapters.

### DSP — Routing, dynamic EQ, multiband and studio nodes

Priority **P2**. Size **XL**, delivered as small node groups after ENG/EQ/OBS.

1. **Utility/routing:** phase invert, DC blocker, channel matrix, mono bass, L/R and M/S processing. Validate channel polarity, gain and recombination first.
2. **Dynamic EQ and multiband:** sidechain detectors, threshold/ratio/attack/release/knee/makeup, maximum dynamic gain/cut, crossovers, per-band solo/mute and meters. Define stereo linking and bypass/tail semantics. Flat crossover recombination must be proven before adding compression.
3. **Dynamics:** improve the existing compressor/limiter, then expander, gate and de-esser. Distinguish sample-peak limiting from a validated oversampled true-peak limiter.
4. **Tone:** exciter, tape/tube variants and bass enhancement, using oversampling where needed.
5. **Spatial/time:** Haas/delay/reverb, HRIR/HRTF and ambiophonic options; document channel/latency requirements. Reuse FIR for appropriate spatial filters.
6. **Modulation:** chorus, flanger, phaser, tremolo and vibrato after the graph, metering and output paths are stable.

**Acceptance per group:** identity/bypass and channel tests; known responses/envelopes; stable parameter automation; preset migration; CPU/tail/latency reporting; audible spot checks using normal music and stress signals. Effect count is not an acceptance criterion.

### COMPARE — Level-matched A/B and blind ABX

Priority **P2**. Size **L**. Requires PRESET snapshots, ENG branch timing and OBS/RATE gain measurement.

- Start with two presets processing **the same decoded sample timeline**. Freeze output configuration and automatic rules; warm both graphs; compensate latency and match loudness over the same selection.
- Use a documented level measurement and a proposed matching tolerance of 0.1 dB where measurable. Apply attenuation rather than introducing clipping. Store the measured gains and residual mismatch.
- Make switching click-free, with a consistent short transition; prevent visual meters, labels, unequal buffering or different transition timing from revealing X.
- Add a fixed trial-count protocol, randomized hidden X assignments, logged guesses and a result report. Compute the one-sided exact binomial probability under chance using `sum(C(n,k), k=correct..n) / 2^n`; disclose trial count and interrupted trials. Preference A/B and discrimination ABX answer different questions.
- Add file/codec and resampler comparisons later, with decoder-delay/padding alignment and verified content identity. Invalidate a session when route, level, timing or source continuity changes.

**Acceptance:** identical A/B chains do not leak identity; known differences can be detected without level cues; alignment and level matching are measured; trial bookkeeping/statistics match reference cases; leaving the test restores the prior player/preset state.

### LISTEN — Adaptive loudness and estimated SPL

Priority **P2** for loudness; **P3** for calibrated SPL/history. Size **L**. Depends on PRESET route profiles and OBS volume/meter data.

- Implement smooth compensation relative to a user-set reference, with separate maximum bass/treble correction and headroom integration. Without acoustic calibration, use a relative listening-level control rather than presenting Android volume percent as dB SPL.
- Add device/headphone calibration profiles: sensitivity in dB/V or dB/mW, impedance, DAC output at gain setting, hardware/software volume curves, and calibration provenance. Account for actual digital RMS/peaks and all active gain stages.
- Present estimated level with an uncertainty indicator. Missing gain/volume/calibration data should produce an unavailable estimate, not a plausible-looking number.
- Store optional local level history. Exposure/dose estimates require a separately reviewed model and validated calibration; do not advertise an estimated level as proof that listening is safe.

**Acceptance:** unit conversions and gain accounting match known fixtures; route/gain changes invalidate inappropriate calibration; compensation is smooth; estimates visibly identify uncertainty and incomplete data.

### USB — Mature output support, DAPs and DSD

Priority **P1** for existing USB reliability/reporting; **P2/P3** for DAP and DSD depth. Size **XL**. Builds on ENG output capabilities and OBS.

- **USB-01:** Formalize native USB descriptor/format/clock discovery, grant lifecycle, hotplug, fallback, underruns and diagnostics. Distinguish source bit depth from the DAC container depth. Integrate native crossfade/varispeed status with Signal Path.
- Test local FLAC and streamed/other decoded formats separately. Maintain a device/firmware/Android/version matrix; prior README claims for the KA13 do not establish support for other DACs or the current modified driver.
- **USB-02:** Add processed USB through the shared graph alongside untouched playback. Define software and hardware volume separately; only expose hardware attenuation when the device supports it and reads/writes can be verified. Avoid sudden full-scale changes on reconnect or mode switch.
- **USB-03:** Evaluate documented vendor DAP output APIs one platform at a time. Unsupported devices use the existing Android path with clear capabilities. No blanket DAP/hi-res promise.
- **USB-04:** First audit decoder/demuxer support with DSF/DFF fixtures. Add metadata, seeking, gapless behavior and DSD-to-PCM playback. Then add DoP/native transport only for compatible hardware and sample rates, with explicit PCM fallback.
- Ordinary PCM DSP cannot be applied directly to a preserved DSD bitstream. The UI must explain when DSD is converted to PCM or later remodulated.
- DST, SACD ISO and PCM-to-DSD are separate later items with performance/compatibility gates. High DSD rates are not a requirement for completing everyday PCM playback.

**Acceptance:** repeated attach/detach and format switches recover cleanly; actual negotiated formats are reported; bypass and processed paths are distinguishable in captured output; no time/pitch drift or underruns in a sustained session; each advertised DSD mode is tested with known fixtures and real hardware.

Primary files: [USB sink][usb-sink], [native engine][usb-native], [service][service], [build][build].

### LIB — Providers, stable identity and source selection

Priority **P1** for identity/capability foundations; **P2** for new providers. Size **XL**. Can advance independently of DSP after the shared source contract is agreed.

**LIB-01: Strengthen the existing abstraction and merged library.**

- Add provider capabilities for paging/search, local access, seeking, downloads, metadata writes, favorites, playlist writes and authentication. Implement them behind `MediaBackend`/`MusicRepository`, including explicit unsupported results. Avoid new server-type branches in screens/ViewModels.
- Replace source-list-index identity with stable provider-instance/account IDs and stable source-item IDs. The current merged IDs depend on source order, so saved queues, favorites, downloads and analysis need a migration strategy when source membership/order changes.
- Introduce canonical recording/edition records that retain **all source copies** and matching evidence. Current dedup selects a winner and discards the other copies from that result; it is not yet a fallback registry.
- Separate artist/album grouping from recording identity. Preserve live/remaster/edition distinctions; allow manual split/merge. Fix normalization for non-Latin names before expanding fuzzy matching. Use MusicBrainz IDs, ISRC and verified fingerprints as evidence, not unconditional equivalence.
- Fix merged paging/global ordering and dedup across pages. Maintain partial results and per-source status when one source is unavailable.

**LIB-02: Selection and recovery.**

- Unify existing local/download/stream tier preference with codec/quality, actual availability, metered network and output compatibility. Show Sources and the chosen copy with its reason.
- Distinguish advertised library quality from the actual file/transcoded stream. Source preference must not repeatedly replace a better selected copy with a lower-quality local match without explaining policy.
- First implement before-start fallback; then bounded mid-playback recovery for aligned copies of the same recording. Preserve position and queue identity. Promise seamless switching only where timing/content can actually be matched; otherwise report a brief recovery transition.
- Make playlist writes and likes respect source ownership. The current merged playlist write filters IDs to one source; a future cross-source playlist should be an explicit app-owned object.

**LIB-03: Provider rollout.**

| Delivery | Providers | Work beyond adding a URL reader |
|---|---|---|
| First | Plex, WebDAV, SMB | Authentication, library discovery/indexing, metadata, paging, range/seek behavior, background refresh and source identity. Build one end-to-end reference provider before repeating. |
| Next | SFTP, UPnP/DLNA source browsing, Audiobookshelf | Host verification/credentials for SFTP; discovery and capabilities for UPnP; resume/chapter/media-kind treatment for Audiobookshelf. |
| Later | NFS, FTP/FTPS, Kodi, cloud storage | Dedicated feasibility/integration tasks covering Android access, discovery, authentication and indexing constraints. |
| Conditional | Qobuz, TIDAL, Bandcamp | Verify available authorized playback APIs, DRM/SDK constraints and permitted caching before committing a provider. A website/catalog API is not playback support. |
| Existing | Internet radio, podcasts, Spotify backend | Retain current functionality; Spotify merging/playback semantics require explicit capability decisions. Radio is not a new feature to build. |

The vendored `SftpDataSource` and factory are potential implementation material, but are not connected as an Aurora library provider. Their scheme declarations alone do not establish FTP/FTPS compatibility.

**Acceptance:** adding/removing/reordering accounts does not retarget saved items; duplicate alternatives remain selectable; live/remaster/non-Latin fixtures group correctly; paging is stable; offline copies resolve to the right account; provider failures recover without silent source mutation; server-specific behavior stays behind the abstraction.

Primary files: [MediaBackend][backend], [MusicRepository][repository], [AppContainer][container], [MergedBackend][merged], [DuplicateFinder][duplicates], [TrackMatch][match], [Sources settings][sources-ui], [vendored SFTP][sftp].

### ANALYZE and META — Analysis records and serious collections

Priority **P1** for shared records and existing metrics; **P2** for advanced analysis/metadata. Size **L** each. Coordinate identity with LIB-01.

**ANALYZE-01:** Create one versioned analysis record keyed to a specific source/content revision, with algorithm/version, scan coverage, confidence, timestamp and failure state. Adapt the existing Sonic/Mix cache and ReplayGain store instead of adding another independent full decoder pass.

- Reuse waveform, RMS, estimated BPM/key, audible boundaries and fingerprints already implemented. Distinguish Sonic similarity vectors from recording-identification fingerprints.
- Validate track/album loudness against known reference files; then store LUFS-I, LRA and true peak directly rather than only derived gain. Define what dynamic range means before exposing it.
- Add incremental, resumable/cancellable jobs with charging/network/storage policy, progress and failure retry. Preserve completed results and avoid rescanning unchanged content; exclude unnecessary stem-separation work.
- Expose BPM/range, harmonic key compatibility, loudness, peak and analysis availability to smart playlists. Missing analysis is Unknown, not zero; combine new fields with existing last-played/play-count rules.

**META-01:** Extend the schema and each backend mapping before building expanded editors.

- Multi-value track/album artists and genres; composer, conductor, remixer, producer; work/movement/number; disc subtitle; release/original date; label/catalog; ISRC/MusicBrainz IDs; ratings, sort fields and custom tags.
- Add explicit recording/release/edition/disc relationships for multi-disc albums, box sets, compilations/Various Artists and classical browsing. Support embedded artwork collections and attached booklets.
- Add CUE virtual tracks with shared-source offsets, duration, seek and gapless semantics. Test them through the same source/queue/analysis contracts.
- Expand local and supported server tag editing; expose per-field/per-provider write capability. Batch editing needs a concrete preview, selection, partial-failure report and preservation of unrelated tags/artwork.
- Keep MusicBrainz/AcoustID matching as user-reviewable enrichment; a suggestion must not silently replace original metadata. Keep raw backend values/provenance where normalized models lose information.

**Acceptance:** scans resume without duplicate work; reference loudness/peak cases pass; smart playlists distinguish missing metrics; rich metadata survives read/edit/write and backup; unsupported writes are visible; batch failure does not corrupt unaffected files; CUE/disc/edition collections retain correct playback order.

Primary files: [ReplayGainScanner][rg], [SonicEngine][sonic], [MixAnalyzer][mix-analysis], [SmartPlaylists][smart], [models][models], [TagEditor][tags], [TagEditViewModel][tag-vm], [MediaBackend][backend].

### NET — Processed network output and Aurora renderer

Priority **P2/P3**. Size **XL**. Requires stable ENG output contracts and LIB source authorization; existing direct Cast remains available.

- **NET-01:** Explicitly distinguish Direct from Processed output. In processed mode, Aurora decodes and runs the graph, then encodes/serves a receiver-supported stream. Negotiate MIME/container/rate and measure latency, seek and gapless behavior; raw in-memory PCM is not itself a Cast integration.
- Start with a single receiver prototype and one known format. Add bounded buffering, timestamped transport, receiver disconnect recovery, route ownership and background execution handling before broad renderer support.
- Implement DLNA/UPnP output as a separate adapter. Evaluate Sonos and Kodi against their actual control/playback interfaces and receiver limits; they are not automatically covered by Chromecast.
- **NET-02:** Make an opt-in Aurora endpoint with pairing, discovery, authenticated commands and a versioned protocol for queue, seek, volume, route, status and errors. Keep controller and renderer roles explicit.
- Define how a remote renderer obtains a playable source without exposing server credentials. Stream serving should use scoped, short-lived access; the renderer should own playback/USB negotiation and survive a controller disconnect.
- Share the DSP/output contracts and telemetry; avoid starting a second competing playback service. Multi-room clock synchronization is a later scope, not implied by a single remote endpoint.

**Acceptance:** Direct mode reports Aurora DSP bypass; processed output produces the expected response on a captured receiver stream; unsupported formats are negotiated/fail clearly; seek/pause/reconnect and controller loss behave predictably; a paired tablet can play through its DAC while the phone controls it; credentials are not exposed in URLs/logs.

Primary files: [service/Cast switching][service], [Cast options][cast], [MediaBackend][backend], future output/controller adapters.

### EXT — Extension architecture

Priority **P3**. Size **XL**. Internal contracts can be prepared earlier; publish an SDK only after they have real built-in consumers.

- Define `AudioPlugin`, `MediaProviderPlugin`, and `MetadataPlugin` contracts around proven graph/provider/metadata interfaces, with version negotiation, capabilities, persistence, settings UI and lifecycle.
- First use build-time extensions in the repository with example nodes/providers/metadata adapters. This proves the contracts without committing to runtime binary loading.
- Evaluate runtime packaging separately: companion-service or sandboxed extension approaches for providers/metadata, and a tightly specified ABI/execution model for audio. Account for callback deadlines, isolation, CPU/memory budgets and unavailable/crashing extensions.
- Add permission declaration, compatibility checks, user-controlled enable/disable, asset/license attribution, offline operation and backup behavior for missing plugins.
- Do not give arbitrary extension code unrestricted access to account credentials or the audio callback merely to meet a plugin checkbox.

**Acceptance:** an example extension for each category works through the public contract; incompatible versions fail clearly; removing an extension leaves readable presets/library data; provider failure does not stop other sources; audio extension failures have bounded behavior.

## 6. Delivery order and dependencies

This is a sequence of reviewable releases, not a commitment to implement the whole roadmap in one pass.

| Delivery | Included work | Exit condition |
|---|---|---|
| **R0 — Find and understand existing features** | SET-01/02; OBS-01 for existing paths; capture behavior/performance fixtures. **Implemented 2026-09-08; [results and validation boundaries](r0-settings-and-signal-path.md).** | Alarm/Signal Path are separate docks; settings ownership is clear; current-path claims are evidence-qualified. Hardware-output and sustained performance checks remain explicit follow-ups. |
| **R1 — High-precision serial processing** | PRESET-01; ENG-01/02; serial part of ENG-03; essential OBS taps. **In progress: [R1a saved states](r1a-processing-presets.md) and [R1b portable bundles, sample measurements and tested precision prototype](r1b-portability-measurements-and-engine.md) delivered. Production adapter, remaining effects/convolution, graph migration/editor and full acceptance gates remain.** | Full presets migrate, no intermediate PCM16 loss in the new processed path, declared precision matches active nodes, baseline playback regressions pass. |
| **R2 — Everyday tuning depth** | EQ; TUNE import/fitting; basic FIR manager; PRESET-02; OBS headroom/meter basics. | 64-band editing/import, measurement projects, accurate response plots, separate L/R correction and reliable output bindings. |
| **R3 — Advanced processing and listening tools** | Graph branches/latency; RATE; deeper FIR; dynamic EQ/multiband; COMPARE; rule engine; relative adaptive loudness. | Routing, resampling and comparison meet measured acceptance gates; no misleading headroom or blind-test claims. |
| **R4 — Output maturity** | USB reliability/processed path, then DAP/DSD milestones. | Each advertised hardware mode has an actual compatibility test and correct Signal Path reporting. USB reliability can be brought forward alongside R1. |
| **Library track** | LIB-01 + ANALYZE shared schema + META foundations; then LIB-02/03 and deeper metadata/analysis. | Stable identities precede mass provider expansion; useful existing scan metrics reach smart playlists early. This track can proceed independently of the DSP releases. |
| **R5 — Network platform** | NET processed Cast/DLNA, then Aurora endpoint. | End-to-end receiver output and controller/renderer lifecycle verified. |
| **R6 — Specialist depth and extensibility** | Calibrated SPL/history, remaining DSP families, later DSD/source formats, runtime EXT SDK. | Each feature passes its own gates and has a maintained format/device/extension support contract. |

Critical dependency chains:

```text
SET → observable current Signal Path
PRESET schema + ENG precision → serial rack → 64-band EQ → tuning/L-R
ENG graph + latency + OBS meters → advanced FIR/RATE → ABX/dynamic processing
ENG output adapters + OBS evidence → processed USB/DSD → processed network output
LIB stable identity + META schema → unified analysis/source alternatives → new providers
Proven graph/provider/metadata contracts → public plugin SDK
```

### Recommended first implementation batch

- [x] Extract Alarm and Signal Path into their own screens and Settings docks.
- [x] Add the Now Playing Signal Path shortcut and an idle state.
- [x] Move output controls and ReplayGain into their owning destinations; preserve all preference values.
- [x] Correct current-path reporting for native USB, inactive/bypassed DSP, Mix and Cast; retain Unknown for unavailable measurements.
- [ ] Complete baseline coverage: preference/UI fixtures, normal playback, crossfade, Mix and MediaBrowser/session checks are captured in [R0 results](r0-settings-and-signal-path.md); actual Android Auto UI, physical USB routing and sustained audio performance remain unverified.
- [ ] Write the ENG/PRESET contract proposal with a measured precision-path prototype before expanding the number of effects.

## 7. Verification and release gates

This section describes checks for **future implementation**; none are claimed to have run during this audit.

| Area | Required evidence |
|---|---|
| Settings | Phone screenshots at normal/large font size; direct routes/Back/idle state; persisted values; alarm schedule/fire/dismiss; no duplicate controls. |
| Processing | Reference impulses, sweeps, tones and low-level 24-bit fixtures; channel isolation; reset/tails; graph changes; correct sample count/timing and final quantization. |
| Audio performance | Same-device baseline and changed build at source rates used in testing; representative 64-band/FIR/parallel chains; callback time vs block deadline, underruns, memory, heat and battery over a sustained run. Record device/OS and configuration. |
| Metering | Reference peak/RMS/LUFS/true-peak cases, actual tap placement, pre/post alignment, no fabricated hardware measurements. |
| Player integration | Normal playback and existing focused Mix tests; queue/shuffle/repeat, gapless, crossfade, speed, seek, pause/focus, sleep/alarm fades, notification/Android Auto commands and source failures. |
| Outputs | Speaker, wired/Bluetooth and actual USB DACs; attach/detach; unsupported formats; Android float vs custom processing; native FLAC vs decoded USB; direct/processed Cast when added. |
| Data | Old preferences and presets; account removal/reorder; offline copies; duplicate editions/non-Latin metadata; paging, backup/restore, cancelled scans, unsupported tag writes. |
| Advanced tools | ABX blindness/timing/statistics; L/R and matrix impulse tests; crossover recombination; SRC/dither references; DSD transport fixtures plus compatible hardware. |

Use **JDK 21 and the Gradle wrapper**. Fast Kotlin/resource checks are `:app:compileDebugKotlin` and `:app:processDebugResources`. Current focused tests use `:app:testReleaseUnitTest`; device work uses the appropriate signed build and release instrumentation setup already recorded in [Mix documentation][mix-doc]. A compile result does not validate sound or output negotiation. Preserve installed app data and signing compatibility when selecting a device build.

New tests should target actual failure modes and numerical/contracts behavior. A settings-only extraction primarily needs navigation/persistence/device review; a graph or source-identity change needs meaningful regression fixtures.

## 8. External research and limits of the comparison

The supplied competitor references were checked on 2026-09-08. These are **publisher-described features**, not independently measured comparisons:

- Aetherfin describes an 86-effect mpv/FFmpeg rack and an output-driven FFT visualizer. That supports prioritizing flexible processing and reliable metering; it does not establish that Aurora must reproduce every effect. [Aetherfin repository](https://github.com/Aetherfin/mobile-app).
- Poweramp advertises 64-band PEQ. Its current site places the major 64-bit/DSD engine work in a beta section, which should remain qualified in comparisons. [Poweramp](https://powerampapp.com/).
- Symfonium describes 64-bit local DSP, DSD/DoP, USB/DAP paths and per-output settings, alongside a broad merged source library. Those are relevant targets for output and provider capability coverage. [Hi-res/DSD features](https://www.symfonium.app/android-hi-res-dsd-music-player/), [source/library overview](https://symfonium.app/).
- Neutron lists advanced resampling, dither, oversampling, adaptive loudness, DSD and DSP-applied network output. Each is a distinct engineering feature rather than one checkbox called audiophile mode. [Neutron features](https://www.neutroncode.com/apps/player).

No claim of universal superiority, audible benefit from 64-bit arithmetic, guaranteed Android mixer visibility, or effortless seamless source switching follows from these pages. The completion criteria above are Aurora's measurable behavior, coverage and usability.

## 9. Source index

| Evidence area | Main local sources and symbols |
|---|---|
| Settings/navigation | [SettingsScreen][settings-ui], [PlaybackSettingsScreen and SignalPathCard][playback-ui], [EqualizerScreen][eq-ui], [Routes][routes], [AuroraApp][nav]. |
| State/migration | [SettingsStore: AudioPrefs, ParamBand, EqBinding, AlarmPrefs][prefs]; [BackupManager][backup]. |
| Runtime processing | [PlaybackService: sink construction, updateSignalPath, applyAudioPrefs, replayGainMultiplier, Cast/Mix transitions][service]; [AuroraDspProcessor][dsp]; [DspCoeffBuilder][coeff]. |
| FIR/analysis taps | [ConvolutionProcessor][conv], [Fft][fft], [TappingAudioSink][tap], [VisualizerController][visualizer]. |
| Tuning | [AutoEqRepository/EqTextParser][autoeq], [AutoEqGenerator][fitter], [SquigClient][squig-client], [SquigEqRepository][squig-repo], [AutoEqController][bindings]. |
| Output | [SignalPath and AppContainer][container], [UsbAudioSink][usb-sink], [native-audio-engine.cpp][usb-native], [CastOptionsProvider][cast]. |
| Library | [MediaBackend][backend], [MusicRepository][repository], [MergedBackend][merged], [DuplicateFinder][duplicates], [TrackMatch][match], [SourcesSettingsScreen][sources-ui]. |
| Analysis/metadata | [ReplayGainScanner][rg], [SonicEngine][sonic], [MixAnalyzer][mix-analysis], [SmartPlaylists][smart], [Models][models], [TagEditor][tags], [TagEditViewModel][tag-vm]. |
| Alarm | [AlarmScheduler/AlarmReceiver][alarm], [AlarmActivity][alarm-activity]. |
| Existing work and tests | [Earlier roadmap][old-roadmap], [Mix notes][mix-doc], [MixPlayer][mix-player], [build configuration][build], [MixMathTest][mix-tests], [MixPlaybackDeviceTest][mix-device-tests]. |

[settings-ui]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/ui/screens/settings/SettingsScreen.kt
[playback-ui]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/ui/screens/settings/PlaybackSettingsScreen.kt
[eq-ui]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/ui/screens/settings/EqualizerScreen.kt
[routes]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/navigation/Destinations.kt
[nav]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/ui/AuroraApp.kt
[prefs]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/SettingsStore.kt
[backup]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/BackupManager.kt
[container]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/AppContainer.kt
[service]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/PlaybackService.kt
[dsp]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/AuroraDspProcessor.kt
[coeff]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/DspCoeffBuilder.kt
[conv]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/ConvolutionProcessor.kt
[fft]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/Fft.kt
[tap]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/TappingAudioSink.kt
[visualizer]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/VisualizerController.kt
[autoeq]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/AutoEqRepository.kt
[fitter]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/AutoEqGenerator.kt
[squig-client]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/remote/SquigClient.kt
[squig-repo]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/SquigEqRepository.kt
[bindings]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/AutoEqController.kt
[usb-sink]: C:/Users/mw/Documents/projects/apps/projects/android_ui/decent/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/UsbAudioSink.kt
[usb-native]: C:/Users/mw/Documents/projects/apps/projects/android_ui/decent/decent-usb-audio-driver/src/main/jni/native-audio-engine.cpp
[cast]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/CastOptionsProvider.kt
[backend]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/MediaBackend.kt
[repository]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/MusicRepository.kt
[merged]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/MergedBackend.kt
[duplicates]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/DuplicateFinder.kt
[match]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/util/TrackMatch.kt
[sources-ui]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/ui/screens/settings/SourcesSettingsScreen.kt
[sftp]: C:/Users/mw/Documents/projects/apps/projects/android_ui/decent/decent-usb-audio-wrapper-media3/src/main/kotlin/com/decent/usbaudio/media3/SftpDataSource.kt
[rg]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/ReplayGainScanner.kt
[sonic]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/SonicEngine.kt
[mix-analysis]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/mix/MixAnalyzer.kt
[smart]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/SmartPlaylists.kt
[models]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/model/Models.kt
[tags]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/data/TagEditor.kt
[tag-vm]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/viewmodel/TagEditViewModel.kt
[alarm]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/AlarmScheduler.kt
[alarm-activity]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/playback/AlarmActivity.kt
[old-roadmap]: C:/Users/mw/Documents/projects/apps/projects/android_ui/aurora-roadmap-phases-2-7.md
[device-presets]: C:/Users/mw/Documents/projects/apps/projects/android_ui/docs/device-presets.md
[mix-doc]: C:/Users/mw/Documents/projects/apps/projects/android_ui/docs/mix-studio.md
[mix-player]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/main/java/com/aurora/music/mix/MixPlayer.kt
[build]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/build.gradle.kts
[mix-tests]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/test/java/com/aurora/music/mix/MixMathTest.kt
[mix-device-tests]: C:/Users/mw/Documents/projects/apps/projects/android_ui/app/src/androidTest/java/com/aurora/music/mix/MixPlaybackDeviceTest.kt
