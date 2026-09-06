# Aurora architecture

## Module / directory layout (`app/src/main/java/com/aurora/music/`)

- `data/` — backends, repository, stores, download manager, connectivity. The composition root lives
  here too.
- `data/remote/` — Retrofit APIs, HTTP clients, DTOs (`SubsonicClient`, `JellyfinClient`,
  `SpotifyClient`).
- `playback/` — `PlaybackService` (Media3), the DSP processors, convolution/FFT, YouTube resolver
  (for Spotify-via-YT), alarm scheduler/receiver/activity, Cast options, now-playing snapshot.
- `viewmodel/` — `PlayerViewModel` and screen ViewModels (all `AndroidViewModel`, read `AppContainer`).
- `ui/screens/{auth,home,search,library,detail,player,profile,settings,stats}` — Compose screens.
- `ui/widget/` — Glance home-screen widget, Quick Settings `TileService`, refresh controller.
- `ui/theme/` — runtime theming (`AuroraTheme`, color presets).
- `navigation/Destinations.kt` — routes (`Routes` object + `topLevelDestinations`).
- `model/Models.kt` — domain models: `Song`, `Album`, `Artist`, `Playlist`.

## The core contract: server-agnostic via three types

```
ViewModel/Composable ──calls──> MusicRepository ──delegates──> active MediaBackend ──> remote client
                                      │                              (per ServerType)
                                      └── offline ──> DownloadManager (local files)
```

- **`MediaBackend`** (interface, `data/`): the abstraction. Returns Aurora's own domain models, owns
  all DTO mapping, auth and URL building. Implementations:
  - `SubsonicBackend` over `remote/SubsonicClient` — token auth (salt + md5, password never stored).
  - `JellyfinBackend` over `remote/JellyfinClient` — `AuthenticateByName` → access token + userId;
    `X-Emby-Authorization` header on API calls, `api_key` query param on stream/image URLs.
  - `SpotifyBackend` over `remote/SpotifyClient` — official OAuth 2.0 PKCE, **DIY client id**
    (the user supplies their own; redirect `aurora://spotify`). Tracks resolve to YouTube audio via
    `aurora-yt://<id>?q=...&dur=...` sentinel URIs, resolved just-in-time in the playback data source.
    (The internal web-player `/v1` API is blocked — don't try to revive it.)
  - `LocalBackend` over `LocalLibrary`/`LocalStore` — MediaStore scan; no server, no sign-in.
- **`MusicRepository`** — the server-agnostic facade ViewModels call. Online → delegates to the active
  backend; offline → serves from `DownloadManager`. Backends apply a `localize(Song)` hook so a
  downloaded track transparently plays from local files even while online.
- **`AppContainer.buildBackend(session)`** picks the impl from `Session.type`
  (`ServerType.SUBSONIC/JELLYFIN/SPOTIFY/LOCAL`). **This is the only place that branches on type.**

### Adding a feature that needs the server
1. Add the method to the `MediaBackend` interface (returning domain models).
2. Implement it in **every** backend (Subsonic, Jellyfin, Spotify, Local — even if a no-op/empty).
3. Surface it through `MusicRepository` (handle the offline branch if relevant).
4. Call it from the ViewModel/screen. Never `when (session.type)` outside a backend.

## AppContainer (composition root)

Constructed once by `AuroraApplication`; reachable via `(app as AuroraApplication).container`. Owns:
`settingsStore`, `backend` (rebuilt when the session or Spotify client id changes), `repository`,
`downloadManager`, `audioEffects` (system AudioEffect chain), `autoEq`/`autoEqController`, `lastfm`,
`discord`, `youtubeResolver`, `playHistory`, `localLibrary`/`localStore`, haptics (`haptic()`),
`preferredAudioDeviceId`, `signalPath` (live bit-perfect status), and the offline/connectivity state
machine. `isLocal` hides server-only UI. There is **no DI framework** — pass dependencies explicitly
through the container.

## SettingsStore (DataStore)

`data/SettingsStore.kt`. Each pref group is a typed `data class` (e.g. `PlaybackPrefs`, `AudioPrefs`,
`UiPrefs`, `GesturePrefs`, `AlarmPrefs`) exposed as a `Flow`, with a `Keys` object entry and a
`suspend set…()`. Flows consumed by long-lived controllers (e.g. the AutoEQ controller) use
`.distinctUntilChanged()` so they don't re-fire on unrelated writes (a real bug was the AutoEQ
controller re-applying on every settings write and wiping a just-applied correction). Complex values
(pins, EQ bindings) are JSON via Gson — remember the nullable-field rule.

## Downloads, likes, offline

- **Downloads** (`DownloadManager`): one `index.json` (audio + cover to `filesDir/downloads`), each
  entry tagged with `serverId` (source server base URL). "Downloaded" views are scoped to the active
  server while online, but **all servers' downloads are merged in offline mode**.
- **Likes** are server stars/favorites (`getStarred2`/`star`/`unstar` for Subsonic; `FavoriteItems`
  for Jellyfin; follow/unfollow for Spotify), re-pulled on app resume. Playlists aren't
  server-starrable, so playlist likes persist locally in `SettingsStore` and merge into `likedIds`.
- **Offline** = manual toggle OR no validated connectivity (uses `NET_CAPABILITY_VALIDATED`); Local
  mode is never "offline".

## Conventions worth copying
- ViewModels are `AndroidViewModel`, hold a `MutableStateFlow<UiState>`, sync from the controller.
- New routes go in `navigation/Destinations.kt` (`Routes` + a `composable(...)` in `AuroraApp.kt`).
- Settings sub-pages are their own file under `ui/screens/settings/` reached from the settings hub.
- Keep `PlaybackService` the single owner of the ExoPlayer; the VM never touches the player directly,
  only the `MediaController`.
