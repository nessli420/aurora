#include "jni_support.h"
#include <windows.h>
#include <wincrypt.h>
#include <dpapi.h>
#include <dwmapi.h>
#include <ShObjIdl_core.h>

using aurora::Worker;

namespace {

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

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_protect(JNIEnv* env, jobject, jbyteArray data, jbyteArray entropy) {
    return crypt(env, data, entropy, true);
}

JNIEXPORT jbyteArray JNICALL
Java_com_aurora_music_desktop_natives_SystemNative_unprotect(JNIEnv* env, jobject, jbyteArray data, jbyteArray entropy) {
    return crypt(env, data, entropy, false);
}

}
