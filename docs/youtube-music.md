# YouTube Music

Open **Settings → Servers & accounts → Add another account → YouTube Music**, sign in with Google, complete any verification, and tap **Connect this account**. No Google Cloud project or API key is required. Reconnect here if the saved session expires.

Aurora encrypts the saved browser session on your device and excludes it from backups. Your library, likes and playlists use the selected YouTube Music profile. Playback uses Aurora's native audio player, including its available DSP controls.

## Listening history

Enable **Listening history sync** in **Settings → Gestures & behaviour** to report YouTube Music playback to the connected Google account. Aurora sends actual playback intervals; pauses and seek jumps do not add listening time. Private sessions stop reporting, and offline plays are not uploaded later.

Google's [Pause watch history setting](https://support.google.com/youtubemusic/answer/6364666?hl=en) remains authoritative. If history is paused on your Google account, played tracks will not appear there. History settings are shared between YouTube and YouTube Music.

With merged libraries, history follows the source actually playing. If Aurora chooses a matching Plex, Jellyfin or Navidrome copy, it reports to that server instead of Google. Local files stay in Aurora's own history.

YouTube Music integration uses unofficial web interfaces, so availability and history reporting can change. Some uploaded, age-restricted, regional or account-restricted content may be unavailable. Streams are lossy, and server ReplayGain metadata is not supplied.
