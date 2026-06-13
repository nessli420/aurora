/**
 * @file native-audio-engine.h
 * @brief Native FLAC decode → USB audio engine interface.
 *
 * See native-audio-engine.cpp for full implementation.
 * The NativeAudioEngine struct is opaque to Kotlin — controlled via JNI.
 */

#ifndef NATIVE_AUDIO_ENGINE_H
#define NATIVE_AUDIO_ENGINE_H

#include <cstdint>
#include <atomic>

// Forward declarations
struct UsbAudioContext;
class FLACParser;
class FileDataSource;

#endif  // NATIVE_AUDIO_ENGINE_H
