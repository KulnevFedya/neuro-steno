// Минимальный JNI над llama.cpp: load / generate (greedy + штраф за повторы) / free.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "STENO", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "STENO", __VA_ARGS__)

namespace {
struct Handle {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_ctx = 0;
};
std::atomic<bool> g_cancel{false};

void log_cb(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_ERROR) LOGE("llama: %s", text);
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    return n > 0 ? std::string(buf, n) : std::string();
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nuclearhack_steno_LlamaBridge_nativeLoad(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    llama_log_set(log_cb, nullptr);
    llama_backend_init();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }
    auto *h = new Handle{model, ctx, (int) llama_n_ctx(ctx)};
    LOGI("llama loaded: n_ctx=%d threads=%d | %s", h->n_ctx, n_threads, llama_print_system_info());
    return (jlong) h;
}

JNIEXPORT void JNICALL
Java_com_nuclearhack_steno_LlamaBridge_nativeCancel(JNIEnv *, jobject) { g_cancel = true; }

// Возвращает сгенерированный текст; null при ошибке (промпт не влезает и т.п.). Промпт уже в формате ChatML.
JNIEXPORT jstring JNICALL
Java_com_nuclearhack_steno_LlamaBridge_nativeGenerate(JNIEnv *env, jobject, jlong handle, jstring jprompt, jint max_new) {
    auto *h = (Handle *) handle;
    if (!h) return nullptr;
    g_cancel = false;
    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true);
    if (n < 0) { toks.resize(-n); n = llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(), true, true); }
    if (n <= 0) return nullptr;
    toks.resize(n);
    if (n + max_new > h->n_ctx) { LOGE("prompt %d + %d > n_ctx %d", n, (int) max_new, h->n_ctx); return nullptr; }

    llama_memory_clear(llama_get_memory(h->ctx), true);
    const int n_batch = llama_n_batch(h->ctx);
    for (int i = 0; i < n; i += n_batch) {
        int c = std::min(n_batch, n - i);
        if (llama_decode(h->ctx, llama_batch_get_one(toks.data() + i, c)) != 0) { LOGE("decode prompt failed"); return nullptr; }
        if (g_cancel) return nullptr;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    for (int i = 0; i < max_new && !g_cancel; i++) {
        llama_token t = llama_sampler_sample(smpl, h->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        out += piece(vocab, t);
        if (llama_decode(h->ctx, llama_batch_get_one(&t, 1)) != 0) { LOGE("decode failed"); break; }
    }
    llama_sampler_free(smpl);
    if (g_cancel) return nullptr;
    // UTF-8 может обрываться на лимите токенов — отрезаем незавершённый хвост
    while (!out.empty() && ((unsigned char) out.back() & 0xC0) == 0x80) out.pop_back();
    if (!out.empty() && ((unsigned char) out.back() & 0x80)) out.pop_back();
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jint JNICALL
Java_com_nuclearhack_steno_LlamaBridge_nativeTokenCount(JNIEnv *env, jobject, jlong handle, jstring jtext) {
    auto *h = (Handle *) handle;
    const llama_vocab *vocab = llama_model_get_vocab(h->model);
    const char *p = env->GetStringUTFChars(jtext, nullptr);
    int len = (int) strlen(p);
    int n = -llama_tokenize(vocab, p, len, nullptr, 0, false, true);
    env->ReleaseStringUTFChars(jtext, p);
    return n;
}

JNIEXPORT void JNICALL
Java_com_nuclearhack_steno_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *h = (Handle *) handle;
    if (!h) return;
    llama_free(h->ctx);
    llama_model_free(h->model);
    delete h;
    llama_backend_free();
}

}  // extern "C"
