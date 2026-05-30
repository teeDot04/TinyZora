#include <jni.h>
#include <android/log.h>
#include <llama.h>
#include <string>
#include <vector>
#include <atomic>

#define TAG "LlamaAndroid"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static std::atomic<bool> g_stop{false};

static int32_t g_top_k = 40;
static float   g_top_p = 0.9f;
static float   g_temp  = 0.7f;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_loadModel(
        JNIEnv* env, jobject,
        jstring model_path, jint n_ctx, jint n_threads,
        jint top_k, jfloat top_p, jfloat temp) {

    if (g_ctx)   { llama_free(g_ctx);         g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }

    g_top_k = top_k;
    g_top_p = top_p;
    g_temp  = temp;

    llama_backend_init();

    auto mparams          = llama_model_default_params();
    mparams.n_gpu_layers  = 0;

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    auto cparams             = llama_context_default_params();
    cparams.n_ctx            = (uint32_t)n_ctx;
    cparams.n_threads        = (uint32_t)n_threads;
    cparams.n_threads_batch  = (uint32_t)n_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded: n_ctx=%d threads=%d", n_ctx, n_threads);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_LlamaAndroid_sendMessageNative(
        JNIEnv* env, jobject thiz, jstring prompt) {

    if (!g_model || !g_ctx) { LOGE("Model not loaded"); return; }

    g_stop.store(false);

    const char* raw = env->GetStringUTFChars(prompt, nullptr);
    std::string text(raw);
    env->ReleaseStringUTFChars(prompt, raw);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    int n_tokens = -llama_tokenize(vocab, text.c_str(), (int)text.size(),
                                   nullptr, 0, true, true);
    std::vector<llama_token> tokens((size_t)n_tokens);
    llama_tokenize(vocab, text.c_str(), (int)text.size(),
                   tokens.data(), n_tokens, true, true);

    llama_kv_self_clear(g_ctx);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    batch.n_tokens = n_tokens;
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]     = tokens[i];
        batch.pos[i]       = i;
        batch.n_seq_id[i]  = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]    = (i == n_tokens - 1) ? 1 : 0;
    }
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(g_top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(g_top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(g_temp));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0xFFFFFFFF));

    jclass    cls = env->GetObjectClass(thiz);
    jmethodID cb  = env->GetMethodID(cls, "onTokenGenerated", "(Ljava/lang/String;)V");

    int n_pos = n_tokens;
    const int n_max = (int)llama_n_ctx(g_ctx) - n_pos;

    for (int i = 0; i < n_max && !g_stop.load(); i++) {
        llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
        if (n < 0) break;

        jstring piece = env->NewStringUTF(std::string(buf, (size_t)n).c_str());
        env->CallVoidMethod(thiz, cb, piece);
        env->DeleteLocalRef(piece);

        if (env->ExceptionCheck()) { env->ExceptionClear(); break; }

        llama_batch next = llama_batch_init(1, 0, 1);
        next.n_tokens     = 1;
        next.token[0]     = token;
        next.pos[0]       = n_pos++;
        next.n_seq_id[0]  = 1;
        next.seq_id[0][0] = 0;
        next.logits[0]    = 1;

        if (llama_decode(g_ctx, next) != 0) {
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);
    }

    llama_sampler_free(sampler);
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
    if (g_ctx)   { llama_free(g_ctx);        g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

}
