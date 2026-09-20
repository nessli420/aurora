# Aurora extension SDK

Extensions are separate Android apps. They can supply sound presets, a read-only music library, or suggested tags. Aurora calls them through Android Messenger; their code does not run inside Aurora's audio callback.

This document describes **API 1**, the first SDK contract. The SDK is built from this repository; it is not published as a Maven dependency. An incompatible contract change requires a new API version.

## Build the example

Use the toolchain in the [build instructions](../README.md#build-from-source): JDK 21, Android SDK 35 and the Gradle wrapper. Extensions support Android 8.0 and newer.

```bash
./gradlew :extension-sdk:assembleRelease :extension-example:assembleDebug
```

On Windows, use `gradlew.bat`. The outputs are:

- `extension-sdk/build/outputs/aar/extension-sdk-release.aar`
- `examples/extension/build/outputs/apk/debug/extension-example-debug.apk`

Install the example APK alongside Aurora. Open **Settings → Extensions**, refresh, and enable **Aurora extension example**. It provides a gain preset, a short test tone and a tag suggestion.

The [example app](../examples/extension) and [SDK](../extension-sdk) use the repository's [Apache 2.0 license](../LICENSE). Use your own package name and state your extension's license.

## Declare a service

Extend [`AuroraExtensionService`](../extension-sdk/src/main/java/com/aurora/extension/AuroraExtensionService.kt). Implement `AuroraExtension.describe()` and whichever interfaces your app needs:

| Interface | Method | Result |
| --- | --- | --- |
| `AudioPlugin` | `audio(settings)` | Array of sound-processing stages. |
| `MediaProviderPlugin` | `media(offset, count, settings)` | A page of tracks and the total track count. |
| `MetadataPlugin` | `metadata(query, settings)` | Array of suggested tags. |

Register the service in your manifest. Declare only the capabilities you implement.

```xml
<service android:name=".MyExtension" android:exported="true">
    <intent-filter>
        <action android:name="com.aurora.extension.SERVICE" />
    </intent-filter>
    <meta-data android:name="aurora.extension.api" android:value="1" />
    <meta-data android:name="aurora.extension.capabilities" android:value="audio,media,metadata" />
    <meta-data android:name="aurora.extension.license" android:value="Apache-2.0" />
</service>
```

`describe()` returns an object with `api`, `name`, `version`, `license`, `capabilities` and optional `settings`. Its capabilities must match the manifest.

```json
{
  "api": 1,
  "name": "My extension",
  "version": "1.0",
  "license": "Apache-2.0",
  "capabilities": ["audio"],
  "settings": [
    { "id": "gainDb", "label": "Gain", "minimum": -24, "maximum": 0, "default": -6 }
  ]
}
```

Settings are finite numbers only. IDs start with a letter, use letters, digits or underscores, and are at most 40 characters. Bounds must stay within −1,000,000 to 1,000,000. Aurora supplies saved values in the `settings` object; validate them again in your extension. API 1 has no password or token fields. Keep any account setup and credentials in your own app.

## Sound presets

Return a recipe using the stages below. Aurora validates it and creates an ordinary processing rack with automatic headroom enabled. The user reviews the replacement before loading it and can then edit or save it.

| `type` | Required fields | Range |
| --- | --- | --- |
| `gain` | `gainDb` | −60 to +12 dB |
| `stereo` | `width`, `balance` | Width 0–2; balance −1 to +1 |
| `crossfeed` | `amount` | 0–1 |
| `equalizer` | `bands` | 1–64 bands per stage |

Each equalizer band has exactly these fields:

```json
{ "frequency": 120, "gainDb": 2, "q": 0.7, "shape": "lowShelf" }
```

`frequency` is 10–24,000 Hz, `gainDb` is −30 to +30 dB, and `q` is 0.01–100. Shapes are `peak`, `lowShelf` and `highShelf`. A recipe supports 1–16 stages and the rack's total limit of 256 equalizer bands. Extra fields and unknown stage types are rejected.

Example `audio()` result:

```json
[
  { "type": "gain", "gainDb": -6 },
  { "type": "crossfeed", "amount": 0.15 }
]
```

API 1 does not load arbitrary PCM processors or native audio code. Processing uses Aurora's built-in stages. Once loaded, the rack is independent of the extension; playback and saved presets continue to work if it is disabled or removed. Changing an extension setting does not change an already loaded rack. Load its preset again to apply the change.

## Music libraries

Return a stable, ordered page for the requested `offset` and `count`:

```json
{
  "total": 1,
  "songs": [
    {
      "id": "track-1",
      "title": "Track title",
      "artist": "Artist",
      "album": "Album",
      "durationSec": 180,
      "uri": "content://com.example.myextension.audio/track-1.flac",
      "suffix": "flac",
      "sampleRate": 48000,
      "bitDepth": 24
    }
  ]
}
```

Keep IDs stable and unique across pages. Use 1–160 characters from `A–Z`, `a–z`, `0–9`, `.`, `_`, `:` and `-`. Titles, artists and albums are required strings of at most 200 characters. Duration is an integer from 0 to 86,400 seconds. `sampleRate` and `bitDepth` are optional; omit them when unknown, or use 0. Their maximums are 768,000 Hz and 64 bits. A suffix is at most 10 characters.

`total` must remain unchanged during a load. Return a nonempty page until that total is reached. Aurora rejects duplicate IDs, missing pages and extra tracks. Libraries are refreshed no more than once per minute during normal browsing; selecting **Use this library** requests a fresh load.

Audio must come from a `content://` provider owned by the extension's package and UID. URLs from other apps, HTTP addresses and file paths are not accepted. Expose read-only, seekable files with known, bounded lengths. Check the caller in the provider itself; Aurora's URI validation does not replace your provider's access control. See [`ExampleAudioProvider`](../examples/extension/src/main/java/com/aurora/extension/example/ExampleAudioProvider.kt).

Aurora downloads the original bytes without transcoding. Downloads require a known length of at most 512 MiB. Local downloaded copies can play without the extension. Without a local copy, opening a track requires the extension to remain installed and enabled.

The library supports browsing, search and playback. It does not expose playlist editing, favorites, tag writing or scrobbling through the SDK. A cached catalog remains readable when the extension is missing or disabled. This does not guarantee access to the audio files. API 1 does not add extension libraries to the merged-library feature.

## Metadata suggestions

Aurora calls enabled metadata extensions when the user requests a tag lookup. The query contains `title`, `artist` and `album`, each limited to 200 characters. Return up to 20 suggestions:

```json
[
  { "title": "Track title", "artist": "Artist", "album": "Album", "year": "2026", "trackNumber": "1", "score": 90 }
]
```

`title`, `artist`, `album` and integer `score` are required. Score ranges from 0 to 100. `year` and `trackNumber` are optional strings of at most 10 characters. Aurora labels the source and shows the suggestions for review. The extension cannot write tags or fetch the user's server credentials through this interface. Artwork URLs and arbitrary metadata fields are not part of API 1.

## Access and failure handling

- Installing an extension does not enable it. The user enables its declared capabilities in **Settings → Extensions**.
- Aurora records the app's signing certificate digest and capabilities. A changed signer or capability list requires enabling it again. Restoring an Aurora backup leaves all extension access disabled.
- Aurora checks the responding UID, request ID, API version, message size and response fields. The SDK service runs calls on its own worker thread in the extension app's process.
- The SDK's default caller check accepts UIDs containing the `com.aurora.music` package. This is a basic package check, not a certificate check. Override `acceptsCaller()` and check certificates if your extension requires a specific Aurora distribution. Apply the same policy to your content provider.
- Aurora does not pass its server passwords or service tokens. An extension can still use permissions granted to its own Android app, including its own network access.
- Disabling access blocks new requests and new file opens through Aurora. It does not revoke a file descriptor that is already open; an existing stream or download may finish. Loaded sound presets and downloaded files remain usable.
- Return promptly and handle your own failures. A timeout stops Aurora from waiting; it does not forcibly terminate work already running in the extension.

## Limits and wire format

| Item | API 1 limit |
| --- | --- |
| Discovered extensions / saved grants | 32 each |
| Numeric settings | 16 per extension |
| JSON request or response | 196,608 UTF-8 bytes |
| Active IPC calls | 2 |
| IPC timeout | 5 seconds per call; 6-second outer request budget |
| Library load | 20 seconds, 10,000 tracks, 100 tracks per page |
| Cached catalog | 8 MiB per extension |
| Metadata providers per lookup | 4 |
| Metadata suggestions | 20 per provider |
| Download | At most 512 MiB; checked 60-second copy deadline |

The SDK handles Messenger framing. For another implementation, send `Message.what = 1`, place the request ID in `arg1`, set `replyTo`, and put the JSON string in `Bundle["json"]`. Requests contain `api: 1`, `method` and, except for the initial description, `settings`. Media requests add `offset` and `count`; metadata requests add `query`.

Replies use `what = 2`, echo `arg1`, and contain `{"api":1,"data":...}` or `{"api":1,"error":"..."}` in the same bundle field. Aurora rejects a reply from a different UID. Errors are reported without exposing the extension's raw exception text.

The checked copy deadline is not a forced interruption of a blocked provider read. Serve local, bounded files and avoid network work inside `openFile()` or blocking file reads.

The authoritative definitions are [`ExtensionContract`](../extension-sdk/src/main/java/com/aurora/extension/ExtensionContract.kt), the [service wrapper](../extension-sdk/src/main/java/com/aurora/extension/AuroraExtensionService.kt), and the host's [validation rules](../app/src/main/java/com/aurora/music/extensions/ExtensionModels.kt). Keep your extension tested against API 1 and the current example before distributing it.
