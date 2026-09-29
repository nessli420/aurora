#include "jni_support.h"
#include <windows.h>
#include <wincrypt.h>
#include <dpapi.h>
#include <dwmapi.h>
#include <ShObjIdl_core.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.UI.ViewManagement.h>

using aurora::Worker;
using winrt::Windows::Foundation::IInspectable;
using winrt::Windows::UI::ViewManagement::UIColorType;
using winrt::Windows::UI::ViewManagement::UISettings;

namespace {

struct Accent {
    UISettings settings{nullptr};
    winrt::event_token changed{};
    jobject listener = nullptr;
    jmethodID onAccent = nullptr;
    jint last = 0;
};

Accent& accentState() {
    static auto* value = new Accent();
    return *value;
}

UISettings& uiSettings() {
    auto& state = accentState();
    if (!state.settings) state.settings = UISettings();
    return state.settings;
}

jint accent() {
    try {
        const auto color = uiSettings().GetColorValue(UIColorType::Accent);
        return static_cast<jint>(0xFF000000u | color.R << 16 | color.G << 8 | color.B);
    } catch (const winrt::hresult_error&) {
    }
    DWORD color = 0;
    BOOL opaque = FALSE;
    return SUCCEEDED(DwmGetColorizationColor(&color, &opaque)) ? static_cast<jint>(color | 0xFF000000u) : 0;
}

void deliverAccent(JNIEnv* env) {
    auto& state = accentState();
    if (!state.listener) return;
    const jint value = accent();
    if (value == 0 || value == state.last) return;
    state.last = value;
    env->CallVoidMethod(state.listener, state.onAccent, value);
}

jbyteArray crypt(JNIEnv* env, jbyteArray data, jbyteArray entropy, bool protect) {
    if (!data) return nullptr;
    std::vector<unsigned char> input = aurora::bytes(env, data), salt = aurora::bytes(env, entropy);
    DATA_BLOB in{static_cast<DWORD>(input.size()), input.data()};
    DATA_BLOB extra{static_cast<DWORD>(salt.size()), salt.data()};
    DATA_BLOB out{};
    const BOOL ok = protect
        ? CryptProtectData(&in, nullptr, entropy ? &extra : nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)
        : CryptUnprotectData(&in, nullptr, entropy ? &extra : nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out);
    SecureZeroMemory(input.data(), input.size());
    if (!ok) return nullptr;
    const jbyteArray result = env->NewByteArray(static_cast<jsize>(out.cbData));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.cbData), reinterpret_cast<const jbyte*>(out.pbData));
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return result;
}

}

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WindowNative_setAttribute(JNIEnv*, jobject, jlong window, jint attribute, jint value) {
    const HWND root = window ? GetAncestor(reinterpret_cast<HWND>(window), GA_ROOT) : nullptr;
    if (!root) return HRESULT_FROM_WIN32(ERROR_INVALID_WINDOW_HANDLE);
    const DWORD data = static_cast<DWORD>(value);
    return DwmSetWindowAttribute(root, static_cast<DWORD>(attribute), &data, sizeof(data));
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WindowNative_accent(JNIEnv*, jobject) {
    return Worker::call([](JNIEnv*) { return accent(); });
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_WindowNative_watchAccent(JNIEnv* env, jobject, jobject callback) {
    jobject global = nullptr;
    jmethodID method = nullptr;
    if (callback) {
        const jclass type = env->GetObjectClass(callback);
        method = env->GetMethodID(type, "onAccent", "(I)V");
        env->DeleteLocalRef(type);
        if (!method) {
            env->ExceptionClear();
            return E_INVALIDARG;
        }
        global = env->NewGlobalRef(callback);
    }
    return Worker::call([&](JNIEnv* workerEnv) -> jint {
        auto& state = accentState();
        if (state.listener) workerEnv->DeleteGlobalRef(state.listener);
        state.listener = global;
        state.onAccent = method;
        try {
            if (global && !state.changed) {
                state.last = accent();
                state.changed = uiSettings().ColorValuesChanged([](const UISettings&, const IInspectable&) {
                    Worker::post(deliverAccent);
                });
            }
            if (!global && state.changed) {
                state.settings.ColorValuesChanged(state.changed);
                state.changed = {};
            }
            return S_OK;
        } catch (const winrt::hresult_error& error) {
            return error.code();
        }
    });
}

JNIEXPORT jboolean JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_keepAwake(JNIEnv*, jobject, jboolean enabled) {
    const EXECUTION_STATE state = enabled ? ES_CONTINUOUS | ES_SYSTEM_REQUIRED : ES_CONTINUOUS;
    return Worker::call([&](JNIEnv*) { return SetThreadExecutionState(state) != 0; }) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_appUserModelId(JNIEnv* env, jobject, jstring id) {
    if (!id) return E_INVALIDARG;
    return SetCurrentProcessExplicitAppUserModelID(aurora::wide(env, id).c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_allowForeground(JNIEnv*, jobject, jlong pid) {
    return AllowSetForegroundWindow(static_cast<DWORD>(pid)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_protect(JNIEnv* env, jobject, jbyteArray data, jbyteArray entropy) {
    return crypt(env, data, entropy, true);
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_unprotect(JNIEnv* env, jobject, jbyteArray data, jbyteArray entropy) {
    return crypt(env, data, entropy, false);
}

}
