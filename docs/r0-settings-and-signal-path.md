# R0: settings ownership and current playback evidence

Implementation report · 2026-09-08 · implemented and validated within the boundaries below

This pass implements the settings and current-path observability work from [R0 in the completeness plan](music-player-completeness-plan.md). It makes existing features easier to find and qualifies playback claims against available runtime evidence.

## Delivered behavior

Settings now groups destinations under Library & accounts, Audio, Timers, Appearance & controls, Connections, and App & data. [SettingsDestinations](../app/src/main/java/com/aurora/music/ui/screens/settings/SettingsDestinations.kt) owns the route labels and descriptions.

| Destination | Ownership |
|---|---|
| Playback & quality | Streaming quality, data saver, crossfade, gapless, silence skipping and speed. |
| Audio output | Preferred Android device, independent output, hi-res preference and experimental direct USB; links to Signal Path. |
| Equalizer & effects | Existing EQ, presets, convolution and effect controls; mono now lives in Channels. |
| Volume & loudness | ReplayGain Off/Track/Album; links to EQ and Signal Path. The existing engine remains attenuation-only. |
| Signal Path | Dedicated dock and Now Playing shortcut to the same inspector, including an idle state and diagnostic copy action. |
| Alarm | Dedicated dock with daily enable/time controls, next submitted trigger, scheduling status and a Permissions link. |

Output and alarm controls no longer live inside Playback; ReplayGain has one settings destination. Preferred output remains an in-memory choice for the current app session. Selecting a preferred device is not presented as confirmation of the actual Android route.

### Signal Path

The inspector separates Source, Decoder, Processing, Resampling, Output, Device and Latency. It distinguishes observed decoder/sink formats from source metadata, user preferences and unmeasured hardware behavior. Copying the report uses allowlisted technical facts and omits media URLs, credentials, track titles and identifying device details.

- Applied processing, gains, fades, channel changes and format reductions report **Modified** when observed.
- Configured but bypassed DSP is identified as bypassed. Enabling the float preference does not incorrectly classify ordinary PCM16 as the float bypass path.
- Native/decoded USB transport uses native stream evidence, independently of an Android mixer preference grant. Accepted USB configuration still does not prove downstream sample preservation.
- Mix reports multiple processed decks and their limitations. Cast reports receiver processing/output as unknown. Neither retains stale source or output formats from local playback.
- Unknown output format, route, downstream resampling and latency remain **Unknown**. R0 does not award a sample-preservation guarantee from preferences or accepted mixer requests.
- Android AudioTrack evidence uses a generation ledger, so an old asynchronous release cannot erase a replacement track, including one with the same format. Automatic output retains opportunistic USB mixer requests without treating the connected candidate as an observed route.

The decision model and its tests are in [SignalPath.kt](../app/src/main/java/com/aurora/music/data/SignalPath.kt) and [SignalPathTest.kt](../app/src/test/java/com/aurora/music/data/SignalPathTest.kt).

### Alarm

The existing one-alarm behavior, liked-song/downloaded fallback and 30-second wake fade remain. The screen follows the device's 12/24-hour preference and locale. Scheduling reports Disabled, Exact, Approximate or Failed; the next time comes from a successful schedule submission rather than a prediction rendered as an armed alarm.

Opening or returning to the screen only refreshes permission observations. Unrelated DataStore writes no longer re-arm the alarm. Boot, app update, clock/timezone changes and exact-access grants reschedule through the existing receiver using one PendingIntent. Daily wall-time calculation handles midnight, year boundaries and DST: missing times shift by the spring gap; an overlapping fall hour uses its first occurrence once that day.

Device testing reproduced an existing race: dismissing while the library lookup was pending could start music when its response arrived. The service now cancels the pending alarm job and checks cancellation before changing playback. The delayed-response regression passes on both the emulator and physical phone.

## Preferences and scope

This extraction keeps the existing DataStore keys, defaults and setters. There is no storage migration or preset rewrite. The visible pre-update fixture records hi-res enabled, direct USB disabled, independent output enabled, mono disabled, and the daily alarm disabled at 00:45. Those values were confirmed in the new destinations after installation; the alarm was briefly enabled to inspect its exact schedule, then restored to Off at 00:45. This is a UI fixture, not a complete raw-preferences backup. See [baseline controls](../_artifacts/r0/baseline-visible-controls.json), [output controls](../_artifacts/r0/audio-output.png), [mono](../_artifacts/r0/equalizer-bottom.png) and [restored alarm](../_artifacts/r0/alarm-restored.png).

R0 does not implement the new precision engine, routing graph, full preset schema, expanded EQ/FIR or advanced metering. The ENG/PRESET design and measured precision prototype remain follow-up work toward R1.

## Verification evidence

| Check | Evidence and current result |
|---|---|
| Build and JVM tests | [Final build](../_artifacts/r0/build-final.log) passed debug Kotlin/resources, signed release assembly, release instrumentation assembly and **35 JVM tests**: 11 Signal Path, 4 AudioTrack evidence, 10 alarm scheduling, 10 existing Mix/AutoMix. The final [test-harness build](../_artifacts/r0/test-harness-build.log) also passed. |
| Playback/session baseline | [Final emulator run](../_artifacts/r0/emulator-final.log) and [physical-phone run](../_artifacts/r0/phone-final.log): **4/4 tests on each**. Real MediaBrowser root/children access, local PCM playback, pause/seek/resume, custom-path reporting, applied ReplayGain, report privacy and clearing stale formats at idle passed. |
| Alarm scheduling | Those runs passed schedule/cancel, read-only status refresh and unrelated-preference-write checks. JVM coverage includes midnight, DST, timezone/clock changes, denied exact access, permission races and schedule failures. The phone displayed a successful exact trigger for 9 September, 00:45 before restoration to Off. |
| Alarm playback | Actual `ACTION_ALARM` service dispatch played the local fixture; dismiss stopped playback and removed the notification. The delayed-lookup case failed in the [initial emulator run](../_artifacts/r0/device-initial.log), then passed after the cancellation fix on both devices. |
| Mix/crossfade regression | **9/9 emulator checks passed** in [final Mix run](../_artifacts/r0/mix-final.log): 3/8-deck playback, full collection/last-track seek, buffering, source error, saved-project isolation, crossfade pause/queue, session handoff and collection transport. |
| Physical phone UI | Nothing Phone (3a) Pro / Android 16, API 36. Signed release installed with `install -r`; app data retained. Settings docks, Alarm → Permissions → Back, output picker, loudness/EQ crosslinks, player shortcut and scrolling inspected. Settings/Alarm/Signal Path also inspected at 150% font scale; system scale restored to 100%. |
| Performance baseline | Before/after [graphics](../_artifacts/r0/baseline-gfx.txt) / [graphics after](../_artifacts/r0/final-gfx.txt) and [memory](../_artifacts/r0/baseline-memory.txt) / [memory after](../_artifacts/r0/final-memory.txt) snapshots were captured. Different UI/runtime histories mean these are reference snapshots, not a benchmark or evidence of performance improvement. |

Tests use bounded local PCM fixtures and restore the temporary settings/backend/queue they change. Playback/alarm tests skip incompatible active exclusive-USB, Mix or remote sessions where applicable; skipped cases must not be counted as validated hardware behavior.

The emulator is API 35, x86_64. Its existing app used the debug certificate, so copies of the release app/test APKs were signed with that certificate for `install -r`, preserving the emulator's app data. The physical phone used the normal signed release. Only the disposable emulator instrumentation package was reinstalled when correcting its certificate.

The initial Mix run had four timeouts because Android denied audio focus to raw players without a foreground activity. [Correlated logs](../_artifacts/r0/mix-focus-diagnosis.log) establish the cause. The test harness now launches and awaits a resumed, focused MainActivity; production Mix behavior was not changed for this issue. The source-error assertion now requires an actual player exception, so audio-focus denial cannot falsely pass that test.

### Visual and diagnostic artifacts

| Artifact | What was checked |
|---|---|
| [Settings docks](../_artifacts/r0/settings-final.png) · [150%](../_artifacts/r0/settings-large-audio.png) | Audio destinations and independent Alarm row; wrapping and scroll access. |
| [Alarm](../_artifacts/r0/alarm-restored.png) · [armed status](../_artifacts/r0/alarm-armed.png) · [150%](../_artifacts/r0/alarm-large.png) | Saved wall time, successful schedule status, permission link, restored disabled state. |
| [Player shortcut](../_artifacts/r0/player.png) · [opened inspector](../_artifacts/r0/player-shortcut.png) | Source/quality row opens the shared inspector and collapses the expanded player; Back returns to the underlying navigation screen. |
| [Output/device/latency](../_artifacts/r0/signal-output.png) · [150%](../_artifacts/r0/signal-large.png) · [idle](../_artifacts/r0/signal-idle.png) | Source rate is distinct from AudioTrack configuration; downstream unknowns remain explicit; idle remains useful. |
| [Volume & loudness](../_artifacts/r0/loudness.png) · [Audio output](../_artifacts/r0/audio-output.png) | One ReplayGain control, preserved output values, route picker available. |
| [Phone diagnostic fixture](../_artifacts/r0/phone-signal-path.txt) · [emulator fixture](../_artifacts/r0/emulator-signal-path.txt) | Local 44.1 kHz stereo PCM16 test stream and processing facts; no fixture title/path in copied diagnostic. |

## Validation boundaries and follow-up

- MediaBrowser coverage validates the library/session contract used by Android Auto; the actual Android Auto UI was not exercised.
- Physical USB DAC negotiation, detach/reattach, native output fidelity and real Cast receiver behavior were not validated live. Their reporting logic has focused fixtures, which do not replace hardware tests.
- Alarm service-action tests do not establish overnight, reboot, Doze or locked-screen delivery. Exact access was granted on the test phone; denied/inexact behavior was covered by JVM fixtures.
- Existing empty-catalog behavior provides no alarm playback. A slow remote lookup before media preparation can still affect foreground-service startup. A fire intent already being delivered can race with disabling the daily schedule. These are separate from the reproduced delayed-dismissal race.
- Hardware fidelity, sustained callback/underrun performance and advanced precision-path fixtures remain future release gates. No new 64-bit or sample-preserving engine is claimed by this pass.
