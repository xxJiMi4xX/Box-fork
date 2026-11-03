#include "LLMInference.h"
#include "ggml-backend.h"
#include "mtmd-helper.h"
#include <memory>
#include <mutex>

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
    if (mmproj_) mtmd_free(mmproj_);
    if (sampler_) llama_sampler_free(sampler_);
    if (context_) llama_free(context_);
    if (model_) llama_model_free(model_);
}

void LLMInference::loadModel(const char *path, float minP, float temperature, float topP,
                             int topK, float repeatPenalty, bool storeChats,
                             long contextSize, const char *chatTemplate,
                             int nThreads, bool useMmap, bool useMlock, const char *mmprojPath) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!path || !*path || model_) throw std::invalid_argument("Invalid model path or duplicate load");
    if (!std::isfinite(temperature) || !std::isfinite(minP) || !std::isfinite(topP) ||
        !std::isfinite(repeatPenalty)) throw std::invalid_argument("Invalid sampling configuration");
    if (contextSize < 256 || contextSize > 8192 || nThreads < 1 || nThreads > 32)
        throw std::invalid_argument("Context must be 256..8192; threads 1..32");
    static std::once_flag initOnce;
    std::call_once(initOnce, [] {
        llama_backend_init();
        ggml_backend_load_all();
    });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // Verified CPU baseline; add Vulkan only after stability.
    mp.load_mode = useMmap
        ? (useMlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
        : (useMlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);
    model_ = llama_model_load_from_file(path, mp);
    if (!model_) throw std::runtime_error("Cannot load GGUF model");
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(contextSize);
    cp.n_batch = static_cast<uint32_t>(std::min<long>(512, contextSize));
    cp.n_ubatch = static_cast<uint32_t>(std::min<long>(256, contextSize));
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    cp.no_perf = true;
    cp.embeddings = true; // Required for LFM3 audio-generation hidden state.
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
    if (mmprojPath && *mmprojPath) {
        auto params = mtmd_context_params_default();
        params.use_gpu = false;
        params.n_threads = nThreads;
        mmproj_ = mtmd_init_from_file(mmprojPath, model_, params);
        if (!mmproj_) throw std::runtime_error("Cannot load the matching mmproj GGUF");
    }
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
        // embeddings=true is required for the speech decoder, but without an
        // explicit logits mask it outputs logits for every prompt position.
        std::vector<int8_t> logitsFlags(static_cast<size_t>(count), 0);
        if (i + static_cast<size_t>(count) == tokens.size()) logitsFlags.back() = 1;
        batch.logits = logitsFlags.data();
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

int LLMInference::audioInputSampleRate() {
    std::lock_guard<std::mutex> lock(mutex_);
    return mmproj_ && mtmd_support_audio(mmproj_) ? mtmd_get_audio_sample_rate(mmproj_) : -1;
}

std::string LLMInference::transcribeAudio(const std::vector<float> &pcm) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || !mtmd_support_audio(mmproj_))
        throw std::runtime_error("Audio input requires a compatible mmproj GGUF");
    if (generating_ || pcm.empty() || pcm.size() > static_cast<size_t>(mtmd_get_audio_sample_rate(mmproj_)) * 90)
        throw std::runtime_error("Audio busy, empty or longer than 90 seconds");
    stopRequested_.store(false);

    const std::string sys = "Perform ASR in Greek.";
    const std::string marker = mtmd_default_marker();
    const llama_chat_message chat[] = {
        {"system", sys.c_str()},
        {"user", marker.c_str()}
    };
    int32_t required = llama_chat_apply_template(chatTemplate_.c_str(), chat, 2, true, nullptr, 0);
    if (required <= 0 || required > 65536)
        throw std::runtime_error("Cannot render the ASR chat template");
    std::vector<char> rendered(static_cast<size_t>(required) + 1);
    int32_t used = llama_chat_apply_template(chatTemplate_.c_str(), chat, 2, true,
                                              rendered.data(), static_cast<int32_t>(rendered.size()));
    if (used < 0 || used > required)
        throw std::runtime_error("Cannot render ASR user prompt");
    mtmd_bitmap *bitmap = mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
    if (!bitmap) throw std::runtime_error("Cannot create audio bitmap");
    std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmapHolder(bitmap, mtmd_bitmap_free);
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (!chunks) throw std::runtime_error("Cannot allocate audio chunks");
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunkHolder(chunks, mtmd_input_chunks_free);
    mtmd_input_text text = {rendered.data(), static_cast<size_t>(used), true, true};
    const mtmd_bitmap *ptr = bitmap;
    if (mtmd_tokenize(mmproj_, chunks, &text, &ptr, 1) != 0)
        throw std::runtime_error("Audio preprocessing failed");
    if (mtmd_helper_get_n_tokens(chunks) + 1 > llama_n_ctx(context_))
        throw std::runtime_error("Audio input exceeds context window");

    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
    llama_pos nPast = 0;
    if (mtmd_helper_eval_chunks(mmproj_, context_, chunks, 0, 0,
        static_cast<int32_t>(llama_n_batch(context_)), true, &nPast) != 0)
        throw std::runtime_error("Audio encoder/decoder failed");
    std::string transcript;
    for (int i = 0; i < 512 && nPast + 1 < static_cast<llama_pos>(llama_n_ctx(context_)); ++i) {
        if (stopRequested_.load()) throw std::runtime_error("Transcription interrupted");
        const llama_token tok = llama_sampler_sample(sampler_, context_, -1);
        if (llama_vocab_is_eog(llama_model_get_vocab(model_), tok)) break;
        transcript += tokenToPiece(tok);
        llama_token mutableToken = tok;
        if (llama_decode(context_, llama_batch_get_one(&mutableToken, 1)) != 0)
            throw std::runtime_error("ASR decode failed");
        ++nPast;
    }
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
    if (transcript.empty()) throw std::runtime_error("No audio transcript produced");
    return transcript;
}

std::vector<uint8_t> LLMInference::synthesizeSpeech(const std::string &text) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || mtmd_gen_audio_get_info(mmproj_).type == MTMD_GEN_AUDIO_TYPE_NONE)
        throw std::runtime_error("Audio synthesis requires a compatible mmproj GGUF");
    if (generating_ || text.empty() || text.size() > 16384)
        throw std::runtime_error("Speech generation unavailable or input too long");
    // LFM3-Omni's audio generation requires the dedicated audio-start token,
    // not a normal chat response. Render the regular model chat structure first.
    const auto caps = mtmd_gen_audio_get_info(mmproj_);
    if (caps.type != MTMD_GEN_AUDIO_TYPE_LFM3A)
        throw std::runtime_error("This Greek voice mode requires an LFM3-Audio/Omni projector");
    stopRequested_.store(false);
    const std::string system = "Perform TTS in Greek using a female voice.";
    const llama_chat_message chat[] = {{"system", system.c_str()}, {"user", text.c_str()}};
    const int32_t required = llama_chat_apply_template(chatTemplate_.c_str(),
                                                         chat, 2, true, nullptr, 0);
    if (required <= 0 || required > 65536)
        throw std::runtime_error("Cannot render LFM3 speech request");
    std::vector<char> rendered(static_cast<size_t>(required) + 1, '\0');
    const int32_t used = llama_chat_apply_template(chatTemplate_.c_str(), chat, 2,
                                                     true, rendered.data(), static_cast<int32_t>(rendered.size()));
    if (used <= 0 || used > required)
        throw std::runtime_error("Cannot render LFM3 speech request");
    std::string promptStr(rendered.data(), static_cast<size_t>(used));
    promptStr += "<|audio_start|>";
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
    auto *gen = mtmd_helper_gen_audio_init(context_, mmproj_);
    if (!gen) throw std::runtime_error("Cannot initialize audio generator");
    std::unique_ptr<mtmd_helper_gen_audio, decltype(&mtmd_helper_gen_audio_free)> holder(
        gen, mtmd_helper_gen_audio_free);
    auto inp = mtmd_helper_gen_audio_inp_default();
    inp.prompt = promptStr.c_str();
    inp.prompt_len = promptStr.size();
    inp.lang = "el";
    inp.voice = "female";
    inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_WAV;
    inp.stream = false;
    if (mtmd_helper_gen_audio_set_input(gen, &inp) != 0)
        throw std::runtime_error("Cannot initialize Greek speech request");
    for (;;) {
        if (stopRequested_.load()) throw std::runtime_error("Speech generation cancelled");
        const int32_t left = mtmd_helper_gen_audio_step_prompt(
            gen, static_cast<int32_t>(llama_n_batch(context_)));
        if (left < 0) throw std::runtime_error("Speech prompt encoding failed");
        if (left == 0) break;
    }
    llama_token semantic = llama_sampler_sample(sampler_, context_, -1);
    const float *hidden = llama_get_embeddings_ith(context_, -1);
    bool done = false;
    for (int frames = 0; frames < 512 && !done; ++frames) {
        if (stopRequested_.load()) throw std::runtime_error("Speech generation cancelled");
        const float *nextHidden = nullptr;
        if (mtmd_helper_gen_audio_step_gen(gen, semantic, hidden, &nextHidden, &done) != 0)
            throw std::runtime_error("Audio codec generation failed");
        if (!nextHidden) break;
        hidden = nextHidden;
        semantic = llama_sampler_sample(sampler_, context_, -1);
    }
    int32_t rate = 0;
    const char *bytes = nullptr;
    size_t size = 0;
    if (mtmd_helper_gen_audio_get_output(gen, &rate, &bytes, &size, nullptr) != 0 ||
        rate <= 0 || !bytes || size < 44 || size > 16 * 1024 * 1024)
        throw std::runtime_error("Invalid synthesized WAV output");
    return std::vector<uint8_t>(reinterpret_cast<const uint8_t *>(bytes),
                                reinterpret_cast<const uint8_t *>(bytes) + size);
}
