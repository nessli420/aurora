#pragma once
#include <alsa/asoundlib.h>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace aurora::alsa {

enum Encoding { S16 = 0, S24 = 1, S24In32 = 2, S32 = 3, F32 = 4, EncodingCount = 5 };

struct DeviceInfo {
    std::string id;
    std::string card;
    std::string name;
    std::string driver;
    std::string plug;
};

int listDevices(std::vector<DeviceInfo>& devices);
bool chooseFormat(snd_pcm_t* pcm, const snd_pcm_hw_params_t* space, int encoding, int channels, int rate, snd_pcm_format_t& format);
void repack24In32(uint8_t* data, size_t bytes);
int capabilities(const std::string& device, const std::vector<int>& rates, int channels, std::vector<int>& masks);

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

    void peek(uint8_t* target, size_t size) const {
        const uint64_t at = read_.load(std::memory_order_relaxed);
        const size_t offset = static_cast<size_t>(at % capacity_), first = std::min(size, capacity_ - offset);
        std::memcpy(target, data_.get() + offset, first);
        if (size > first) std::memcpy(target + first, data_.get(), size - first);
    }

    void skip(size_t size) { read_.store(read_.load(std::memory_order_relaxed) + size, std::memory_order_release); }

    void drop() { read_.store(write_.load(std::memory_order_acquire), std::memory_order_release); }

private:
    std::unique_ptr<uint8_t[]> data_;
    size_t capacity_;
    std::atomic<uint64_t> write_{0};
    std::atomic<uint64_t> read_{0};
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

class Stream {
public:
    using Copy = std::function<void(uint8_t* target, size_t offset, size_t count)>;

    static int open(const std::string& device, int rate, int channels, int encoding, int bufferMs, const std::vector<int>& rates,
        std::shared_ptr<Stream>& stream);
    ~Stream();
    Stream(const Stream&) = delete;
    Stream& operator=(const Stream&) = delete;

    const std::string& device() const { return device_; }
    const std::vector<int>& capabilities() const { return capabilities_; }
    int write(size_t size, int timeoutMs, const Copy& copy);
    int resume();
    int pause();
    int flush();
    void close();
    StreamStatus status();

private:
    Stream() = default;
    int configure(int rate, int channels, int encoding, int bufferMs);
    void run();
    void pump();
    int prefillAndStart();
    void transfer(snd_pcm_uframes_t frames);
    bool recover(int error);
    void fail(int error);

    std::string device_;
    std::vector<int> capabilities_;
    snd_pcm_t* pcm_ = nullptr;
    int rate_ = 0;
    size_t frameBytes_ = 0;
    bool repack24_ = false;
    bool canPause_ = false;
    snd_pcm_uframes_t bufferFrames_ = 0;
    snd_pcm_uframes_t periodFrames_ = 0;
    std::unique_ptr<ByteRing> ring_;
    std::vector<uint8_t> scratch_;
    std::thread thread_;

    std::mutex lock_;
    std::condition_variable wake_;
    std::mutex spaceLock_;
    std::condition_variable space_;
    bool playing_ = false;
    bool pausedInHardware_ = false;
    bool needStart_ = true;
    uint64_t written_ = 0;
    std::atomic<int64_t> underruns_{0};
    std::atomic<int> lastError_{0};
    std::atomic<bool> invalidated_{false};
    std::atomic<bool> closing_{false};
};

}
