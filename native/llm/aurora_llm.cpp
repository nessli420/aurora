#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

using Clock = std::chrono::steady_clock;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string prefix;
    std::vector<uint8_t> prefixState;
    int prefixTokens = 0;
    std::atomic<bool> cancel{false};
    std::mutex lock;
    jlong timings[6] = {};
};

bool aborted(void *data) { return static_cast<Engine *>(data)->cancel.load(); }

void quiet(ggml_log_level, const char *, void *) {}

std::string text(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    std::string out(static_cast<size_t>(env->GetArrayLength(value)), '\0');
    env->GetByteArrayRegion(value, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &value, bool special) {
    int n = -llama_tokenize(vocab, value.data(), static_cast<int32_t>(value.size()), nullptr, 0, false, special);
    std::vector<llama_token> tokens(static_cast<size_t>(std::max(n, 0)));
    if (n > 0) llama_tokenize(vocab, value.data(), static_cast<int32_t>(value.size()), tokens.data(), n, false, special);
    return tokens;
}

bool decode(Engine *e, std::vector<llama_token> &tokens) {
    const int batch = static_cast<int>(llama_n_batch(e->ctx));
    for (size_t i = 0; i < tokens.size(); i += batch) {
        const int n = static_cast<int>(std::min(tokens.size() - i, static_cast<size_t>(batch)));
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) return false;
    }
    return true;
}

long long since(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

// greedy over the grammar: try the strongest candidates one at a time before filtering the whole vocabulary
llama_token pick(Engine *e, llama_sampler *grammar) {
    const float *logits = llama_get_logits_ith(e->ctx, -1);
    const int n = llama_vocab_n_tokens(e->vocab);
    std::vector<int> order(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) order[i] = i;
    const int head = std::min(n, 48);
    std::partial_sort(order.begin(), order.begin() + head, order.end(), [&](int a, int b) { return logits[a] > logits[b]; });
    for (int i = 0; i < head; i++) {
        llama_token_data one{order[i], logits[order[i]], 0.0f};
        llama_token_data_array single{&one, 1, -1, false};
        llama_sampler_apply(grammar, &single);
        if (std::isfinite(one.logit)) return order[i];
    }
    std::vector<llama_token_data> all(static_cast<size_t>(n));
    for (int i = 0; i < n; i++) all[i] = {i, logits[i], 0.0f};
    llama_token_data_array array{all.data(), all.size(), -1, false};
    llama_sampler_apply(grammar, &array);
    llama_token best = -1;
    float top = -INFINITY;
    for (const auto &c : all) if (c.logit > top) { top = c.logit; best = c.id; }
    return best;
}

}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_aurora_music_ai_LlamaNative_load(JNIEnv *env, jclass, jbyteArray path, jint threads, jint context) {
    llama_log_set(quiet, nullptr);
    llama_backend_init();
    auto modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    auto *model = llama_model_load_from_file(text(env, path).c_str(), modelParams);
    if (!model) return 0;
    auto *e = new Engine();
    auto params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context);
    params.n_batch = 512;
    params.n_ubatch = 512;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.abort_callback = aborted;
    params.abort_callback_data = e;
    params.no_perf = true;
    auto *ctx = llama_init_from_model(model, params);
    if (!ctx) { llama_model_free(model); delete e; return 0; }
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jbyteArray JNICALL Java_com_aurora_music_ai_LlamaNative_generate(JNIEnv *env, jclass, jlong handle, jbyteArray prefixText,
                                                                         jbyteArray promptText, jbyteArray grammarText, jint maxTokens) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return nullptr;
    std::lock_guard<std::mutex> guard(e->lock);
    e->cancel = false;
    std::memset(e->timings, 0, sizeof(e->timings));
    const std::string prefix = text(env, prefixText);
    auto *memory = llama_get_memory(e->ctx);
    auto start = Clock::now();
    llama_memory_clear(memory, true);
    if (prefix != e->prefix || e->prefixState.empty()) {
        e->prefix.clear();
        e->prefixState.clear();
        auto tokens = tokenize(e->vocab, prefix, true);
        if (!decode(e, tokens)) { llama_memory_clear(memory, true); return nullptr; }
        e->prefixState.resize(llama_state_seq_get_size(e->ctx, 0));
        llama_state_seq_get_data(e->ctx, e->prefixState.data(), e->prefixState.size(), 0);
        e->prefix = prefix;
        e->prefixTokens = static_cast<int>(tokens.size());
        e->timings[0] = e->prefixTokens;
        e->timings[1] = since(start);
    } else if (llama_state_seq_set_data(e->ctx, e->prefixState.data(), e->prefixState.size(), 0) == 0) {
        e->prefixState.clear();
        e->prefix.clear();
        return nullptr;
    }
    start = Clock::now();
    auto prompt = tokenize(e->vocab, text(env, promptText), true);
    if (prompt.empty() || static_cast<int>(prompt.size()) + e->prefixTokens + maxTokens >= static_cast<int>(llama_n_ctx(e->ctx))) return nullptr;
    if (!decode(e, prompt)) return nullptr;
    e->timings[2] = static_cast<jlong>(prompt.size());
    e->timings[3] = since(start);
    auto *grammar = llama_sampler_init_grammar(e->vocab, text(env, grammarText).c_str(), "root");
    if (!grammar) return nullptr;
    start = Clock::now();
    std::string out;
    int produced = 0;
    for (; produced < maxTokens && !e->cancel; produced++) {
        llama_token token = pick(e, grammar);
        if (token < 0 || llama_vocab_is_eog(e->vocab, token)) break;
        llama_sampler_accept(grammar, token);
        char piece[256];
        const int n = llama_token_to_piece(e->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, static_cast<size_t>(n));
        if (llama_decode(e->ctx, llama_batch_get_one(&token, 1)) != 0) break;
    }
    llama_sampler_free(grammar);
    e->timings[4] = produced;
    e->timings[5] = since(start);
    if (e->cancel) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT jlongArray JNICALL Java_com_aurora_music_ai_LlamaNative_timings(JNIEnv *env, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    jlongArray result = env->NewLongArray(6);
    if (e) env->SetLongArrayRegion(result, 0, 6, e->timings);
    return result;
}

JNIEXPORT void JNICALL Java_com_aurora_music_ai_LlamaNative_cancel(JNIEnv *, jclass, jlong handle) {
    if (auto *e = reinterpret_cast<Engine *>(handle)) e->cancel = true;
}

JNIEXPORT void JNICALL Java_com_aurora_music_ai_LlamaNative_free(JNIEnv *, jclass, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->cancel = true;
    {
        std::lock_guard<std::mutex> guard(e->lock);
        llama_free(e->ctx);
        llama_model_free(e->model);
    }
    delete e;
}

}
