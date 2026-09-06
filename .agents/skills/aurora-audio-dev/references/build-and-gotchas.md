# Build, run & the trap catalogue

## Build / run toolchain (non-standard machine)
- **JDK 21 required** — default `java` is JDK 8 (too old). Pass it:
  `JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"`.
- **Always `./gradlew`** (wrapper, 8.11.1 / AGP 8.7.3). The scoop/system `gradle` is broken under
  JDK 21 (Kotlin DSL fails settings evaluation) — never use it.
- **Android SDK** at `C:\Users\mw\Documents\projects\apps\reset\.android-sdk` (`local.properties`
  points `sdk.dir` there; `ANDROID_HOME` unset). `adb` under that path's `platform-tools`.
- Fast compile check: `./gradlew :app:compileDebugKotlin` (+ `:app:processDebugResources` for
  manifest/resource validation). Debug APK: `:app:assembleDebug`. Signed release:
  `:app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (keystore
  `keystore/aurora-release.jks`). compileSdk/targetSdk 35, minSdk 26. **No test suite, no lint gate.**
- Driving the app on the device (build → install → launch → screenshot → logcat) is the sibling
  **`run-aurora`** skill — use it instead of re-deriving adb invocations.
- Run commands through **bash** (the Bash tool / git-bash), not PowerShell, when they redirect binary
  (screenshots) — PowerShell's `>` corrupts the PNG.

## The trap catalogue (each cost real debugging time)

**Gson injects `null` into non-null Kotlin fields.** When a JSON key is missing, Gson bypasses Kotlin
default values and writes `null` into a `val x: String` field → NPE. Every field added to a persisted
data class must be `val x: Type? = default` and null-coalesced at use. The highest-frequency avoidable
crash in this codebase.

**Float output silently bypasses all app AudioProcessors.** `setEnableFloatOutput(true)` runs the
pipeline without Sonic, mono, silence-skip, or the custom DSP. Bit-perfect hi-res and Custom DSP are
therefore mutually exclusive; Aurora keeps float off when Custom DSP is active. "EQ does nothing on
hi-res" → this.

**MediaLibrarySession.onConnect must use `DEFAULT_SESSION_AND_LIBRARY_COMMANDS`.** Plain
`DEFAULT_SESSION_COMMANDS` strips the browse commands → `MediaBrowser`/Android Auto connection refused
(`GH.MediaBCConnection onConnectionFailed`, "app isn't working"), while the in-app controller still
works, so it hides until you test Auto.

**`MediaRouteButton` / `MediaRouteChooserDialog` don't work in this Compose app.** The button renders
invisible inside an `AndroidView`; the dialog throws `IllegalStateException: You need to use a
Theme.AppCompat theme` because the host is a Compose `ComponentActivity` on a Material theme. A
`ContextThemeWrapper` doesn't fix it (it first throws `IllegalArgumentException: background can not be
translucent: #0` from `MediaRouterThemeHelper`). Drive `MediaRouter` directly from a native Compose
sheet instead.

**`queueEndOfStream` is final.** In a `BaseAudioProcessor`, override `onQueueEndOfStream()` to flush
tails, not `queueEndOfStream`.

**`MAX_PARAMETRIC` must fit real AutoEQ profiles.** They can carry ~10 filters including shelves; the
original 6 truncated corrections silently. Now 12, with shelf filter types added.

**Long-lived controllers re-fire on every settings write without `distinctUntilChanged`.** The AutoEQ
controller was re-applying (and clearing) on unrelated writes → corrections wiped / loops. DataStore
flows feeding such controllers need `.distinctUntilChanged()`, and the controller should only ever
*apply* bound state, never clear manual state.

**Release vs debug signing.** A debug-signed build can't overwrite a release-signed install (and
vice-versa) — `adb uninstall com.aurora.music` first when switching. The project is on release builds.

**Single USB-C port on the test phone.** Plugging a USB DAC disconnects adb — you can't drive the app
over adb and exercise the USB DAC at the same time.

**`adb devices` goes empty after heavy use** (e.g. DHU + port-forward sessions). `adb kill-server &&
adb start-server`, re-check. (Covered in the `run-aurora` skill.)

**Bit-perfect mixer isn't universal.** Many phones (incl. Nothing Phone 3a Pro) expose no
`MIXER_BEHAVIOR_BIT_PERFECT` — check `getSupportedMixerAttributes` and degrade gracefully; don't
assume Android 14 = bit-perfect.

**Third-party apps can't read the active Bluetooth codec name.** Detect that output *is* Bluetooth and
say "re-encoded by the system codec"; don't fabricate LDAC/aptX detection.

**App is locked to portrait.** Don't add landscape layouts expecting them to show.
