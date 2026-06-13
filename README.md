# Aurora

A native Android music client for self-hosted libraries (**Navidrome/Subsonic** and **Jellyfin**) that also connects to **Green Music App** and plays **local files** on the device. Built with Jetpack Compose on Media3/ExoPlayer. Includes a switchable software DSP engine, ReplayGain, and an experimental USB DAC driver for bit-perfect output.

One app treats a Navidrome server, a Jellyfin server, a Green Music App account, and on-device files as a single library, with no forced resampling on the path to a USB DAC.

> **Status:** actively developed and used daily. Not on the Play Store, no test suite, and some pieces (notably the USB bit-perfect driver) are experimental. Caveats are noted where they apply.

## Table of contents

- [What it does](#what-it-does)
- [Bit-perfect USB output (experimental)](#bit-perfect-usb-output-experimental)
- [Building it yourself](#building-it-yourself)
- [Configuration: bring your own keys](#configuration-bring-your-own-keys)
- [How it's put together](#how-its-put-together)
- [Project layout](#project-layout)
- [Troubleshooting](#troubleshooting)
- [Third-party code & credits](#third-party-code--credits)
- [License](#license)

## What it does

### Connect to anything
The app sits behind one `MediaBackend` interface, so every screen behaves identically regardless of where the music lives:

- **Navidrome / Subsonic / OpenSubsonic**: token auth (salt + md5; the password is never stored).
- **Jellyfin**: `AuthenticateByName`, with support for editing track metadata back on the server.
- **Green Music App**: your own app via OAuth 2.0 PKCE (supply the client ID, no secret needed). Tracks resolve to playable audio through NewPipeExtractor.
- **Local**: scans on-device audio through MediaStore, no sign-in.

Multiple logins can be saved and switched between, including a jump to the local library, from one place in Settings. The same quick-pick appears on the sign-in screen. Switching servers stops the previous one's playback and restores that account's own queue.

### Playback
- Runs in a Media3 `MediaLibraryService`, exposing a browsable tree to Android Auto, the lock screen, and similar surfaces.
- Gapless, crossfade (a second overlapping player), skip-silence, mono downmix, and varispeed.
- ReplayGain (off / track / album), plus an **offline EBU R128 loudness scanner** (pure-Kotlin, via MediaCodec) to populate gains for files whose tags lack them.
- A switchable **software DSP engine**: 10/15/31-band graphic EQ, parametric bands, preamp, balance, stereo width, crossfeed, compressor, brick-wall limiter, harmonic saturation, per-channel delay/trim, and partitioned overlap-save **convolution** for impulse responses.
- **AutoEQ** headphone correction: a bundled profile database plus live fetch, with per-output-device auto-switching.
- **Per-account queue persistence**: swipe the app away or switch servers and it returns to the same state (current track, position, shuffle/repeat mode, and the full queue).

### Library & metadata
- Folder / file-tree browsing across local and server backends.
- **Smart playlists**: a rule engine over the library (title, artist, album, format, duration, bitrate, play count, last-played, liked, downloaded) with AND/OR matching, sort, and limit; re-evaluated lazily on open.
- **Duplicate finder**: fuzzy match on normalized artist/title with duration clustering, so live/extended cuts aren't lumped together.
- **M3U / M3U8 import & export** across all backends.
- **In-app tag editor**: local files via JAudiotagger (with the Android 11+ MediaStore write-consent flow), and Jellyfin items via the server's metadata API. Auto-fills from a **MusicBrainz** match (with **Cover Art Archive** art) or identifies a track by acoustic fingerprint via **AcoustID** (Chromaprint, native).

### Scrobbling, stats & backup
- **Last.fm** and **ListenBrainz** scrobbling (now-playing + scrobble).
- A local, "Wrapped"-style **listening dashboard** computed across every backend at once: top artists/tracks/albums, a 24-hour listening clock, and daily streaks.
- **Discord Rich Presence**.
- **Backup & restore** of settings, on-device playlists, likes, and listening history to a single JSON file (downloads are excluded, since they're re-downloadable).

### Platform integration
- Home-screen **widget** (Glance) and a **Quick Settings tile**, plus a monochrome themed launcher icon.
- **Chromecast**: convenience mode only. Cast hands the stream URL to the receiver, so the DSP / bit-perfect path doesn't travel. The UI notes this.
- **Sleep timer** (with end-of-track and fade-out) and a **wake-to-music alarm**.
- Runtime theming: light/dark/AMOLED/system, accent presets / custom / Material You, and per-surface tweaks.

## Bit-perfect USB output (experimental)

Android resamples USB audio to 48 kHz before it reaches the DAC. Aurora has an experimental path that bypasses the entire Android audio stack (AudioFlinger, AudioTrack, AAudio, ALSA, and the kernel `snd-usb-audio` driver) and writes PCM straight to the DAC over Linux `usbdevfs` isochronous transfers. No root, Android 10+.

It's built on **[decent-player](https://github.com/Ma145/decent-player)** by Marcelo Silva (MIT). Its USB Audio Class 2.0 driver and Media3 `AudioSink` wrapper are vendored under `decent/`. Two decoding paths feed it:

- **Local FLAC**: a native libFLAC engine decodes to integer PCM and sign-extends to the DAC's bit depth, with no float math in the chain.
- **Everything else** (streamed FLAC, lossy, non-FLAC): decoded to float32 by FFmpeg and converted to the DAC's bit depth with an exact `x·2^N` round-trip (lossless for 16/24-bit).

Either way the DSP chain is bypassed (bit-perfect and EQ are mutually exclusive by definition), the Android mixer is muted, and nothing touches the samples on the way out.

**Using it:** Settings → Playback & quality → "USB DAC bit-perfect (experimental)", plug in a DAC, restart playback. With no DAC connected it falls back to normal output, so it's safe to leave on.

**Tested:** verified bit-perfect on a **FiiO KA13** at 44.1 kHz streamed and 96 kHz/24-bit local, clock locked to the source rate with no resampling. Other DACs are unverified. Playback that's too fast or distorted usually points to the driver's clock or format auto-detection for that hardware. The KA13 already needed a fix to the UAC2 clock control-interface addressing (the driver hardcoded interface 0; the KA13 uses 1); that fix lives in `decent/decent-usb-audio-driver`. Reports for other DACs are welcome.

## Building it yourself

The toolchain is slightly non-standard, so a few specifics matter.

**Requirements**
- **JDK 21** (the default `java` on PATH may be older; point Gradle at 21 explicitly).
- **Android SDK** with `compileSdk`/`targetSdk` 35, `minSdk` 26.
- **NDK 27** and **CMake 3.22+** for the native code (AcoustID/Chromaprint fingerprinting and the bit-perfect FLAC/USB driver):
  ```bash
  sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
  ```

**Setup**
1. Create `local.properties` pointing at the SDK:
   ```properties
   sdk.dir=/absolute/path/to/Android/sdk
   ```
2. **Display font (required to compile).** Aurora's UI uses Circular Std, a commercial font that can't be redistributed, so it's not in the repo. Add your own licensed `.otf` files to `app/src/main/res/font/` with these exact names:
   ```
   circular_light.otf        circular_light_italic.otf
   circular_book.otf         circular_book_italic.otf
   circular_medium.otf       circular_medium_italic.otf
   circular_bold.otf         circular_bold_italic.otf
   circular_black.otf        circular_black_italic.otf
   ```
   Without a license, change `ui/theme/Type.kt` to an OSS face (Inter, Manrope, etc.) or the system default. The `FontFamily` there is the only place the font is referenced.
3. For a release build, provide a signing keystore and wire it into `app/build.gradle.kts`. The keystore and its passwords are gitignored; debug builds need nothing extra.

**Build**
```bash
# always use the wrapper, not a system 'gradle'
JAVA_HOME="/path/to/jdk-21" ./gradlew :app:assembleDebug      # debug APK
JAVA_HOME="/path/to/jdk-21" ./gradlew :app:assembleRelease    # signed release
```
A fresh clone builds without a submodule step. The vendored native sources (libFLAC, Chromaprint + KissFFT, and the decent-player driver) are included.

## Configuration: bring your own keys

Nothing requiring a personal API credential is shipped hardcoded. Enter everything under **Settings → Integrations** (or the sign-in screen for Green Music App). It's stored locally on the device.

| Integration | What you need | Where to get it |
|---|---|---|
| **Green Music App** | App **client ID** (PKCE, no secret) | [developer.spotify.com](https://developer.spotify.com/dashboard), set redirect `aurora://spotify` |
| **Last.fm** | **API key + shared secret** | [last.fm/api/account/create](https://www.last.fm/api/account/create) |
| **ListenBrainz** | **User token** | [listenbrainz.org/profile](https://listenbrainz.org/profile) |
| **AcoustID** (tag identify) | **Application API key** | [acoustid.org/new-application](https://acoustid.org/new-application) |
| **Discord** presence | **Application ID** (plus optional Imgur client ID for album art) | [discord.com/developers](https://discord.com/developers/applications) |

Skip any of them and that integration is disabled; the rest of the app is unaffected.

## How it's put together

MVVM + Compose + Navigation + Media3, with **manual dependency injection** (no Hilt/Dagger).

- **`AppContainer`** is the composition root, constructed once by `AuroraApplication` and reachable via `(app as AuroraApplication).container`. It owns the settings store, the active backend, the download manager, the DSP/effects controllers, the play-history and queue stores, and the integration clients. ViewModels read it through `AndroidViewModel`.
- **`MediaBackend`** is the server abstraction. It returns the app's own domain models, and each implementation (one per server type, plus the streaming-service and local-file backends) owns its DTO mapping, auth, and URL building. A server-touching feature is added to the interface and every backend, then exposed through the repository; UI/playback code never branches on server type.
- **`MusicRepository`** is the server-agnostic facade every ViewModel calls. Online it delegates to the active backend; offline it serves from downloads.
- **Playback** lives entirely in **`PlaybackService`** (a Media3 `MediaLibraryService` hosting ExoPlayer). The UI drives it through a `MediaController`; the service is the single owner of the player. Shuffle is a physical queue reorder owned by the service (custom session commands), not ExoPlayer's native shuffle.
- **Settings** are typed `data class`es backed by DataStore, each with a `Flow` and a setter; complex values serialize to JSON via Gson.

Two recurring gotchas the code works around:
- **Float output bypasses every app `AudioProcessor`** in Media3, so 32-bit float hi-res and the custom DSP are mutually exclusive. The service decides once at startup which one wins.
- **Gson injects `null` into non-null Kotlin fields** when a JSON key is missing (it bypasses default values), so every field added to a persisted data class is declared nullable and null-coalesced at use.

## Project layout

```
.
├── app/                         The Android app
│   └── src/main/
│       ├── java/com/aurora/music/
│       │   ├── data/            backends, MusicRepository, stores, DSP, download manager
│       │   ├── data/remote/     Retrofit clients + DTOs (Subsonic, Jellyfin, Green Music App,
│       │   │                    MusicBrainz, AcoustID, ListenBrainz, Last.fm)
│       │   ├── playback/        PlaybackService, DSP processors, convolution/FFT, alarm, cast
│       │   ├── viewmodel/        AndroidViewModels
│       │   ├── ui/screens/       Compose screens (home, search, library, detail, player,
│       │   │                    settings, stats, auth)
│       │   └── ui/widget/        Glance widget + Quick Settings tile
│       ├── cpp/chromaprint/     vendored Chromaprint + KissFFT (AcoustID fingerprinting)
│       └── res/                 resources
├── decent/                      vendored decent-player modules (USB bit-perfect driver)
│   ├── decent-usb-audio-driver/         native UAC2 driver + JNI (+ libFLAC under jni/)
│   ├── decent-usb-audio-wrapper-media3/ Media3 AudioSink wrapper
│   └── decent-media3-decoder-flac/      native FLAC decoder + parser
├── gradle/                      version catalog + wrapper
└── settings.gradle.kts          includes :app + the two decent modules
```

## Troubleshooting

- **`gradle` fails but `./gradlew` works:** use the wrapper. A system/scoop Gradle under JDK 21 can choke on the Kotlin DSL settings evaluation.
- **NDK / CMake errors:** confirm NDK `27.0.12077973` and CMake `3.22.1` are installed (`sdkmanager`), and that you're on JDK 21.
- **`sdk.dir` not found:** create `local.properties` (it's gitignored, so it won't be in a fresh clone).
- **Bit-perfect plays too fast / distorted on a DAC:** the driver's auto-detection likely picked the wrong format for that hardware; see the bit-perfect section above. Logs are tagged `UsbAudioDevice` / `UsbAudioOutput` / `NativeAudioEngine`.
- **Debugging with a USB DAC plugged in:** a single USB-C port means the DAC knocks out USB ADB, so use wireless ADB (`adb tcpip 5555 && adb connect <phone-ip>:5555`) and logcat survives over Wi-Fi.

## Third-party code & credits

Aurora uses a number of other projects. The notable ones:

- **[decent-player](https://github.com/Ma145/decent-player)** (MIT): the USB Audio Class 2.0 bit-perfect driver and its Media3 wrapper. Vendored under `decent/`, realigned to this app's toolchain, with a control-interface fix for DACs that don't put AudioControl on interface 0. See [`decent/NOTICE.md`](decent/NOTICE.md).
- **[xiph/flac](https://github.com/xiph/flac)** (BSD): libFLAC, used by both the bit-perfect native engine and the AcoustID parser.
- **[Chromaprint](https://github.com/acoustid/chromaprint)** (with bundled KissFFT): acoustic fingerprinting for AcoustID, vendored under `app/src/main/cpp/`.
- **[jellyfin media3-ffmpeg-decoder](https://github.com/jellyfin/jellyfin-androidx-media)**: FFmpeg float decoder for the bit-perfect streaming path.
- **[JAudiotagger (Adonai fork)](https://github.com/Adonai/jaudiotagger)**: reading/writing audio file tags on Android.
- **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)**: resolving Green Music App tracks to playable audio.
- **[JSch (mwiede fork)](https://github.com/mwiede/jsch)**: SFTP streaming (via the decent-player wrapper).
- AndroidX **Media3/ExoPlayer**, **Jetpack Compose**, **Glance**, **DataStore**, **Palette**; **Retrofit** / **OkHttp** / **Gson**; **Coil**; **Lottie**; and the Google **Cast SDK**.
- Metadata from **[MusicBrainz](https://musicbrainz.org)**; cover art from the **[Cover Art Archive](https://coverartarchive.org)**.

Vendored `decent/` modules retain their MIT licensing; bundled xiph/flac and Chromaprint sources keep their original licenses (see the file headers). decent-player's AGPL proof-of-concept harness is not included, only its MIT libraries.

Aurora isn't affiliated with or endorsed by Navidrome, Jellyfin, Green Music App, Last.fm, ListenBrainz, Discord, or any DAC manufacturer; brand and device names are used descriptively, for interoperability.

## License

_TODO: choose a license for the app code before publishing._ The third-party components above keep their own licenses regardless of the choice. For open source, an MIT or GPL-3.0 `LICENSE` at the repo root is the usual choice.
