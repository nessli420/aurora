---
name: aurora-audio-dev
description: Expert guidance for building Aurora (the com.aurora.music native Android / Jetpack-Compose music client) and advanced Android audio apps in general. Use this whenever working in the Aurora codebase OR on any Kotlin / Jetpack Compose / Media3 / ExoPlayer / audio-DSP / equalizer / MediaSessionService / MediaLibraryService / Android Auto / Chromecast / ReplayGain / bit-perfect / playback-queue task — even when the user doesn't name the skill. It encodes Aurora's server-agnostic MediaBackend architecture, the build/run toolchain, the custom software DSP engine, and the non-obvious Media3/audio traps that cause silent, hard-to-debug bugs.
---

# Aurora & advanced Android audio development

Aurora (`com.aurora.music`) is a native Android (Jetpack Compose) music client for self-hosted
**Navidrome/Subsonic + Jellyfin**, plus **Spotify** (DIY OAuth) and an on-device **Local** library.
MVVM + Navigation Compose + **Media3/ExoPlayer**; **manual DI** (no Hilt/Dagger). It has an
audiophile bent: a switchable custom software DSP engine, convolution, ReplayGain, bit-perfect
output, Android Auto, widgets, and casting.

This skill exists because Android audio is full of traps that compile fine and then misbehave at
runtime — a float pipeline that silently drops your EQ, a browser connection refused for a missing
command flag, a Gson field that NPEs because the JSON key was absent. The whole job is producing code
that is **idiomatic to this codebase and correct on the device**, not just plausible.

## How to work here (read this first)

1. **The codebase is server-agnostic by design — keep it that way.** `MediaBackend` (interface in
   `data/`) returns the app's own domain models (`model/Models.kt`). To add a feature that touches the
   server, add it to `MediaBackend` **and both/all backends**, then expose it through
   **`MusicRepository`** (the facade every ViewModel calls). **Do not** branch on `ServerType` in
   UI/ViewModel/playback code — the only sanctioned exceptions are cosmetic labels (e.g. the settings
   server badge). If you find yourself writing `when (session.type)` outside a backend, stop and move
   it behind the abstraction. See `references/architecture.md`.

2. **`AppContainer` is the composition root.** Constructed once by `AuroraApplication`, reachable via
   `(app as AuroraApplication).container`. It owns the `SettingsStore`, active `MediaBackend`,
   `MusicRepository`, `DownloadManager`, DSP/effects controllers, connectivity/offline state, haptics,
   and integration clients. ViewModels read it via `AndroidViewModel`; Composables via
   `(LocalContext.current.applicationContext as AuroraApplication).container`.

3. **Persisted Kotlin data classes have a Gson landmine.** Gson injects `null` into a **non-null**
   Kotlin field when the JSON key is missing (it bypasses Kotlin default values), which already caused
   an NPE crash. Any field added later to a persisted class (`DownloadedSong`, Jellyfin/Spotify DTOs,
   anything serialized into DataStore JSON) **must be declared nullable** (`val x: String? = ""`) and
   null-coalesced at use. This is the single most common avoidable crash here.

4. **Verify by compiling — and prefer the fast path.** `./gradlew :app:compileDebugKotlin` is the
   quick check (`:app:processDebugResources` validates manifest/resources). Don't claim a change works
   without at least compiling; for behavioural changes, build + install + drive on the device (the
   sibling **`run-aurora`** skill is the harness). Build env is non-standard — see
   `references/build-and-gotchas.md` (JDK 21, the Gradle wrapper, the broken system `gradle`).

5. **Match the surrounding code.** Settings screens reuse the components in
   `ui/screens/settings/SettingsComponents.kt` (`SettingsGroup`, `SettingsSwitchRow`,
   `SettingsSliderRow`, `SettingsTopBar`, `SegmentedRow`). Prefs flow through `SettingsStore`
   (DataStore) as a typed `data class` + `Flow` + setter + a `Keys` entry. Don't invent parallel
   patterns; copy the nearest one.

## The Media3 spine

Playback lives in `playback/PlaybackService` — a Media3 **`MediaLibraryService`** (not just
`MediaSessionService`, because the browsable tree powers Android Auto / lock screen / future Wear).
`viewmodel/PlayerViewModel` drives it through a `MediaController`. A few load-bearing facts that are
easy to get wrong:

- **Browser connections need the library commands.** `MediaLibrarySession.Callback.onConnect` must
  build its allowed commands from `MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS`,
  **not** `DEFAULT_SESSION_COMMANDS`. The latter strips `getLibraryRoot`/`getChildren` so a
  `MediaBrowser` (Android Auto) is refused (`onConnectionFailed` → "App isn't working"), while the
  in-app `MediaController` still works — so the bug hides until you test Auto.
- **Shuffle is a physical queue reorder owned by the service**, not ExoPlayer's native shuffle. The
  notification's shuffle/repeat buttons are custom `CommandButton`s backed by custom `SessionCommand`s
  (`CMD_SHUFFLE`/`CMD_REPEAT`); `PlayerViewModel` sends the same commands. Enabling snapshots the
  original order and shuffles; disabling restores it.
- **Custom service actions** (`ACTION_PLAY_PAUSE/NEXT/PREV/ALARM/ALARM_DISMISS`) arrive via
  `onStartCommand` and operate `player` directly — that's how the widget, QS tile and alarm drive
  playback without a controller.
- **Float output bypasses every app `AudioProcessor`.** This is the big one — see DSP below.

Full detail (browse tree, content-style hints, voice search, Cast player swap, custom commands):
`references/media3-and-playback.md`.

## The audio / DSP engine

The signal chain matters and the failure modes are subtle. The canonical gotcha, worth memorizing:

> **In Media3, enabling 32-bit float output (`setEnableFloatOutput(true)`) runs the pipeline WITHOUT
> any app audio processors.** Sonic (speed/pitch), mono downmix, silence-skip **and Aurora's custom
> DSP are all bypassed.** So bit-perfect hi-res and Custom DSP are mutually exclusive: Aurora keeps
> float OFF whenever Custom DSP is the active engine. If you "lose" your EQ on hi-res tracks, this is
> why — process float internally if you must, but transport at 16-bit so the processors engage.

The custom DSP (`playback/AuroraDspProcessor.kt`, a `BaseAudioProcessor`; coefficients in
`DspCoeffBuilder.kt`) does float math on a 16-bit stereo stream: RBJ biquads (peaking + low/high
shelf), graphic layouts (10/15/31-band), parametric bands, preamp/balance/width/crossfeed,
saturation, per-channel delay+trim, compressor, limiter. There's also partitioned overlap-save FFT
**convolution** (`ConvolutionProcessor.kt` + `Fft.kt`) for impulse responses, **ReplayGain**
(attenuate-only, via player volume in the audio tick), **AutoEQ** (bundled DB + live profile fetch,
per-output auto-switch), and a **bit-perfect** path (Android 14 `setPreferredMixerAttributes`).

Coefficient math, the AudioProcessor lifecycle, headroom/`eqPeakDb`, ReplayGain, bit-perfect and
signal-path reporting: `references/dsp-and-audio.md`.

## Compose & UI

Standard MVVM-Compose. `AuroraApp.kt` is the nav host + overlay orchestration (player, queue, sheets).
Theming is a runtime system (`UiPrefs`: theme mode + accent presets/custom/Material You) propagated
via `AuroraTheme`. **Lock to portrait.** Embedding Android Views in Compose is risky here — e.g.
`MediaRouteButton`/`MediaRouteChooserDialog` do **not** work (invisible render / AppCompat-theme
crash) because the host is a Compose `ComponentActivity` on a Material theme; drive `MediaRouter`
directly from a native Compose sheet instead. Pattern detail in `references/media3-and-playback.md`.

## Reference map

Load the file that matches the task — don't read all of them up front:

- `references/architecture.md` — module layout, the MediaBackend/MusicRepository/AppContainer
  contract, how to add a backend feature, downloads/likes/offline, key directories.
- `references/media3-and-playback.md` — MediaLibraryService browse tree, custom session
  commands, audio sink / float output, Cast (and the Compose Cast pitfalls), Android Auto + DHU,
  widgets/tile/alarm plumbing.
- `references/dsp-and-audio.md` — the DSP chain, RBJ biquad coefficients, AudioProcessor lifecycle,
  ReplayGain, convolution, AutoEQ, bit-perfect, Bluetooth codec awareness.
- `references/build-and-gotchas.md` — build/run toolchain, device quirks, and the consolidated
  catalogue of non-obvious traps (with the *why* for each).

## Definition of good output here

Server-agnostic (behind `MediaBackend`/`MusicRepository`), compiles under JDK 21 via `./gradlew`,
reuses the existing Compose/settings/store patterns, declares persisted fields nullable, and respects
the Media3 + float-DSP rules above. When in doubt about a runtime audio behaviour, reason from the
signal chain (decoder → processors → sink → device) rather than guessing.
