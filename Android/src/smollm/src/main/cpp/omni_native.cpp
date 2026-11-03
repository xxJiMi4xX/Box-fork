#include <jni.h>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

constexpr const char * kTag = "BoxOmniNative";
constexpr int32_t kBatchSize = 256;
constexpr int32_t kMaxTextTokens = 4096;

class JavaString {
public:
    JavaString(JNIEnv * env, jstring value) : env_(env), source_(value) {
        if (!value) throw std::invalid_argument("Null Java string");
        data_ = env_->GetStringUTFChars(source_, nullptr);
        if (!data_) throw std::runtime_error("GetStringUTFChars failed");
    }
    JavaString(const JavaString &) = delete;
    JavaString & operator=(const JavaString &) = delete;
    ~JavaString() { if (data_) env_->ReleaseStringUTFChars(source_, data_); }
    const char * c_str() const { return data_; }
private:
    JNIEnv * env_;
    jstring source_;
    const char * data_ = nullptr;
};

void throwJava(JNIEnv * env, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}

std::u16string toUtf16(const std::string & input) {
    std::u16string output;
    output.reserve(input.size());
    for (size_t i = 0; i < input.size();) {
        const auto a = static_cast<unsigned char>(input[i]);
        uint32_t cp = 0;
        int n = 0;
        if (a < 0x80) { cp = a; n = 1; }
        else if ((a & 0xe0) == 0xc0) { cp = a & 0x1f; n = 2; }
        else if ((a & 0xf0) == 0xe0) { cp = a & 0x0f; n = 3; }
        else if ((a & 0xf8) == 0xf0) { cp = a & 0x07; n = 4; }
        else throw std::runtime_error("Invalid UTF-8 lead byte");
        if (i + static_cast<size_t>(n) > input.size()) {
            throw std::runtime_error("Truncated UTF-8 sequence");
        }
        for (int j = 1; j < n; ++j) {
            const auto b = static_cast<unsigned char>(input[i + j]);
            if ((b & 0xc0) != 0x80) throw std::runtime_error("Invalid UTF-8 continuation");
            cp = (cp << 6) | (b & 0x3f);
        }
        if ((n == 2 && cp < 0x80) || (n == 3 && cp < 0x800) ||
            (n == 4 && cp < 0x10000) || cp > 0x10ffff ||
            (cp >= 0xd800 && cp <= 0xdfff)) {
            throw std::runtime_error("Invalid UTF-8 code point");
        }
        if (cp < 0x10000) {
            output.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            output.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
            output.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
        }
        i += n;
    }
    return output;
}

jstring newJavaString(JNIEnv * env, const std::string & utf8) {
    const auto u16 = toUtf16(utf8);
    return env->NewString(reinterpret_cast<const jchar *>(u16.data()), static_cast<jsize>(u16.size()));
}

bool isCompleteUtf8(const std::string & input) {
    try { (void) toUtf16(input); return true; }
    catch (const std::runtime_error & error) {
        if (std::string(error.what()) == "Truncated UTF-8 sequence") return false;
        throw;
    }
}

struct LlamaContextFree {
    void operator()(llama_context * p) const { if (p) llama_free(p); }
};
struct LlamaModelFree {
    void operator()(llama_model * p) const { if (p) llama_model_free(p); }
};
struct LlamaSamplerFree {
    void operator()(llama_sampler * p) const { if (p) llama_sampler_free(p); }
};

class Inference final {
public:
    Inference(const std::string & filename, float minP, float temperature, float topP, int topK,
              float repeatPenalty, bool storeChats, int64_t contextSize, const std::string & chatTemplate,
              int threads, bool mmap, bool mlock, int maxOutputTokens)
              : storeChats_(storeChats), maxOutputTokens_(std::clamp(maxOutputTokens, 1, 4096)) {
        static std::once_flag once;
        std::call_once(once, [] { llama_backend_init(); });
        if (filename.empty()) throw std::invalid_argument("Model path is empty");

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.load_mode = mlock ? (mmap ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MLOCK)
                             : (mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE);
        model_.reset(llama_model_load_from_file(filename.c_str(), mp));
        if (!model_) throw std::runtime_error("Failed to load GGUF model");

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx = static_cast<uint32_t>(std::clamp<int64_t>(contextSize, 512, 8192));
        cp.n_batch = kBatchSize;
        cp.n_ubatch = std::min<uint32_t>(128, cp.n_batch);
        cp.n_threads = std::clamp(threads, 1, 8);
        cp.n_threads_batch = cp.n_threads;
        cp.abort_callback = [](void * data) -> bool {
            return static_cast<Inference *>(data)->cancelled_.load(std::memory_order_relaxed);
        };
        cp.abort_callback_data = this;
        context_.reset(llama_init_from_model(model_.get(), cp));
        if (!context_) throw std::runtime_error("Failed to initialize llama context");

        auto sp = llama_sampler_chain_default_params();
        sampler_.reset(llama_sampler_chain_init(sp));
        if (!sampler_) throw std::runtime_error("Failed to initialize sampler");

        const auto * vocabulary = llama_model_get_vocab(model_.get());
        if (repeatPenalty > 1.0f && std::isfinite(repeatPenalty)) {
            llama_sampler_chain_add(sampler_.get(),
                llama_sampler_init_penalties(llama_vocab_n_tokens(vocabulary), 128,
                                             repeatPenalty, 0.0f, 0.0f));
        }
        if (topK > 0) llama_sampler_chain_add(sampler_.get(), llama_sampler_init_top_k(topK));
        if (topP > 0.0f && topP < 1.0f && std::isfinite(topP)) {
            llama_sampler_chain_add(sampler_.get(), llama_sampler_init_top_p(topP, 1));
        }
        if (minP > 0.0f && minP < 1.0f && std::isfinite(minP)) {
            llama_sampler_chain_add(sampler_.get(), llama_sampler_init_min_p(minP, 1));
        }
        if (std::isfinite(temperature) && temperature > 0.0f) {
            llama_sampler_chain_add(sampler_.get(), llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler_.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        } else {
            llama_sampler_chain_add(sampler_.get(), llama_sampler_init_greedy());
        }

        const char * defaultTemplate = llama_model_chat_template(model_.get(), nullptr);
        chatTemplate_ = !chatTemplate.empty() ? chatTemplate
                     : (defaultTemplate ? defaultTemplate : "");
        if (chatTemplate_.empty()) throw std::runtime_error("GGUF does not provide a chat template");

        batch_ = llama_batch_init(kBatchSize, 0, 1);
        batchReady_ = true;
        if (!batch_.token || !batch_.pos || !batch_.logits || !batch_.seq_id || !batch_.n_seq_id) {
            throw std::runtime_error("Failed to allocate llama batch");
        }
    }

    ~Inference() {
        if (batchReady_) llama_batch_free(batch_);
    }

    void cancel() { cancelled_.store(true, std::memory_order_release); }

    std::array<int32_t, 5> loadProjector(const std::string & mmprojPath) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (generationActive_) throw std::runtime_error("Cannot load projector during generation");
        if (mmprojPath.empty()) throw std::invalid_argument("Projector path is empty");
        mtmd_context_params params = mtmd_context_params_default();
        params.use_gpu = false;
        params.n_threads = 4;
        params.warmup = false;
        mtmd::context_ptr candidate(mtmd_init_from_file(mmprojPath.c_str(), model_.get(), params));
        if (!candidate) throw std::runtime_error("Failed to load mmproj for GGUF model");
        const auto audioOutput = mtmd_gen_audio_get_info(candidate.get());
        const std::array<int32_t, 5> capabilities = {
            mtmd_support_audio(candidate.get()) ? 1 : 0,
            mtmd_support_vision(candidate.get()) ? 1 : 0,
            audioOutput.type != MTMD_GEN_AUDIO_TYPE_NONE ? 1 : 0,
            mtmd_get_audio_sample_rate(candidate.get()),
            audioOutput.sample_rate,
        };
        projector_ = std::move(candidate);
        return capabilities;
    }

    void resetSession() {
        std::lock_guard<std::mutex> guard(mutex_);
        cancelled_.store(true, std::memory_order_release);
        generationActive_ = false;
        turnStored_ = false;
        messages_.clear();
        response_.clear();
        utf8Pending_.clear();
        generated_ = 0;
        decodedPosition_ = 0;
        elapsedUs_ = 0;
        hasPendingToken_ = false;
        hasLogits_ = false;
        pendingToken_ = LLAMA_TOKEN_NULL;
        llama_memory_clear(llama_get_memory(context_.get()), false);
        llama_sampler_reset(sampler_.get());
        cancelled_.store(false, std::memory_order_release);
    }

    void addMessage(const std::string & role, const std::string & text) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (generationActive_) throw std::runtime_error("Cannot edit conversation during generation");
        if (role != "system" && role != "user" && role != "assistant") {
            throw std::invalid_argument("Invalid chat role");
        }
        messages_.emplace_back(role, text);
    }

    void start(const std::string & query) {
        std::lock_guard<std::mutex> guard(mutex_);
        if (generationActive_) throw std::runtime_error("Generation is already active");
        cancelled_.store(false, std::memory_order_release);
        if (!storeChats_) messages_.clear();
        messages_.emplace_back("user", query);
        generationActive_ = true;
        turnStored_ = false;
        try {
            response_.clear();
            utf8Pending_.clear();
            elapsedUs_ = 0;
            generated_ = 0;
            decodedPosition_ = 0;
            hasPendingToken_ = false;
            hasLogits_ = false;

            std::vector<llama_chat_message> chat;
            chat.reserve(messages_.size());
            size_t textSize = 0;
            for (const auto & msg : messages_) {
                textSize += msg.second.size() + msg.first.size() + 16;
                chat.push_back({msg.first.c_str(), msg.second.c_str()});
            }
            if (textSize > 2 * 1024 * 1024) throw std::runtime_error("Chat history exceeds maximum size");
            std::vector<char> formatted(std::max<size_t>(1024, textSize * 2));
            int32_t len = llama_chat_apply_template(chatTemplate_.c_str(), chat.data(),
                chat.size(), true, formatted.data(), static_cast<int32_t>(formatted.size()));
            if (len < 0) throw std::runtime_error("Unsupported model chat template");
            if (static_cast<size_t>(len) >= formatted.size()) {
                formatted.resize(static_cast<size_t>(len) + 1);
                len = llama_chat_apply_template(chatTemplate_.c_str(), chat.data(),
                    chat.size(), true, formatted.data(), static_cast<int32_t>(formatted.size()));
            }
            if (len <= 0 || static_cast<size_t>(len) >= formatted.size()) {
                throw std::runtime_error("Failed to render model chat template");
            }

            const auto * vocab = llama_model_get_vocab(model_.get());
            const int32_t need = llama_tokenize(vocab, formatted.data(), len, nullptr, 0, true, true);
            if (need == INT32_MIN) throw std::runtime_error("Tokenizer overflow");
            const int32_t capacity = need < 0 ? -need : need;
            if (capacity <= 0 || capacity >= static_cast<int32_t>(llama_n_ctx(context_.get())) ||
                capacity > kMaxTextTokens) throw std::runtime_error("Prompt exceeds context capacity");
            std::vector<llama_token> tokens(static_cast<size_t>(capacity));
            const int32_t n = llama_tokenize(vocab, formatted.data(), len, tokens.data(), capacity, true, true);
            if (n <= 0 || n > capacity) throw std::runtime_error("Prompt tokenization failed");
            tokens.resize(static_cast<size_t>(n));

            llama_memory_clear(llama_get_memory(context_.get()), false);
            llama_sampler_reset(sampler_.get());
            for (int i = 0; i < n; i += kBatchSize) {
                if (cancelled_.load(std::memory_order_acquire)) throw std::runtime_error("Generation cancelled");
                const int count = std::min<int>(kBatchSize, n - i);
                for (int j = 0; j < count; ++j) {
                    batch_.token[j] = tokens[static_cast<size_t>(i + j)];
                    batch_.pos[j] = i + j;
                    batch_.n_seq_id[j] = 1;
                    batch_.seq_id[j][0] = 0;
                    batch_.logits[j] = static_cast<int8_t>(i + j == n - 1);
                }
                batch_.n_tokens = count;
                if (llama_decode(context_.get(), batch_) != 0) {
                    throw std::runtime_error("Prompt decoding failed");
                }
            }
            decodedPosition_ = n;
            hasLogits_ = true;
        } catch (...) {
            generationActive_ = false;
            messages_.pop_back();
            throw;
        }
    }

    std::string next() {
        std::lock_guard<std::mutex> guard(mutex_);
        if (!generationActive_) return "[EOG]";
        if (cancelled_.load(std::memory_order_acquire)) {
            finalize();
            return "[EOG]";
        }
        if (!hasLogits_ || generated_ >= maxOutputTokens_ ||
            decodedPosition_ >= static_cast<int32_t>(llama_n_ctx(context_.get()) - 1)) {
            finalize();
            return "[EOG]";
        }

        const int64_t startUs = llama_time_us();
        if (hasPendingToken_) {
            batch_.n_tokens = 1;
            batch_.token[0] = pendingToken_;
            batch_.pos[0] = decodedPosition_;
            batch_.n_seq_id[0] = 1;
            batch_.seq_id[0][0] = 0;
            batch_.logits[0] = 1;
            const int rc = llama_decode(context_.get(), batch_);
            if (rc == 2 && cancelled_.load(std::memory_order_acquire)) {
                finalize();
                return "[EOG]";
            }
            if (rc != 0) throw std::runtime_error("Generation decoding failed");
            ++decodedPosition_;
        }

        const auto * vocab = llama_model_get_vocab(model_.get());
        const llama_token token = llama_sampler_sample(sampler_.get(), context_.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) {
            finalize();
            return "[EOG]";
        }
        if (token == LLAMA_TOKEN_NULL) throw std::runtime_error("Sampler returned invalid token");
        // Update sampler history so repeat penalties apply to generated tokens.
        llama_sampler_accept(sampler_.get(), token);
        pendingToken_ = token;
        hasPendingToken_ = true;
        ++generated_;

        std::vector<char> piece(64);
        int32_t bytes = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (bytes < 0) {
            piece.resize(static_cast<size_t>(-bytes));
            bytes = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (bytes < 0) throw std::runtime_error("Token text conversion failed");
        utf8Pending_.append(piece.data(), static_cast<size_t>(bytes));
        elapsedUs_ += llama_time_us() - startUs;
        if (!isCompleteUtf8(utf8Pending_)) return {};
        const std::string text = std::move(utf8Pending_);
        utf8Pending_.clear();
        response_ += text;
        return text;
    }

    void stop() {
        std::lock_guard<std::mutex> guard(mutex_);
        if (generationActive_) finalize();
    }

    float generationSpeed() const {
        std::lock_guard<std::mutex> guard(mutex_);
        return elapsedUs_ > 0 ? static_cast<float>(generated_ * 1000000.0 / elapsedUs_) : 0.0f;
    }

    int contextSizeUsed() const {
        std::lock_guard<std::mutex> guard(mutex_);
        return decodedPosition_;
    }

    std::string benchmark(int pp, int tg, int pl, int nr) {
        (void) pp; (void) tg; (void) pl; (void) nr;
        throw std::runtime_error("Native benchmark unavailable until text inference validation");
    }

private:
    void finalize() {
        generationActive_ = false;
        hasLogits_ = false;
        hasPendingToken_ = false;
        if (!turnStored_ && storeChats_ && !response_.empty()) {
            messages_.emplace_back("assistant", response_);
        }
        turnStored_ = true;
    }

    mutable std::mutex mutex_;
    std::atomic<bool> cancelled_{false};
    std::unique_ptr<llama_model, LlamaModelFree> model_;
    std::unique_ptr<llama_context, LlamaContextFree> context_;
    mtmd::context_ptr projector_;
    std::unique_ptr<llama_sampler, LlamaSamplerFree> sampler_;
    llama_batch batch_{};
    bool batchReady_ = false;
    std::vector<std::pair<std::string, std::string>> messages_;
    std::string chatTemplate_;
    std::string response_;
    std::string utf8Pending_;
    bool storeChats_ = true;
    int32_t maxOutputTokens_ = 1024;
    bool generationActive_ = false;
    bool turnStored_ = false;
    bool hasLogits_ = false;
    bool hasPendingToken_ = false;
    llama_token pendingToken_ = LLAMA_TOKEN_NULL;
    int32_t decodedPosition_ = 0;
    int64_t elapsedUs_ = 0;
    int32_t generated_ = 0;
};

std::mutex registryMutex;
std::unordered_map<jlong, std::shared_ptr<Inference>> registry;
std::atomic<jlong> nextHandle{1};

std::shared_ptr<Inference> lookup(jlong handle) {
    std::lock_guard<std::mutex> guard(registryMutex);
    const auto it = registry.find(handle);
    if (it == registry.end()) throw std::runtime_error("Invalid or closed model handle");
    return it->second;
}

template <typename F>
void invokeVoid(JNIEnv * env, F && f) {
    try { f(); }
    catch (const std::exception & e) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", e.what());
        throwJava(env, e.what());
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_loadModel(
    JNIEnv * env, jobject, jstring modelPath, jfloat minP, jfloat temperature,
    jfloat topP, jint topK, jfloat repeatPenalty, jboolean storeChats,
    jlong contextSize, jstring chatTemplate, jint nThreads, jboolean mmap, jboolean mlock,
    jint maxOutputTokens) {
    jlong handle = 0;
    invokeVoid(env, [&] {
        JavaString path(env, modelPath);
        JavaString tmpl(env, chatTemplate);
        auto engine = std::make_shared<Inference>(
            path.c_str(), minP, temperature, topP, topK, repeatPenalty,
            storeChats == JNI_TRUE, contextSize, tmpl.c_str(), nThreads,
            mmap == JNI_TRUE, mlock == JNI_TRUE, maxOutputTokens);
        handle = nextHandle.fetch_add(1, std::memory_order_relaxed);
        std::lock_guard<std::mutex> guard(registryMutex);
        registry.emplace(handle, std::move(engine));
    });
    return handle;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_loadProjectorNative(
    JNIEnv * env, jobject, jlong handle, jstring mmprojPath) {
    jintArray output = nullptr;
    invokeVoid(env, [&] {
        JavaString path(env, mmprojPath);
        const auto capabilities = lookup(handle)->loadProjector(path.c_str());
        output = env->NewIntArray(static_cast<jsize>(capabilities.size()));
        if (!output) throw std::runtime_error("Failed to allocate projector capability array");
        env->SetIntArrayRegion(output, 0, static_cast<jsize>(capabilities.size()),
                               reinterpret_cast<const jint *>(capabilities.data()));
    });
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_resetSessionNative(
    JNIEnv * env, jobject, jlong handle) {
    invokeVoid(env, [&] { lookup(handle)->resetSession(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_addChatMessage(
    JNIEnv * env, jobject, jlong handle, jstring message, jstring role) {
    invokeVoid(env, [&] {
        JavaString text(env, message), kind(env, role);
        lookup(handle)->addMessage(kind.c_str(), text.c_str());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_startCompletion(
    JNIEnv * env, jobject, jlong handle, jstring prompt) {
    invokeVoid(env, [&] {
        JavaString text(env, prompt);
        lookup(handle)->start(text.c_str());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_completionLoop(
    JNIEnv * env, jobject, jlong handle) {
    jstring result = nullptr;
    invokeVoid(env, [&] { result = newJavaString(env, lookup(handle)->next()); });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_stopCompletion(
    JNIEnv * env, jobject, jlong handle) {
    invokeVoid(env, [&] { lookup(handle)->stop(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_requestAbort(
    JNIEnv * env, jobject, jlong handle) {
    invokeVoid(env, [&] { lookup(handle)->cancel(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_close(
    JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Inference> model;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        const auto it = registry.find(handle);
        if (it == registry.end()) return;
        model = std::move(it->second);
        registry.erase(it);
    }
    model->cancel();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getResponseGenerationSpeed(
    JNIEnv * env, jobject, jlong handle) {
    jfloat result = 0;
    invokeVoid(env, [&] { result = lookup(handle)->generationSpeed(); });
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getContextSizeUsed(
    JNIEnv * env, jobject, jlong handle) {
    jint result = 0;
    invokeVoid(env, [&] { result = lookup(handle)->contextSizeUsed(); });
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_benchModel(
    JNIEnv * env, jobject, jlong handle, jint pp, jint tg, jint pl, jint nr) {
    jstring result = nullptr;
    invokeVoid(env, [&] { result = newJavaString(env, lookup(handle)->benchmark(pp, tg, pl, nr)); });
    return result;
}
