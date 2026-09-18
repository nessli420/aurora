# Mix studio and crossfade

Tap **Mix** on an album, playlist or artist to start a mix in the normal player. Queue selection, seeking, next/previous, repeat and lock-screen controls remain available. Cached analysis supplies the transitions; tracks without analysis use smooth fades. Open **Mix studio** from the player menu, queue or library to edit them.

## Editing a mix

- Add tracks with **Blend after last** or **Layer from start**. A mix supports up to 10,000 songs and eight simultaneous tracks.
- Select a track to edit its start time, source cue points, fades, level, pan, EQ, tempo or pitch. Tap a number for precise entry.
- Use mute, solo, undo/redo and transition preview while editing. **All tracks** jumps to a song in a long collection.
- **Auto mix** analyzes tracks and rebuilds transitions. Tempo and beat alignment are estimates; every transition remains editable.
- Save with the save button; tap the title to rename. Mixes belong to the current account. Closing Studio keeps playback running. **Stop** restores the ordinary queue; playing a library song also returns to regular playback.

Overlap headroom reduces levels when songs play together. Disabling it can increase combined peaks. Cast and exclusive USB output do not support Studio playback.

## Track analysis

Analysis works with streamed and downloaded tracks. It reads audio without saving a library download. Mix Studio and Sonic Discovery share the results, including waveforms, estimated tempo and sound fingerprints.

Use **Settings → Sonic discovery → Analyze library** to scan the library. The scan supports cancellation and keeps completed results for the next run. Failed tracks can be retried. Tempo estimates may be half or double the musical tempo; manual mixing remains available when analysis fails.

## Vocals and backing

Select **Vocals only**, **Backing only** or **Original** for a track. The first separation downloads the 64 MB model. Processing runs on the device, supports cancellation and does not upload songs. Separation can take several minutes and leave audible artifacts.

Mono and stereo tracks up to 30 minutes are supported. Derived stems stay in the app's private cache and do not appear as library downloads. Saved mixes retain the selected stem. If Android clears its cache, prepare the stem again.

See [vocal separation credits](vocal-separation-attribution.md) for the model source and attribution.

## Automatic crossfade

Ordinary queue playback supports fades from 0 to 30 seconds, three curves and overlap headroom. Short tracks limit the overlap to half their duration. Fades pause with playback; manually seeking cancels the outgoing tail. If the next track cannot be prepared, ordinary playback remains available.

Exclusive USB output supports crossfade for compatible local FLAC pairs.
