<img src="../docs/assets/aurora.svg" alt="Aurora logo" width="72" height="72">

# Aurora for macOS (experimental)

This folder holds the macOS port of the Aurora desktop app. It is the same Compose desktop app that runs on Windows and Linux, with the same library, servers, equalizers, effects rack and presets, plus the pieces it needs to work properly on a Mac.

It runs on **macOS 12 Monterey or newer**, on **Apple silicon and Intel** Macs. There is no prebuilt download yet, so you build it yourself (about five minutes the first time).

## Quick start

1. Install JDK 21. With [Homebrew](https://brew.sh):

   ```bash
   brew install --cask temurin@21
   ```

2. From the repository root, start Aurora:

   ```bash
   mac/build.sh run
   ```

No Android SDK, Xcode or C++ toolchain is needed. The script finds JDK 21 by itself and builds the desktop app only.

## Build commands

| Command | What it does |
| --- | --- |
| `mac/build.sh run` | Builds and launches Aurora |
| `mac/build.sh app` | Builds `Aurora.app` in `desktop/build/compose/binaries/main/app/` |
| `mac/build.sh dmg` | Builds a disk image in `desktop/build/compose/binaries/main/dmg/` |
| `mac/build.sh test` | Runs the shared and desktop unit tests |

The app is built for the kind of Mac you build on: build on Apple silicon for Apple silicon, on Intel for Intel. Java is bundled inside the app, so the person you give it to doesn't need to install anything.

The app and DMG are **not signed or notarized**. The first time you open them, right-click Aurora and choose **Open**, or clear the quarantine flag:

```bash
xattr -dr com.apple.quarantine /Applications/Aurora.app
```

## What works on a Mac

- **Playback.** Aurora decodes with FFmpeg, runs everything through its own DSP chain (EQ, rack, convolution, ReplayGain, crossfade and gapless), and plays through Core Audio in shared mode. Pick any output device under **Settings → Audio output**.
- **Your music.** Local folders, Navidrome/Subsonic, Jellyfin and Plex.
- **Web sign-ins.** YouTube Music, Spotify, SoundCloud and Discord sign in inside Aurora, as on Windows and Linux. The sign-in browser is an embedded Chromium that Aurora downloads (about 300 MB) the first time you need it.
- **Passwords in the Keychain.** Server passwords and sign-ins are encrypted with a key that Aurora keeps in your login Keychain, under *com.aurora.music*. macOS may ask you once to allow Aurora to use it.
- **Mac shortcuts.** ⌘ takes the place of Ctrl:

  | Action | Keys |
  | --- | --- |
  | Play / pause | Space |
  | Next / previous | ⌘ → / ⌘ ← |
  | Seek | ⇧ → / ⇧ ← |
  | Volume up / down | ⌘ ↑ / ⌘ ↓ |
  | Mute | ⌘ ⇧ ↓ |
  | Shuffle / repeat | ⌘ S / ⌘ R |
  | Like | ⌘ L or ⌥ ⇧ B |
  | Queue | ⌥ ⇧ Q |
  | Search | ⌘ F or ⌘ K |
  | Settings | ⌘ , |
  | Full screen | ⌃ ⌘ F |
  | Back | ⌘ [ or Esc |
  | Shortcut list | ⌘ / |

- **The usual Mac behaviour.** ⌘Q and **Aurora → Quit** save your place and quit cleanly, **Aurora → Settings…** opens settings, and clicking the Dock icon brings the window back after it was hidden.
- **Media keys and Now Playing.** The play/pause, next and previous keys, AirPods and headphone buttons, and the Now Playing widget in Control Center and the menu bar control Aurora. The widget shows the title, artist and cover, and you can scrub, shuffle and repeat from it.
- **Close to the menu bar.** The red close button keeps Aurora playing in the menu bar and takes it out of the Dock. Click the menu bar icon for play/pause, next, previous, **Show** and **Close**; showing the window brings Aurora back to the Dock. ⌘Q still quits. Turn it off under **Settings → Close to menu bar** to make the close button quit instead.
- **Stays awake while playing.** Aurora stops the Mac from idle-sleeping while music plays (the display can still turn off), and lets go when playback stops.
- **Accent colour.** With the system accent theme, Aurora follows the accent colour in **System Settings → Appearance**.
- **AI DJ voice.** Without the downloaded voice, the AI DJ speaks with the built-in macOS voice.
- **Your data.** Settings, library, downloads and cache live in `~/Library/Application Support/Aurora`.

## Not on the Mac yet

- Exclusive (hog mode / bit-perfect) output and hardware volume control
- Places (home and work) for listening stats, which need location
- Everything that is also missing on Windows and Linux: see the [main README](../README.md#windows-desktop)

## How the port is put together

The Mac port reuses the desktop module in [`desktop/`](../desktop). The Mac-only code is here and is compiled into the desktop app:

| Path | Purpose |
| --- | --- |
| [`build.sh`](build.sh) | Finds JDK 21 and runs the desktop-only Gradle build |
| [`packaging/aurora.icns`](packaging/aurora.icns) | The app icon |
| [`KeychainStore.kt`](src/main/kotlin/com/aurora/music/desktop/mac/KeychainStore.kt) | Stores the settings encryption key in the login Keychain (Security framework through JNA) |
| [`MacSleepInhibitor.kt`](src/main/kotlin/com/aurora/music/desktop/mac/MacSleepInhibitor.kt) | Keeps the Mac awake during playback with `caffeinate` |
| [`MacAccent.kt`](src/main/kotlin/com/aurora/music/desktop/mac/MacAccent.kt) | Reads the system accent colour |
| [`MacNowPlaying.kt`](src/main/kotlin/com/aurora/music/desktop/mac/MacNowPlaying.kt) | Media keys and the Now Playing widget, through MPNowPlayingInfoCenter and MPRemoteCommandCenter |
| [`MacDock.kt`](src/main/kotlin/com/aurora/music/desktop/mac/MacDock.kt) | Takes Aurora out of the Dock while it lives in the menu bar |
| [`ObjC.kt`](src/main/kotlin/com/aurora/music/desktop/mac/ObjC.kt) | A small bridge to the Objective-C runtime (messages, classes, main-thread calls) used by the above |
| [`CefBrowserSwing.java`](src/main/java/org/cef/browser/CefBrowserSwing.java) | Draws the sign-in browser's pages with Java2D (see below) |

Small hooks in the shared desktop code switch these on when Aurora runs on macOS: `HostPlatform.isMac`, the Application Support paths in `DesktopPaths`, the ⌘ shortcuts in `Shortcuts.kt`, the app-menu handlers in `Main.kt`, the `say` voice in `DesktopDjVoice`, and the DMG settings in `desktop/build.gradle.kts`.

**Why the sign-in browser has its own class.** JCEF's off-screen browser draws through a JOGL OpenGL canvas, and JOGL 2.4 closes AppKit windows from Java's UI thread. Current macOS kills any app that does that, so the first sign-in page took Aurora down. Chromium already hands over every frame as pixels, so `CefBrowserSwing` copies them into an image that Swing draws at Retina resolution, and forwards mouse, wheel, keyboard and focus to Chromium the way JCEF's browser does. It lives in JCEF's package because JCEF's browser base class is package-private, and it's written in Java because Kotlin won't extend a package-private class. `MacBrowserSmokeTest` checks that a page paints and takes typing:

```bash
AURORA_CHROMIUM_SMOKE=~/Library/Application\ Support/Aurora/Data/chromium mac/build.sh test
```

`-Paurora.desktopOnly=true`, which `build.sh` passes, leaves the Android modules out of the Gradle build so no Android SDK is needed. To build by hand:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew -Paurora.desktopOnly=true :desktop:run
```

## Troubleshooting

- **"Aurora needs JDK 21".** Install it as in the quick start. Newer JDKs such as 25 can't run this Gradle version.
- **"Aurora is damaged and can't be opened".** That is the quarantine flag on an unsigned app; run the `xattr` command above.
- **Aurora quits when you choose "Continue with Apple" or a passkey.** Passkey sign-ins reach for Bluetooth, and macOS closes any app that does so without declaring it. The app's Info.plist declares Bluetooth (and camera) access for this; if you built Aurora before that was added, rebuild it.
- **No sound.** Check **Settings → Audio output** and pick your device instead of the default. Logs are in `~/Library/Application Support/Aurora/Data/logs`.
- **Packaging fails with "Homebrew's JDK distribution may cause issues".** Compose refuses to package with Homebrew's `openjdk@21`. Use Temurin (`brew install --cask temurin@21`); `build.sh` picks it up first.
