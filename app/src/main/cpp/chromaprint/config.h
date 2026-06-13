// Hand-written replacement for Chromaprint's CMake-generated config.h, fixed for the Aurora NDK
// build: KissFFT for the transform, the bundled internal av_resample (no FFmpeg), and the standard
// C99 math functions the NDK provides.
#ifndef CHROMAPRINT_CONFIG_H_
#define CHROMAPRINT_CONFIG_H_

#define HAVE_ROUND 1
#define HAVE_LRINTF 1

#define USE_KISSFFT 1

#endif
