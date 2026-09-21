# Contributing to Aurora

Bug reports, fixes, feature ideas and clearer documentation are welcome. You do not need to write code to help.

## Report a problem or suggest a feature

Search [existing issues](https://github.com/nessli420/aurora/issues) first, then [choose an issue form](https://github.com/nessli420/aurora/issues/new/choose). If someone has reported the same problem, add your details there.

- **Bug reports:** include steps to reproduce, the release tag or build date, your device and Android version. For playback problems, add the music source, output device and relevant settings. Signal Path details can help.
- **Feature requests:** explain what you want to do, what gets in the way and how the change would help.

Remove passwords, access tokens and private server addresses from logs and screenshots. Keep reports focused and discussions respectful.

## Set up a build

Follow [Build from source](README.md#build-from-source). It covers JDK 21, the Android SDK, native build tools and the font setup needed for a fresh checkout.

Use the included Gradle wrapper. On Windows, replace `./gradlew` with `.\gradlew.bat`.

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds use a different signing key from published releases. Use an emulator or a separate test device to keep your installed release and its data intact.

For releases, set `versionName` in `version.properties` to the GitHub tag without its `V` prefix, and increase `versionCode` for every release. Build with `./gradlew :app:assembleRelease -PreleaseTag=V2.5.0` (using the intended tag); a mismatched tag fails the build. Upload the signed APK as `Aurora.apk` on that release. About and the updater read the installed build version; the updater checks GitHub's latest stable release.

## Make a change

1. Fork the repository and create a branch for your change.
2. Keep the change focused. For a large feature or redesign, open an issue first to discuss the approach.
3. Follow the surrounding code and test the behavior you changed.
4. Open a pull request explaining the problem, the resulting behavior and how you checked it. Link the issue if there is one.

Small fixes do not need an issue first. Screenshots help with visual changes; device and output details help with audio changes. Say what you could not test.

## Code guidelines

- Keep server-specific behavior behind `MediaBackend` and `MusicRepository`. Screens and view models should work across supported sources.
- Reuse existing Compose components, settings and theme styles.
- Keep interface text short and clear. Add code comments only when useful; keep them short, lowercase and free of emojis.
- Preserve saved settings and file compatibility. Add migration coverage when changing persisted data.
- For audio changes, check the actual playback route. A working Android output does not prove USB or network playback works.
- Keep credentials, signing keys, personal files, build output and internal notes out of pull requests. Keep local font and signing changes local.
- Preserve third-party license notices when adding or updating dependencies.

## Check your work

For code changes, build the app and run the unit tests and lint:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Add a regression test for a reproducible logic bug when practical. For interface changes, check the affected screen on a device or emulator. For playback changes, check playback, pause, seeking and track changes on the affected route.

Device tests live in `app/src/androidTest/` and currently use the release build. They require your own release signing setup and a matching test installation. Some tests require specific hardware or play test signals; inspect the selected test before running it with headphones connected.

Documentation-only changes need a check of the wording, links and formatting; they do not need an APK build.

## Project layout

App code lives under `app/src/main/java/com/aurora/music/`:

- `data/`: sources, repositories, settings and storage.
- `playback/`: playback service, audio processing and output routes.
- `ui/` and `viewmodel/`: screens, components and screen state.
- `navigation/`: app routes.

Unit tests are in `app/src/test/`. USB libraries are in `decent/`. For extension development, see the [extension SDK guide](docs/extensions.md).
