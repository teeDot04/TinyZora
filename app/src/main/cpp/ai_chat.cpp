#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <android/log.h>
#include "llama.h"
#include "common.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ai-chat", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ai-chat", __VA_ARGS__)

static llama_model*    g_model    = nullptr;
static llama_context*  g_context  = nullptr;
static llama_sampler*  g_sampler  = nullptr;
static std::vector<llama_token> g_tokens;
static size_t g_token_pos = 0;
static llama_pos g_system_prompt_pos = 0;
static llama_pos g_current_pos = 0;
static std::string g_system_prompt_text;

static void free_model_resources() {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_context) { llama_free(g_context);          g_context = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    g_tokens.clear();
    g_token_pos = 0;
    g_system_prompt_pos = 0;
    g_current_pos = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_init(JNIEnv*, jobject) {
    llama_backend_init();
    LOGI("Llama backend initialized");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_load(
    JNIEnv* env, jobject, jstring path, jint ctx_size) {
    
    free_model_resources();
    g_system_prompt_text.clear();

    const char* model_path = env->GetStringUTFChars(path, nullptr);
    LOGI("Loading model: %s  ctx=%d", model_path, ctx_size);

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap  = true;
    mp.use_mlock = false;
    g_model = llama_model_load_from_file(model_path, mp);
    env->ReleaseStringUTFChars(path, model_path);
    
    if (!g_model) { LOGE("Failed to load model"); return 1; }
    
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx    = ctx_size;
    cp.n_batch  = 512;
    cp.n_ubatch = 512;
    g_context = llama_init_from_model(g_model, cp);
    
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model); g_model = nullptr;
        return 2;
    }
    LOGI("Model loaded");
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
    g_system_prompt_text = s;
    auto voc = llama_model_get_vocab(g_model);
    
    // add_special=true: Inject BOS exactly once at the start
    auto tokens = common_tokenize(voc, s, true, true);
    env->ReleaseStringUTFChars(prompt, s);
    
    // FIX: Use explicit positions starting at 0
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i]   = tokens[i];
        batch.pos[i]     = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]  = (i == tokens.size() - 1) ? 1 : 0;
    }
    
    if (llama_decode(g_context, batch)) {
        llama_batch_free(batch);
        return 1;
    }
    llama_batch_free(batch);
    
    g_system_prompt_pos = tokens.size();
    g_current_pos = tokens.size();
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_processUserPrompt(
    JNIEnv* env, jobject, jstring prompt, jint predict_length,
    jfloat temperature, jint top_k, jfloat top_p) {
    
    const char* s = env->GetStringUTFChars(prompt, nullptr);
    auto voc = llama_model_get_vocab(g_model);
    
    // FIX: add_special=false. BOS was already added by system prompt.
    auto tokens = common_tokenize(voc, s, false, true);
    env->ReleaseStringUTFChars(prompt, s);
    
    // FIX: Use explicit positions continuing from g_current_pos
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i]   = tokens[i];
        batch.pos[i]     = g_current_pos + i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i]  = (i == tokens.size() - 1) ? 1 : 0;
    }
    
    if (llama_decode(g_context, batch)) {
        llama_batch_free(batch);
        return 1;
    }
    llama_batch_free(batch);
    g_current_pos += tokens.size();
    
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    
    auto* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (top_k > 0)       llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    if (top_p < 1.0f)    llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    if (temperature > 0) llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    
    // FIX: Terminal dist sampler required to actually pick a token
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));
    g_sampler = chain;
    
    g_tokens.clear();
    g_token_pos = 0;
    
    for (int i = 0; i < predict_length; i++) {
        auto token = llama_sampler_sample(g_sampler, g_context, -1);
        llama_sampler_accept(g_sampler, token);
        if (llama_vocab_is_eog(voc, token)) break;
        
        g_tokens.push_back(token);
        
        // FIX: Explicit position for generated tokens
        llama_batch gen_batch = llama_batch_init(1, 0, 1);
        gen_batch.token[0] = token;
        gen_batch.pos[0] = g_current_pos;
        gen_batch.n_seq_id[0] = 1;
        gen_batch.seq_id[0][0] = 0;
        gen_batch.logits[0] = 1;
        
        if (llama_decode(g_context, gen_batch)) {
            llama_batch_free(gen_batch);
            break;
        }
        llama_batch_free(gen_batch);
        g_current_pos++;
    }
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_generateNextToken(JNIEnv* env, jobject) {
    if (g_token_pos >= g_tokens.size()) return nullptr;
    auto token = g_tokens[g_token_pos++];
    auto voc = llama_model_get_vocab(g_model);
    char buf[256];
    int n = llama_token_to_piece(voc, token, buf, sizeof(buf), 0, true);
    if (n < 0) return nullptr;
    return env->NewStringUTF(std::string(buf, n).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_benchModel(
    JNIEnv* env, jobject, jint pp, jint tg, jint pl, jint nr) {
    
    auto voc = llama_model_get_vocab(g_model);
    llama_kv_cache_clear(g_context);
    g_current_pos = 0;
    
    auto* chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));
    
    auto start = std::chrono::high_resolution_clock::now();
    llama_pos bench_pos = 0;
    
    for (int run = 0; run < nr; run++) {
        for (int i = 0; i < tg; i++) {
            auto token = llama_sampler_sample(chain, g_context, -1);
            llama_sampler_accept(chain, token);
            
            llama_batch batch = llama_batch_init(1, 0, 1);
            batch.token[0] = token;
            batch.pos[0] = bench_pos;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = 1;
            
            llama_decode(g_context, batch);
            llama_batch_free(batch);
            bench_pos++;
        }
    }
    
    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
    llama_sampler_free(chain);
    
    // Restore conversation context so user can keep chatting
    if (!g_system_prompt_text.empty()) {
        auto sys_tokens = common_tokenize(voc, g_system_prompt_text.c_str(), true, true);
        llama_batch sys_batch = llama_batch_init(sys_tokens.size(), 0, 1);
        for (size_t i = 0; i < sys_tokens.size(); i++) {
            sys_batch.token[i] = sys_tokens[i];
            sys_batch.pos[i] = i;
            sys_batch.n_seq_id[i] = 1;
            sys_batch.seq_id[i][0] = 0;
            sys_batch.logits[i] = (i == sys_tokens.size() - 1) ? 1 : 0;
        }
        llama_decode(g_context, sys_batch);
        llama_batch_free(sys_batch);
        g_current_pos = sys_tokens.size();
        g_system_prompt_pos = sys_tokens.size();
    }
    
    float tokens_per_sec = (tg * nr * 1000.0f) / (float)duration;
    char desc[256]; llama_model_desc(g_model, desc, sizeof(desc));
    char result[512];
    snprintf(result, sizeof(result),
        "Generated %d tokens in %lld ms\nSpeed: %.2f t/s\nModel: %s",
        tg * nr, (long long)duration, tokens_per_sec, desc);
        
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_unload(JNIEnv*, jobject) {
    free_model_resources();
    g_system_prompt_text.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_telo_tinyzora_core_inference_InferenceEngineImpl_shutdown(JNIEnv*, jobject) {
    free_model_resources();
    llama_backend_free();
}
