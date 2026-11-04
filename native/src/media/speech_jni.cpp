#include "jni_support.h"
#include <windows.h>
#include <sapi.h>
#include <string>

namespace {

std::wstring wide(JNIEnv *env, jstring value) {
    const jchar *chars = env->GetStringChars(value, nullptr);
    std::wstring out(reinterpret_cast<const wchar_t *>(chars), static_cast<size_t>(env->GetStringLength(value)));
    env->ReleaseStringChars(value, chars);
    return out;
}

}

extern "C" JNIEXPORT jboolean JNICALL Java_com_aurora_music_desktop_natives_SpeechNative_toWave(JNIEnv *env, jobject, jstring text, jstring path) {
    const std::wstring line = wide(env, text);
    const std::wstring file = wide(env, path);
    const HRESULT com = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool ok = false;
    ISpVoice *voice = nullptr;
    ISpStream *stream = nullptr;
    WAVEFORMATEX format{WAVE_FORMAT_PCM, 1, 22050, 44100, 2, 16, 0};
    if (SUCCEEDED(CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_ISpVoice, reinterpret_cast<void **>(&voice))) &&
        SUCCEEDED(CoCreateInstance(CLSID_SpStream, nullptr, CLSCTX_ALL, IID_ISpStream, reinterpret_cast<void **>(&stream))) &&
        SUCCEEDED(stream->BindToFile(file.c_str(), SPFM_CREATE_ALWAYS, &SPDFID_WaveFormatEx, &format, 0)) &&
        SUCCEEDED(voice->SetOutput(stream, TRUE)) &&
        SUCCEEDED(voice->Speak(line.c_str(), SPF_IS_NOT_XML, nullptr))) ok = true;
    if (stream) { stream->Close(); stream->Release(); }
    if (voice) voice->Release();
    if (SUCCEEDED(com)) CoUninitialize();
    return ok ? JNI_TRUE : JNI_FALSE;
}
