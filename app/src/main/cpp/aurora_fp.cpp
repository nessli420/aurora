// JNI bridge from com.aurora.music.data.Chromaprint to the vendored Chromaprint C API.
// Kotlin streams decoded 16-bit PCM in; we return the AcoustID-compatible compressed fingerprint.
#include <jni.h>
#include <cstdint>
#include <chromaprint.h>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aurora_music_data_Chromaprint_nativeNew(JNIEnv *, jobject, jint sampleRate, jint channels) {
    ChromaprintContext *ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (ctx == nullptr) return 0;
    if (chromaprint_start(ctx, sampleRate, channels) != 1) {
        chromaprint_free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_aurora_music_data_Chromaprint_nativeFeed(JNIEnv *env, jobject, jlong ctxPtr, jshortArray pcm, jint length) {
    auto *ctx = reinterpret_cast<ChromaprintContext *>(ctxPtr);
    if (ctx == nullptr || pcm == nullptr || length <= 0) return;
    jshort *data = env->GetShortArrayElements(pcm, nullptr);
    if (data == nullptr) return;
    chromaprint_feed(ctx, reinterpret_cast<const int16_t *>(data), length);
    env->ReleaseShortArrayElements(pcm, data, JNI_ABORT); // read-only; don't copy back
}

JNIEXPORT jstring JNICALL
Java_com_aurora_music_data_Chromaprint_nativeFinish(JNIEnv *env, jobject, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<ChromaprintContext *>(ctxPtr);
    if (ctx == nullptr) return nullptr;
    jstring result = nullptr;
    if (chromaprint_finish(ctx) == 1) {
        char *fingerprint = nullptr;
        if (chromaprint_get_fingerprint(ctx, &fingerprint) == 1 && fingerprint != nullptr) {
            result = env->NewStringUTF(fingerprint);
            chromaprint_dealloc(fingerprint);
        }
    }
    chromaprint_free(ctx);
    return result;
}

} // extern "C"
