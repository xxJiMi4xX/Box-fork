#include "LLMInference.h"
#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace {
std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<LLMInference>> instances;
std::atomic<jlong> nextId{1};

std::shared_ptr<LLMInference> getInstance(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto it = instances.find(id);
    if (it == instances.end()) throw std::runtime_error("Invalid or closed native model handle");
    return it->second;
}

void throwJava(JNIEnv *env, const char *message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message);
}

std::string utf(JNIEnv *env, jstring str) {
    if (!str) throw std::invalid_argument("Null string argument");
    const char *c = env->GetStringUTFChars(str, nullptr);
    if (!c) throw std::runtime_error("Cannot access Java string");
    std::string value(c);
    env->ReleaseStringUTFChars(str, c);
    return value;
}

template<typename F> void checked(JNIEnv *env, F &&f) {
    try { f(); }
    catch (const std::exception &e) { throwJava(env, e.what()); }
    catch (...) { throwJava(env, "Unexpected native inference error"); }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_loadModel(
    JNIEnv *env, jobject, jstring path, jfloat minP, jfloat temperature, jfloat topP,
    jint topK, jfloat repeatPenalty, jboolean storeChats, jlong contextSize,
    jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock) {
    jlong id = 0;
    checked(env, [&] {
        auto instance = std::make_shared<LLMInference>();
        const std::string p = utf(env, path);
        const std::string tmpl = utf(env, chatTemplate);
        instance->loadModel(p.c_str(), minP, temperature, topP, topK, repeatPenalty,
                            storeChats, contextSize, tmpl.c_str(), nThreads, useMmap, useMlock);
        id = nextId.fetch_add(1);
        std::lock_guard<std::mutex> lock(registryMutex);
        instances.emplace(id, std::move(instance));
    });
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_addChatMessage(
    JNIEnv *env, jobject, jlong id, jstring message, jstring role) {
    checked(env, [&] {
        auto instance = getInstance(id);
        const std::string m = utf(env, message);
        const std::string r = utf(env, role);
        instance->addChatMessage(m.c_str(), r.c_str());
    });
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getResponseGenerationSpeed(
    JNIEnv *env, jobject, jlong id) {
    jfloat value = 0;
    checked(env, [&] { value = getInstance(id)->getResponseGenerationTime(); });
    return value;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getContextSizeUsed(
    JNIEnv *env, jobject, jlong id) {
    jint value = 0;
    checked(env, [&] { value = getInstance(id)->getContextSizeUsed(); });
    return value;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_close(JNIEnv *env, jobject, jlong id) {
    checked(env, [&] {
        std::shared_ptr<LLMInference> instance;
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            auto it = instances.find(id);
            if (it == instances.end()) return;
            instance = std::move(it->second);
            instances.erase(it);
        }
        instance->requestStop();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_startCompletion(
    JNIEnv *env, jobject, jlong id, jstring prompt) {
    checked(env, [&] {
        auto instance = getInstance(id);
        const std::string q = utf(env, prompt);
        instance->startCompletion(q.c_str());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_completionLoop(
    JNIEnv *env, jobject, jlong id) {
    jstring result = nullptr;
    checked(env, [&] {
        const std::string piece = getInstance(id)->completionLoop();
        result = env->NewStringUTF(piece.c_str());
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_stopCompletion(
    JNIEnv *env, jobject, jlong id) {
    checked(env, [&] { getInstance(id)->stopCompletion(); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_benchModel(
    JNIEnv *env, jobject, jlong id, jint pp, jint tg, jint pl, jint nr) {
    jstring result = nullptr;
    checked(env, [&] {
        const std::string value = getInstance(id)->benchModel(pp, tg, pl, nr);
        result = env->NewStringUTF(value.c_str());
    });
    return result;
}
