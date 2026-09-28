#pragma once
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <wrl/client.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <functional>
#include <future>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace aurora::audio {

using Microsoft::WRL::ComPtr;

struct DeviceInfo {
    std::wstring id;
    std::wstring name;
    int formFactor = 0;
};

using DeviceCallback = std::function<void(int kind, std::wstring id, int64_t value)>;

bool makeFormat(int rate, int channels, int encoding, WAVEFORMATEXTENSIBLE& format);
HRESULT findDevice(const std::wstring& id, IMMDevice** device);
HRESULT listDevices(std::vector<DeviceInfo>& devices);
HRESULT defaultDevice(std::wstring& id);
HRESULT mixFormat(const std::wstring& id, WAVEFORMATEXTENSIBLE& format);
HRESULT probeFormat(const std::wstring& id, bool exclusive, int rate, int channels, int encoding);
bool isRenderEndpoint(IMMDeviceEnumerator* enumerator, const std::wstring& id);
HRESULT watchDevices(DeviceCallback callback, ComPtr<IMMDeviceEnumerator>& enumerator, ComPtr<IMMNotificationClient>& client);

class ByteRing {
public:
    explicit ByteRing(size_t capacity) : data_(new uint8_t[capacity]), capacity_(capacity) {}

    size_t capacity() const { return capacity_; }
    size_t readable() const { return static_cast<size_t>(write_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire)); }

    template <class Copy>
    size_t write(size_t size, Copy&& copy) {
        const uint64_t at = write_.load(std::memory_order_relaxed);
        const size_t count = std::min(size, capacity_ - static_cast<size_t>(at - read_.load(std::memory_order_acquire)));
        if (!count) return 0;
        const size_t offset = static_cast<size_t>(at % capacity_), first = std::min(count, capacity_ - offset);
        copy(data_.get() + offset, size_t{0}, first);
        if (count > first) copy(data_.get(), first, count - first);
        write_.store(at + count, std::memory_order_release);
        return count;
    }

    void read(uint8_t* target, size_t size) {
        const uint64_t at = read_.load(std::memory_order_relaxed);
        const size_t offset = static_cast<size_t>(at % capacity_), first = std::min(size, capacity_ - offset);
        std::memcpy(target, data_.get() + offset, first);
        if (size > first) std::memcpy(target + first, data_.get(), size - first);
        read_.store(at + size, std::memory_order_release);
    }

    void drop() { read_.store(write_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::unique_ptr<uint8_t[]> data_;
    size_t capacity_;
    std::atomic<uint64_t> write_{0};
    std::atomic<uint64_t> read_{0};
};

struct StreamConfig {
    std::wstring deviceId;
    bool exclusive = false;
    int rate = 0;
    int channels = 0;
    int encoding = 0;
    int bufferMs = 0;
};

struct StreamStatus {
    int64_t played = 0;
    int64_t buffered = 0;
    int64_t underruns = 0;
    int64_t flags = 0;
    int64_t lastError = 0;
    int64_t latencyUs = 0;
    int64_t positionNanos = 0;
    int64_t bufferFrames = 0;
    int64_t ringFrames = 0;
};

class Stream : public std::enable_shared_from_this<Stream> {
public:
    enum class Command { Resume = 1, Pause, Flush, Drain, Close };
    using Copy = std::function<void(uint8_t* target, size_t offset, size_t count)>;

    static HRESULT open(const StreamConfig& config, std::function<void(const std::wstring&)> onInvalidated,
        std::shared_ptr<Stream>& stream);
    ~Stream();
    Stream(const Stream&) = delete;
    Stream& operator=(const Stream&) = delete;

    const std::wstring& deviceId() const { return deviceId_; }
    int write(size_t size, int timeoutMs, const Copy& copy);
    HRESULT command(Command command);
    int drain(int timeoutMs);
    void close();
    StreamStatus status() const;

private:
    struct Gap {
        uint64_t start;
        uint64_t length;
    };

    explicit Stream(const StreamConfig& config);
    HRESULT activate();
    HRESULT initialize();
    void run(std::promise<HRESULT>* ready);
    void loop();
    HRESULT execute(Command command);
    HRESULT start();
    bool fill(bool prefill);
    void fail(HRESULT hr);
    void publish();
    void addGap(uint64_t start, uint64_t length);
    void stopDrain(bool ended);

    StreamConfig config_;
    std::wstring deviceId_;
    WAVEFORMATEXTENSIBLE format_{};
    uint32_t frameBytes_ = 0;
    ComPtr<IMMDevice> device_;
    ComPtr<IAudioClient> client_;
    ComPtr<IAudioRenderClient> render_;
    ComPtr<IAudioClock> clock_;
    uint64_t clockFrequency_ = 0;
    UINT32 bufferFrames_ = 0;
    REFERENCE_TIME latency_ = 0;
    HANDLE audioEvent_ = nullptr;
    HANDLE commandEvent_ = nullptr;
    HANDLE ackEvent_ = nullptr;
    HANDLE spaceEvent_ = nullptr;
    HANDLE drainEvent_ = nullptr;
    std::thread thread_;
    std::unique_ptr<ByteRing> ring_;
    std::function<void(const std::wstring&)> onInvalidated_;

    std::mutex commandMutex_;
    std::atomic<int> pending_{0};
    std::atomic<uint32_t> commandSeq_{0};
    std::atomic<uint32_t> ackSeq_{0};
    std::atomic<HRESULT> commandResult_{S_OK};
    bool finished_ = false;

    bool playing_ = false;
    bool draining_ = false;
    bool ended_ = false;
    bool prefill_ = true;
    bool starving_ = false;
    uint64_t deviceFrames_ = 0;
    uint64_t contentEnd_ = 0;
    uint64_t passedSilence_ = 0;
    std::array<Gap, 64> gaps_{};
    size_t gapHead_ = 0;
    size_t gapCount_ = 0;

    std::atomic<uint32_t> sequence_{0};
    std::atomic<uint64_t> played_{0};
    std::atomic<uint64_t> queued_{0};
    std::atomic<uint64_t> qpc_{0};
    std::atomic<int64_t> underruns_{0};
    std::atomic<int> flags_{0};
    std::atomic<HRESULT> lastError_{S_OK};
    std::atomic<bool> closing_{false};
    std::atomic<bool> invalidated_{false};
};

}
