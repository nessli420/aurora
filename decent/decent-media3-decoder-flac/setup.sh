#!/bin/bash
# Setup script for decent-media3-decoder-flac
# Downloads the xiph/flac source code required to build libflacJNI.so

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBFLAC_DIR="$SCRIPT_DIR/src/main/jni/libflac"

if [ -d "$LIBFLAC_DIR" ]; then
    echo "libflac already exists at $LIBFLAC_DIR"
    echo "To re-download, delete the directory and run this script again."
    exit 0
fi

echo "Cloning xiph/flac (depth=1)..."
git clone https://github.com/xiph/flac.git --depth=1 "$LIBFLAC_DIR"

if [ $? -eq 0 ]; then
    echo "Done! libflac is ready at $LIBFLAC_DIR"
    echo "You can now build with: ./gradlew :decent-media3-decoder-flac:assembleDebug"
else
    echo "ERROR: Failed to clone xiph/flac"
    exit 1
fi
