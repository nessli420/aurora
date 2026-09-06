# Media3 & playback

Media3 version 1.5.1. `playback/PlaybackService` is a `MediaLibraryService` hosting one `ExoPlayer`;
`viewmodel/PlayerViewModel` drives it via a `MediaController`. The service is the **single owner** of
the player.

## MediaSessionService vs MediaLibrarySession

Aurora uses **`MediaLibraryService`** + **`MediaLibrarySession`** (not the plain `MediaSessionService`)
so it exposes a browsable tree to `MediaBrowser` clients (Android Auto, Assistant, Wear, system).
`onGetSession` returns the `MediaLibrarySession`.

### onConnect — the command-set trap
For a `MediaLibrarySession`, browser clients need the **library** commands. Base the connection result
on `DEFAULT_SESSION_AND_LIBRARY_COMMANDS`:

```kotlin
val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
    .add(SessionCommand(CMD_SHUFFLE, Bundle.EMPTY))
    .add(SessionCommand(CMD_REPEAT, Bundle.EMPTY))
    .add(SessionCommand(CMD_SLEEP_FADE, Bundle.EMPTY))
    .build()
```

Using `DEFAULT_SESSION_COMMANDS` removes `COMMAND_GET_LIBRARY_ROOT`/`COMMAND_GET_CHILDREN`, so a
`MediaBrowser` connection is refused — symptom: Android Auto shows "<app> doesn't seem to be working",
logcat `GH.MediaBCConnection onConnectionFailed`, while the in-app player is unaffected.

### Browse tree
`onGetLibraryRoot` → root item (`MEDIA_TYPE_FOLDER_MIXED`, browsable). `onGetChildren(parentId)` builds
children by id prefix: `root → cat_liked/cat_playlists/cat_albums/cat_artists/cat_downloads`, then
`cat_* → collections`, then `alb_/pl_/art_<id> → tracks` via `repository.detail(...)`. Playable leaves
use media id `song_<id>` with a real URI. `onAddMediaItems`/`onGetItem` resolve browsed-then-played
items back to playable ones (a `browseCache` map + `repository.songFor(id)`). Suspend work is bridged
to `ListenableFuture` via a `SettableFuture` + `scope.launch` helper.

**Content-style hints** (Android Auto grid vs list) come from `androidx.media3.session.MediaConstants`
extras set on the parent's `MediaMetadata`: `EXTRAS_KEY_CONTENT_STYLE_BROWSABLE/_PLAYABLE` with
`EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM` (albums/artists/playlists) or `_LIST_ITEM` (songs). Note the
constants are `EXTRAS_…` (plural), not `EXTRA_…`.

**Voice/text search**: override `onSearch` (notify count via `session.notifySearchResultChanged`) and
`onGetSearchResult` (return cached items). Back both with `repository.search(query)`.

## Custom session commands & physical shuffle
The notification shuffle/repeat are custom `CommandButton`s bound to `SessionCommand`s, handled in
`onCustomCommand`. Shuffle is a **physical reorder**: on enable, snapshot the current order (by media
id), shuffle everything after the current track, set an identity `ShuffleOrder` so ExoPlayer follows
the physical order; on disable, restore the snapshot. "Shuffle from start" passes the original order in
the command extras so it can still be restored. The VM sends the same `CMD_SHUFFLE` (extras `target`:
1/0/-1) so the notification button and the UI share one path.

## Custom service actions (widget / tile / alarm)
`onStartCommand` handles plain intents that operate the player directly (no controller needed):
`ACTION_PLAY_PAUSE`, `ACTION_NEXT`, `ACTION_PREV`, `ACTION_ALARM` (wake-to-music: shuffle liked/
downloaded with a 30 s fade-in), `ACTION_ALARM_DISMISS`. Always `return super.onStartCommand(...)` so
Media3 still handles its own media-button intents. A `NowPlaying` snapshot
(`playback/NowPlaying.kt`, `NowPlayingBus` + a SharedPreferences `NowPlayingStore`) is published on
track/state change and consumed by the Glance widget + `TileService`.

## Audio sink & float output (the most consequential setting)
A custom `DefaultRenderersFactory.buildAudioSink` installs the app `AudioProcessor`s
(`monoProcessor`, `auroraDsp`, `convolver`) and toggles float output:

```kotlin
DefaultAudioSink.Builder(context)
    .setAudioProcessors(arrayOf(monoProcessor, auroraDsp, convolver))
    .setEnableFloatOutput(useFloat)                 // hi-res bit-perfect, but BYPASSES all the above
    .setEnableAudioTrackPlaybackParams(useFloat || enableAudioTrackPlaybackParams) // varispeed in float mode
    .build()
```

- **Float output bypasses every app `AudioProcessor`** (Sonic speed/pitch, mono, silence-skip AND the
  custom DSP). So Aurora computes `useFloat = preferHighRes && dspMode != CUSTOM` once at service
  start (the sink can't be cheaply rebuilt mid-session) — Custom DSP takes priority over bit-perfect.
- In float mode Sonic is gone, so speed falls back to hardware playback params (varispeed: pitch
  tracks speed; independent pitch isn't available there).
- The player is routed to `container.audioSessionId` so the system `AudioEffect` chain applies.

## ReplayGain & crossfade in the audio tick
A 100 ms coroutine tick (`tickAudio`) owns `player.volume`: ReplayGain multiplier (attenuate-only),
the real overlapping crossfade (a 2nd `fadePlayer`), the sleep fade-out, and the alarm fade-in. Order
matters — sleep/wake fades return early so they own the volume; otherwise RG sets it. If you add a new
volume effect, slot it into this tick rather than fighting it from elsewhere.

## Casting (Chromecast)
- `CastOptionsProvider` (default media receiver `CC1AD845`) + manifest `OPTIONS_PROVIDER_CLASS_NAME`
  meta-data. Service `setupCast()` creates a `CastPlayer`; a `SessionAvailabilityListener` swaps the
  session's player between the local ExoPlayer and the CastPlayer (`switchToPlayer`), transferring
  queue + position and guessing MIME from the URL.
- **Cast UI in Compose — avoid two dead-ends:** `MediaRouteButton` inside an `AndroidView` renders
  invisible/empty, and `MediaRouteChooserDialog` throws `IllegalStateException: You need to use a
  Theme.AppCompat theme` (the host is a Compose `ComponentActivity` on a Material, non-AppCompat
  theme — a `ContextThemeWrapper` does not fix it). Instead drive `androidx.mediarouter.media.
  MediaRouter` directly from a native Compose `ModalBottomSheet`: an active-scan callback over the
  cast `MediaRouteSelector`, list `router.routes.filter { it.matchesSelector(selector) }`,
  `route.select()` to cast, `router.unselect(UNSELECT_REASON_STOPPED)` to stop.
- **DSP does not travel** — Cast hands the receiver the raw stream URL it fetches itself, so it only
  plays if the receiver can reach the source (e.g. Navidrome on Tailscale needs the TV on the
  tailnet). Local (`content://`) and Spotify (`aurora-yt`) can't cast at all. Label it "DSP off,
  convenience mode".

## Android Auto testing
The local AVD is `google_apis` (no Play Store) → can't run Android Auto. Test via DHU against the
physical phone. The full procedure (DHU install, head-unit server, `adb forward tcp:5277`,
clean-connect recipe, and Auto's per-session cached-failure verdict) is in `PHASE3_HANDOFF.md` at the
repo root and the `run-aurora` skill's gotchas.
