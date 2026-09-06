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
#include <cmath>
#include <sys/stat.h>
#include <sys/mman.h>

#define TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Mono ring for the visualizer (power of two). Written by the decode thread,
// read by the JNI getter from the app's analyser thread.
#define VIZ_RING_SIZE 16384

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

    // Visualizer mono ring (decode thread writes, JNI reader copies the latest window)
    float *vizRing;
    std::atomic<int> vizWrite;
    std::atomic<int64_t> vizCount;

    // ── Varispeed (speed/pitch on bit-perfect) ──────────────────────
    // 1.0 = untouched bit-perfect passthrough. Otherwise the decoded block is linearly
    // resampled by this ratio before USB submit (tempo + pitch shift together, matching the
    // app's default match-pitch mode). Strict bit-perfect resumes the instant ratio returns to 1.
    std::atomic<double> speed;
    double rsPos;          // fractional source position carried across decode blocks
    float rsPrev[8];       // last source sample per channel for cross-block interpolation
    bool rsHasPrev;
    float *rsIn;           // decoded block as interleaved float
    float *rsOut;          // resampled interleaved float
    uint8_t *rsBytes;      // resampled/mixed, converted to DAC bit depth
    size_t rsInCap;        // floats
    size_t rsOutCap;       // floats
    size_t rsBytesCap;     // bytes

    // ── Crossfade (true-overlap mix on bit-perfect) ─────────────────
    // The OUTGOING track is decoded here as a fade-out secondary while this engine plays the
    // already-queue-advanced INCOMING track as primary. Equal-power: incoming fades in, outgoing
    // fades out over tailFadeSamples. No 2nd ExoPlayer and no promotion — incoming position stays
    // normal, and any failure (rate mismatch, open error) degrades to plain playback.
    FLACParser *tailParser;
    AsyncBufferedDataSource *tailDs;
    int tailBits;              // outgoing bit depth (== primary's; verified on open)
    uint8_t *tailPcm;          // outgoing decoded block (its own bit depth)
    float *tailFifo;           // outgoing samples as interleaved float (ring)
    int tailFifoCap;           // floats
    int tailFifoHead;          // read index (floats)
    int tailFifoCount;         // floats available
    bool tailEof;
    std::atomic<bool> tailActive;
    int64_t tailFadeSamples;   // fade length in frames
    int64_t tailElapsed;       // frames mixed since fade start
    float *mixBuf;             // primary+secondary mixed interleaved float

    // Pending slot: the outgoing file is opened + metadata-parsed + seeked on the JNI/caller thread
    // (NOT the real-time decode thread, which must never block on disk I/O), then the ready parser is
    // adopted by the decode loop. tailMu guards the pending slot handoff.
    pthread_mutex_t tailMu;
    FLACParser *tailPendingParser;
    AsyncBufferedDataSource *tailPendingDs;
    int64_t tailPendingFadeSamples;
    std::atomic<bool> tailPendingReady;
};

// ── Shared float helpers (varispeed + crossfade) ────────────────────

// Decode-block (source bit depth) -> interleaved float in `out`. Returns sample count, or -1.
static int srcToFloat(int sbits, const uint8_t *pcm, int nSamp, float *out) {
    if (sbits == 16) {
        const int16_t *s = reinterpret_cast<const int16_t *>(pcm);
        for (int i = 0; i < nSamp; i++) out[i] = s[i] / 32768.0f;
    } else if (sbits == 24) {
        for (int i = 0; i < nSamp; i++) {
            const uint8_t *p = pcm + (size_t)i * 3;
            int v = (p[0]) | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            out[i] = v / 8388608.0f;
        }
    } else if (sbits == 32) {
        const int32_t *s = reinterpret_cast<const int32_t *>(pcm);
        for (int i = 0; i < nSamp; i++) out[i] = (float)(s[i] / 2147483648.0);
    } else {
        return -1;
    }
    return nSamp;
}

// Interleaved float -> DAC bit depth into e->rsBytes. Returns output bytes, or -1.
static int convertFloatToDac(NativeAudioEngine *e, const float *in, int outSamp) {
    const int dac = e->dacBitDepth;
    if (dac == 32) {
        int32_t *d = reinterpret_cast<int32_t *>(e->rsBytes);
        for (int i = 0; i < outSamp; i++) {
            float f = in[i]; if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
            d[i] = (int32_t)lrintf(f * 2147483392.0f);   // 2^31 - 256, headroom against overflow
        }
        return outSamp * 4;
    } else if (dac == 24) {
        uint8_t *d = e->rsBytes;
        for (int i = 0; i < outSamp; i++) {
            float f = in[i]; if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
            int32_t v = (int32_t)lrintf(f * 8388607.0f);
            d[i * 3] = v & 0xFF; d[i * 3 + 1] = (v >> 8) & 0xFF; d[i * 3 + 2] = (v >> 16) & 0xFF;
        }
        return outSamp * 3;
    } else if (dac == 16) {
        int16_t *d = reinterpret_cast<int16_t *>(e->rsBytes);
        for (int i = 0; i < outSamp; i++) {
            float f = in[i]; if (f > 1.f) f = 1.f; else if (f < -1.f) f = -1.f;
            d[i] = (int16_t)lrintf(f * 32767.0f);
        }
        return outSamp * 2;
    }
    return -1;
}

// Linear-resample `in` (frames, interleaved) by `ratio` into e->rsOut with carried phase.
// Returns output frame count.
static int resampleFloat(NativeAudioEngine *e, const float *in, int frames, double ratio) {
    const int ch = e->channels;
    double pos = e->rsPos;
    int outFrames = 0;
    while (true) {
        int i0 = (int)floor(pos);
        int i1 = i0 + 1;
        if (i1 > frames - 1) break;
        if ((size_t)((outFrames + 1) * ch) > e->rsOutCap) break;
        float frac = (float)(pos - i0);
        for (int c = 0; c < ch; c++) {
            float s0 = (i0 < 0) ? (e->rsHasPrev ? e->rsPrev[c] : in[c]) : in[i0 * ch + c];
            float s1 = in[i1 * ch + c];
            e->rsOut[outFrames * ch + c] = s0 + (s1 - s0) * frac;
        }
        outFrames++;
        pos += ratio;
    }
    for (int c = 0; c < ch; c++) e->rsPrev[c] = in[(frames - 1) * ch + c];
    e->rsHasPrev = true;
    e->rsPos = pos - frames;   // re-base onto the next block's origin (lands in [-1, 0))
    return outFrames;
}

// Interleaved float block -> USB bytes (resample first if ratio != 1). Returns bytes, or -1.
static int floatToUsb(NativeAudioEngine *e, const float *in, int frames, double ratio) {
    if (fabs(ratio - 1.0) <= 1e-4) {
        return convertFloatToDac(e, in, frames * e->channels);
    }
    int outFrames = resampleFloat(e, in, frames, ratio);
    return convertFloatToDac(e, e->rsOut, outFrames * e->channels);
}

// Varispeed-only path: source PCM -> float -> resample -> DAC. Returns bytes, or -1.
static int resampleBlock(NativeAudioEngine *e, const uint8_t *pcm, int frames, double ratio) {
    const int nSamp = frames * e->channels;
    if (frames <= 0 || (size_t)nSamp > e->rsInCap) return -1;
    if (srcToFloat(e->bitsPerSample, pcm, nSamp, e->rsIn) < 0) return -1;
    return floatToUsb(e, e->rsIn, frames, ratio);
}

// ── Crossfade tail (outgoing track decoded as a fade-out secondary) ──

static void closeTail(NativeAudioEngine *e) {
    if (e->tailParser) { delete e->tailParser; e->tailParser = nullptr; }
    if (e->tailDs) { delete e->tailDs; e->tailDs = nullptr; }
    e->tailActive.store(false);
    e->tailEof = false;
    e->tailFifoHead = 0;
    e->tailFifoCount = 0;
    e->tailElapsed = 0;
}

// Decode outgoing blocks into the float FIFO until it holds >= needFloats (or the tail ends).
static void refillTailFifo(NativeAudioEngine *e, int needFloats) {
    const int ch = e->channels;
    const int sbits = e->tailBits;
    while (e->tailFifoCount < needFloats && !e->tailEof) {
        size_t br = e->tailParser->readBuffer(e->tailPcm, e->pcmBufferSize);
        if (br == (size_t)-1 || br == 0) { e->tailEof = true; break; }
        int frames = (int)(br / ((size_t)(sbits / 8) * ch));
        int ns = frames * ch;
        for (int i = 0; i < ns; i++) {
            if (e->tailFifoCount >= e->tailFifoCap) return;   // full (shouldn't happen if sized right)
            float f;
            if (sbits == 16) {
                f = reinterpret_cast<const int16_t *>(e->tailPcm)[i] / 32768.0f;
            } else if (sbits == 32) {
                f = (float)(reinterpret_cast<const int32_t *>(e->tailPcm)[i] / 2147483648.0);
            } else {
                const uint8_t *p = e->tailPcm + (size_t)i * 3;
                int v = (p[0]) | (p[1] << 8) | (p[2] << 16);
                if (v & 0x800000) v |= ~0xFFFFFF;
                f = v / 8388608.0f;
            }
            e->tailFifo[(e->tailFifoHead + e->tailFifoCount) % e->tailFifoCap] = f;
            e->tailFifoCount++;
        }
    }
}

// Adopt a tail that the JNI thread already opened + seeked (no disk I/O on this real-time thread).
static void adoptTailIfReady(NativeAudioEngine *e) {
    if (!e->tailPendingReady.load() || e->tailActive.load()) return;
    pthread_mutex_lock(&e->tailMu);
    if (e->tailPendingReady.load() && !e->tailActive.load() && e->tailPendingParser) {
        e->tailParser = e->tailPendingParser;
        e->tailDs = e->tailPendingDs;
        e->tailFadeSamples = e->tailPendingFadeSamples;
        e->tailPendingParser = nullptr;
        e->tailPendingDs = nullptr;
        e->tailPendingReady.store(false);
        e->tailBits = e->bitsPerSample;
        e->tailEof = false;
        e->tailFifoHead = 0;
        e->tailFifoCount = 0;
        e->tailElapsed = 0;
        e->tailActive.store(true);
        LOGI("Crossfade tail adopted: fadeSamples=%lld", (long long)e->tailFadeSamples);
    }
    pthread_mutex_unlock(&e->tailMu);
}

// Equal-power mix of the primary block (incoming, fades in) with the outgoing tail (fades out) into
// e->mixBuf. Returns the frame count written (== frames). Advances tailElapsed; closes the tail when done.
static void mixTailBlock(NativeAudioEngine *e, const uint8_t *primaryPcm, int frames) {
    const int ch = e->channels;
    const int nSamp = frames * ch;
    if (srcToFloat(e->bitsPerSample, primaryPcm, nSamp, e->rsIn) < 0) {
        // unsupported depth — abandon the crossfade and emit one clean (silent) block rather than stale data
        closeTail(e);
        memset(e->mixBuf, 0, (size_t)nSamp * sizeof(float));
        return;
    }
    double t = (double)e->tailElapsed / (double)(e->tailFadeSamples > 0 ? e->tailFadeSamples : 1);
    if (t > 1.0) t = 1.0;
    float inGain = sinf((float)(t * 1.5707963f));
    float outGain = cosf((float)(t * 1.5707963f));
    refillTailFifo(e, nSamp);
    for (int i = 0; i < nSamp; i++) {
        float sec = 0.f;
        if (e->tailFifoCount > 0) {
            sec = e->tailFifo[e->tailFifoHead];
            e->tailFifoHead = (e->tailFifoHead + 1) % e->tailFifoCap;
            e->tailFifoCount--;
        }
        e->mixBuf[i] = inGain * e->rsIn[i] + outGain * sec;
    }
    e->tailElapsed += frames;
    if (e->tailElapsed >= e->tailFadeSamples) closeTail(e);   // incoming now at full -> bit-perfect resumes
}

// Downmix one decoded block (source bit depth) to mono float and append to the viz ring.
static void pushVizSamples(NativeAudioEngine *e, const uint8_t *pcm, int frames) {
    if (!e->vizRing || frames <= 0) return;
    const int ch = e->channels;
    const int bps = e->bitsPerSample;
    int w = e->vizWrite.load();
    for (int i = 0; i < frames; i++) {
        float mono;
        if (bps == 16) {
            const int16_t *s = reinterpret_cast<const int16_t *>(pcm) + (size_t)i * ch;
            int acc = 0;
            for (int c = 0; c < ch; c++) acc += s[c];
            mono = (float)acc / ch / 32768.0f;
        } else if (bps == 24) {
            const uint8_t *base = pcm + (size_t)i * ch * 3;
            int acc = 0;
            for (int c = 0; c < ch; c++) {
                const uint8_t *p = base + c * 3;
                int v = (p[0]) | (p[1] << 8) | (p[2] << 16);
                if (v & 0x800000) v |= ~0xFFFFFF;  // sign-extend 24→32
                acc += v;
            }
            mono = (float)acc / ch / 8388608.0f;
        } else {
            mono = 0.0f;
        }
        e->vizRing[w] = mono;
        w = (w + 1) & (VIZ_RING_SIZE - 1);
    }
    e->vizWrite.store(w);
    e->vizCount.fetch_add(frames);
}

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

        // Adopt a crossfade tail that the JNI thread already opened (never blocks this RT thread on I/O)
        adoptTailIfReady(engine);

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
            // drop resampler carryover so it doesn't smear across the seek discontinuity
            engine->rsHasPrev = false;
            engine->rsPos = 0.0;
            // a user seek mid-crossfade abandons the fade (the outgoing tail is no longer relevant)
            if (engine->tailActive.load() || engine->tailParser) closeTail(engine);
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
            // primary ended mid-crossfade (incoming shorter than the fade) — drop the dangling tail
            if (engine->tailActive.load() || engine->tailParser) closeTail(engine);
            break;
        }

        // Calculate frame count from decoded bytes
        int srcBytesPerSample = engine->bitsPerSample / 8;
        int srcBytesPerFrame = srcBytesPerSample * engine->channels;
        int framesInBuffer = (int)(bytesRead / srcBytesPerFrame);
        int totalSamples = framesInBuffer * engine->channels;

        // Feed the visualizer (mono float) from the source PCM, before bit-depth conversion.
        pushVizSamples(engine, engine->pcmBuffer, framesInBuffer);

        // Convert bit depth: source → DAC (or resample first when varispeed is engaged)
        const uint8_t *usbData;
        int usbBytes;

        double sp = engine->speed.load();
        if (engine->tailActive.load()) {
            // Crossfade: equal-power mix of incoming (this engine) + outgoing tail, then varispeed if any.
            mixTailBlock(engine, engine->pcmBuffer, framesInBuffer);
            int rb = floatToUsb(engine, engine->mixBuf, framesInBuffer, sp);
            if (rb < 0) { LOGE("Crossfade mix/convert failed"); break; }
            usbData = engine->rsBytes;
            usbBytes = rb;
        } else if (fabs(sp - 1.0) > 1e-4) {
            // Speed/pitch active — resample (no longer strictly bit-perfect, by definition).
            int rb = resampleBlock(engine, engine->pcmBuffer, framesInBuffer, sp);
            if (rb < 0) {
                LOGE("Resample/convert failed (dac=%d src=%d)", engine->dacBitDepth, engine->bitsPerSample);
                break;
            }
            usbData = engine->rsBytes;
            usbBytes = rb;
        } else {
            // ratio back at 1.0 — drop carried phase so re-engaging starts clean, then passthrough
            engine->rsHasPrev = false;
            engine->rsPos = 0.0;
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
    engine->vizRing = (float *)calloc(VIZ_RING_SIZE, sizeof(float));
    engine->vizWrite.store(0);
    engine->vizCount.store(0);

    // Varispeed scratch: output can grow up to 2x (ratio 0.5) plus a few frames of slack.
    engine->speed.store(1.0);
    engine->rsPos = 0.0;
    engine->rsHasPrev = false;
    engine->rsInCap = maxBlock * engine->channels;
    engine->rsOutCap = engine->rsInCap * 2 + (size_t)engine->channels * 4;
    engine->rsBytesCap = engine->rsOutCap * 4;   // up to 4 bytes/sample (32-bit DAC)
    engine->rsIn = (float *)malloc(engine->rsInCap * sizeof(float));
    engine->rsOut = (float *)malloc(engine->rsOutCap * sizeof(float));
    engine->rsBytes = (uint8_t *)malloc(engine->rsBytesCap);
    engine->mixBuf = (float *)malloc(engine->rsInCap * sizeof(float));

    // Crossfade tail scratch + state.
    engine->tailParser = nullptr;
    engine->tailDs = nullptr;
    engine->tailPcm = (uint8_t *)malloc(engine->pcmBufferSize);
    engine->tailFifoCap = (int)(engine->rsInCap * 4);   // a few decode blocks of headroom
    engine->tailFifo = (float *)malloc((size_t)engine->tailFifoCap * sizeof(float));
    engine->tailFifoHead = 0;
    engine->tailFifoCount = 0;
    engine->tailEof = false;
    engine->tailActive.store(false);
    engine->tailFadeSamples = 0;
    engine->tailElapsed = 0;
    engine->tailBits = engine->bitsPerSample;
    pthread_mutex_init(&engine->tailMu, nullptr);
    engine->tailPendingParser = nullptr;
    engine->tailPendingDs = nullptr;
    engine->tailPendingFadeSamples = 0;
    engine->tailPendingReady.store(false);

    if (!engine->pcmBuffer || !engine->convertBuffer || !engine->rsIn || !engine->rsOut ||
        !engine->rsBytes || !engine->mixBuf || !engine->tailPcm || !engine->tailFifo || !engine->vizRing) {
        LOGE("nativeCreateFromFd: buffer allocation failed");
        free(engine->pcmBuffer);
        free(engine->convertBuffer);
        free(engine->vizRing);
        free(engine->rsIn);
        free(engine->rsOut);
        free(engine->rsBytes);
        free(engine->mixBuf);
        free(engine->tailPcm);
        free(engine->tailFifo);
        pthread_mutex_destroy(&engine->tailMu);
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

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeSetSpeed(
        JNIEnv *, jobject, jlong handle, jdouble speed) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;
    double s = speed;
    if (s < 0.5) s = 0.5; else if (s > 2.0) s = 2.0;   // matches the app's speed slider range
    engine->speed.store(s);
}

JNIEXPORT void JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeStartTailFade(
        JNIEnv *, jobject, jlong handle, jint fd, jlong startUs, jlong fadeUs) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine) return;
    // Open + metadata-parse + seek the outgoing file HERE (caller's thread, e.g. the player thread),
    // NOT on the real-time decode thread — the decode thread must never block on disk I/O or it underruns.
    int owned = dup(fd);   // caller closes its ParcelFileDescriptor right after this returns
    if (owned < 0) {
        LOGW("nativeStartTailFade: dup failed errno=%d", errno);
        return;
    }
    auto *ds = new AsyncBufferedDataSource(owned, true);   // takes ownership of the dup'd fd
    auto *parser = new FLACParser(ds);
    if (!parser->init() || !parser->decodeMetadata()) {
        LOGW("Crossfade tail: parser init failed");
        delete parser; delete ds; return;
    }
    // must match the primary stream exactly — one USB stream carries one rate/format
    if ((int)parser->getSampleRate() != engine->sampleRate ||
        (int)parser->getChannels() != engine->channels ||
        (int)parser->getBitsPerSample() != engine->bitsPerSample ||
        (int)parser->getMaxBlockSize() * engine->channels * (engine->bitsPerSample / 8) > (int)engine->pcmBufferSize) {
        LOGW("Crossfade tail: format mismatch — skipping (rate=%d ch=%d bits=%d)",
             (int)parser->getSampleRate(), (int)parser->getChannels(), (int)parser->getBitsPerSample());
        delete parser; delete ds; return;
    }
    if (startUs > 0) parser->seekAbsolute((FLAC__uint64)(startUs * engine->sampleRate / 1000000LL));
    int64_t fadeSamples = fadeUs * engine->sampleRate / 1000000LL;
    if (fadeSamples < 1) fadeSamples = 1;

    // publish the ready parser; the decode thread adopts it on its next iteration
    pthread_mutex_lock(&engine->tailMu);
    if (engine->tailPendingParser) {            // discard a stale, never-adopted pending tail
        delete engine->tailPendingParser;
        delete engine->tailPendingDs;
    }
    engine->tailPendingParser = parser;
    engine->tailPendingDs = ds;
    engine->tailPendingFadeSamples = fadeSamples;
    engine->tailPendingReady.store(true);
    pthread_mutex_unlock(&engine->tailMu);
    LOGI("Crossfade tail opened: fadeSamples=%lld startUs=%lld", (long long)fadeSamples, (long long)startUs);
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
    // decode thread has exited; drop both the active tail and any never-adopted pending one so stop()
    // is self-contained (not just leak-free because callers happen to destroy() right after).
    closeTail(engine);
    if (engine->tailPendingParser) {
        delete engine->tailPendingParser; engine->tailPendingParser = nullptr;
        delete engine->tailPendingDs; engine->tailPendingDs = nullptr;
        engine->tailPendingReady.store(false);
    }
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

    // tear down any in-flight crossfade tail + a never-adopted pending tail
    closeTail(engine);
    if (engine->tailPendingParser) { delete engine->tailPendingParser; delete engine->tailPendingDs; }
    pthread_mutex_destroy(&engine->tailMu);

    free(engine->pcmBuffer);
    free(engine->convertBuffer);
    free(engine->vizRing);
    free(engine->rsIn);
    free(engine->rsOut);
    free(engine->rsBytes);
    free(engine->mixBuf);
    free(engine->tailPcm);
    free(engine->tailFifo);
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

// Copy the latest out.length mono samples into out. Returns the number of valid (decoded) samples.
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_NativeAudioEngine_nativeReadVisualizer(
        JNIEnv *env, jobject, jlong handle, jfloatArray out) {
    auto *engine = reinterpret_cast<NativeAudioEngine *>(handle);
    if (!engine || !engine->vizRing) return 0;
    jsize len = env->GetArrayLength(out);
    if (len <= 0) return 0;
    int n = (int)len;
    if (n > VIZ_RING_SIZE) n = VIZ_RING_SIZE;
    int w = engine->vizWrite.load();
    int start = (((w - n) % VIZ_RING_SIZE) + VIZ_RING_SIZE) % VIZ_RING_SIZE;
    int first = VIZ_RING_SIZE - start;
    if (first > n) first = n;
    env->SetFloatArrayRegion(out, 0, first, engine->vizRing + start);
    if (first < n) {
        env->SetFloatArrayRegion(out, first, n - first, engine->vizRing);
    }
    int64_t avail = engine->vizCount.load();
    return (jint)((avail < n) ? avail : n);
}

} // extern "C"
