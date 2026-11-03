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

void appendUtf8(std::string &out, uint32_t cp) {
    if (cp <= 0x7F) out.push_back(static_cast<char>(cp));
    else if (cp <= 0x7FF) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp <= 0xFFFF) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

// JNI's GetStringUTFChars returns Modified UTF-8. llama.cpp expects real UTF-8.
std::string utf(JNIEnv *env, jstring str) {
    if (!str) throw std::invalid_argument("Null string argument");
    const jsize n = env->GetStringLength(str);
    const jchar *data = env->GetStringChars(str, nullptr);
    if (!data) throw std::runtime_error("Cannot access Java UTF-16 string");
    std::string value;
    value.reserve(static_cast<size_t>(n) * 3);
    for (jsize i = 0; i < n; ++i) {
        uint32_t cp = data[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n &&
            data[i + 1] >= 0xDC00 && data[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (data[++i] - 0xDC00);
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        appendUtf8(value, cp);
    }
    env->ReleaseStringChars(str, data);
    if (value.find('\0') != std::string::npos)
        throw std::invalid_argument("NUL characters cannot be used in model prompts/paths");
    return value;
}

// LLM text is standard UTF-8, including 4-byte Greek-adjacent symbols/emojis.
// NewStringUTF would require Modified UTF-8; use NewString with UTF-16 instead.
jstring javaString(JNIEnv *env, const std::string &value) {
    std::vector<jchar> text;
    text.reserve(value.size());
    const auto *ptr = reinterpret_cast<const unsigned char *>(value.data());
    for (size_t i = 0; i < value.size();) {
        const uint8_t c = ptr[i++];
        uint32_t cp = 0;
        size_t continuation = 0;
        if (c <= 0x7F) cp = c;
        else if (c >= 0xC2 && c <= 0xDF) { cp = c & 0x1F; continuation = 1; }
        else if (c >= 0xE0 && c <= 0xEF) { cp = c & 0x0F; continuation = 2; }
        else if (c >= 0xF0 && c <= 0xF4) { cp = c & 0x07; continuation = 3; }
        else throw std::runtime_error("Model returned invalid UTF-8");
        if (continuation > value.size() - i)
            throw std::runtime_error("Model returned incomplete UTF-8");
        while (continuation--) {
            if ((ptr[i] & 0xC0) != 0x80)
                throw std::runtime_error("Model returned malformed UTF-8");
            cp = (cp << 6) | (ptr[i++] & 0x3F);
        }
        if ((cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF)
            throw std::runtime_error("Model returned invalid Unicode");
        if (cp <= 0xFFFF) text.push_back(static_cast<jchar>(cp));
        else {
            cp -= 0x10000;
            text.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            text.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    if (text.empty()) return env->NewStringUTF("");
    return env->NewString(text.data(), static_cast<jsize>(text.size()));
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
    jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock, jstring mmprojPath) {
    jlong id = 0;
    checked(env, [&] {
        auto instance = std::make_shared<LLMInference>();
        const std::string p = utf(env, path);
        const std::string tmpl = utf(env, chatTemplate);
        const std::string mmproj = utf(env, mmprojPath);
        instance->loadModel(p.c_str(), minP, temperature, topP, topK, repeatPenalty,
                            storeChats, contextSize, tmpl.c_str(), nThreads, useMmap, useMlock, mmproj.c_str());
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
        const auto piece = getInstance(id)->completionLoop();
        if (piece.has_value()) result = javaString(env, *piece);
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeStartMixedCompletion(
    JNIEnv *env, jobject, jlong id, jstring prompt) {
    checked(env, [&] {
        const std::string q = utf(env, prompt);
        getInstance(id)->startMixedCompletion(q.c_str());
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeMixedCompletionLoop(
    JNIEnv *env, jobject, jlong id) {
    jobject result = nullptr;
    checked(env, [&] {
        const auto part = getInstance(id)->mixedCompletionLoop();
        if (part.pcm16.size() > static_cast<size_t>(INT32_MAX))
            throw std::runtime_error("Mixed PCM chunk is too large");
        jclass cls = env->FindClass(
            "com/jegly/offlineLLM/smollm/SmolLM$MixedVoiceStep");
        if (!cls) return;
        const jmethodID constructor = env->GetMethodID(
            cls, "<init>", "(Ljava/lang/String;[BIZ)V");
        if (!constructor) { env->DeleteLocalRef(cls); return; }
        const jstring text = javaString(env, part.text);
        const jbyteArray pcm = env->NewByteArray(static_cast<jsize>(part.pcm16.size()));
        if (pcm && !part.pcm16.empty()) {
            env->SetByteArrayRegion(
                pcm, 0, static_cast<jsize>(part.pcm16.size()),
                reinterpret_cast<const jbyte *>(part.pcm16.data()));
        }
        if (!env->ExceptionCheck() && text && pcm) {
            result = env->NewObject(cls, constructor, text, pcm,
                                    static_cast<jint>(part.sampleRate),
                                    static_cast<jboolean>(part.done));
        }
        if (pcm) env->DeleteLocalRef(pcm);
        if (text) env->DeleteLocalRef(text);
        env->DeleteLocalRef(cls);
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
        result = javaString(env, value);
    });
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeAudioSampleRate(
    JNIEnv *env, jobject, jlong id) {
    jint rate = -1;
    checked(env, [&] { rate = getInstance(id)->audioInputSampleRate(); });
    return rate;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeTranscribeAudio(
    JNIEnv *env, jobject, jlong id, jfloatArray samples) {
    jstring result = nullptr;
    checked(env, [&] {
        if (!samples) throw std::runtime_error("Audio samples are missing");
        auto instance = getInstance(id);
        const int sampleRate = instance->audioInputSampleRate();
        const jsize n = env->GetArrayLength(samples);
        if (sampleRate <= 0 || n <= 0 || n > sampleRate * 90)
            throw std::runtime_error("Invalid PCM audio length or sample rate");
        std::vector<float> pcm(static_cast<size_t>(n));
        env->GetFloatArrayRegion(samples, 0, n, pcm.data());
        if (env->ExceptionCheck()) return;
        const std::string transcript = instance->transcribeAudio(pcm);
        result = javaString(env, transcript);
    });
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeSynthesizeSpeech(
    JNIEnv *env, jobject, jlong id, jstring text) {
    jbyteArray result = nullptr;
    checked(env, [&] {
        const std::string content = utf(env, text);
        const auto data = getInstance(id)->synthesizeSpeech(content);
        result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                                           reinterpret_cast<const jbyte *>(data.data()));
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeRequestStop(
    JNIEnv *env, jobject, jlong id) {
    checked(env, [&] { getInstance(id)->requestStop(); });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeAudioCapabilities(
    JNIEnv *env, jobject, jlong id) {
    jint capabilities = 0;
    checked(env, [&] { capabilities = getInstance(id)->audioCapabilities(); });
    return capabilities;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_nativeResetConversation(
    JNIEnv *env, jobject, jlong id, jstring systemPrompt) {
    checked(env, [&] {
        auto instance = getInstance(id);
        const std::string prompt = utf(env, systemPrompt);
        instance->resetConversation(prompt.c_str());
    });
}
