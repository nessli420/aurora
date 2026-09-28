#include "audio/wasapi.h"
#include <avrt.h>

namespace aurora::audio {
namespace {

constexpr REFERENCE_TIME kSharedBuffer = 500000;
constexpr REFERENCE_TIME kMinimumPeriod = 100000;
constexpr DWORD kCommandTimeout = 5000;

enum Flag : int {
    Playing = 1,
    Draining = 2,
    Ended = 4,
    Invalidated = 8,
    Exclusive = 16,
};

bool isInvalidation(HRESULT hr) {
    return hr == AUDCLNT_E_DEVICE_INVALIDATED || hr == AUDCLNT_E_RESOURCES_INVALIDATED || hr == AUDCLNT_E_SERVICE_NOT_RUNNING;
}

}

Stream::Stream(const StreamConfig& config)
    : config_(config),
      audioEvent_(CreateEventW(nullptr, FALSE, FALSE, nullptr)),
      commandEvent_(CreateEventW(nullptr, FALSE, FALSE, nullptr)),
      ackEvent_(CreateEventW(nullptr, FALSE, FALSE, nullptr)),
      spaceEvent_(CreateEventW(nullptr, FALSE, FALSE, nullptr)),
      drainEvent_(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}

Stream::~Stream() {
    if (thread_.joinable()) thread_.detach();
    for (HANDLE event : {audioEvent_, commandEvent_, ackEvent_, spaceEvent_, drainEvent_}) {
        if (event) CloseHandle(event);
    }
}

HRESULT Stream::open(const StreamConfig& config, std::function<void(const std::wstring&)> onInvalidated,
    std::shared_ptr<Stream>& stream) {
    std::shared_ptr<Stream> created(new Stream(config));
    if (!makeFormat(config.rate, config.channels, config.encoding, created->format_)) return E_INVALIDARG;
    if (!created->audioEvent_ || !created->commandEvent_ || !created->ackEvent_ || !created->spaceEvent_ || !created->drainEvent_) {
        return HRESULT_FROM_WIN32(GetLastError());
    }
    created->frameBytes_ = created->format_.Format.nBlockAlign;
    created->onInvalidated_ = std::move(onInvalidated);
    std::promise<HRESULT> ready;
    auto result = ready.get_future();
    created->thread_ = std::thread(&Stream::run, created.get(), &ready);
    const HRESULT hr = result.get();
    if (FAILED(hr)) {
        created->thread_.join();
        return hr;
    }
    stream = std::move(created);
    return S_OK;
}

HRESULT Stream::activate() {
    HRESULT hr = device_->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, &client_);
    if (FAILED(hr)) return hr;
    ComPtr<IAudioClient2> client2;
    if (SUCCEEDED(client_.As(&client2))) {
        AudioClientProperties properties{};
        properties.cbSize = sizeof(properties);
        properties.eCategory = AudioCategory_Media;
        client2->SetClientProperties(&properties);
    }
    return S_OK;
}

HRESULT Stream::initialize() {
    HRESULT hr = findDevice(config_.deviceId, &device_);
    if (FAILED(hr)) return hr;
    LPWSTR id = nullptr;
    if (SUCCEEDED(device_->GetId(&id))) {
        deviceId_ = id;
        CoTaskMemFree(id);
    }
    if (FAILED(hr = activate())) return hr;
    if (config_.exclusive) {
        REFERENCE_TIME defaultPeriod = 0, minimumPeriod = 0;
        if (FAILED(hr = client_->GetDevicePeriod(&defaultPeriod, &minimumPeriod))) return hr;
        REFERENCE_TIME period = std::max(defaultPeriod, kMinimumPeriod);
        hr = client_->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, &format_.Format, nullptr);
        if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED) {
            UINT32 frames = 0;
            if (FAILED(hr = client_->GetBufferSize(&frames))) return hr;
            period = static_cast<REFERENCE_TIME>(10000.0 * 1000 / format_.Format.nSamplesPerSec * frames + 0.5);
            client_.Reset();
            if (FAILED(hr = activate())) return hr;
            hr = client_->Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, &format_.Format, nullptr);
        }
    } else {
        hr = client_->Initialize(AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            kSharedBuffer, 0, &format_.Format, nullptr);
    }
    if (FAILED(hr)) return hr;
    if (FAILED(hr = client_->SetEventHandle(audioEvent_))) return hr;
    if (FAILED(hr = client_->GetBufferSize(&bufferFrames_))) return hr;
    if (FAILED(hr = client_->GetService(IID_PPV_ARGS(&render_)))) return hr;
    if (FAILED(hr = client_->GetService(IID_PPV_ARGS(&clock_)))) return hr;
    if (FAILED(hr = clock_->GetFrequency(&clockFrequency_))) return hr;
    client_->GetStreamLatency(&latency_);
    const uint64_t frames = std::max<uint64_t>(static_cast<uint64_t>(config_.rate) * config_.bufferMs / 1000, 2ull * bufferFrames_);
    try {
        ring_ = std::make_unique<ByteRing>(static_cast<size_t>(frames * frameBytes_));
    } catch (const std::bad_alloc&) {
        return E_OUTOFMEMORY;
    }
    flags_ = config_.exclusive ? Exclusive : 0;
    return S_OK;
}

void Stream::run(std::promise<HRESULT>* ready) {
    const HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const HRESULT hr = FAILED(com) ? com : initialize();
    ready->set_value(hr);
    if (SUCCEEDED(hr)) {
        DWORD index = 0;
        const HANDLE task = AvSetMmThreadCharacteristicsW(L"Pro Audio", &index);
        if (task) AvSetMmThreadPriority(task, AVRT_PRIORITY_HIGH);
        loop();
        if (playing_) client_->Stop();
        if (task) AvRevertMmThreadCharacteristics(task);
    }
    clock_.Reset();
    render_.Reset();
    client_.Reset();
    device_.Reset();
    if (SUCCEEDED(com)) CoUninitialize();
}

void Stream::loop() {
    const HANDLE handles[] = {commandEvent_, audioEvent_};
    uint32_t handled = 0;
    for (;;) {
        const DWORD wait = WaitForMultipleObjects(2, handles, FALSE, playing_ ? 200 : INFINITE);
        const uint32_t seq = commandSeq_.load(std::memory_order_acquire);
        if (seq != handled) {
            const auto command = static_cast<Command>(pending_.load(std::memory_order_acquire));
            const HRESULT hr = execute(command);
            if (command != Command::Close) publish();
            handled = seq;
            commandResult_.store(hr);
            ackSeq_.store(seq, std::memory_order_release);
            SetEvent(ackEvent_);
            if (command == Command::Close) return;
            continue;
        }
        if (!playing_ || invalidated_) continue;
        if (wait == WAIT_OBJECT_0 + 1 || !config_.exclusive) {
            fill(false);
        } else {
            UINT32 padding = 0;
            const HRESULT hr = client_->GetCurrentPadding(&padding);
            if (FAILED(hr)) fail(hr);
        }
        if (!invalidated_) publish();
    }
}

HRESULT Stream::execute(Command command) {
    if (command == Command::Close) return S_OK;
    if (invalidated_) return AUDCLNT_E_DEVICE_INVALIDATED;
    switch (command) {
    case Command::Resume:
        ended_ = false;
        ResetEvent(drainEvent_);
        return start();
    case Command::Pause:
        if (draining_) stopDrain(false);
        if (playing_) {
            const HRESULT hr = client_->Stop();
            if (FAILED(hr)) {
                fail(hr);
                return hr;
            }
            playing_ = false;
        }
        return S_OK;
    case Command::Flush: {
        if (playing_) client_->Stop();
        playing_ = draining_ = ended_ = starving_ = false;
        prefill_ = true;
        const HRESULT hr = client_->Reset();
        ring_->drop();
        deviceFrames_ = contentEnd_ = passedSilence_ = 0;
        gapHead_ = gapCount_ = 0;
        ResetEvent(drainEvent_);
        SetEvent(spaceEvent_);
        if (FAILED(hr)) fail(hr);
        return hr;
    }
    case Command::Drain:
        draining_ = true;
        ended_ = false;
        ResetEvent(drainEvent_);
        return start();
    default:
        return E_INVALIDARG;
    }
}

HRESULT Stream::start() {
    if (playing_) return S_OK;
    if (prefill_ && !fill(true)) return lastError_.load();
    const HRESULT hr = client_->Start();
    if (FAILED(hr)) {
        fail(hr);
        return hr;
    }
    playing_ = true;
    prefill_ = false;
    starving_ = false;
    return S_OK;
}

bool Stream::fill(bool prefill) {
    UINT32 frames = bufferFrames_;
    HRESULT hr = S_OK;
    if (!config_.exclusive) {
        UINT32 padding = 0;
        if (FAILED(hr = client_->GetCurrentPadding(&padding))) {
            fail(hr);
            return false;
        }
        frames -= std::min(padding, frames);
    }
    if (!frames) return true;
    BYTE* data = nullptr;
    if (FAILED(hr = render_->GetBuffer(frames, &data))) {
        fail(hr);
        return false;
    }
    const auto content = static_cast<UINT32>(std::min<size_t>(ring_->readable() / frameBytes_, frames));
    if (content) ring_->read(data, static_cast<size_t>(content) * frameBytes_);
    if (content < frames) {
        std::memset(data + static_cast<size_t>(content) * frameBytes_, 0, static_cast<size_t>(frames - content) * frameBytes_);
        addGap(deviceFrames_ + content, frames - content);
        if (!prefill && !draining_) {
            if (!starving_) underruns_.fetch_add(1, std::memory_order_relaxed);
            starving_ = true;
        }
    } else {
        starving_ = false;
    }
    if (FAILED(hr = render_->ReleaseBuffer(frames, 0))) {
        fail(hr);
        return false;
    }
    if (content) {
        contentEnd_ = deviceFrames_ + content;
        SetEvent(spaceEvent_);
    }
    deviceFrames_ += frames;
    return true;
}

void Stream::addGap(uint64_t start, uint64_t length) {
    if (gapCount_) {
        Gap& last = gaps_[(gapHead_ + gapCount_ - 1) % gaps_.size()];
        if (last.start + last.length == start || gapCount_ == gaps_.size()) {
            last.length += length;
            return;
        }
    }
    gaps_[(gapHead_ + gapCount_) % gaps_.size()] = {start, length};
    ++gapCount_;
}

void Stream::stopDrain(bool ended) {
    draining_ = false;
    ended_ = ended;
    SetEvent(drainEvent_);
}

void Stream::fail(HRESULT hr) {
    lastError_.store(hr);
    if (!isInvalidation(hr) || invalidated_.exchange(true)) return;
    playing_ = draining_ = false;
    flags_.fetch_or(Invalidated);
    flags_.fetch_and(~(Playing | Draining));
    SetEvent(spaceEvent_);
    SetEvent(drainEvent_);
    if (onInvalidated_) onInvalidated_(deviceId_);
}

void Stream::publish() {
    UINT64 position = 0, qpc = 0;
    uint64_t frames = deviceFrames_ - queued_.load(std::memory_order_relaxed);
    if (clockFrequency_ && SUCCEEDED(clock_->GetPosition(&position, &qpc))) {
        const uint64_t rate = format_.Format.nSamplesPerSec;
        frames = std::min<uint64_t>(position / clockFrequency_ * rate + position % clockFrequency_ * rate / clockFrequency_, deviceFrames_);
        qpc *= 100;
    } else {
        qpc = qpc_.load(std::memory_order_relaxed);
    }
    while (gapCount_ && gaps_[gapHead_].start + gaps_[gapHead_].length <= frames) {
        passedSilence_ += gaps_[gapHead_].length;
        gapHead_ = (gapHead_ + 1) % gaps_.size();
        --gapCount_;
    }
    const uint64_t partial = gapCount_ && gaps_[gapHead_].start < frames ? frames - gaps_[gapHead_].start : 0;
    if (draining_ && ring_->readable() < frameBytes_ && frames >= contentEnd_) {
        const HRESULT hr = client_->Stop();
        if (FAILED(hr)) fail(hr);
        playing_ = false;
        stopDrain(true);
    }
    const uint32_t seq = sequence_.load(std::memory_order_relaxed);
    sequence_.store(seq + 1, std::memory_order_relaxed);
    std::atomic_thread_fence(std::memory_order_release);
    played_.store(frames - passedSilence_ - partial, std::memory_order_relaxed);
    queued_.store(deviceFrames_ - frames, std::memory_order_relaxed);
    qpc_.store(qpc, std::memory_order_relaxed);
    sequence_.store(seq + 2, std::memory_order_release);
    int flags = config_.exclusive ? Exclusive : 0;
    if (playing_) flags |= Playing;
    if (draining_) flags |= Draining;
    if (ended_) flags |= Ended;
    if (invalidated_) flags |= Invalidated;
    flags_.store(flags);
}

int Stream::write(size_t size, int timeoutMs, const Copy& copy) {
    const ULONGLONG deadline = timeoutMs < 0 ? 0 : GetTickCount64() + static_cast<ULONGLONG>(timeoutMs);
    size_t done = 0;
    while (done < size) {
        if (invalidated_) return done ? static_cast<int>(done) : AUDCLNT_E_DEVICE_INVALIDATED;
        if (closing_) return done ? static_cast<int>(done) : E_ABORT;
        const size_t base = done;
        done += ring_->write(size - done, [&](uint8_t* target, size_t offset, size_t count) { copy(target, base + offset, count); });
        if (done == size) break;
        if (done != base) continue;
        DWORD wait = INFINITE;
        if (timeoutMs >= 0) {
            const ULONGLONG now = GetTickCount64();
            if (now >= deadline) break;
            wait = static_cast<DWORD>(deadline - now);
        }
        WaitForSingleObject(spaceEvent_, wait);
    }
    return static_cast<int>(done);
}

HRESULT Stream::command(Command command) {
    std::lock_guard lock(commandMutex_);
    if (finished_) return E_HANDLE;
    pending_.store(static_cast<int>(command), std::memory_order_release);
    const uint32_t seq = commandSeq_.fetch_add(1, std::memory_order_acq_rel) + 1;
    SetEvent(commandEvent_);
    const ULONGLONG deadline = GetTickCount64() + kCommandTimeout;
    while (ackSeq_.load(std::memory_order_acquire) != seq) {
        const ULONGLONG now = GetTickCount64();
        if (now >= deadline || WaitForSingleObject(ackEvent_, static_cast<DWORD>(deadline - now)) == WAIT_TIMEOUT) {
            if (ackSeq_.load(std::memory_order_acquire) == seq) break;
            return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
        }
    }
    if (command == Command::Close) finished_ = true;
    return commandResult_.load();
}

int Stream::drain(int timeoutMs) {
    const HRESULT hr = command(Command::Drain);
    if (FAILED(hr)) return hr;
    if (WaitForSingleObject(drainEvent_, timeoutMs < 0 ? INFINITE : static_cast<DWORD>(timeoutMs)) != WAIT_OBJECT_0) return 0;
    if (invalidated_) return AUDCLNT_E_DEVICE_INVALIDATED;
    return (flags_.load() & Ended) ? 1 : 0;
}

void Stream::close() {
    closing_ = true;
    SetEvent(spaceEvent_);
    SetEvent(drainEvent_);
    const HRESULT hr = command(Command::Close);
    if (!thread_.joinable()) return;
    if (hr != HRESULT_FROM_WIN32(ERROR_TIMEOUT)) {
        thread_.join();
        return;
    }
    // a hung driver keeps the render thread alive, so the stream must outlive it
    new std::shared_ptr<Stream>(shared_from_this());
    thread_.detach();
}

StreamStatus Stream::status() const {
    uint64_t played = 0, queued = 0, qpc = 0;
    for (;;) {
        const uint32_t before = sequence_.load(std::memory_order_acquire);
        played = played_.load(std::memory_order_relaxed);
        queued = queued_.load(std::memory_order_relaxed);
        qpc = qpc_.load(std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_acquire);
        if (!(before & 1) && before == sequence_.load(std::memory_order_relaxed)) break;
        YieldProcessor();
    }
    StreamStatus status;
    status.played = static_cast<int64_t>(played);
    status.buffered = static_cast<int64_t>(ring_->readable() / frameBytes_ + queued);
    status.underruns = underruns_.load(std::memory_order_relaxed);
    status.flags = flags_.load();
    status.lastError = lastError_.load();
    status.latencyUs = latency_ / 10;
    status.positionNanos = static_cast<int64_t>(qpc);
    status.bufferFrames = bufferFrames_;
    status.ringFrames = static_cast<int64_t>(ring_->capacity() / frameBytes_);
    return status;
}

}
