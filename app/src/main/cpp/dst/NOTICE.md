# DST decoder

`decoder.c` derives from Peter Ross's FFmpeg DST decoder through DSD-Nexus,
commit `b3f443d4c42ccf52806eb57def3b3a34805714bd`.

Source: https://github.com/wichers/dsd-nexus/tree/b3f443d4c42ccf52806eb57def3b3a34805714bd/libs/libdst

License: LGPL-2.1-or-later. See `COPYING.LGPL-2.1`.

Modified for Aurora on 2026-09-20. Changes replace the utility dependency with bounded input access, validate
output capacity and uncoded-frame lengths, propagate table/filter errors, and add
the JNI entry point. The files in this directory are distributed under the same
LGPL terms. Existing copyright notices remain in `decoder.c`.

The decoder is built as the separate `libaurora_dst.so` shared library by the
parent CMake file. Rebuild with Android NDK 27.0.12077973 and CMake 3.22.1 using
the repository's Gradle wrapper. All modified library source and build files are
included. The application may be rebuilt or repackaged with a modified library;
reverse engineering for debugging modifications to this library is permitted.

Support currently covers mono/stereo DSD64 frames with shared, unsegmented
filter/probability data. Other segmentation modes return an explicit unsupported
error. Files are never treated as uncompressed DSD after a decode failure.
