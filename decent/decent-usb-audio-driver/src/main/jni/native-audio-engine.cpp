/**
 * @file native-audio-engine.cpp
 * @brief Native FLAC decode → USB audio engine.
 *
 * Bypasses the entire ExoPlayer audio pipeline for FLAC files:
 * FLACParser decodes → bit-depth conversion → submitPcmToUrbs.
 * Single native thread, zero JNI in the hot path.
 *
 * The engine is controlled from Kotlin via JNI (start/pause/seek/stop)
 * and reports position via an atomic counter.
 */

#include "native-audio-engine.h"
#include "usb-audio-output.h"

#include <flac_parser.h>
#include <data_source.h>

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <sys/stat.h>
#include <sys/mman.h>

#define TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── MmapDataSource: memory-mapped file for zero-syscall reads ───────

/** DataSource with dedicated I/O thread. Decode thread NEVER does disk I/O.
 *  8MB buffer with compaction. I/O thread is the ONLY thread touching the fd.
 *  When buffer misses, decode waits for I/O thread to fill (no direct pread64). */
class AsyncBufferedDataSource : public DataSource {
    int fd_;
    bool ownsFd_;
    off64_t fileLength_;

    uint8_t *buf_;
    static const size_t BUF_CAP = 8 * 1024 * 1024;
    static const size_t READ_CHUNK = 128 * 1024;

    // Protected by mutex (shared between I/O thread and decode thread)
    pthread_mutex_t mu_;
    pthread_cond_t cond_;   // signal decode thread when data available
    off64_t bufStart_;
    size_t bufFilled_;
    off64_t ioPos_;         // next read position for I/O thread
    bool seekPending_;
    off64_t seekTarget_;
    bool alive_;

    static void *ioLoop(void *arg) {
        auto *ds = static_cast<AsyncBufferedDataSource *>(arg);
        uint8_t *tempBuf = (uint8_t *)malloc(READ_CHUNK);

        while (true) {
            pthread_mutex_lock(&ds->mu_);
            if (!ds->alive_) { pthread_mutex_unlock(&ds->mu_); break; }

            if (ds->seekPending_) {
                ds->bufStart_ = ds->seekTarget_;
                ds->bufFilled_ = 0;
                ds->ioPos_ = ds->seekTarget_;
                ds->seekPending_ = false;
            }

            if (ds->bufFilled_ >= BUF_CAP) {
                pthread_mutex_unlock(&ds->mu_);
                usleep(5000);
                continue;
            }

            off64_t readPos = ds->ioPos_;
            size_t space = BUF_CAP - ds->bufFilled_;
            pthread_mutex_unlock(&ds->mu_);

            // Read into TEMP buffer (not main buffer — avoids race with compaction)
            size_t toRead = space < READ_CHUNK ? space : READ_CHUNK;
            ssize_t n = pread64(ds->fd_, tempBuf, toRead, readPos);

            if (n > 0) {
                pthread_mutex_lock(&ds->mu_);
                if (ds->ioPos_ == readPos && !ds->seekPending_) {
                    // Copy to CURRENT end of buffer (safe — mutex held)
                    size_t currentFilled = ds->bufFilled_;
                    if (currentFilled + n <= BUF_CAP) {
                        memcpy(ds->buf_ + currentFilled, tempBuf, n);
                        ds->bufFilled_ = currentFilled + n;
                        ds->ioPos_ += n;
                    }
                    pthread_cond_signal(&ds->cond_);
                }
                pthread_mutex_unlock(&ds->mu_);
            } else {
                usleep(5000);
            }
        }
        free(tempBuf);
        return nullptr;
    }

    pthread_t ioThread_;
public:
    AsyncBufferedDataSource(int fd, bool ownsFd) : fd_(fd), ownsFd_(ownsFd),
            fileLength_(0), buf_(nullptr), bufStart_(0), bufFilled_(0),
            ioPos_(0), seekPending_(false), seekTarget_(0), alive_(true) {
        struct stat st;
        if (fstat(fd, &st) == 0) fileLength_ = st.st_size;
        buf_ = (uint8_t *)malloc(BUF_CAP);
        pthread_mutex_init(&mu_, nullptr);
        pthread_cond_init(&cond_, nullptr);
        posix_fadvise(fd, 0, fileLength_, POSIX_FADV_SEQUENTIAL);
        // Only readahead first 2MB — enough for FLAC metadata + initial frames.
        // Reading the entire file monopolizes the FUSE daemon on SD cards,
        // blocking our pread64 calls (measured: >5s timeout for 128KB).
        off64_t raSize = fileLength_ < 2*1024*1024 ? fileLength_ : 2*1024*1024;
        readahead(fd, 0, raSize);
        pthread_create(&ioThread_, nullptr, ioLoop, this);
        LOGI("AsyncBufferedDataSource: fd=%d size=%lld (8MB buf, readahead issued)",
             fd, (long long)fileLength_);
    }

    ~AsyncBufferedDataSource() override {
        pthread_mutex_lock(&mu_);
        alive_ = false;
        pthread_mutex_unlock(&mu_);
        pthread_join(ioThread_, nullptr);
        pthread_mutex_destroy(&mu_);
        pthread_cond_destroy(&cond_);
        free(buf_);
        if (ownsFd_ && fd_ >= 0) close(fd_);
    }

    ssize_t readAt(off64_t offset, void *const data, size_t size) override {
        if (offset >= fileLength_) return 0;

        pthread_mutex_lock(&mu_);

        // Fast path: data is already in buffer
        if (offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_) {
            memcpy(data, buf_ + (offset - bufStart_), size);

            // Compact when consumed past half
            size_t consumed = (size_t)(offset + size - bufStart_);
            if (consumed > BUF_CAP / 2 && bufFilled_ > consumed) {
                size_t remaining = bufFilled_ - consumed;
                memmove(buf_, buf_ + consumed, remaining);
                bufStart_ = offset + size;
                bufFilled_ = remaining;
            }
            pthread_mutex_unlock(&mu_);
            return (ssize_t)size;
        }

        // Partial hit: return what we have
        if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
            size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
            memcpy(data, buf_ + (offset - bufStart_), avail);
            pthread_mutex_unlock(&mu_);
            return (ssize_t)avail;
        }

        // Miss: data not in buffer. For small reads (e.g., FLAC seek binary search),
        // do a direct pread64 — much faster than waiting for the I/O thread to
        // seek+fill, especially on SD card through FUSE.
        if (size <= 64 * 1024) {
            pthread_mutex_unlock(&mu_);
            ssize_t n = pread64(fd_, data, size, offset);
            return n > 0 ? n : 0;
        }

        // Large miss: redirect I/O thread and wait
        seekTarget_ = offset;
        seekPending_ = true;

        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += 15;

        while (!(offset >= bufStart_ && (size_t)(offset - bufStart_) + size <= bufFilled_)) {
            if (offset >= bufStart_ && offset < bufStart_ + (off64_t)bufFilled_) {
                size_t avail = (size_t)(bufStart_ + bufFilled_ - offset);
                memcpy(data, buf_ + (offset - bufStart_), avail);
                pthread_mutex_unlock(&mu_);
                return (ssize_t)avail;
            }
            int rc = pthread_cond_timedwait(&cond_, &mu_, &deadline);
            if (rc != 0) {
                LOGW("readAt: timeout waiting for data @ %lld", (long long)offset);
                pthread_mutex_unlock(&mu_);
                return 0;
            }
        }

        memcpy(data, buf_ + (offset - bufStart_), size);

        size_t consumed = (size_t)(offset + size - bufStart_);
        if (consumed > BUF_CAP / 2 && bufFilled_ > consumed) {
            size_t remaining = bufFilled_ - consumed;
            memmove(buf_, buf_ + consumed, remaining);
            bufStart_ = offset + size;
            bufFilled_ = remaining;
        }

        pthread_mutex_unlock(&mu_);
        return (ssize_t)size;
    }

    off64_t getLength() override { return fileLength_; }
};

// ── NativeAudioEngine ───────────────────────────────────────────────

struct NativeAudioEngine {
    // Input
    AsyncBufferedDataSource *dataSource;
    FLACParser *parser;

    // USB output (owned by UsbAudioStream on the Java side)
    UsbAudioContext *usbCtx;

    // Stream info (from FLAC metadata)
    int sampleRate;
    int channels;
    int bitsPerSample;
    int dacBitDepth;  // from UsbAudioContext

    // Decode thread
    pthread_t thread;
    std::atomic<bool> running;
    std::atomic<bool> paused;

    // Position tracking
    std::atomic<int64_t> framesDecoded;
    int64_t seekTargetSampleIndex;  // -1 = no seek pending
    std::atomic<bool> seekPending;

    // Buffers
    uint8_t *pcmBuffer;       // raw decoded PCM from FLACParser
    uint8_t *convertBuffer;   // bit-depth converted PCM for USB
    size_t pcmBufferSize;
    size_t convertBufferSize;
};

static void *decodeThreadFunc(void *arg) {
    auto *engine = static_cast<NativeAudioEngine *>(arg);
    LOGI("Decode thread started: rate=%d ch=%d bits=%d dacBits=%d",
         engine->sampleRate, engine->channels,
         engine->bitsPerSample, engine->dacBitDepth);

    while (engine->running.load()) {
        // Handle pause
        if (engine->paused.load()) {
            usleep(20000);  // 20ms
            continue;
        }

        // Handle seek
        if (engine->seekPending.load()) {
            int64_t targetSample = engine->seekTargetSampleIndex;
            FLAC__uint64 totalSamples = engine->parser->getTotalSamples();
            LOGI("Seek: target=%lld total=%llu state=%s",
                 (long long)targetSample, (unsigned long long)totalSamples,
                 engine->parser->getDecoderStateString());

            // Clamp to valid range
            if (targetSample < 0) targetSample = 0;
            if (totalSamples > 0 && (FLAC__uint64)targetSample >= totalSamples) {
                targetSample = (int64_t)(totalSamples - 1);
            }

            bool seekOk = engine->parser->seekAbsolute((FLAC__uint64)targetSample);
            if (seekOk) {
                engine->framesDecoded.store(targetSample);
                LOGI("Seek OK: sample %lld (%.1f sec), state=%s",
                     (long long)targetSample,
                     (double)targetSample / engine->sampleRate,
                     engine->parser->getDecoderStateString());
            } else {
                LOGE("Seek FAILED: target=%lld total=%llu state=%s — resetting",
                     (long long)targetSample, (unsigned long long)totalSamples,
                     engine->parser->getDecoderStateString());
                engine->parser->reset(0);
                engine->parser->decodeMetadata();
                engine->framesDecoded.store(0);
            }
            engine->seekPending.store(false);
        }

        // Decode one FLAC frame
        size_t bytesRead = engine->parser->readBuffer(
            engine->pcmBuffer, engine->pcmBufferSize);

        if (bytesRead == (size_t)-1 || bytesRead == 0) {
            if (engine->parser->isDecoderAtEndOfStream()) {
                LOGI("End of FLAC stream, %lld frames decoded",
                     (long long)engine->framesDecoded.load());
            } else {
                LOGE("Decode error: %s",
                     engine->parser->getDecoderStateString());
            }
            break;
        }

        // Calculate frame count from decoded bytes
        int srcBytesPerSample = engine->bitsPerSample / 8;
        int srcBytesPerFrame = srcBytesPerSample * engine->channels;
        int framesInBuffer = (int)(bytesRead / srcBytesPerFrame);
        int totalSamples = framesInBuffer * engine->channels;

        // Convert bit depth: source → DAC
        const uint8_t *usbData;
        int usbBytes;

        if (engine->bitsPerSample == engine->dacBitDepth) {
            // Same bit depth — direct
            usbData = engine->pcmBuffer;
            usbBytes = (int)bytesRead;
        } else if (engine->bitsPerSample == 16 && engine->dacBitDepth == 32) {
            padInt16ToInt32(engine->pcmBuffer, engine->convertBuffer, totalSamples);
            usbData = engine->convertBuffer;
            usbBytes = totalSamples * 4;
        } else if (engine->bitsPerSample == 24 && engine->dacBitDepth == 32) {
            // FLACParser outputs 24-bit as packed 3-byte samples (little-endian)
            padInt24ToInt32(engine->pcmBuffer, engine->convertBuffer, totalSamples);
            usbData = engine->convertBuffer;
            usbBytes = totalSamples * 4;
        } else {
            LOGE("Unsupported bit-depth conversion: %d → %d",
                 engine->bitsPerSample, engine->dacBitDepth);
            break;
        }

        // Check running before USB submit (allows quick exit on stop)
        if (!engine->running.load()) break;

        // Submit to USB (blocks naturally on URB pipeline = perfect backpressure)
        submitPcmToUrbs(engine->usbCtx, usbData, usbBytes);

        int64_t newTotal = engine->framesDecoded.fetch_add(framesInBuffer) + framesInBuffer;
        // Log every ~1 second of audio
        if (newTotal % engine->sampleRate < framesInBuffer) {
            LOGI("Decode: %lld frames (~%.0f sec)",
                 (long long)newTotal, (double)newTotal / engine->sampleRate);
        }
    }

    engine->running.store(false);
    LOGI("Decode thread exited, %lld total frames",
         (long long)engine->framesDecoded.load());
    return nullptr;
}

// ── JNI entry points ────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreate(
        JNIEnv *, jobject, jstring jFilePath, jlong usbHandle) {
    // Will be implemented after Kotlin wrapper is ready
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeCreateFromFd(
        JNIEnv *, jobject, jint fd, jlong usbHandle) {
    auto *usbCtx = reinterpret_cast<UsbAudioContext *>(usbHandle);
    if (!usbCtx) {
        LOGE("nativeCreateFromFd: null USB context");
        return 0;
    }

    // Duplicate fd so we own it
    int ownedFd = dup(fd);
    if (ownedFd < 0) {
        LOGE("nativeCreateFromFd: dup() failed errno=%d", errno);
        return 0;
    }

    auto *ds = new AsyncBufferedDataSource(ownedFd, true);
    auto *parser = new FLACParser(ds);

    if (!parser->init()) {
        LOGE("nativeCreateFromFd: FLACParser init failed");
        delete parser;
        delete ds;
        return 0;
    }

    if (!parser->decodeMetadata()) {
        LOGE("nativeCreateFromFd: metadata decode failed");
        delete parser;
        delete ds;
        return 0;
    }

    auto *engine = new NativeAudioEngine();
    engine->dataSource = ds;
    engine->parser = parser;
    engine->usbCtx = usbCtx;
    engine->sampleRate = (int)parser->getSampleRate();
    engine->channels = (int)parser->getChannels();
    engine->bitsPerSample = (int)parser->getBitsPerSample();
    engine->dacBitDepth = usbCtx->bitDepth;
    engine->running.store(false);
    engine->paused.store(false);
    engine->framesDecoded.store(0);
    engine->seekTargetSampleIndex = -1;
    engine->seekPending.store(false);

    // Allocate decode buffers
    size_t maxBlock = parser->getMaxBlockSize();
    engine->pcmBufferSize = maxBlock * engine->channels * (engine->bitsPerSample / 8);
    engine->convertBufferSize = maxBlock * engine->channels * 4;  // max 32-bit output
    engine->pcmBuffer = (uint8_t *)malloc(engine->pcmBufferSize);
    engine->convertBuffer = (uint8_t *)malloc(engine->convertBufferSize);

    if (!engine->pcmBuffer || !engine->convertBuffer) {
        LOGE("nativeCreateFromFd: buffer allocation failed");
        free(engine->pcmBuffer);
        free(engine->convertBuffer);
        delete parser;
        delete ds;
        delete engine;
        return 0;
    }

    LOGI("Engine created: rate=%d ch=%d bits=%d dacBits=%d maxBlock=%zu",
         engine->sampleRate, engine->channels, engine->bitsPerSample,
         engine->dacBitDepth, maxBlock);

    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStart(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->running.load()) return JNI_FALSE;

    engine->running.store(true);
    engine->paused.store(false);

    int ret = pthread_create(&engine->thread, nullptr, decodeThreadFunc, engine);
    if (ret != 0) {
        LOGE("nativeStart: pthread_create failed ret=%d", ret);
        engine->running.store(false);
        return JNI_FALSE;
    }

    // Set high priority for the decode thread
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(engine->thread, SCHED_FIFO, &param);

    LOGI("Engine started");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativePause(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->paused.store(true);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeResume(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (engine) engine->paused.store(false);
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeSeek(
        JNIEnv *, jobject, jlong handle, jlong positionUs) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return JNI_FALSE;

    engine->seekTargetSampleIndex = positionUs * engine->sampleRate / 1000000LL;
    // Update framesDecoded immediately so getCurrentPositionUs returns the
    // seek target right away, before the decode thread processes the seek.
    // Prevents ExoPlayer from seeing a stale backwards position jump.
    engine->framesDecoded.store(engine->seekTargetSampleIndex);
    engine->seekPending.store(true);
    LOGI("Seek requested: %lld us → sample %lld",
         (long long)positionUs, (long long)engine->seekTargetSampleIndex);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStop(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    engine->running.store(false);
    engine->paused.store(false);
    pthread_join(engine->thread, nullptr);
    LOGI("Engine stopped");
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeDestroy(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;

    if (engine->running.load()) {
        engine->running.store(false);
        pthread_join(engine->thread, nullptr);
    }

    free(engine->pcmBuffer);
    free(engine->convertBuffer);
    delete engine->parser;
    delete engine->dataSource;
    LOGI("Engine destroyed, %lld total frames",
         (long long)engine->framesDecoded.load());
    delete engine;
}

JNIEXPORT jlong JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetPositionUs(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || engine->sampleRate <= 0) return 0;
    return engine->framesDecoded.load() * 1000000LL / engine->sampleRate;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetSampleRate(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->sampleRate : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetChannels(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->channels : 0;
}

JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeGetBitsPerSample(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return engine ? engine->bitsPerSample : 0;
}

JNIEXPORT jboolean JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeIsRunning(
        JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    return (engine && engine->running.load()) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
