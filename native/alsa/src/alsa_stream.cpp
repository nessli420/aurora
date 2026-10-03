#include "alsa_output.h"
#include <pthread.h>
#include <sched.h>
#include <cerrno>
#include <chrono>
#include <ctime>

namespace aurora::alsa {
namespace {

enum Flag : int {
    Playing = 1,
    Invalidated = 8,
    Exclusive = 16,
};

size_t sampleBytes(int encoding) {
    switch (encoding) {
    case S16: return 2;
    case S24: return 3;
    default: return 4;
    }
}

bool isInvalidation(int error) {
    return error == -ENODEV || error == -ENXIO || error == -EBADFD || error == -EIO || error == -ESHUTDOWN;
}

int64_t monotonicNanos() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
}

}

void repack24In32(uint8_t* data, size_t bytes) {
    for (size_t at = 0; at + 4 <= bytes; at += 4) {
        uint8_t* sample = data + at;
        sample[0] = sample[1];
        sample[1] = sample[2];
        sample[2] = sample[3];
        sample[3] = (sample[2] & 0x80) ? 0xFF : 0x00;
    }
}

int Stream::open(const std::string& device, int rate, int channels, int encoding, int bufferMs, const std::vector<int>& rates,
    std::shared_ptr<Stream>& stream) {
    if (encoding < 0 || encoding >= EncodingCount || channels <= 0 || rate <= 0) return -EINVAL;
    std::shared_ptr<Stream> created(new Stream());
    created->device_ = device;
    int error = snd_pcm_open(&created->pcm_, device.c_str(), SND_PCM_STREAM_PLAYBACK, SND_PCM_NONBLOCK);
    if (error < 0) {
        created->pcm_ = nullptr;
        return error;
    }
    snd_pcm_hw_params_t* space;
    snd_pcm_hw_params_alloca(&space);
    if ((error = snd_pcm_hw_params_any(created->pcm_, space)) < 0) return error;
    created->capabilities_.assign(rates.size(), 0);
    for (size_t i = 0; i < rates.size(); ++i) {
        for (int candidate = 0; candidate < EncodingCount; ++candidate) {
            snd_pcm_format_t format;
            if (chooseFormat(created->pcm_, space, candidate, channels, rates[i], format)) created->capabilities_[i] |= 1 << candidate;
        }
    }
    if ((error = created->configure(rate, channels, encoding, bufferMs)) < 0) return error;
    created->thread_ = std::thread(&Stream::run, created.get());
    stream = std::move(created);
    return 0;
}

Stream::~Stream() {
    close();
}

int Stream::configure(int rate, int channels, int encoding, int bufferMs) {
    snd_pcm_hw_params_t* params;
    snd_pcm_hw_params_alloca(&params);
    int error = snd_pcm_hw_params_any(pcm_, params);
    if (error < 0) return error;
    snd_pcm_hw_params_set_rate_resample(pcm_, params, 0);
    snd_pcm_format_t format;
    if (!chooseFormat(pcm_, params, encoding, channels, rate, format)) return -EINVAL;
    if ((error = snd_pcm_hw_params_set_access(pcm_, params, SND_PCM_ACCESS_RW_INTERLEAVED)) < 0) return error;
    if ((error = snd_pcm_hw_params_set_format(pcm_, params, format)) < 0) return error;
    if ((error = snd_pcm_hw_params_set_channels(pcm_, params, static_cast<unsigned>(channels))) < 0) return error;
    if ((error = snd_pcm_hw_params_set_rate(pcm_, params, static_cast<unsigned>(rate), 0)) < 0) return error;
    unsigned bufferUs = static_cast<unsigned>(std::clamp(bufferMs / 2, 40, 200)) * 1000;
    unsigned periodUs = bufferUs / 4;
    int dir = 0;
    snd_pcm_hw_params_set_buffer_time_near(pcm_, params, &bufferUs, &dir);
    dir = 0;
    snd_pcm_hw_params_set_period_time_near(pcm_, params, &periodUs, &dir);
    if ((error = snd_pcm_hw_params(pcm_, params)) < 0) return error;
    snd_pcm_hw_params_get_buffer_size(params, &bufferFrames_);
    snd_pcm_hw_params_get_period_size(params, &periodFrames_, &dir);
    canPause_ = snd_pcm_hw_params_can_pause(params) == 1;

    snd_pcm_sw_params_t* sw;
    snd_pcm_sw_params_alloca(&sw);
    if ((error = snd_pcm_sw_params_current(pcm_, sw)) < 0) return error;
    snd_pcm_uframes_t boundary = 0;
    snd_pcm_sw_params_get_boundary(sw, &boundary);
    snd_pcm_sw_params_set_start_threshold(pcm_, sw, boundary);
    snd_pcm_sw_params_set_avail_min(pcm_, sw, periodFrames_);
    if ((error = snd_pcm_sw_params(pcm_, sw)) < 0) return error;

    rate_ = rate;
    frameBytes_ = sampleBytes(encoding) * static_cast<size_t>(channels);
    repack24_ = encoding == S24In32 && format == SND_PCM_FORMAT_S24_LE;
    const uint64_t ringFrames = std::max<uint64_t>(static_cast<uint64_t>(rate) * static_cast<uint64_t>(bufferMs) / 1000, 2ull * bufferFrames_);
    try {
        ring_ = std::make_unique<ByteRing>(static_cast<size_t>(ringFrames) * frameBytes_);
        scratch_.resize(static_cast<size_t>(bufferFrames_) * frameBytes_);
    } catch (const std::bad_alloc&) {
        return -ENOMEM;
    }
    return 0;
}

void Stream::run() {
    sched_param priority{};
    priority.sched_priority = std::max(1, sched_get_priority_min(SCHED_FIFO) + 9);
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &priority);
    pthread_setname_np(pthread_self(), "aurora-alsa");
    const auto nap = std::chrono::microseconds(std::clamp<int64_t>(
        static_cast<int64_t>(periodFrames_) * 1'000'000 / std::max(rate_, 1) / 2, 1'000, 5'000));
    std::unique_lock lock(lock_);
    while (!closing_) {
        if (!playing_ || invalidated_) {
            wake_.wait_for(lock, std::chrono::milliseconds(500));
            if (!closing_ && !invalidated_ && snd_pcm_state(pcm_) == SND_PCM_STATE_DISCONNECTED) fail(-ENODEV);
            continue;
        }
        pump();
        wake_.wait_for(lock, nap);
    }
}

void Stream::pump() {
    snd_pcm_sframes_t avail = snd_pcm_avail_update(pcm_);
    if (avail < 0) {
        if (!recover(static_cast<int>(avail))) return;
        avail = snd_pcm_avail_update(pcm_);
        if (avail < 0) return;
    }
    const size_t content = ring_->readable() / frameBytes_;
    const auto frames = static_cast<snd_pcm_uframes_t>(std::min<size_t>({static_cast<size_t>(avail), content, static_cast<size_t>(bufferFrames_)}));
    if (frames) transfer(frames);
    if (!needStart_ || invalidated_) return;
    const snd_pcm_sframes_t after = snd_pcm_avail_update(pcm_);
    if (after < 0) return;
    const auto queued = bufferFrames_ - std::min<snd_pcm_uframes_t>(static_cast<snd_pcm_uframes_t>(after), bufferFrames_);
    if (!queued) return;
    if (queued >= std::min<snd_pcm_uframes_t>(2 * periodFrames_, bufferFrames_) || ring_->readable() == 0) {
        const int error = snd_pcm_start(pcm_);
        if (error < 0) {
            recover(error);
            return;
        }
        needStart_ = false;
    }
}

int Stream::prefillAndStart() {
    needStart_ = true;
    snd_pcm_sframes_t avail = snd_pcm_avail_update(pcm_);
    if (avail < 0) return static_cast<int>(avail);
    const size_t content = ring_->readable() / frameBytes_;
    const auto frames = static_cast<snd_pcm_uframes_t>(std::min<size_t>({static_cast<size_t>(avail), content, static_cast<size_t>(bufferFrames_)}));
    if (frames) transfer(frames);
    if (invalidated_) return lastError_.load();
    if (!frames && snd_pcm_avail_update(pcm_) >= static_cast<snd_pcm_sframes_t>(bufferFrames_)) return 0;
    const int error = snd_pcm_start(pcm_);
    if (error < 0) return error;
    needStart_ = false;
    return 0;
}

void Stream::transfer(snd_pcm_uframes_t frames) {
    const size_t bytes = static_cast<size_t>(frames) * frameBytes_;
    ring_->peek(scratch_.data(), bytes);
    if (repack24_) repack24In32(scratch_.data(), bytes);
    snd_pcm_sframes_t sent = snd_pcm_writei(pcm_, scratch_.data(), frames);
    if (sent == -EAGAIN) return;
    if (sent < 0) {
        recover(static_cast<int>(sent));
        return;
    }
    ring_->skip(static_cast<size_t>(sent) * frameBytes_);
    written_ += static_cast<uint64_t>(sent);
    std::lock_guard space(spaceLock_);
    space_.notify_all();
}

bool Stream::recover(int error) {
    if (error == -EPIPE && playing_) underruns_.fetch_add(1, std::memory_order_relaxed);
    if (error == -EPIPE || error == -ESTRPIPE || error == -EINTR) {
        const int result = snd_pcm_recover(pcm_, error, 1);
        if (result < 0) {
            fail(result);
            return false;
        }
        needStart_ = true;
        return true;
    }
    fail(error);
    return false;
}

void Stream::fail(int error) {
    lastError_.store(error);
    if (!isInvalidation(error) || invalidated_.exchange(true)) return;
    playing_ = false;
    std::lock_guard space(spaceLock_);
    space_.notify_all();
}

int Stream::write(size_t size, int timeoutMs, const Copy& copy) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(timeoutMs, 0));
    size_t done = 0;
    while (done < size) {
        if (invalidated_) return done ? static_cast<int>(done) : -ENODEV;
        if (closing_) return done ? static_cast<int>(done) : -ESHUTDOWN;
        const size_t base = done;
        done += ring_->write(size - done, [&](uint8_t* target, size_t offset, size_t count) { copy(target, base + offset, count); });
        if (done == size) break;
        if (done != base) continue;
        if (timeoutMs >= 0 && std::chrono::steady_clock::now() >= deadline) break;
        wake_.notify_one();
        std::unique_lock space(spaceLock_);
        space_.wait_for(space, std::chrono::milliseconds(5));
    }
    if (done) wake_.notify_one();
    return static_cast<int>(done);
}

int Stream::resume() {
    std::lock_guard lock(lock_);
    if (invalidated_) return -ENODEV;
    if (playing_) return 0;
    if (pausedInHardware_) {
        pausedInHardware_ = false;
        if (snd_pcm_pause(pcm_, 0) >= 0) {
            playing_ = true;
            wake_.notify_one();
            return 0;
        }
        snd_pcm_drop(pcm_);
        const int error = snd_pcm_prepare(pcm_);
        if (error < 0) {
            fail(error);
            return error;
        }
    }
    playing_ = true;
    const int error = prefillAndStart();
    if (error < 0 && !recover(error)) {
        playing_ = false;
        return lastError_.load();
    }
    wake_.notify_one();
    return 0;
}

int Stream::pause() {
    std::lock_guard lock(lock_);
    if (invalidated_) return -ENODEV;
    if (!playing_) return 0;
    playing_ = false;
    if (canPause_ && snd_pcm_state(pcm_) == SND_PCM_STATE_RUNNING && snd_pcm_pause(pcm_, 1) >= 0) {
        pausedInHardware_ = true;
        return 0;
    }
    snd_pcm_sframes_t delay = 0;
    if (snd_pcm_delay(pcm_, &delay) == 0 && delay > 0) written_ -= std::min<uint64_t>(static_cast<uint64_t>(delay), written_);
    snd_pcm_drop(pcm_);
    const int error = snd_pcm_prepare(pcm_);
    needStart_ = true;
    if (error < 0) {
        fail(error);
        return error;
    }
    return 0;
}

int Stream::flush() {
    std::lock_guard lock(lock_);
    if (invalidated_) return -ENODEV;
    playing_ = pausedInHardware_ = false;
    needStart_ = true;
    snd_pcm_drop(pcm_);
    const int error = snd_pcm_prepare(pcm_);
    ring_->drop();
    written_ = 0;
    {
        std::lock_guard space(spaceLock_);
        space_.notify_all();
    }
    if (error < 0) {
        fail(error);
        return error;
    }
    return 0;
}

void Stream::close() {
    if (closing_.exchange(true)) return;
    wake_.notify_all();
    {
        std::lock_guard space(spaceLock_);
        space_.notify_all();
    }
    if (thread_.joinable()) thread_.join();
    std::lock_guard lock(lock_);
    if (pcm_) {
        snd_pcm_drop(pcm_);
        snd_pcm_close(pcm_);
        pcm_ = nullptr;
    }
}

StreamStatus Stream::status() {
    std::lock_guard lock(lock_);
    StreamStatus status;
    snd_pcm_sframes_t delay = 0;
    if (pcm_ && !invalidated_) {
        const snd_pcm_state_t state = snd_pcm_state(pcm_);
        if (state == SND_PCM_STATE_DISCONNECTED) {
            fail(-ENODEV);
        } else if (state == SND_PCM_STATE_RUNNING || state == SND_PCM_STATE_PAUSED || state == SND_PCM_STATE_PREPARED ||
            state == SND_PCM_STATE_DRAINING) {
            const int error = snd_pcm_delay(pcm_, &delay);
            if (error < 0) {
                delay = 0;
                if (isInvalidation(error)) fail(error);
            }
        }
    }
    const auto queued = static_cast<uint64_t>(std::clamp<int64_t>(delay, 0, static_cast<int64_t>(written_)));
    status.played = static_cast<int64_t>(written_ - queued);
    status.buffered = static_cast<int64_t>(ring_->readable() / frameBytes_ + queued);
    status.underruns = underruns_.load(std::memory_order_relaxed);
    status.flags = Exclusive | (playing_ ? Playing : 0) | (invalidated_ ? Invalidated : 0);
    status.lastError = lastError_.load();
    status.latencyUs = static_cast<int64_t>(bufferFrames_) * 1'000'000 / std::max(rate_, 1);
    status.positionNanos = monotonicNanos();
    status.bufferFrames = static_cast<int64_t>(bufferFrames_);
    status.ringFrames = static_cast<int64_t>(ring_->capacity() / frameBytes_);
    return status;
}

}
