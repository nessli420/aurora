#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
#include <linux/usbdevice_fs.h>

#define USB_AUDIO_PACKETS_PER_URB 8
#define USB_AUDIO_NUM_URBS 80
#define USB_AUDIO_URB_BUFFER_SIZE (3072 * USB_AUDIO_PACKETS_PER_URB)
#define USB_AUDIO_CONVERSION_BUFFER_SIZE 1048576

struct UrbSlot {
    usbdevfs_urb *urb = nullptr;
    uint8_t *buffer = nullptr;
    bool pending = false;
    int validFrames = 0;
};

struct UsbAudioContext {
    int fd = -1;
    int ownerFd = -1;
    int interfaceId = 0;
    int alternateSetting = 0;
    int endpointOut = 0;
    int endpointFeedback = 0;
    int32_t sampleRate = 0;
    int32_t channelCount = 0;
    int32_t bitDepth = 0;
    int32_t validBits = 0;
    int wireFormat = 0;
    int32_t bytesPerSample = 0;
    int32_t bytesPerFrame = 0;
    int32_t maxPacketSize = 0;
    std::mutex ioMutex;
    std::atomic<bool> running{false};
    std::atomic<bool> startRequested{false};
    std::atomic<int64_t> framesWritten{0};
    std::atomic<int64_t> submittedFrames{0};
    std::atomic<int64_t> completedFrames{0};
    std::atomic<int64_t> packetErrors{0};
    std::atomic<int64_t> submitErrors{0};
    std::atomic<int64_t> reapTimeouts{0};
    std::atomic<int64_t> starvationEvents{0};
    std::atomic<int64_t> feedbackPackets{0};
    std::atomic<int64_t> invalidFeedbackPackets{0};
    std::atomic<int> lastError{0};
    std::atomic<int> urbsInFlight{0};
    std::atomic<double> calibratedFpmf{0};
    uint8_t *transferBuffer = nullptr;
    UrbSlot ring[USB_AUDIO_NUM_URBS];
    int submitIdx = 0;
    double frameAccumulator = 0;
    uint8_t residualBuffer[USB_AUDIO_URB_BUFFER_SIZE]{};
    int residualBytes = 0;
    int plannedBytes = 0;
    int plannedSizes[USB_AUDIO_PACKETS_PER_URB]{};
    double plannedAccumulator = 0;
    usbdevfs_urb *feedbackUrb = nullptr;
    uint8_t feedbackBuffer[4]{};
    bool feedbackInFlight = false;
    bool startedAudio = false;
};

void submitPcmToUrbs(UsbAudioContext *ctx, const uint8_t *pcmData, int totalBytes);
bool finishUsbStream(UsbAudioContext *ctx);
void flushUsbStream(UsbAudioContext *ctx);
void padInt16ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);
void padInt24ToInt32(const uint8_t *src, uint8_t *dst, int numSamples);
void shiftInt32From24(const uint8_t *src, uint8_t *dst, int numSamples);
void convertFloatPcm(const float *src, uint8_t *dst, int count, int validBits, int containerBits);
