---
name: run-aurora
description: Build, install, launch, screenshot and drive the Aurora Android music app (com.aurora.music) on a connected device. Use when asked to run, build, install, launch, screenshot, or test Aurora / the Android app on the phone.
---

# Run Aurora (Android)

Aurora is a native Android / Jetpack-Compose music client (`com.aurora.music`). It is **not** a
headless app — it builds with the Gradle wrapper (JDK 21) and runs on a **physically connected
Android device** reachable over `adb`. There is no emulator path for the full app here (the local
AVD is `google_apis` with no Play Store). You drive it with
**[`.Codex/skills/run-aurora/driver.sh`](driver.sh)**: build → install → launch → screenshot, plus
`session` / `logcat` / `tap` / `stop` helpers.

All paths below are relative to the app root (`…/projects/android_ui`). The driver runs under **bash**
(the Bash tool / git-bash) — `gradlew` and `adb` are cross-platform; do not run it from PowerShell
(PowerShell's `>` corrupts the screenshot PNG).

## Prerequisites

- **JDK 21** at `C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot` (the default `java` is JDK 8
  and too old). The driver exports `JAVA_HOME` for you.
- **Android SDK** at `C:\Users\mw\Documents\projects\apps\reset\.android-sdk` (`local.properties`
  already points `sdk.dir` there). `adb` =
  `…\.android-sdk\platform-tools\adb.exe` — the driver uses this path.
- A **device connected + authorized**. Confirm:
  ```bash
  "C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe" devices
  # 0016615BM001167  device
  ```
  If the list is empty, bounce the daemon (see Gotchas).

## Run (agent path) — the driver

Full cycle (build release APK → install → launch → screenshot):

```bash
bash .Codex/skills/run-aurora/driver.sh all
# >> assembleRelease … BUILD SUCCESSFUL
# >> install -r … Success
# >> launch com.aurora.music
# screenshot: aurora-shot.png
```

Individual commands (faster when you don't need a rebuild):

```bash
bash .Codex/skills/run-aurora/driver.sh launch                       # bring app to front
bash .Codex/skills/run-aurora/driver.sh shot aurora-shot.png         # capture screen to a PNG
bash .Codex/skills/run-aurora/driver.sh session                      # show the MediaLibrarySession
bash .Codex/skills/run-aurora/driver.sh logcat                       # recent app/media3/crash logs
bash .Codex/skills/run-aurora/driver.sh tap 540 1200                 # tap x y (device pixels)
bash .Codex/skills/run-aurora/driver.sh stop                         # force-stop the app
```

Then **Read the PNG** to see the UI. A healthy `session` prints
`androidx.media3.session.id. com.aurora.music …` — that confirms the playback service + browse tree
(used by Android Auto / lock screen) are alive.

Env overrides: `SERIAL=<id>` (default: first connected device), `ADB=<path>`, `JAVA_HOME=<path>`.

## Build / install without the driver (the exact commands the driver runs)

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
./gradlew :app:assembleRelease                 # → app/build/outputs/apk/release/app-release.apk
ADB="C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe"
"$ADB" -s 0016615BM001167 install -r app/build/outputs/apk/release/app-release.apk
"$ADB" -s 0016615BM001167 shell am start -n com.aurora.music/.MainActivity
```

Fast compile-only check (no APK): `./gradlew :app:compileDebugKotlin` (and
`:app:processDebugResources` to validate resources/manifest).

## Run (human path)

Open the project in Android Studio and Run, or sideload the release APK above. Useless without a
device/screen attached. There is no test suite and no lint gate wired up.

## Gotchas (things that actually bit, this session)

- **`adb devices` suddenly empty / "No device connected".** The daemon drops the device after heavy
  use (e.g. the Android-Auto DHU + port-forward sessions). Fix:
  ```bash
  ADB="C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe"
  "$ADB" kill-server; "$ADB" start-server; sleep 2; "$ADB" devices
  ```
- **`Warning: Activity not started, its current task has been brought to the front`** on `launch` is
  normal (app already running) — not an error.
- **The app is release-signed.** `install -r` works across rebuilds. But a *debug*-signed build can't
  overwrite a release one (and vice-versa) — `adb uninstall com.aurora.music` first when switching
  signing.
- **Single USB-C port.** Plugging a USB DAC into the test phone disconnects `adb`.
- **Don't pipe binary through PowerShell.** `screencap -p > file.png` only stays intact under bash;
  PowerShell's `>` mangles it. The driver uses `adb exec-out screencap -p > …` from bash.
- **`uiautomator dump` returns a stale/blocked dump** while the player's animated waveform is on
  screen. Pause playback first (`adb -s <serial> shell input keyevent 127`), then dump for exact tap
  bounds — visual estimates from screenshots are unreliable.
- **Android Auto** can't run on the local AVD (no Play Store). Testing it needs DHU against the
  physical phone; the full procedure (install DHU, start head-unit server, `adb forward tcp:5277`,
  clean-connect recipe, cached-failure-verdict quirk) is documented in `PHASE3_HANDOFF.md` at the app
  root.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No device connected` from the driver | `adb kill-server && adb start-server`, re-check `adb devices` |
| `gradlew` fails with a Kotlin DSL / settings error | You're on the system `gradle` or wrong JDK. Use `./gradlew` + JDK 21 (the driver sets both). |
| Install fails with signature mismatch | `adb -s <serial> uninstall com.aurora.music`, then install again |
| `session` prints "(no session)" | Start playback in the app once; the MediaLibrarySession is created lazily |
