# YouTube Music

YouTube Music is a standalone source. It does not merge with Spotify, local files, Navidrome, Subsonic or Jellyfin. Switch sources in **Settings → Servers & accounts**.

## Sign in

1. Open **Settings → Servers & accounts → Add another account → YouTube Music**.
2. Tap **Sign in with Google** and complete Google's sign-in page, including any verification Google requests.
3. Wait until YouTube Music opens. If Google stays on the verification screen after approval, tap **Continue to YouTube Music**. Aurora also attempts one automatic recovery when signed-in session cookies are present. If you have multiple YouTube profiles, choose the desired profile in the website's account menu.
4. Tap **Connect this account**. Aurora checks the selected profile before saving the connection.

No Google Cloud project, OAuth client, API key or client secret is required. The integration uses the browser-session approach found in [Metrolist's login flow](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt). It uses the device's normal WebView user agent and reads session information only from the secure YouTube Music origin. Credentials are entered on Google's page.

Aurora encrypts the YouTube session using Android Keystore and keeps it outside device backups. Saved account settings contain only a reference to that encrypted file. Temporary Google and YouTube sign-in cookies are cleared when the sign-in window closes; other embedded sign-ins are retained. WebView data is excluded from Android cloud backups and device transfer.

## Home feed

Home shows the named recommendation sections returned for your account, including mixes, community playlists, albums and songs. More sections load as you scroll. The selection and order come from YouTube Music and can change between visits. Tap a playlist or mix to open it, or a song to play that section's songs.

Generated mixes open their current queue. Saved playlists retain their normal complete tracklist behavior. Changing EQ or other DSP settings does not refresh the source or reload the home feed.

## Playback and audio

Aurora resolves the selected YouTube video ID and decodes it in the normal native player. Custom DSP, EQ, convolution and the normal PCM processing path remain available. Bit-perfect modes still bypass processing, as with other sources.

YouTube streams are lossy. Aurora does not turn them into lossless or high-resolution originals. Uploaded, age-restricted, regional and account-restricted content may be unavailable to the playback resolver. Library access and playback have separate availability limits. Server ReplayGain metadata and YouTube listening-history updates are not supplied by this integration.

## Reconnecting

YouTube Music library access uses an unofficial interface and can change. If the session expires, reconnect through **Servers & accounts**. **Forget** deletes Aurora's encrypted session. Google may reject embedded sign-in on some devices or require additional verification; this flow cannot guarantee acceptance or bypass those checks. Keep Android System WebView up to date and complete any account checks Google presents.
