#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>
#include "llama.h"
#include "common.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ai-chat", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ai-chat", __VA_ARGS__)

static llama_model*    g_model   = nullptr;
static llama_context*  g_context = nullptr;
static llama_sampler*  g_sampler = nullptr;
static llama_pos       g_current_pos = 0;

static void free_model_resources() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) { llama_free(g_context);          g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_current_pos = 0;
}

// Helper: decode a batch of tokens at explicit positions
static int decode_tokens(const std::vector<llama_token>& tokens, llama_pos start_pos) {
    int n = tokens.size();
    llama_batch batch = llama_batch_init(n, 0, 1);
    for (int i = 0; i < n; i++) {
        batch.token[i]    = tokens[i];
        batch.pos[i]      = start_pos + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]   = (i == n - 1) ? 1 : 0;
    }
    int ret = llama_decode(g_context, batch);
    llama_batch_free(batch);
    return ret;
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_init(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("Backend initialized");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_load(
    JNIEnv* env, jobject, jstring path, jint ctx_size) {

    free_model_resources();

    const char* p = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading: %s  ctx=%d", p, ctx_size);

    llama_model_params mp = llama_model_default_params();
    g_model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(path, p);
    if (!g_model) return 1;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx    = ctx_size;
    cp.n_batch  = 512;
    cp.n_ubatch = 512;
    g_context = llama_init_from_model(g_model, cp);
    if (!g_context) { llama_model_free(g_model); g_model = nullptr; return 2; }

    g_current_pos = 0;
    LOGI("Model loaded OK");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_prepare(JNIEnv*, jobject) {
    return (!g_model || !g_context) ? 1 : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_systemInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_processSystemPrompt(
    JNIEnv* env, jobject, jstring prompt) {

    const char* s = env->GetStringUTFChars(prompt, nullptr);
    auto voc = llama_model_get_vocab(g_model);
    auto tokens = common_tokenize(voc, s, true, true);
    env->ReleaseStringUTFChars(prompt, s);

    if (decode_tokens(tokens, 0)) return 1;
    g_current_pos = tokens.size();
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_processUserPrompt(
    JNIEnv* env, jobject, jstring prompt,
    jfloat temperature, jint top_k, jfloat top_p) {

    const char* s = env->GetStringUTFChars(prompt, nullptr);
    auto voc = llama_model_get_vocab(g_model);
    // add_special=false: BOS already injected by system prompt
    auto tokens = common_tokenize(voc, s, false, true);
    env->ReleaseStringUTFChars(prompt, s);

    LOGI("Decoding %zu user tokens at pos %d", tokens.size(), g_current_pos);
    if (decode_tokens(tokens, g_current_pos)) return 1;
    g_current_pos += tokens.size();

    // Build sampler chain
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    auto* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (top_k > 0)       llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    if (top_p < 1.0f)    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    if (temperature > 0) llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    g_sampler = chain;

    return 0;
}

// STREAMING: generates exactly ONE token per call
extern "C" JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_generateNextToken(JNIEnv* env, jobject) {
    if (!g_sampler || !g_context) return nullptr;

    auto token = llama_sampler_sample(g_sampler, g_context, -1);
    llama_sampler_accept(g_sampler, token);

    auto voc = llama_model_get_vocab(g_model);
    if (llama_vocab_is_eog(voc, token)) return nullptr;

    // Decode this single token at the correct position
    llama_batch batch = llama_batch_init(1, 0, 1);
    batch.token[0]    = token;
    batch.pos[0]      = g_current_pos;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0]   = 1;

    if (llama_decode(g_context, batch)) {
        llama_batch_free(batch);
        return nullptr;
    }
    llama_batch_free(batch);
    g_current_pos++;

    char buf[256];
    int n = llama_token_to_piece(voc, token, buf, sizeof(buf), 0, true);
    if (n <= 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buf, n).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_benchModel(
    JNIEnv* env, jobject, jint pp, jint tg, jint pl, jint nr) {

    auto* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto start = std::chrono::high_resolution_clock::now();
    for (int run = 0; run < nr; run++) {
        for (int i = 0; i < tg; i++) {
            auto token = llama_sampler_sample(chain, g_context, -1);
            llama_sampler_accept(chain, token);
            llama_batch batch = llama_batch_init(1, 0, 1);
            batch.token[0] = token; batch.pos[0] = i;
            batch.n_seq_id[0] = 1; batch.seq_id[0][0] = 0; batch.logits[0] = 1;
            llama_decode(g_context, batch);
            llama_batch_free(batch);
        }
    }
    auto end = std::chrono::high_resolution_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    llama_sampler_free(chain);

    float tps = (tg * nr * 1000.0f) / (float)ms;
    char desc[256]; llama_model_desc(g_model, desc, sizeof(desc));
    char res[512];
    snprintf(res, sizeof(res),
        "Generated %d tokens in %lld ms\nSpeed: %.2f t/s\nModel: %s",
        tg * nr, (long long)ms, tps, desc);
    return env->NewStringUTF(res);
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_unload(JNIEnv*, jobject) {
    free_model_resources();
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_shutdown(JNIEnv*, jobject) {
    free_model_resources();
    llama_backend_free();
}
