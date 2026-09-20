#include "usb-audio-output.h"
#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <new>
#include <algorithm>
#include <vector>
#include <unistd.h>
#include <time.h>
#include <sys/ioctl.h>

#ifndef USBDEVFS_GET_SPEED
#define USBDEVFS_GET_SPEED _IO('U', 31)
#endif

static std::mutex quarantineMutex;
static std::vector<UsbAudioContext *> quarantine;

static int64_t nowMs() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000LL + t.tv_nsec / 1000000;
}
static void fail(UsbAudioContext *ctx, int code) {
    ctx->lastError.store(code > 0 ? code : EIO);
    ctx->running.store(false);
    __android_log_print(ANDROID_LOG_ERROR, "UsbAudioOutput",
        "USB failure: error=%d accepted=%lld submitted=%lld completed=%lld inflight=%d feedback=%lld invalidFeedback=%lld",
        ctx->lastError.load(), (long long)ctx->framesWritten.load(), (long long)ctx->submittedFrames.load(),
        (long long)ctx->completedFrames.load(), ctx->urbsInFlight.load(),
        (long long)ctx->feedbackPackets.load(), (long long)ctx->invalidFeedbackPackets.load());
}
static bool submitFeedback(UsbAudioContext *ctx) {
    if (!ctx->running.load() || ctx->endpointFeedback <= 0 || ctx->feedbackInFlight) return true;
    auto *u = ctx->feedbackUrb;
    memset(u, 0, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc));
    u->type = USBDEVFS_URB_TYPE_ISO; u->flags = USBDEVFS_URB_ISO_ASAP;
    u->endpoint = ctx->endpointFeedback; u->buffer = ctx->feedbackBuffer;
    u->buffer_length = 4; u->number_of_packets = 1; u->iso_frame_desc[0].length = 4;
    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) { fail(ctx, errno); return false; }
    ctx->feedbackInFlight = true;
    return true;
}
static bool completeUrb(UsbAudioContext *ctx, usbdevfs_urb *u, bool cancelling) {
    if (u == ctx->feedbackUrb) {
        ctx->feedbackInFlight = false;
        if (!cancelling && ctx->running.load() && u->status == 0 && u->iso_frame_desc[0].status == 0 && u->iso_frame_desc[0].actual_length == 4) {
            const auto *b = ctx->feedbackBuffer;
            const uint32_t raw = uint32_t(b[0]) | (uint32_t(b[1]) << 8) | (uint32_t(b[2]) << 16) | (uint32_t(b[3]) << 24);
            const double measured = raw / 65536.0;
            const double nominal = ctx->sampleRate / 8000.0;
            if (measured >= nominal * .99 && measured <= nominal * 1.01) {
                ctx->calibratedFpmf.store(measured); ctx->feedbackPackets.fetch_add(1);
            } else ctx->invalidFeedbackPackets.fetch_add(1);
        } else if (!cancelling && ctx->running.load()) ctx->invalidFeedbackPackets.fetch_add(1);
        return cancelling || submitFeedback(ctx);
    }
    for (auto &slot : ctx->ring) if (u == slot.urb && slot.pending) {
        slot.pending = false; ctx->urbsInFlight.fetch_sub(1);
        if (cancelling) return true;
        int bytes = 0; int errors = u->status == 0 ? 0 : 1; int firstBadPacket = -1;
        for (int p = 0; p < u->number_of_packets; ++p) {
            if (u->iso_frame_desc[p].status != 0 || u->iso_frame_desc[p].actual_length != u->iso_frame_desc[p].length) {
                ++errors;
                if (firstBadPacket < 0) firstBadPacket = p;
            }
            else bytes += u->iso_frame_desc[p].actual_length;
        }
        if (errors) {
            __android_log_print(ANDROID_LOG_ERROR, "UsbAudioOutput", "USB completion: status=%d errors=%d endpoint=%u",
                u->status, errors, u->endpoint);
            if (firstBadPacket >= 0) {
                const auto &packet = u->iso_frame_desc[firstBadPacket];
                __android_log_print(ANDROID_LOG_ERROR, "UsbAudioOutput", "USB packet %d: status=%d actual=%u expected=%u",
                    firstBadPacket, int(packet.status), packet.actual_length, packet.length);
            }
            ctx->packetErrors.fetch_add(errors); fail(ctx, u->status < 0 ? -u->status : EIO);
        }
        else ctx->completedFrames.fetch_add(std::min(slot.validFrames, bytes / ctx->bytesPerFrame));
        return errors == 0;
    }
    fail(ctx, EPROTO);
    return false;
}
static int reap(UsbAudioContext *ctx, int timeoutMs, bool cancelling = false) {
    const int64_t deadline = nowMs() + timeoutMs;
    do {
        usbdevfs_urb *u = nullptr;
        if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &u) == 0 && u) return completeUrb(ctx, u, cancelling) ? 1 : -1;
        if (errno != EAGAIN && errno != EINTR) { fail(ctx, errno); return -1; }
        if (timeoutMs == 0) return 0;
        usleep(125);
    } while (nowMs() < deadline);
    ctx->reapTimeouts.fetch_add(1); fail(ctx, ETIMEDOUT);
    return -1;
}
static bool drain(UsbAudioContext *ctx, bool discardAudio) {
    ctx->running.store(false);
    if (ctx->feedbackInFlight) ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->feedbackUrb);
    if (discardAudio) for (auto &slot : ctx->ring) if (slot.pending) ioctl(ctx->fd, USBDEVFS_DISCARDURB, slot.urb);
    const int64_t deadline = nowMs() + 1000;
    while ((ctx->urbsInFlight.load() > 0 || ctx->feedbackInFlight) && nowMs() < deadline) {
        if (reap(ctx, 100, discardAudio) < 0) break;
    }
    if (ctx->urbsInFlight.load() > 0 || ctx->feedbackInFlight) {
        for (auto &slot : ctx->ring) if (slot.pending) ioctl(ctx->fd, USBDEVFS_DISCARDURB, slot.urb);
        const int64_t cancelledDeadline = nowMs() + 1000;
        while ((ctx->urbsInFlight.load() > 0 || ctx->feedbackInFlight) && nowMs() < cancelledDeadline) {
            if (reap(ctx, 100, true) < 0) break;
        }
    }
    return ctx->urbsInFlight.load() == 0 && !ctx->feedbackInFlight;
}
static int readInterfaceSetting(int fd, int interfaceId) {
    uint8_t alt = 0;
    usbdevfs_ctrltransfer request{};
    request.bRequestType = 0x81; request.bRequest = 0x0a; request.wIndex = interfaceId;
    request.wLength = 1; request.timeout = 1000; request.data = &alt;
    const int result = ioctl(fd, USBDEVFS_CONTROL, &request);
    return result == 1 ? alt : -(result < 0 ? errno : EIO);
}
template<class ReadAlt, class SetAlt>
static bool resetStreamingInterface(UsbAudioContext *ctx, ReadAlt readAlt, SetAlt setAlt) {
    if (ctx->urbsInFlight.load() || ctx->feedbackInFlight) { fail(ctx, EBUSY); return false; }
    const int current = readAlt();
    if (ctx->alternateSetting <= 0 || current != ctx->alternateSetting) {
        fail(ctx, current < 0 ? -current : EPROTO); return false;
    }
    for (int alt : {0, ctx->alternateSetting}) {
        const int error = setAlt(alt);
        if (error) { fail(ctx, error); return false; }
    }
    const int observed = readAlt();
    if (observed != ctx->alternateSetting) { fail(ctx, observed < 0 ? -observed : EPROTO); return false; }
    ctx->calibratedFpmf.store(ctx->sampleRate / 8000.0);
    return true;
}
static bool resetStreamingInterface(UsbAudioContext *ctx) {
    return resetStreamingInterface(ctx,
        [ctx] { return readInterfaceSetting(ctx->fd, ctx->interfaceId); },
        [ctx](int alt) {
            usbdevfs_setinterface request{}; request.interface = ctx->interfaceId; request.altsetting = alt;
            return ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &request) == 0 ? 0 : errno;
        });
}
static bool startUsbStream(UsbAudioContext *ctx) {
    if (ctx->running.load()) return true;
    if (ctx->lastError.load() || ctx->urbsInFlight.load() || ctx->feedbackInFlight) return false;
    const int64_t feedbackBefore = ctx->feedbackPackets.load();
    ctx->running.store(true);
    if (!submitFeedback(ctx)) return false;
    if (ctx->endpointFeedback > 0) {
        const int64_t deadline = nowMs() + 100;
        while (ctx->feedbackPackets.load() == feedbackBefore && ctx->running.load() && nowMs() < deadline)
            if (reap(ctx, 100) < 0) return false;
        if (!ctx->running.load()) return false;
        if (ctx->feedbackPackets.load() == feedbackBefore) { fail(ctx, EPROTO); return false; }
    }
    return ctx->running.load();
}
static bool plan(UsbAudioContext *ctx) {
    if (ctx->plannedBytes > 0) return true;
    double accumulator = ctx->frameAccumulator;
    int bytes = 0;
    for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; ++p) {
        accumulator += ctx->calibratedFpmf.load();
        const int frames = int(accumulator); accumulator -= frames;
        const int packet = frames * ctx->bytesPerFrame;
        if (packet > ctx->maxPacketSize || packet < 0 || bytes + packet > USB_AUDIO_URB_BUFFER_SIZE) { fail(ctx, EOVERFLOW); return false; }
        ctx->plannedSizes[p] = packet; bytes += packet;
    }
    if (bytes <= 0) { fail(ctx, EINVAL); return false; }
    ctx->plannedBytes = bytes; ctx->plannedAccumulator = accumulator;
    return true;
}
static bool submitPending(UsbAudioContext *ctx, int validFrames) {
    while (ctx->ring[ctx->submitIdx].pending && ctx->running.load()) if (reap(ctx, 200) < 0) return false;
    if (!ctx->running.load()) return false;
    auto &slot = ctx->ring[ctx->submitIdx]; auto *u = slot.urb;
    memset(u, 0, sizeof(usbdevfs_urb) + USB_AUDIO_PACKETS_PER_URB * sizeof(usbdevfs_iso_packet_desc));
    memcpy(slot.buffer, ctx->residualBuffer, ctx->plannedBytes);
    u->type = USBDEVFS_URB_TYPE_ISO; u->flags = USBDEVFS_URB_ISO_ASAP; u->endpoint = ctx->endpointOut;
    u->buffer = slot.buffer; u->buffer_length = ctx->plannedBytes; u->number_of_packets = USB_AUDIO_PACKETS_PER_URB;
    for (int p = 0; p < USB_AUDIO_PACKETS_PER_URB; ++p) u->iso_frame_desc[p].length = ctx->plannedSizes[p];
    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, u) < 0) { ctx->submitErrors.fetch_add(1); fail(ctx, errno); return false; }
    slot.pending = true; slot.validFrames = validFrames;
    ctx->urbsInFlight.fetch_add(1); ctx->submittedFrames.fetch_add(validFrames);
    ctx->submitIdx = (ctx->submitIdx + 1) % USB_AUDIO_NUM_URBS;
    ctx->frameAccumulator = ctx->plannedAccumulator; ctx->plannedBytes = 0; ctx->residualBytes = 0; ctx->startedAudio = true;
    return true;
}
template<class Submit> static void appendStaged(UsbAudioContext *ctx, const uint8_t *data, int bytes, Submit submit) {
    if (bytes < 0 || bytes % ctx->bytesPerFrame != 0) { fail(ctx, EINVAL); return; }
    int position = 0;
    while (position < bytes && ctx->running.load()) {
        if (!plan(ctx)) return;
        const int count = std::min(bytes - position, ctx->plannedBytes - ctx->residualBytes);
        memcpy(ctx->residualBuffer + ctx->residualBytes, data + position, count);
        ctx->residualBytes += count; position += count;
        ctx->framesWritten.fetch_add(count / ctx->bytesPerFrame);
        if (ctx->residualBytes == ctx->plannedBytes && !submit(ctx, ctx->plannedBytes / ctx->bytesPerFrame)) return;
    }
}
static void appendPcm(UsbAudioContext *ctx, const uint8_t *data, int bytes) {
    for (int count = 0; count < USB_AUDIO_NUM_URBS * 2 && reap(ctx, 0) > 0; ++count) {}
    if (ctx->startedAudio && ctx->urbsInFlight.load() == 0 && bytes > 0) ctx->starvationEvents.fetch_add(1);
    appendStaged(ctx, data, bytes, submitPending);
}
template<class Submit> static bool finishStaged(UsbAudioContext *ctx, Submit submit) {
    if (ctx->residualBytes == 0) return true;
    if (!ctx->running.load() || !plan(ctx)) return false;
    const int frames = ctx->residualBytes / ctx->bytesPerFrame;
    if (ctx->wireFormat == 0) memset(ctx->residualBuffer + ctx->residualBytes, 0, ctx->plannedBytes - ctx->residualBytes);
    else {
        int64_t frame = ctx->framesWritten.load();
        for (int offset = ctx->residualBytes; offset < ctx->plannedBytes; offset += ctx->bytesPerFrame, ++frame) {
            for (int channel = 0; channel < ctx->channelCount; ++channel) {
                auto* sample = ctx->residualBuffer + offset + channel * ctx->bytesPerSample;
                memset(sample, 0x69, ctx->bytesPerSample);
                if (ctx->wireFormat == 1) {
                    if (ctx->bytesPerSample == 4) sample[0] = 0;
                    sample[ctx->bytesPerSample - 1] = (frame & 1) ? 0xfa : 0x05;
                }
            }
        }
    }
    return submit(ctx, frames);
}
void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *data, int bytes) {
    if (!ctx) return;
    std::lock_guard<std::mutex> guard(ctx->ioMutex);
    if (ctx->running.load()) appendPcm(ctx, data, bytes);
}
bool finishUsbStream(UsbAudioContext *ctx) {
    if (!ctx) return false;
    std::lock_guard<std::mutex> guard(ctx->ioMutex);
    if (!finishStaged(ctx, submitPending)) return false;
    return drain(ctx, false) && ctx->lastError.load() == 0;
}
template<class ResetInterface> static void flushUsbStreamWithReset(UsbAudioContext *ctx, ResetInterface resetInterface) {
    if (!ctx) return;
    ctx->running.store(false);
    std::lock_guard<std::mutex> guard(ctx->ioMutex);
    if (!drain(ctx, true)) {
        __android_log_print(ANDROID_LOG_ERROR, "UsbAudioOutput", "USB flush could not reap cancelled transfers: error=%d inflight=%d feedback=%d",
            ctx->lastError.load(), ctx->urbsInFlight.load(), ctx->feedbackInFlight);
        return;
    }
    ctx->frameAccumulator = 0; ctx->plannedBytes = 0; ctx->residualBytes = 0; ctx->submitIdx = 0; ctx->startedAudio = false;
    ctx->framesWritten.store(0); ctx->submittedFrames.store(0); ctx->completedFrames.store(0);
    if (ctx->startRequested.load() && !ctx->lastError.load()) {
        if (!resetInterface(ctx)) return;
        if (ctx->startRequested.load()) startUsbStream(ctx);
    }
    __android_log_print(ANDROID_LOG_INFO, "UsbAudioOutput", "USB flush complete: requested=%d running=%d error=%d",
        ctx->startRequested.load(), ctx->running.load(), ctx->lastError.load());
}
void flushUsbStream(UsbAudioContext *ctx) {
    flushUsbStreamWithReset(ctx, [](UsbAudioContext *state) { return resetStreamingInterface(state); });
}
static void freeMemory(UsbAudioContext *ctx) {
    for (auto &slot : ctx->ring) { free(slot.urb); free(slot.buffer); }
    free(ctx->feedbackUrb); free(ctx->transferBuffer);
    if (ctx->fd >= 0) close(ctx->fd);
}
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; ++i) { dst[4*i] = 0; dst[4*i+1] = 0; dst[4*i+2] = src[2*i]; dst[4*i+3] = src[2*i+1]; }
}
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; ++i) { dst[4*i] = 0; memcpy(dst + 4*i + 1, src + 3*i, 3); }
}
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int n) {
    for (int i = 0; i < n; ++i) { dst[4*i] = 0; memcpy(dst + 4*i + 1, src + 4*i, 3); }
}
void convertFloatPcm(const float *src, uint8_t *dst, int count, int validBits, int containerBits) {
    const double scale = std::ldexp(1.0, validBits - 1);
    const int bytes = containerBits / 8;
    for (int i = 0; i < count; ++i) {
        const double normalized = std::isfinite(src[i]) ? std::max(-1.0, std::min(1.0, double(src[i]))) : 0;
        const int64_t value = std::llround(std::max(-scale, std::min(scale - 1, normalized * scale))) * (int64_t(1) << (containerBits - validBits));
        for (int b = 0; b < bytes; ++b) dst[i * bytes + b] = uint64_t(value) >> (8 * b);
    }
}
extern "C" {
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeGetUsbSpeed(JNIEnv *, jclass, jint fd) {
    int speed = ioctl(fd, USBDEVFS_GET_SPEED); return speed < 0 ? -errno : speed;
}
JNIEXPORT jlong JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioCreate(
        JNIEnv *, jobject, jint fd, jint iface, jint out, jint feedback, jint rate, jint channels, jint bits, jint maxPacket, jint validBits, jint alt, jint wireFormat) {
    if (fd < 0 || ioctl(fd, USBDEVFS_GET_SPEED) != 3 || iface < 0 || out <= 0 || out >= 0x80 ||
        (feedback > 0 && (feedback < 0x80 || feedback > 255)) || rate < 8000 || rate > 384000 ||
        channels < 1 || channels > 2 || (bits != 16 && bits != 24 && bits != 32) || validBits < 16 || validBits > bits || maxPacket <= 0 || maxPacket > 512 ||
        std::ceil(rate * (feedback > 0 ? 1.01 : 1.0) / 8000.0) * channels * (bits / 8) > maxPacket ||
        wireFormat < 0 || wireFormat > 2 || (wireFormat == 1 && (bits < 24 || validBits < 24)) ||
        (wireFormat == 2 && (bits != 32 || validBits != 32)) ||
        alt <= 0 || alt > 255 || readInterfaceSetting(fd, iface) != alt) return 0;
    auto *ctx = new(std::nothrow) UsbAudioContext();
    if (!ctx) return 0;
    {
        std::lock_guard<std::mutex> guard(quarantineMutex);
        for (auto *pending : quarantine) if (pending->ownerFd == fd) { delete ctx; return 0; }
    }
    ctx->ownerFd = fd; ctx->fd = dup(fd); ctx->interfaceId = iface; ctx->endpointOut = out; ctx->endpointFeedback = feedback;
    ctx->alternateSetting = alt;
    ctx->wireFormat = wireFormat;
    ctx->sampleRate = rate; ctx->channelCount = channels; ctx->bitDepth = bits; ctx->validBits = validBits; ctx->bytesPerSample = bits / 8;
    ctx->bytesPerFrame = channels * ctx->bytesPerSample; ctx->maxPacketSize = maxPacket;
    ctx->calibratedFpmf.store(rate / 8000.0);
    for (auto &slot : ctx->ring) {
        slot.urb = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb) + USB_AUDIO_PACKETS_PER_URB * sizeof(usbdevfs_iso_packet_desc)));
        slot.buffer = static_cast<uint8_t *>(malloc(USB_AUDIO_URB_BUFFER_SIZE));
        if (!slot.urb || !slot.buffer) { freeMemory(ctx); delete ctx; return 0; }
    }
    ctx->feedbackUrb = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
    ctx->transferBuffer = static_cast<uint8_t *>(malloc(USB_AUDIO_CONVERSION_BUFFER_SIZE));
    if (ctx->fd < 0 || !ctx->feedbackUrb || !ctx->transferBuffer) { freeMemory(ctx); delete ctx; return 0; }
    return reinterpret_cast<jlong>(ctx);
}
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetAltSetting(JNIEnv *, jobject, jlong h, jint alt) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx || alt < 0) return false;
    std::lock_guard<std::mutex> guard(ctx->ioMutex);
    if (ctx->running.load() || ctx->urbsInFlight.load() || ctx->feedbackInFlight) return false;
    usbdevfs_setinterface request{}; request.interface = ctx->interfaceId; request.altsetting = alt;
    if (ioctl(ctx->fd, USBDEVFS_SETINTERFACE, &request) != 0) return false;
    if (alt > 0) ctx->alternateSetting = alt;
    return true;
}
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioSetSampleRate(JNIEnv *, jobject, jlong h, jint rate, jint) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    return ctx && rate == ctx->sampleRate;
}
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return false;
    std::lock_guard<std::mutex> guard(ctx->ioMutex);
    ctx->startRequested.store(true);
    return startUsbStream(ctx);
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWrite(JNIEnv *env, jobject, jlong h, jfloatArray pcm) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return;
    std::lock_guard<std::mutex> guard(ctx->ioMutex); if (!ctx->running.load()) return;
    if (ctx->wireFormat != 0) { fail(ctx, EINVAL); return; }
    const int count = env->GetArrayLength(pcm);
    if (count % ctx->channelCount || count > USB_AUDIO_CONVERSION_BUFFER_SIZE / ctx->bytesPerSample) { fail(ctx, EINVAL); return; }
    auto *samples = env->GetFloatArrayElements(pcm, nullptr); if (!samples) { fail(ctx, ENOMEM); return; }
    convertFloatPcm(samples, ctx->transferBuffer, count, ctx->validBits, ctx->bitDepth);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    appendPcm(ctx, ctx->transferBuffer, count * ctx->bytesPerSample);
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWriteRaw(JNIEnv *env, jobject, jlong h, jbyteArray pcm, jint bits) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return;
    std::lock_guard<std::mutex> guard(ctx->ioMutex); if (!ctx->running.load()) return;
    if (ctx->wireFormat != 0) { fail(ctx, EINVAL); return; }
    const int bytes = env->GetArrayLength(pcm);
    if ((bits != 16 && bits != 24 && bits != 32) || bits > ctx->bitDepth || bytes % ((bits / 8) * ctx->channelCount)) { fail(ctx, EINVAL); return; }
    const int sourceBytes = bits / 8; const int count = bytes / sourceBytes;
    if (count > USB_AUDIO_CONVERSION_BUFFER_SIZE / ctx->bytesPerSample) { fail(ctx, EOVERFLOW); return; }
    auto *data = env->GetByteArrayElements(pcm, nullptr); if (!data) { fail(ctx, ENOMEM); return; }
    const int padding = ctx->bytesPerSample - sourceBytes;
    for (int i = 0; i < count; ++i) {
        if (bits > ctx->validBits) {
            uint32_t value = 0;
            for (int b = 0; b < sourceBytes; ++b) value |= uint32_t(uint8_t(data[i * sourceBytes + b])) << (8 * b);
            const uint32_t mask = (uint32_t(1) << (bits - ctx->validBits)) - 1;
            if ((value & mask) != 0) {
                env->ReleaseByteArrayElements(pcm, data, JNI_ABORT); fail(ctx, EINVAL); return;
            }
        }
        memset(ctx->transferBuffer + i * ctx->bytesPerSample, 0, padding);
        memcpy(ctx->transferBuffer + i * ctx->bytesPerSample + padding, data + i * sourceBytes, sourceBytes);
    }
    env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);
    appendPcm(ctx, ctx->transferBuffer, count * ctx->bytesPerSample);
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioWritePacked(JNIEnv *env, jobject, jlong h, jbyteArray bytes) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return;
    std::lock_guard<std::mutex> guard(ctx->ioMutex); if (!ctx->running.load()) return;
    const int size = env->GetArrayLength(bytes);
    if (ctx->wireFormat == 0 || size > USB_AUDIO_CONVERSION_BUFFER_SIZE || size % ctx->bytesPerFrame) { fail(ctx, EINVAL); return; }
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(ctx->transferBuffer));
    if (env->ExceptionCheck()) { fail(ctx, ENOMEM); return; }
    appendPcm(ctx, ctx->transferBuffer, size);
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h);
    if (ctx) { ctx->startRequested.store(false); ctx->running.store(false); }
}
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeFinish(JNIEnv *, jobject, jlong h) {
    return finishUsbStream(reinterpret_cast<UsbAudioContext *>(h));
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeFlush(JNIEnv *, jobject, jlong h) {
    flushUsbStream(reinterpret_cast<UsbAudioContext *>(h));
}
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeDrainUrbs(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return 0;
    ctx->startRequested.store(false);
    ctx->running.store(false); std::lock_guard<std::mutex> guard(ctx->ioMutex);
    const int count = ctx->urbsInFlight.load(); return drain(ctx, false) ? count : -1;
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioDestroy(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return;
    ctx->startRequested.store(false);
    ctx->running.store(false);
    {
        std::lock_guard<std::mutex> guard(ctx->ioMutex);
        if (!drain(ctx, true)) {
            // keep unresolved completion targets alive after a failed cancellation.
            if (ctx->fd >= 0) { close(ctx->fd); ctx->fd = -1; }
            std::lock_guard<std::mutex> quarantineGuard(quarantineMutex);
            quarantine.push_back(ctx);
            __android_log_print(ANDROID_LOG_ERROR, "UsbAudioOutput", "Unreaped USB transfers retained after cancellation failure");
            return;
        }
    }
    freeMemory(ctx); delete ctx;
}
JNIEXPORT jboolean JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeIsRunning(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); return ctx && ctx->running.load();
}
JNIEXPORT jlong JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeGetFramesWritten(JNIEnv *, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); return ctx ? ctx->framesWritten.load() : 0;
}
JNIEXPORT jlongArray JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeGetTelemetry(JNIEnv *env, jobject, jlong h) {
    auto *ctx = reinterpret_cast<UsbAudioContext *>(h); if (!ctx) return nullptr;
    const jlong values[] = {ctx->framesWritten.load(), ctx->submittedFrames.load(), ctx->completedFrames.load(),
        ctx->packetErrors.load(), ctx->submitErrors.load(), ctx->reapTimeouts.load(), ctx->starvationEvents.load(),
        ctx->feedbackPackets.load(), ctx->invalidFeedbackPackets.load(), ctx->lastError.load(),
        jlong(ctx->calibratedFpmf.load() * 8000.0), ctx->urbsInFlight.load()};
    auto out = env->NewLongArray(12); if (out) env->SetLongArrayRegion(out, 0, 12, values); return out;
}
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeUsbReset(JNIEnv *, jclass, jint fd) {
    return ioctl(fd, USBDEVFS_RESET, nullptr) == 0 ? 0 : -errno;
}
JNIEXPORT void JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeConnectionClosed(JNIEnv *, jclass, jint fd) {
    std::lock_guard<std::mutex> guard(quarantineMutex);
    for (auto entry = quarantine.begin(); entry != quarantine.end();) {
        if ((*entry)->ownerFd == fd) { freeMemory(*entry); delete *entry; entry = quarantine.erase(entry); }
        else ++entry;
    }
}

JNIEXPORT jbyteArray JNICALL Java_com_aurora_music_playback_UsbDriverTestSupport_packetize(
        JNIEnv *env, jobject, jbyteArray pcm, jint rate, jint channels, jint bits, jint chunkFrames, jint wireFormat) {
    const int size = env->GetArrayLength(pcm);
    if (rate < 8000 || rate > 384000 || channels < 1 || channels > 2 || (bits != 16 && bits != 24 && bits != 32) ||
        chunkFrames < 1 || chunkFrames > 65536 || size > 1048576 || size % (channels * bits / 8) ||
        wireFormat < 0 || wireFormat > 2 || (wireFormat == 1 && bits < 24) || (wireFormat == 2 && bits != 32)) return nullptr;
    UsbAudioContext ctx; ctx.sampleRate = rate; ctx.channelCount = channels; ctx.bytesPerSample = bits / 8;
    ctx.wireFormat = wireFormat;
    ctx.bytesPerFrame = channels * ctx.bytesPerSample; ctx.maxPacketSize = 512;
    ctx.calibratedFpmf.store(rate / 8000.0); ctx.running.store(true);
    std::vector<uint8_t> input(size), output;
    env->GetByteArrayRegion(pcm, 0, size, reinterpret_cast<jbyte *>(input.data()));
    auto collect = [&output](UsbAudioContext *state, int) {
        output.insert(output.end(), state->residualBuffer, state->residualBuffer + state->plannedBytes);
        state->frameAccumulator = state->plannedAccumulator; state->plannedBytes = 0; state->residualBytes = 0;
        return true;
    };
    for (int offset = 0; offset < size;) {
        const int count = std::min(size - offset, chunkFrames * ctx.bytesPerFrame);
        appendStaged(&ctx, input.data() + offset, count, collect); offset += count;
    }
    if (!finishStaged(&ctx, collect) || ctx.lastError.load()) return nullptr;
    auto result = env->NewByteArray(output.size());
    if (result && !output.empty()) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<jbyte *>(output.data()));
    return result;
}
JNIEXPORT jlongArray JNICALL Java_com_aurora_music_playback_UsbDriverTestSupport_completeOutOfOrder(
        JNIEnv *env, jobject, jint errorMode) {
    UsbAudioContext ctx; ctx.bytesPerFrame = 8; ctx.running.store(true); ctx.urbsInFlight.store(2);
    for (int index : {0, 7}) {
        auto &slot = ctx.ring[index];
        slot.urb = static_cast<usbdevfs_urb *>(calloc(1, sizeof(usbdevfs_urb) + sizeof(usbdevfs_iso_packet_desc)));
        if (!slot.urb) { free(ctx.ring[0].urb); return nullptr; }
        slot.pending = true; slot.validFrames = index == 0 ? 8 : 3;
        slot.urb->number_of_packets = 1; slot.urb->iso_frame_desc[0].length = 64;
        slot.urb->iso_frame_desc[0].actual_length = 64;
    }
    if (errorMode == 1) ctx.ring[7].urb->iso_frame_desc[0].actual_length = 32;
    if (errorMode == 2) ctx.ring[7].urb->status = -ENODEV;
    if (errorMode == 3) { ctx.ring[7].urb->iso_frame_desc[0].status = -EXDEV; ctx.ring[7].urb->iso_frame_desc[0].actual_length = 0; }
    completeUrb(&ctx, ctx.ring[7].urb, false); completeUrb(&ctx, ctx.ring[0].urb, false);
    const jlong values[] = {ctx.completedFrames.load(), ctx.packetErrors.load(), ctx.lastError.load(), ctx.urbsInFlight.load()};
    free(ctx.ring[0].urb); free(ctx.ring[7].urb);
    auto result = env->NewLongArray(4); if (result) env->SetLongArrayRegion(result, 0, 4, values); return result;
}
JNIEXPORT jlongArray JNICALL Java_com_aurora_music_playback_UsbDriverTestSupport_flushLifecycle(JNIEnv *env, jobject) {
    UsbAudioContext ctx; ctx.alternateSetting = 1;
    const auto reset = [](UsbAudioContext *state) {
        return resetStreamingInterface(state, [] { return 1; }, [](int) { return 0; });
    };
    const jlong handle = reinterpret_cast<jlong>(&ctx);
    const bool started = Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStart(env, nullptr, handle);
    ctx.framesWritten.store(12); ctx.submittedFrames.store(12); ctx.completedFrames.store(12);
    const bool finished = finishUsbStream(&ctx);
    const bool drained = !ctx.running.load();
    flushUsbStreamWithReset(&ctx, reset);
    const bool restarted = ctx.running.load();
    const int64_t accepted = ctx.framesWritten.load(), submitted = ctx.submittedFrames.load(), completed = ctx.completedFrames.load();
    Java_com_decent_usbaudio_UsbAudioStream_nativeUsbAudioStop(env, nullptr, handle);
    flushUsbStreamWithReset(&ctx, reset);
    const bool stopped = !ctx.running.load();
    ctx.startRequested.store(true); ctx.lastError.store(EIO);
    flushUsbStreamWithReset(&ctx, reset);
    const jlong values[] = {started, finished, drained, restarted, accepted, submitted, completed, stopped, !ctx.running.load()};
    auto result = env->NewLongArray(9);
    if (result) env->SetLongArrayRegion(result, 0, 9, values);
    return result;
}
JNIEXPORT jlongArray JNICALL Java_com_aurora_music_playback_UsbDriverTestSupport_resetInterface(JNIEnv *env, jobject, jint errorMode) {
    UsbAudioContext ctx; ctx.alternateSetting = 3; ctx.sampleRate = 44100;
    if (errorMode == 3) ctx.urbsInFlight.store(1);
    if (errorMode == 5) ctx.feedbackInFlight = true;
    int active = 3, reads = 0;
    std::vector<int> calls;
    const bool success = resetStreamingInterface(&ctx,
        [&] { ++reads; return (errorMode == 4 && reads == 2 || errorMode == 6) ? 4 : active; },
        [&](int alt) {
            calls.push_back(alt);
            if (errorMode == 1 && alt == 0 || errorMode == 2 && alt == 3) return EIO;
            active = alt; return 0;
        });
    const jlong values[] = {success, ctx.lastError.load(), jlong(calls.size()), calls.empty() ? -1 : calls[0],
        calls.size() < 2 ? -1 : calls[1], reads, jlong(ctx.calibratedFpmf.load() * 8000)};
    auto result = env->NewLongArray(7);
    if (result) env->SetLongArrayRegion(result, 0, 7, values);
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_com_aurora_music_playback_UsbDriverTestSupport_convertFloat(
        JNIEnv *env, jobject, jfloatArray samples, jint validBits, jint containerBits) {
    const int count = env->GetArrayLength(samples);
    if (count > 65536 || (containerBits != 16 && containerBits != 24 && containerBits != 32) || validBits < 16 || validBits > containerBits) return nullptr;
    auto *source = env->GetFloatArrayElements(samples, nullptr); if (!source) return nullptr;
    std::vector<uint8_t> output(count * containerBits / 8);
    convertFloatPcm(source, output.data(), count, validBits, containerBits);
    env->ReleaseFloatArrayElements(samples, source, JNI_ABORT);
    auto result = env->NewByteArray(output.size());
    if (result && !output.empty()) env->SetByteArrayRegion(result, 0, output.size(), reinterpret_cast<jbyte *>(output.data()));
    return result;
}
}
