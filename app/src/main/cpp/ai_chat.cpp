#include <jni.h>
#include <string>
#include <android/log.h>
#include <chrono>
#include "llama.h"
#include "common.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ai-chat", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ai-chat", __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_context = nullptr;
static common_sampler* g_sampler = nullptr;
static std::vector<llama_token> g_tokens;
static size_t g_token_pos = 0;
static llama_pos g_system_prompt_pos = 0;
static llama_pos g_current_pos = 0;

extern "C"
JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_init(JNIEnv* env, jobject thiz) {
    llama_backend_init();
    LOGI("Llama backend initialized");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_load(
    JNIEnv* env, jobject thiz, jstring path, jint ctx_size) {
    
    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model from: %s with context size: %d", model_path, ctx_size);
    
    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(path, model_path);
    
    if (!g_model) {
        LOGE("Failed to load model");
        return 1;
    }
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = ctx_size;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    
    g_context = llama_init_from_model(g_model, ctx_params);
    
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return 2;
    }
    
    LOGI("Model loaded successfully");
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_prepare(
    JNIEnv* env, jobject thiz) {
    
    if (!g_model || !g_context) {
        LOGE("Model or context not initialized");
        return 1;
    }
    
    LOGI("Context prepared");
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_systemInfo(
    JNIEnv* env, jobject thiz) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_processSystemPrompt(
    JNIEnv* env, jobject thiz, jstring prompt) {
    
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Setting system prompt");
    
    auto tokens = common_tokenize(g_context, prompt_str, true, true);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_context, batch)) {
        LOGE("Failed to decode system prompt");
        return 1;
    }
    
    g_system_prompt_pos = tokens.size();
    g_current_pos = tokens.size();
    
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_processUserPrompt(
    JNIEnv* env, jobject thiz, jstring prompt, jint predict_length,
    jfloat temperature, jint top_k, jfloat top_p) {
    
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Processing user prompt: temp=%.2f, top_k=%d, top_p=%.2f, predict_length=%d",
         temperature, top_k, top_p, predict_length);
    
    auto tokens = common_tokenize(g_context, prompt_str, true, true);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_context, batch)) {
        LOGE("Failed to decode user prompt");
        return 1;
    }
    
    g_current_pos += tokens.size();
    
    // Create sampler with user preferences
    common_sampler_params sampler_params;
    sampler_params.temp = temperature;
    sampler_params.top_k = top_k;
    sampler_params.top_p = top_p;
    
    if (g_sampler) {
        common_sampler_free(g_sampler);
    }
    g_sampler = common_sampler_init(g_model, sampler_params);
    
    g_tokens.clear();
    g_token_pos = 0;
    
    // Generate tokens
    for (int i = 0; i < predict_length; i++) {
        auto token = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, token, true);
        
        if (llama_token_is_eog(g_model, token)) {
            break;
        }
        
        g_tokens.push_back(token);
        
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_context, batch)) {
            LOGE("Failed to decode token");
            break;
        }
        
        g_current_pos++;
    }
    
    LOGI("Generated %zu tokens", g_tokens.size());
    return 0;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_generateNextToken(
    JNIEnv* env, jobject thiz) {
    
    if (g_token_pos >= g_tokens.size()) {
        return nullptr;
    }
    
    auto token = g_tokens[g_token_pos++];
    char buf[256];
    int n = llama_token_to_piece(g_model, token, buf, sizeof(buf), 0, true);
    
    if (n < 0) {
        return nullptr;
    }
    
    std::string piece(buf, n);
    return env->NewStringUTF(piece.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_benchModel(
    JNIEnv* env, jobject thiz, jint pp, jint tg, jint pl, jint nr) {
    
    LOGI("Running benchmark: pp=%d, tg=%d, pl=%d, nr=%d", pp, tg, pl, nr);
    
    auto start = std::chrono::high_resolution_clock::now();
    
    common_sampler_params params;
    params.temp = 0.7f;
    params.top_k = 40;
    params.top_p = 0.9f;
    
    auto* sampler = common_sampler_init(g_model, params);
    
    // Generate tokens for benchmark
    for (int run = 0; run < nr; run++) {
        for (int i = 0; i < tg; i++) {
            auto token = common_sampler_sample(sampler, g_context, -1);
            common_sampler_accept(sampler, token, true);
            
            llama_batch batch = llama_batch_get_one(&token, 1);
            llama_decode(g_context, batch);
        }
    }
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    
    common_sampler_free(sampler);
    
    float tokens_per_sec = (tg * nr * 1000.0f) / duration;
    char result[512];
    snprintf(result, sizeof(result),
             "Benchmark Results:\n"
             "Generated %d tokens in %lld ms\n"
             "Speed: %.2f tokens/sec\n"
             "Model: %s",
             tg * nr, (long long)duration, tokens_per_sec,
             llama_model_desc(g_model, nullptr, 0));
    
    LOGI("Benchmark result: %s", result);
    return env->NewStringUTF(result);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_unload(
    JNIEnv* env, jobject thiz) {
    
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    
    g_tokens.clear();
    g_token_pos = 0;
    g_system_prompt_pos = 0;
    g_current_pos = 0;
    
    LOGI("Unload complete");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_shutdown(
    JNIEnv* env, jobject thiz) {
    
    llama_backend_free();
    LOGI("Shutdown complete");
}
