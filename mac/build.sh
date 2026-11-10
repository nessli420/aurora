#!/bin/sh
# builds, runs or packages the macOS desktop app without an android sdk
# usage: mac/build.sh [run|dmg|test|app]
set -e
cd "$(dirname "$0")/.."

is21() { [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21[."]'; }

# java_home falls back to any jdk when 21 is missing, so every candidate is checked
if ! is21 "$JAVA_HOME"; then
    JAVA_HOME=""
    for candidate in "$(/usr/libexec/java_home -v 21 2>/dev/null)" \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
        if is21 "$candidate"; then JAVA_HOME=$candidate; break; fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    echo "Aurora needs JDK 21. Install it with: brew install --cask temurin@21" >&2
    exit 1
fi
export JAVA_HOME

case "${1:-run}" in
    run) task=":desktop:run" ;;
    dmg) task=":desktop:packageDmg" ;;
    app) task=":desktop:createDistributable" ;;
    test) task=":core:test :desktop:test" ;;
    *) echo "usage: mac/build.sh [run|dmg|test|app]" >&2; exit 2 ;;
esac

# shellcheck disable=SC2086
exec sh ./gradlew -Paurora.desktopOnly=true $task
