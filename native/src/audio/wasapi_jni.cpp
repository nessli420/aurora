#include "audio/wasapi.h"
#include "jni_support.h"
#include <ksmedia.h>
#include <unordered_map>

using aurora::Worker;
using namespace aurora::audio;

namespace {

constexpr int kStreamInvalidated = 4;

struct Registry {
    std::mutex mutex;
    std::unordered_map<jlong, std::shared_ptr<Stream>> streams;
    jlong next = 1;
};

Registry& registry() {
    static auto* value = new Registry();
    return *value;
}

std::shared_ptr<Stream> find(jlong handle) {
    auto& streams = registry();
    std::lock_guard lock(streams.mutex);
    const auto it = streams.streams.find(handle);
    return it == streams.streams.end() ? nullptr : it->second;
}

struct Listener {
    jobject object = nullptr;
    jmethodID onEvent = nullptr;
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMNotificationClient> notifier;
};

Listener& listener() {
    static auto* value = new Listener();
    return *value;
}

void deliver(JNIEnv* env, int kind, const std::wstring& id, jlong value) {
    const Listener& target = listener();
    if (!target.object) return;
    if ((kind == 1 || kind == 3) && target.enumerator && !isRenderEndpoint(target.enumerator.Get(), id)) return;
    const jstring device = id.empty() ? nullptr : aurora::javaString(env, id);
    env->CallVoidMethod(target.object, target.onEvent, kind, device, value);
    aurora::clearException(env);
    if (device) env->DeleteLocalRef(device);
}

jint control(jlong handle, Stream::Command command) {
    const auto stream = find(handle);
    return stream ? stream->command(command) : E_HANDLE;
}

}

extern "C" {

JNIEXPORT jobjectArray JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_devices(JNIEnv* env, jobject) {
    std::vector<DeviceInfo> devices;
    if (FAILED(Worker::call([&](JNIEnv*) { return listDevices(devices); }))) return nullptr;
    const jobjectArray result = env->NewObjectArray(static_cast<jsize>(devices.size() * 3), env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    jsize index = 0;
    for (const auto& device : devices) {
        for (const std::wstring& value : {device.id, device.name, std::to_wstring(device.formFactor)}) {
            const jstring item = aurora::javaString(env, value);
            env->SetObjectArrayElement(result, index++, item);
            env->DeleteLocalRef(item);
        }
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_defaultDevice(JNIEnv* env, jobject) {
    std::wstring id;
    if (FAILED(Worker::call([&](JNIEnv*) { return defaultDevice(id); }))) return nullptr;
    return aurora::javaString(env, id);
}

JNIEXPORT jintArray JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_mixFormat(JNIEnv* env, jobject, jstring deviceId) {
    const std::wstring id = aurora::wide(env, deviceId);
    WAVEFORMATEXTENSIBLE format;
    if (FAILED(Worker::call([&](JNIEnv*) { return mixFormat(id, format); }))) return nullptr;
    const jint values[] = {
        static_cast<jint>(format.Format.nSamplesPerSec),
        format.Format.nChannels,
        format.Format.wBitsPerSample,
        format.Samples.wValidBitsPerSample,
        format.SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT ? 1 : 0,
        static_cast<jint>(format.dwChannelMask),
    };
    const jintArray result = env->NewIntArray(6);
    if (result) env->SetIntArrayRegion(result, 0, 6, values);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_probe(JNIEnv* env, jobject, jstring deviceId, jboolean exclusive,
    jint rate, jint channels, jint encoding) {
    const std::wstring id = aurora::wide(env, deviceId);
    return Worker::call([&](JNIEnv*) { return probeFormat(id, exclusive, rate, channels, encoding); });
}

JNIEXPORT jlong JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_open(JNIEnv* env, jobject, jstring deviceId, jboolean exclusive,
    jint rate, jint channels, jint encoding, jint bufferMs) {
    StreamConfig config{aurora::wide(env, deviceId), exclusive == JNI_TRUE, rate, channels, encoding, std::clamp(bufferMs, 20, 5000)};
    jlong handle = 0;
    {
        std::lock_guard lock(registry().mutex);
        handle = registry().next++;
    }
    std::shared_ptr<Stream> stream;
    const HRESULT hr = Stream::open(config, [handle](const std::wstring& device) {
        Worker::post([handle, device](JNIEnv* workerEnv) { deliver(workerEnv, kStreamInvalidated, device, handle); });
    }, stream);
    if (FAILED(hr)) return hr;
    std::lock_guard lock(registry().mutex);
    registry().streams.emplace(handle, std::move(stream));
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_write(JNIEnv* env, jobject, jlong handle, jbyteArray bytes,
    jint offset, jint length, jint timeoutMs) {
    const auto stream = find(handle);
    if (!stream) return E_HANDLE;
    if (!bytes || offset < 0 || length < 0 || offset > env->GetArrayLength(bytes) - length) return E_INVALIDARG;
    return stream->write(static_cast<size_t>(length), timeoutMs, [&](uint8_t* target, size_t at, size_t count) {
        env->GetByteArrayRegion(bytes, offset + static_cast<jsize>(at), static_cast<jsize>(count), reinterpret_cast<jbyte*>(target));
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_resume(JNIEnv*, jobject, jlong handle) {
    return control(handle, Stream::Command::Resume);
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_pause(JNIEnv*, jobject, jlong handle) {
    return control(handle, Stream::Command::Pause);
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_flush(JNIEnv*, jobject, jlong handle) {
    return control(handle, Stream::Command::Flush);
}

JNIEXPORT jlongArray JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_status(JNIEnv* env, jobject, jlong handle) {
    const auto stream = find(handle);
    if (!stream) return nullptr;
    const StreamStatus status = stream->status();
    const jlong values[] = {status.played, status.buffered, status.underruns, status.flags, status.lastError,
        status.latencyUs, status.positionNanos, status.bufferFrames, status.ringFrames};
    const jlongArray result = env->NewLongArray(9);
    if (result) env->SetLongArrayRegion(result, 0, 9, values);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_streamDevice(JNIEnv* env, jobject, jlong handle) {
    const auto stream = find(handle);
    return stream ? aurora::javaString(env, stream->deviceId()) : nullptr;
}

JNIEXPORT void JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_close(JNIEnv*, jobject, jlong handle) {
    std::shared_ptr<Stream> stream;
    {
        std::lock_guard lock(registry().mutex);
        const auto it = registry().streams.find(handle);
        if (it == registry().streams.end()) return;
        stream = std::move(it->second);
        registry().streams.erase(it);
    }
    stream->close();
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WasapiNative_setListener(JNIEnv* env, jobject, jobject callback) {
    jobject global = nullptr;
    jmethodID method = nullptr;
    if (callback) {
        const jclass type = env->GetObjectClass(callback);
        method = env->GetMethodID(type, "onEvent", "(ILjava/lang/String;J)V");
        env->DeleteLocalRef(type);
        if (!method) {
            env->ExceptionClear();
            return E_INVALIDARG;
        }
        global = env->NewGlobalRef(callback);
    }
    return Worker::call([&](JNIEnv* workerEnv) -> jint {
        Listener& target = listener();
        if (target.object) workerEnv->DeleteGlobalRef(target.object);
        target.object = global;
        target.onEvent = method;
        if (global && !target.notifier) {
            return watchDevices([](int kind, std::wstring id, int64_t value) {
                Worker::post([kind, id = std::move(id), value](JNIEnv* eventEnv) { deliver(eventEnv, kind, id, value); });
            }, target.enumerator, target.notifier);
        }
        if (!global && target.notifier) {
            target.enumerator->UnregisterEndpointNotificationCallback(target.notifier.Get());
            target.notifier.Reset();
            target.enumerator.Reset();
        }
        return S_OK;
    });
}

}
