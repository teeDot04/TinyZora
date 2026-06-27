#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <string>
#include <vector>
#include <atomic>
#include <algorithm>

#define TAG "LlamaAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_stop{false};
static std::atomic<bool> g_generation_in_progress{false};
static int g_n_past = 0;

static int32_t g_top_k = 40;
static float   g_top_p = 0.9f;
static float   g_temp  = 0.7f;

static void llama_log_callback_android(ggml_log_level level, const char* text, void*) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_isModelLoaded(
        JNIEnv*, jobject) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_loadModel(
        JNIEnv* env, jobject,
        jstring model_path, jint n_ctx, jint n_threads,
        jint top_k, jfloat top_p, jfloat temp) {

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }

    g_top_k  = top_k;
    g_top_p  = top_p;
    g_temp   = temp;
    g_n_past = 0;

    llama_backend_init();
    llama_log_set(llama_log_callback_android, nullptr);

    auto mparams         = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    auto cparams            = llama_context_default_params();
    cparams.n_ctx           = (uint32_t)n_ctx;
    cparams.n_threads       = (uint32_t)n_threads;
    cparams.n_threads_batch = (uint32_t)n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded: n_ctx=%d threads=%d top_k=%d top_p=%.2f temp=%.2f",
         n_ctx, n_threads, top_k, top_p, temp);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_sendMessageNative(
        JNIEnv* env, jobject thiz, jstring prompt) {

    if (!g_model || !g_ctx) { LOGE("Model not loaded"); return; }
    if (g_generation_in_progress.exchange(true)) { LOGE("Already generating"); return; }

    g_stop.store(false);

    const char* raw = env->GetStringUTFChars(prompt, nullptr);
    std::string text(raw);
    env->ReleaseStringUTFChars(prompt, raw);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);
    const int n_ctx = (int)llama_n_ctx(g_ctx);

    int n_tokens = -llama_tokenize(vocab, text.c_str(), (int)text.size(), nullptr, 0, true, true);
    if (n_tokens <= 0) {
        LOGE("Tokenization failed");
        g_generation_in_progress.store(false);
        return;
    }
    if (n_tokens >= n_ctx) {
        LOGE("Prompt too long: %d >= %d", n_tokens, n_ctx);
        g_generation_in_progress.store(false);
        return;
    }

    // Clear KV cache and reset position when context would overflow
    if (g_n_past + n_tokens >= n_ctx) {
        LOGI("Context window full (n_past=%d + n_tokens=%d >= n_ctx=%d), clearing KV cache",
             g_n_past, n_tokens, n_ctx);
        llama_kv_cache_clear(g_ctx);
        g_n_past = 0;
    }

    LOGI("Prompt tokens: %d, n_past: %d, ctx: %d", n_tokens, g_n_past, n_ctx);

    std::vector<llama_token> tokens((size_t)n_tokens);
    llama_tokenize(vocab, text.c_str(), (int)text.size(), tokens.data(), n_tokens, true, true);

    const int BATCH_SIZE = 32;
    int n_past = g_n_past;

    for (int batch_start = 0; batch_start < n_tokens && !g_stop.load(); batch_start += BATCH_SIZE) {
        int batch_end = std::min(batch_start + BATCH_SIZE, n_tokens);
        int chunk = batch_end - batch_start;

        llama_batch batch = llama_batch_init(chunk, 0, 1);
        batch.n_tokens = chunk;
        for (int i = 0; i < chunk; i++) {
            batch.token[i]     = tokens[batch_start + i];
            batch.pos[i]       = n_past + i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = (batch_start + i == n_tokens - 1) ? 1 : 0;
        }

        LOGI("Decoding prompt batch [%d..%d] at pos %d", batch_start, batch_end - 1, n_past);
        int ret = llama_decode(g_ctx, batch);
        llama_batch_free(batch);

        if (ret != 0) {
            LOGE("Prompt decode failed at [%d..%d] ret=%d", batch_start, batch_end - 1, ret);
            g_generation_in_progress.store(false);
            return;
        }
        n_past += chunk;
    }

    LOGI("Prompt decode done at n_past=%d, starting generation", n_past);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(g_top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(g_top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(g_temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0xFFFFFFFF));

    jclass    cls = env->GetObjectClass(thiz);
    jmethodID cb  = env->GetMethodID(cls, "onTokenGenerated", "(Ljava/lang/String;)V");

    int tokens_generated = 0;
    const int n_max = n_ctx - n_past;

    for (int i = 0; i < n_max && !g_stop.load(); i++) {
        llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) {
            LOGI("EOG at token %d", i);
            n_past++;
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n < 0) { LOGE("token_to_piece failed"); break; }

        jstring piece = env->NewStringUTF(std::string(buf, (size_t)n).c_str());
        env->CallVoidMethod(thiz, cb, piece);
        env->DeleteLocalRef(piece);

        if (env->ExceptionCheck()) { env->ExceptionClear(); break; }

        llama_batch next = llama_batch_init(1, 0, 1);
        next.n_tokens     = 1;
        next.token[0]     = token;
        next.pos[0]       = n_past++;
        next.n_seq_id[0]  = 1;
        next.seq_id[0][0] = 0;
        next.logits[0]    = 1;

        if (llama_decode(g_ctx, next) != 0) {
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);

        tokens_generated++;
        if (tokens_generated % 10 == 0) LOGI("Generated %d tokens", tokens_generated);
    }

    // Persist n_past for next call — this is how conversation continuity works
    g_n_past = n_past;

    llama_sampler_free(sampler);
    g_generation_in_progress.store(false);
    LOGI("Generation complete: %d tokens, n_past now=%d", tokens_generated, g_n_past);
}

JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_stopGeneration(
        JNIEnv*, jobject) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_unloadModel(
        JNIEnv*, jobject) {
    g_stop.store(true);
    g_n_past = 0;
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

}
