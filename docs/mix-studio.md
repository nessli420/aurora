# Mix studio and crossfade

Tap **Mix** on any album, playlist or artist to load the complete tracklist, create transitions, and start playback in the normal player. Track artwork, titles, queue selection, next/previous, seeking, repeat, and lock-screen controls remain available. The Mix action uses cached Sonic analysis immediately; tracks without measurements receive smooth fallback fades, without starting a library scan. Open Mix Studio and use **Auto mix** to analyze and refine them. **Library → Mix studio**, **Queue → Open mix studio**, and the player menu also open the editor.

## Using a mix

- A mix can hold up to 10,000 songs, with up to eight playing simultaneously. Choose **Blend after last** for a sequence or **Layer from start** to play songs together.
- Tap a track in the arrangement to open its controls. **Timing** sets the position in the mix and source cue points. Tap a numeric value for precise entry.
- **Fades** offers smooth, linear, equal-power and cut curves, independent fade lengths, and independent entrance/exit bend controls. The transition close-up shows the actual analyzed waveforms and volume envelopes.
- **Sound** controls track level, pan, bass/mid/treble EQ, tempo and pitch. Level, pan and EQ react while dragging. Tempo preserves pitch unless pitch is changed separately.
- Mute and solo tracks; undo/redo edits; preview from three seconds before a track's entrance; loop the whole mix. Master controls include attenuation and overlap headroom.
- Tempo matching and 4/8/16-bar transitions use estimated BPM. Bar timing assumes four beats per bar. These are editable starting points, not verified downbeat grids. Without analysis, the transition shortcut uses eight seconds.
- Save by tapping the save icon; tap the title to rename. Saved mixes belong to the current account. Closing the editor keeps playback running. Stop restores the normal queue; starting a library song also returns to normal playback.

The saved **Mix studio preview** on the test phone uses “1991” and “bella” with an eight-second transition. It is a functional example, not a claim that these tracks are an optimal musical pairing.

## Analysis without a saved download

Analysis reads the playable URI through Android's extractor/decoder. It does not enqueue a download, write an audio file, or require that the song be saved offline. HTTP range access is used when the source supports it. Audio bytes still need to cross the network, and a complete waveform requires reading the track.

Sonic Discovery and Mix Studio share a durable account-scoped analysis library: waveform peaks, energy, RMS, estimated BPM/beat phase, audible boundaries and Sonic sound fingerprints. Each stream is decoded once for both features. Sonic spectral fingerprints retain the existing 30-second characterization window; waveform and energy analysis cover the track. The previous 100-track cache limit is removed. **Settings → Sonic discovery → Analyze library** traverses the whole available library, including streams. Completed records survive interruption; failed tracks remain eligible for retry. Auto-analyze on launch uses the same scan. Analysis is cancellable, runs one decoder at a time, and is bounded to one hour of audio / three minutes of decoder-loop time. A blocking platform network call can take longer to cancel. Unsupported or failed streams report an error; manual mixing remains available.

Tempo comes from a 50 Hz onset-energy envelope and autocorrelation. It can be ambiguous, including half/double tempo. The UI labels confidence and estimates instead of presenting an exact beat grid. Waveforms retain the maximum absolute amplitude across channels at ten points per second.

## Playback design

`MixPlayer` is a Media3 `SimpleBasePlayer` exposed through the existing `MediaLibrarySession`. It keeps a rolling set of up to eight active/upcoming ExoPlayer decks, releasing old ones and preparing tracks near the playhead. Its transport controls those decks, including notification/media-button pause and seek, audio focus, noisy-output events, looping and sleep fades. A buffering active deck pauses the group. The mix timeline follows a playing deck's audio position, avoiding a wall-clock lead after seeking or resuming an AudioTrack. The ordinary queue is retained separately. Collection playback exposes individual songs through MediaSession; metadata changes at the overlap midpoint, with positions mapped back to source-song time. Studio preview retains a continuous mix timeline. Shuffle and queue reordering belong in the editor while a mix is active.

Each deck uses the same source resolver as normal playback. Track EQ/pan has attenuation for boosts and a safety limiter; mono sources are expanded to stereo before that stage. Global custom/system EQ, mono, convolution and attenuate-only ReplayGain remain connected. Mix playback uses processed PCM through Android audio output. Cast and the exclusive native USB path cannot host the eight-deck studio; the UI explains this when playback is requested.

With overlap headroom enabled, gain sums cannot exceed the master level. This conservative protection also changes the loudness shape of an equal-power curve. Turning it off restores the raw equal-power envelope and can produce higher combined peaks. Independent Android AudioTracks are not a sample-locked professional multichannel audio workstation.

## Automatic collection transitions

The collection action preserves the order and repeated occurrences of a playlist or album. Artist mixes load the full available discography rather than only the detail screen's popular tracks. Backend-specific pagination is kept in the data layer; UI and playback stay server-agnostic.

The planner trims limited leading/trailing silence, estimates rhythm compatibility and chroma similarity, balances RMS with attenuation, and selects a short smooth fade or an 8/16-beat overlap. Close, confident tempos can match within 6% while preserving pitch. Fade timing can align to the estimated beat phase. A bass exchange avoids two bass lines dominating the overlap. Every choice is editable; **Auto mix** can rebuild transitions, with undo and an option to leave tempos unchanged. Unavailable analysis produces an explicitly labeled gentle-fade fallback. These are estimates and musical starting points, not verified downbeats or a guarantee of ideal phrasing.

Use **All tracks** to jump to any track in a long collection. The arrangement shows nearby tracks so hundreds of tracks do not create hundreds of simultaneous waveform views. The editor reports analysis progress and supports cancellation. It saves an existing unsaved draft before replacing it with a collection mix.

## Vocals and backing

Select **Vocals only** to mute the accompaniment, **Backing only** to remove vocals, or **Original** for the unseparated song. The first selection downloads and verifies the 64 MB UVR neural model. Inference runs locally on the device, with a progress display and cancellation. Songs are not uploaded. Separation takes substantially longer than waveform analysis and may leave audible artifacts.

Mono/stereo tracks up to 30 minutes are supported. The source is streamed into temporary 44.1 kHz PCM, which is deleted after processing or cancellation. Two derived WAV stems remain in private cache for quick reuse; they do not appear as library downloads. Saved mixes remember the chosen stem and resolve the cache on reopen. If Android clears a stem, the app asks you to prepare it again instead of silently playing the original.

Credits and reproducible model parameters are in [vocal-separation-attribution.md](vocal-separation-attribution.md).

## Automatic crossfade

The outgoing player/decoder now stays alive. A muted incoming player prepares in advance with the same source resolution, output route, audio session and processing configuration. Playback ownership passes to the prepared incoming player while the original player fades out. The old implementation recreated the outgoing tail with a plain player and skipped its processing chain.

Fades support 0–30 seconds, three curves and overlap headroom. Short tracks cap overlap at half their duration. Each fade snapshots its duration, curve and gains, pauses with transport/focus/buffering, and abandons the tail on a manual seek. Sleep/wake fades multiply the whole overlap. Preparation failure leaves ordinary queue playback available.

Exclusive USB retains native crossfade for compatible local FLAC pairs. An off-main-thread STREAMINFO check verifies format/block compatibility before advancing. Native gains are calculated per frame, honor the selected curve/headroom, and the incoming engine starts paused. Its initial seek completes before the pending tail is adopted, preventing premature output and accidental tail cancellation.

## Verification

Use JDK 21 and the repository Gradle wrapper:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
./gradlew.bat :app:testReleaseUnitTest :app:assembleRelease :app:assembleReleaseAndroidTest
```

The instrumentation APK is release-signed, matching the installed app. Install the normal release and `app/build/outputs/apk/androidTest/release/app-release-androidTest.apk`, then run:

```powershell
adb -s <serial> shell am instrument -w -r -e class com.aurora.music.mix.MixPlaybackDeviceTest com.aurora.music.test/androidx.test.runner.AndroidJUnitRunner
```

The focused suite covers fade monotonicity and peak bounds, raw equal-power energy, eight-way overlap/mute/solo, cue/tempo arithmetic, invalid settings, device playback with three/eight simultaneous decks, pause/seek/loop/live controls, sleep fade, source failure, HTTP buffering, streamed analysis of a known 120 BPM pulse, persistence/account isolation, normal crossfade queue handoff, and mix-session return to normal playback.

Phone UI checks use actual library tracks, saved/reopened mixes, completed waveform analysis and transition preview. Original test output is under `_artifacts/mix/`; collection, shared-analysis and separation validation is under `_artifacts/mix-v2/`. Additional checks cover a 300-track arrangement, a 32-track phone seek, server-only Sonic scanning and account isolation, stereo transform reconstruction, and real neural inference followed by stem playback. Native USB changes are compiled for arm64 and x86_64 but have not been listened to through a physical USB DAC in this pass. The checks do not establish perceptual perfection or superiority over another player.

## Device validation for collection mixes and stems (2026-09-08)

- Ten JVM checks and ten release instrumentation checks on the connected Nothing Phone (3a) Pro. The instrumentation covers a 32-track mix with bounded decoder count, server-only Sonic scanning, account isolation and persistence, direct-HTTP neural separation, waveform reconstruction, stem playback, and the existing crossfade/transport regressions.
- A real 11-track **Floral Green mix** was generated from the album's Mix button, analyzed without adding library downloads, saved, reopened, and played through the first automatic transition. Sonic Discovery showed the same 11 records.
- The complete **Leaf** track was separated on the phone. Vocals-only playback and switching to backing-only during playback completed without a transport error. The saved album mix was restored to Original afterward; both stems remain cached for reuse.
- The 12-second streamed inference fixture took approximately 45 seconds on this phone. Full-song separation takes several minutes. Sampling memory during inference showed about 1.1 GiB peak PSS with CPU arena retention disabled; this is a device sample, not a universal memory ceiling.
- No subjective claim of perfect isolation, beat tracking or superior sound quality is established by these checks. Transitions remain editable and separated audio can contain artifacts.

## Normal player collection playback follow-up

- Album/playlist/artist Mix starts the normal player using cached analysis, with smooth fallback fades and no automatic full-library scan. The highlighted mix control opens Studio for edits.
- Release and debug builds plus ten JVM checks passed. Two focused emulator instrumentation tests passed, covering the normal PlayerViewModel and MediaController, automatic metadata changes, duplicate queue occurrences, pause, source-relative seek, next/previous, repeat-one and return to regular playback.
- Tapped the album Mix button in the emulator and verified automatic playback in PlayerScreen, individual queue entries and the edit-mix control. Screenshot: `_artifacts/mix-v2/album-mix-normal-player.png`. The phone was disconnected during this follow-up; its installed build was not changed.
