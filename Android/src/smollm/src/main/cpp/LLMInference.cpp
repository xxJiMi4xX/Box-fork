#include "LLMInference.h"
#include "ggml-backend.h"

#include <algorithm>
#include <cmath>
#include <climits>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <stdexcept>

LLMInference::~LLMInference() {
    stopRequested_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    if (sampler_) llama_sampler_free(sampler_);
    if (context_) llama_free(context_);
    if (model_) llama_model_free(model_);
}

void LLMInference::loadModel(const char *path, float minP, float temperature, float topP,
                             int topK, float repeatPenalty, bool storeChats,
                             long contextSize, const char *chatTemplate,
                             int nThreads, bool useMmap, bool useMlock) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!path || !*path || model_) throw std::invalid_argument("Invalid model path or duplicate load");
    if (!std::isfinite(temperature) || !std::isfinite(minP) || !std::isfinite(topP) ||
        !std::isfinite(repeatPenalty)) throw std::invalid_argument("Invalid sampling configuration");
    if (contextSize < 256 || contextSize > 8192 || nThreads < 1 || nThreads > 32)
        throw std::invalid_argument("Context must be 256..8192; threads 1..32");
    ggml_backend_load_all();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // Verified CPU baseline; add Vulkan only after stability.
    mp.use_mmap = useMmap;
    mp.use_mlock = useMlock;
    model_ = llama_model_load_from_file(path, mp);
    if (!model_) throw std::runtime_error("Cannot load GGUF model");
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(contextSize);
    cp.n_batch = static_cast<uint32_t>(std::min<long>(512, contextSize));
    cp.n_ubatch = static_cast<uint32_t>(std::min<long>(256, contextSize));
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    context_ = llama_init_from_model(model_, cp);
    if (!context_) throw std::runtime_error("Cannot initialize llama context");
    llama_sampler_chain_params sc = llama_sampler_chain_default_params();
    sc.no_perf = true;
    sampler_ = llama_sampler_chain_init(sc);
    if (!sampler_) throw std::runtime_error("Cannot initialize sampler");
    if (repeatPenalty > 1.0f) {
        llama_sampler_chain_add(sampler_, llama_sampler_init_penalties(
            llama_vocab_n_tokens(llama_model_get_vocab(model_)), 256,
            repeatPenalty, 0.0f, 0.0f));
    }
    if (topK > 0) llama_sampler_chain_add(sampler_, llama_sampler_init_top_k(topK));
    if (topP > 0 && topP < 1) llama_sampler_chain_add(sampler_, llama_sampler_init_top_p(topP, 1));
    if (minP > 0 && minP < 1) llama_sampler_chain_add(sampler_, llama_sampler_init_min_p(minP, 1));
    llama_sampler_chain_add(sampler_, llama_sampler_init_temp(std::max(0.0f, temperature)));
    llama_sampler_chain_add(sampler_, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    const char *modelTemplate = llama_model_chat_template(model_, nullptr);
    chatTemplate_ = chatTemplate && *chatTemplate ? chatTemplate :
        (modelTemplate ? modelTemplate : "");
    if (chatTemplate_.empty()) throw std::runtime_error("GGUF has no chat template; select a compatible template");
    storeChats_ = storeChats;
}

std::vector<llama_token> LLMInference::tokenize(const std::string &str) {
    if (str.size() > static_cast<size_t>(INT32_MAX)) throw std::length_error("Prompt too large");
    auto *vocab = llama_model_get_vocab(model_);
    int32_t needed = llama_tokenize(vocab, str.c_str(), static_cast<int32_t>(str.size()),
                                    nullptr, 0, true, true);
    if (needed == INT32_MIN) throw std::runtime_error("Token count overflow");
    if (needed == 0) return {};
    size_t cap = static_cast<size_t>(std::abs(needed));
    if (cap > 1048576) throw std::runtime_error("Prompt token limit exceeded");
    std::vector<llama_token> tokens(cap);
    int32_t count = llama_tokenize(vocab, str.c_str(), static_cast<int32_t>(str.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (count < 0 || static_cast<size_t>(count) > tokens.size())
        throw std::runtime_error("llama_tokenize failed");
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

std::string LLMInference::tokenToPiece(llama_token token) {
    const llama_vocab *vocab = llama_model_get_vocab(model_);
    int32_t len = llama_token_to_piece(vocab, token, nullptr, 0, 0, false);
    if (len == INT32_MIN) throw std::runtime_error("Invalid token piece");
    std::vector<char> buffer(static_cast<size_t>(std::max(8, std::abs(len))));
    int32_t n = llama_token_to_piece(vocab, token, buffer.data(),
                                    static_cast<int32_t>(buffer.size()), 0, false);
    if (n < 0 || n > static_cast<int32_t>(buffer.size()))
        throw std::runtime_error("llama_token_to_piece failed");
    return {buffer.data(), static_cast<size_t>(n)};
}

void LLMInference::addChatMessage(const char *message, const char *role) {
    if (!message || !role) throw std::invalid_argument("Null chat message");
    std::lock_guard<std::mutex> lock(mutex_);
    if (generating_) throw std::runtime_error("Cannot modify history while generating");
    messages_.emplace_back(role, message);
}

float LLMInference::getResponseGenerationTime() {
    std::lock_guard<std::mutex> lock(mutex_);
    return responseTimeUs_ > 0 ? static_cast<float>(responseTokens_ * 1000000.0 / responseTimeUs_) : 0.0f;
}

int LLMInference::getContextSizeUsed() {
    std::lock_guard<std::mutex> lock(mutex_);
    return contextUsed_;
}

void LLMInference::startCompletion(const char *query) {
    if (!query) throw std::invalid_argument("Null user prompt");
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ || generating_) throw std::runtime_error("Model unavailable or generation active");
    stopRequested_.store(false);
    if (!storeChats_) {
        messages_.erase(std::remove_if(messages_.begin(), messages_.end(),
            [](const auto &m) { return m.first != "system"; }), messages_.end());
    }
    messages_.emplace_back("user", query);
    std::vector<llama_chat_message> chat;
    chat.reserve(messages_.size());
    for (const auto &m : messages_) chat.push_back({m.first.c_str(), m.second.c_str()});
    int32_t needed = llama_chat_apply_template(chatTemplate_.c_str(), chat.data(), chat.size(),
                                                true, nullptr, 0);
    if (needed < 0 || needed > 1048576)
        throw std::runtime_error("Model chat template is incompatible");
    std::vector<char> buffer(static_cast<size_t>(needed) + 1, '\0');
    int32_t actual = llama_chat_apply_template(chatTemplate_.c_str(), chat.data(), chat.size(),
                                               true, buffer.data(), static_cast<int32_t>(buffer.size()));
    if (actual < 0 || actual > needed) throw std::runtime_error("Chat template rendering failed");
    auto tokens = tokenize(std::string(buffer.data(), static_cast<size_t>(actual)));
    if (tokens.empty() || tokens.size() + 1 > llama_n_ctx(context_))
        throw std::runtime_error("Conversation exceeds llama context");
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
    response_.clear();
    pendingUtf8_.clear();
    responseTokens_ = 0;
    responseTimeUs_ = 0;
    contextUsed_ = 0;
    const size_t batchCap = llama_n_batch(context_);
    if (batchCap == 0) throw std::runtime_error("Invalid batch size");
    for (size_t i = 0; i < tokens.size(); i += batchCap) {
        if (stopRequested_.load()) throw std::runtime_error("Prefill cancelled");
        auto count = static_cast<int32_t>(std::min(batchCap, tokens.size() - i));
        llama_batch batch = llama_batch_get_one(tokens.data() + i, count);
        if (llama_decode(context_, batch) != 0) throw std::runtime_error("Prompt decoding failed");
        contextUsed_ += count;
    }
    generating_ = true;
}

bool LLMInference::validUtf8(const std::string &bytes) {
    const auto *p = reinterpret_cast<const unsigned char *>(bytes.data());
    const size_t n = bytes.size();
    for (size_t i = 0; i < n;) {
        unsigned char c = p[i];
        if (c < 0x80) { ++i; continue; }
        size_t count = (c >= 0xC2 && c <= 0xDF) ? 2 :
                       (c >= 0xE0 && c <= 0xEF) ? 3 :
                       (c >= 0xF0 && c <= 0xF4) ? 4 : 0;
        if (!count || i + count > n) return false;
        for (size_t j = 1; j < count; ++j) if ((p[i + j] & 0xC0) != 0x80) return false;
        if (count == 3 && ((c == 0xE0 && p[i + 1] < 0xA0) ||
                           (c == 0xED && p[i + 1] >= 0xA0))) return false;
        if (count == 4 && ((c == 0xF0 && p[i + 1] < 0x90) ||
                           (c == 0xF4 && p[i + 1] >= 0x90))) return false;
        i += count;
    }
    return true;
}

void LLMInference::finishGeneration() {
    if (!generating_) return;
    generating_ = false;
    if (!pendingUtf8_.empty()) pendingUtf8_.clear();
    if (!response_.empty()) messages_.emplace_back("assistant", response_);
}

std::string LLMInference::completionLoop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!generating_) return "[EOG]";
    if (stopRequested_.load() || contextUsed_ + 1 >= static_cast<int>(llama_n_ctx(context_))) {
        finishGeneration();
        return "[EOG]";
    }
    int64_t start = llama_time_us();
    llama_token token = llama_sampler_sample(sampler_, context_, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(model_), token)) {
        finishGeneration();
        return "[EOG]";
    }
    std::string piece = tokenToPiece(token);
    llama_batch batch = llama_batch_get_one(&token, 1);
    if (llama_decode(context_, batch) != 0) {
        finishGeneration();
        throw std::runtime_error("Token decoding failed");
    }
    contextUsed_ += 1;
    responseTimeUs_ += llama_time_us() - start;
    responseTokens_ += 1;
    pendingUtf8_ += piece;
    // Never pass an incomplete UTF-8 code point to JNI NewStringUTF.
    if (!validUtf8(pendingUtf8_)) {
        if (pendingUtf8_.size() > 8) throw std::runtime_error("Invalid UTF-8 model output");
        return "";
    }
    response_ += pendingUtf8_;
    std::string complete = std::move(pendingUtf8_);
    pendingUtf8_.clear();
    return complete;
}

void LLMInference::requestStop() noexcept { stopRequested_.store(true); }

void LLMInference::stopCompletion() {
    stopRequested_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    finishGeneration();
}

std::string LLMInference::benchModel(int, int, int, int) {
    // A synthetic benchmark could corrupt in-flight KV state; fail explicitly until
    // a separate benchmark context is allocated and validated.
    throw std::runtime_error("GGUF benchmark requires a separate context (not enabled in this build)");
}
