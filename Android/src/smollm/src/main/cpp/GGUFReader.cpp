#include "gguf.h"
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_GGUFReader_getGGUFContextNativeHandle(
    JNIEnv *env, jobject, jstring modelPath) {
    if (!modelPath) return 0;
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return 0;
    gguf_init_params params = { .no_alloc = true, .ctx = nullptr };
    gguf_context *ctx = gguf_init_from_file(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_GGUFReader_getContextSize(
    JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<gguf_context *>(handle);
    if (!ctx) return -1;
    int64_t key = gguf_find_key(ctx, "general.architecture");
    if (key < 0) return -1;
    const char *arch = gguf_get_val_str(ctx, key);
    std::string lengthKey = std::string(arch) + ".context_length";
    key = gguf_find_key(ctx, lengthKey.c_str());
    if (key < 0 || gguf_get_kv_type(ctx, key) != GGUF_TYPE_UINT32) return -1;
    return gguf_get_val_u32(ctx, key);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_GGUFReader_getChatTemplate(
    JNIEnv *env, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<gguf_context *>(handle);
    if (!ctx) return env->NewStringUTF("");
    const int64_t key = gguf_find_key(ctx, "tokenizer.chat_template");
    return env->NewStringUTF(key < 0 ? "" : gguf_get_val_str(ctx, key));
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_GGUFReader_releaseContext(
    JNIEnv *, jobject, jlong handle) {
    if (handle) gguf_free(reinterpret_cast<gguf_context *>(handle));
}
