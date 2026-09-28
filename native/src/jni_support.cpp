#include "jni_support.h"
#include <windows.h>
#include <objbase.h>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace aurora {
namespace {

JavaVM* vm = nullptr;

struct WorkerState {
    std::once_flag started;
    std::mutex mutex;
    std::condition_variable wake;
    std::deque<Worker::Task> tasks;
    std::atomic<DWORD> thread{0};
    JNIEnv* env = nullptr;
};

WorkerState& worker() {
    static auto* state = new WorkerState();
    return *state;
}

void runWorker() {
    auto& state = worker();
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    JavaVMAttachArgs args{JNI_VERSION_1_8, const_cast<char*>("aurora-native"), nullptr};
    if (vm) vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&state.env), &args);
    state.thread = GetCurrentThreadId();
    for (;;) {
        Worker::Task task;
        {
            std::unique_lock lock(state.mutex);
            state.wake.wait(lock, [&] { return !state.tasks.empty(); });
            task = std::move(state.tasks.front());
            state.tasks.pop_front();
        }
        try {
            task(state.env);
        } catch (...) {
        }
        if (state.env) clearException(state.env);
    }
}

}

void Worker::post(Task task) {
    auto& state = worker();
    std::call_once(state.started, [] { std::thread(runWorker).detach(); });
    {
        std::lock_guard lock(state.mutex);
        state.tasks.push_back(std::move(task));
    }
    state.wake.notify_one();
}

bool Worker::current() { return worker().thread.load() == GetCurrentThreadId(); }

JNIEnv* Worker::env() { return worker().env; }

std::wstring wide(JNIEnv* env, jstring value) {
    if (!value) return {};
    const jsize length = env->GetStringLength(value);
    std::wstring result(static_cast<size_t>(length), L'\0');
    env->GetStringRegion(value, 0, length, reinterpret_cast<jchar*>(result.data()));
    return result;
}

jstring javaString(JNIEnv* env, std::wstring_view value) {
    return env->NewString(reinterpret_cast<const jchar*>(value.data()), static_cast<jsize>(value.size()));
}

std::vector<unsigned char> bytes(JNIEnv* env, jbyteArray value) {
    if (!value) return {};
    std::vector<unsigned char> result(static_cast<size_t>(env->GetArrayLength(value)));
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte*>(result.data()));
    return result;
}

void clearException(JNIEnv* env) {
    if (!env->ExceptionCheck()) return;
    env->ExceptionDescribe();
    env->ExceptionClear();
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* jvm, void*) {
    aurora::vm = jvm;
    return JNI_VERSION_1_8;
}
