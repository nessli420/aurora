#include <jni.h>
#include <whisper.h>

#include <algorithm>
#include <string>
#include <vector>

namespace {

void quiet(ggml_log_level, const char *, void *) {}

std::string text(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    std::string out(static_cast<size_t>(env->GetArrayLength(value)), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_aurora_music_ai_WhisperNative_load(JNIEnv *env, jclass, jbyteArray path) {
    whisper_log_set(quiet, nullptr);
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    return reinterpret_cast<jlong>(whisper_init_from_file_with_params_no_state(text(env, path).c_str(), params));
}

JNIEXPORT jbyteArray JNICALL Java_com_aurora_music_ai_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray pcm, jint threads, jbyteArray language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return nullptr;
    const std::string lang = text(env, language);
    const jsize count = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(count));
    env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang.c_str();
    params.no_context = true;
    // nudges english towards music words, the bare model hears jazz as just
    if (lang == "en") params.initial_prompt = "Music request: play jazz, funk, soul, hip hop, rock, pop, electronic.";
    // timestamps give the decoder a way to notice the clip has ended
    params.no_timestamps = false;
    params.single_segment = true;
    params.suppress_nst = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    // the encoder only needs as much context as the clip is long, which cuts its time on short requests
    params.audio_ctx = std::min(1500, count / 320 + 16);
    // a short clip can talk itself into a repetition loop, retrying that at higher temperatures only costs seconds
    params.temperature_inc = 0.0f;
    params.max_tokens = 6 + count / 2500;
    // a reused state leaks words from the previous clip into a shorter one
    auto *state = whisper_init_state(ctx);
    if (!state) return nullptr;
    if (whisper_full_with_state(ctx, state, params, samples.data(), count) != 0) {
        whisper_free_state(state);
        return nullptr;
    }
    std::string out;
    for (int i = 0; i < whisper_full_n_segments_from_state(state); i++) {
        if (whisper_full_get_segment_no_speech_prob_from_state(state, i) < 0.6f) out += whisper_full_get_segment_text_from_state(state, i);
    }
    whisper_free_state(state);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL Java_com_aurora_music_ai_WhisperNative_free(JNIEnv *, jclass, jlong handle) {
    if (auto *ctx = reinterpret_cast<whisper_context *>(handle)) whisper_free(ctx);
}

}
