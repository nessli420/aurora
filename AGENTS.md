# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

Aurora (`com.aurora.music`) is a native Android (Jetpack Compose) music client for self-hosted **Navidrome/Subsonic** and **Jellyfin** servers.

## Build & run

This machine's toolchain is non-standard — these specifics matter:

- **Always build with the Gradle wrapper** (`./gradlew`, pinned 8.11.1, AGP 8.7.3). The system/scoop `gradle` is broken under JDK 21 (its Kotlin DSL fails settings evaluation) — do not use it.
- **JDK 21 is required.** The default `java` on PATH is JDK 8 (too old). Pass JDK 21 explicitly:
  ```bash
  JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot" ./gradlew :app:assembleRelease
  ```
- **Android SDK** lives at `C:\Users\mw\Documents\projects\apps\reset\.android-sdk` (not the default AppData path; `local.properties` points `sdk.dir` there). `ANDROID_HOME` is unset. `adb`/`emulator` are under that path's `platform-tools`/`emulator`.
- Fast compile check: `:app:compileDebugKotlin`. Debug APK: `:app:assembleDebug` → `app/build/outputs/apk/debug/`. Signed release: `:app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (keystore at `keystore/aurora-release.jks`).
- There is **no test suite** and no lint gate wired up.
- compileSdk/targetSdk 35, minSdk 26.

Install: `adb -s <serial> install -r <apk>`. A debug-signed build can't overwrite a release-signed one (and vice-versa) — `adb uninstall com.aurora.music` first when switching.

### Emulator testing against the real servers
The emulator can't reach the Tailscale Navidrome IP or the host's Jellyfin directly. Use port-forwarding:
- Navidrome: run `python tools/proxy.py` (host TCP proxy `127.0.0.1:4533` → the Tailscale server) + `adb reverse tcp:4533 tcp:4533`, then sign in with `http://127.0.0.1:4533`.
- Jellyfin (host `localhost:8096`): `adb reverse tcp:8096 tcp:8096`, sign in with `http://127.0.0.1:8096`.
- UI automation tip: the player's animated waveform blocks `uiautomator dump` ("could not get idle state" → returns a stale dump). **Pause playback first** (`adb shell input keyevent 127`), then dump and parse `bounds=` for exact tap coordinates — visual estimates from screenshots are unreliable.

## Architecture

MVVM + Jetpack Compose + Navigation Compose + Media3. Manual DI; no DI framework.

**`AppContainer`** (constructed once by `AuroraApplication`, reachable via `(app as AuroraApplication).container`) is the composition root. It owns the `SettingsStore`, the active `MediaBackend`, `DownloadManager`, `MusicRepository`, `LyricsRepository`, `AudioEffectsController`, `PlayHistoryStore`, and connectivity/offline state. ViewModels read it through `AndroidViewModel`.

**Server abstraction is the core design.** `MediaBackend` (interface in `data/`) returns the app's own domain models (`model/Models.kt`: `Song`/`Album`/`Artist`/`Playlist`, plus `HomeData`/`SearchResults`/`DetailData` from `MusicRepository.kt`). Two implementations own all DTO mapping, auth, and URL building:
- `SubsonicBackend` over `remote/SubsonicClient` (token auth: salt+md5, password never stored).
- `JellyfinBackend` over `remote/JellyfinClient` (`AuthenticateByName` → access token + userId; `X-Emby-Authorization` header on API calls, `api_key` query param on stream/image URLs).

`AppContainer.buildBackend(session)` picks the impl from `Session.type` (`ServerType.SUBSONIC`/`JELLYFIN`). **The rest of the app — every ViewModel, screen, and the playback layer — is server-agnostic.** To add a feature, add it to `MediaBackend` and both backends; do not branch on server type in UI/VM code (the only deliberate exceptions are cosmetic labels like the settings server badge).

**`MusicRepository`** is the server-agnostic facade the ViewModels call. Online → delegates to the active backend. Offline → serves from `DownloadManager`. Backends apply a `localize(Song)` hook so a downloaded track transparently plays from local files even while online.

**Playback** runs in `playback/PlaybackService` (a Media3 `MediaSessionService` hosting `ExoPlayer`); `viewmodel/PlayerViewModel` drives it through a `MediaController`. Notes:
- Audio prefs (skip-silence, crossfade via a 2nd overlapping player, mono downmix, ReplayGain) are observed live from `SettingsStore` and applied to the engine. A custom `buildAudioSink` enables float output for hi-res, with `setEnableAudioTrackPlaybackParams` so varispeed still works in that mode.
- **Shuffle is a physical queue reorder owned by the service**, not ExoPlayer's native shuffle. The notification's shuffle/repeat buttons are custom `CommandButton`s backed by custom `SessionCommand`s (`CMD_SHUFFLE`/`CMD_REPEAT`); `PlayerViewModel` sends the same commands. Enabling snapshots the original order and shuffles; disabling restores it. Shuffle-from-start (`shufflePlay`) shuffles the whole list (random first track) and passes the original order to the service for later restore.
- `onTaskRemoved` stops playback so music doesn't keep going after a swipe-away.

**Downloads** (`DownloadManager`) write one `index.json` (audio + cover to `filesDir/downloads`), each entry tagged with `serverId` (the source server's base URL). `MusicRepository` scopes the "Downloaded" views to the active server while online, but **merges all servers' downloads in offline mode**. App is locked to **portrait**.

**Likes** are server stars/favorites (`getStarred2`/`star`-`unstar` for Subsonic; `FavoriteItems` for Jellyfin), re-pulled on app resume. Playlists aren't server-starrable, so playlist likes persist locally in `SettingsStore` and are merged into `likedIds`. The virtual "Liked Songs" library row uses kind `"liked"`.

**Gson + Kotlin trap:** Gson injects `null` into a non-null Kotlin field when the JSON key is missing (it bypasses Kotlin default values). This already caused an NPE crash. Any field added later to a persisted data class (`DownloadedSong`, `DownloadedCollection`, Jellyfin DTOs) must be declared nullable (`String? = ""`) and null-coalesced at use.

## Key directories
`data/` (backends, repository, stores, download manager) · `data/remote/` (Retrofit APIs, clients, DTOs) · `playback/` · `viewmodel/` · `ui/screens/{auth,home,search,library,detail,player,profile,settings,stats}` · `navigation/Destinations.kt` (routes).
