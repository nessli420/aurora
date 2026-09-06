#!/usr/bin/env bash
# Aurora driver — build, install, launch and drive the Android app on a connected device via adb.
# Runs under bash (git-bash / the Bash tool) on this Windows machine. gradlew + adb are cross-platform.
#
# Usage:  bash .claude/skills/run-aurora/driver.sh {all|build|install|launch|shot [file]|session|logcat|tap X Y|stop}
# Env overrides: JAVA_HOME, ADB, SERIAL.
set -uo pipefail

# --- machine-specific defaults (this repo's documented toolchain) ---
JAVA_HOME_DEFAULT="C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
ADB_DEFAULT="C:/Users/mw/Documents/projects/apps/reset/.android-sdk/platform-tools/adb.exe"
PKG="com.aurora.music"
APK="app/build/outputs/apk/release/app-release.apk"

export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
ADB="${ADB:-$ADB_DEFAULT}"

# Project root = three levels up from this script (.claude/skills/run-aurora/).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$ROOT"

# First connected device unless SERIAL is set.
pick_serial() {
  [ -n "${SERIAL:-}" ] && { echo "$SERIAL"; return; }
  "$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}'
}
S="$(pick_serial)"
[ -z "$S" ] && { echo "No device connected (adb devices is empty). Plug in / authorize the phone."; exit 1; }
adbx() { "$ADB" -s "$S" "$@"; }

build()   { echo ">> assembleRelease"; ./gradlew :app:assembleRelease; }
install() { echo ">> install -r on $S";  adbx install -r "$APK"; }
launch()  { echo ">> launch $PKG";       adbx shell am start -n "$PKG/.MainActivity" >/dev/null; }
shot()    { local f="${1:-aurora-shot.png}"; adbx exec-out screencap -p > "$f"; echo "screenshot: $f"; }
session() { echo ">> media session"; adbx shell dumpsys media_session | grep -i "$PKG" || echo "(no session — start playback first)"; }
logcat()  { adbx logcat -d | grep -iE "$PKG|AndroidRuntime|FATAL|media3" | grep -viE "block_engine|Thermal" | tail -40; }
tap()     { adbx shell input tap "$1" "$2"; }
stop()    { adbx shell am force-stop "$PKG"; echo "force-stopped $PKG"; }

case "${1:-all}" in
  build)   build ;;
  install) install ;;
  launch)  launch ;;
  shot)    shot "${2:-}" ;;
  session) session ;;
  logcat)  logcat ;;
  tap)     tap "$2" "$3" ;;
  stop)    stop ;;
  all)     build && install && launch && sleep 4 && shot ;;
  *) echo "usage: driver.sh {all|build|install|launch|shot [file]|session|logcat|tap X Y|stop}"; exit 1 ;;
esac
