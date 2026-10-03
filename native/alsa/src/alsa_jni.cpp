#include "alsa_output.h"
#include <jni.h>
#include <cerrno>
#include <unordered_map>

using namespace aurora::alsa;

namespace {

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

void quiet(const char*, int, const char*, int, const char*, ...) {}

std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<int> ints(JNIEnv* env, jintArray value) {
    if (!value) return {};
    std::vector<int> result(static_cast<size_t>(env->GetArrayLength(value)));
    env->GetIntArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jint*>(result.data()));
    return result;
}

jintArray outcome(JNIEnv* env, int error, const std::vector<int>& masks) {
    const jintArray result = env->NewIntArray(static_cast<jsize>(masks.size() + 1));
    if (!result) return nullptr;
    const jint head = error;
    env->SetIntArrayRegion(result, 0, 1, &head);
    if (!masks.empty()) env->SetIntArrayRegion(result, 1, static_cast<jsize>(masks.size()), reinterpret_cast<const jint*>(masks.data()));
    return result;
}

}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    snd_lib_error_set_handler(quiet);
    return JNI_VERSION_1_8;
}

JNIEXPORT jobjectArray JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_devices(JNIEnv* env, jobject) {
    std::vector<DeviceInfo> devices;
    if (listDevices(devices) < 0 && devices.empty()) return nullptr;
    const jobjectArray result = env->NewObjectArray(static_cast<jsize>(devices.size() * 5), env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    jsize index = 0;
    for (const auto& device : devices) {
        for (const std::string* value : {&device.id, &device.card, &device.name, &device.driver, &device.plug}) {
            const jstring item = env->NewStringUTF(value->c_str());
            env->SetObjectArrayElement(result, index++, item);
            env->DeleteLocalRef(item);
        }
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_capabilities(JNIEnv* env, jobject, jstring device, jintArray rates, jint channels) {
    std::vector<int> masks;
    const int error = capabilities(text(env, device), ints(env, rates), channels, masks);
    return outcome(env, error, masks);
}

JNIEXPORT jlong JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_open(JNIEnv* env, jobject, jstring device, jint rate, jint channels, jint encoding,
    jint bufferMs, jintArray rates) {
    std::shared_ptr<Stream> stream;
    const int error = Stream::open(text(env, device), rate, channels, encoding, std::clamp(bufferMs, 20, 5000), ints(env, rates), stream);
    if (error < 0) return error;
    std::lock_guard lock(registry().mutex);
    const jlong handle = registry().next++;
    registry().streams.emplace(handle, std::move(stream));
    return handle;
}

JNIEXPORT jintArray JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_streamCapabilities(JNIEnv* env, jobject, jlong handle) {
    const auto stream = find(handle);
    if (!stream) return outcome(env, -EBADF, {});
    return outcome(env, 0, stream->capabilities());
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_write(JNIEnv* env, jobject, jlong handle, jbyteArray bytes, jint offset, jint length,
    jint timeoutMs) {
    const auto stream = find(handle);
    if (!stream) return -EBADF;
    if (!bytes || offset < 0 || length < 0 || offset > env->GetArrayLength(bytes) - length) return -EINVAL;
    return stream->write(static_cast<size_t>(length), timeoutMs, [&](uint8_t* target, size_t at, size_t count) {
        env->GetByteArrayRegion(bytes, offset + static_cast<jsize>(at), static_cast<jsize>(count), reinterpret_cast<jbyte*>(target));
    });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_resume(JNIEnv*, jobject, jlong handle) {
    const auto stream = find(handle);
    return stream ? stream->resume() : -EBADF;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_pause(JNIEnv*, jobject, jlong handle) {
    const auto stream = find(handle);
    return stream ? stream->pause() : -EBADF;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_flush(JNIEnv*, jobject, jlong handle) {
    const auto stream = find(handle);
    return stream ? stream->flush() : -EBADF;
}

JNIEXPORT jlongArray JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_status(JNIEnv* env, jobject, jlong handle) {
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
Java_com_aurora_music_desktop_natives_AlsaNative_errorText(JNIEnv* env, jobject, jint error) {
    return env->NewStringUTF(snd_strerror(error));
}

JNIEXPORT void JNICALL
Java_com_aurora_music_desktop_natives_AlsaNative_close(JNIEnv*, jobject, jlong handle) {
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

}
