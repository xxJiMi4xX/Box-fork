#include "LLMInference.h"
#include "ggml-backend.h"
#include "mtmd-helper.h"
#include <memory>
#include <mutex>

#include <algorithm>
#include <cmath>
#include <climits>
#include <cstring>
#include <string_view>
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
    char architecture[128] = {};
    isLfm3_ = llama_model_meta_val_str(
        model_, "general.architecture", architecture, sizeof(architecture)) > 0 &&
        std::string_view(architecture) == "lfm3";
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
        if (!isLfm3_)
            throw std::runtime_error("Voice projector requires an LFM3-Omni model");
        const mtmd_helper_cap capabilities = mtmd_helper_get_capabilities(model_, mmproj_);
        const mtmd_gen_audio_info audioInfo = mtmd_gen_audio_get_info(mmproj_);
        const int audioRate = mtmd_get_audio_sample_rate(mmproj_);
        if (!capabilities.chat || !capabilities.text_gen ||
            !capabilities.audio || !mtmd_support_audio(mmproj_) || audioRate < 8000 ||
            !capabilities.audio_gen || audioInfo.type != MTMD_GEN_AUDIO_TYPE_LFM3A ||
            audioInfo.sample_rate < 8000)
            throw std::runtime_error(
                "This mmproj cannot support both LFM3 Greek ASR and audio synthesis");
        const char *tts = llama_model_chat_template(model_, "tts");
        if (!tts || !std::strstr(tts, "bos_token") ||
            !std::strstr(tts, "Perform TTS in ") ||
            !std::strstr(tts, "messages[0]['content']") ||
            !std::strstr(tts, "<|audio_start|>") ||
            !std::strstr(tts, "<think></think>"))
            throw std::runtime_error(
                "The GGUF does not contain the supported LFM3-Omni TTS template");
        const llama_vocab *vocab = llama_model_get_vocab(model_);
        const llama_token bos = llama_vocab_bos(vocab);
        const char *bosText = bos == LLAMA_TOKEN_NULL ? nullptr : llama_vocab_get_text(vocab, bos);
        if (!bosText || std::strcmp(bosText, "<|startoftext|>") != 0)
            throw std::runtime_error("Unsupported LFM3 beginning-of-text token");
        ttsTemplate_ = tts;
        audioCapabilities_ = 3; // 1: audio input, 2: audio output
    }
}

std::vector<llama_token> LLMInference::tokenize(const std::string &str) {
    if (str.size() > static_cast<size_t>(INT32_MAX)) throw std::length_error("Prompt too large");
    auto *vocab = llama_model_get_vocab(model_);
    // LFM3's formatted prompt already contains the <|startoftext|> token.
    // Do not insert an additional BOS (the GGUF declares add_bos_token=false).
    const bool addSpecial = !isLfm3_;
    int32_t needed = llama_tokenize(vocab, str.c_str(), static_cast<int32_t>(str.size()),
                                    nullptr, 0, addSpecial, true);
    if (needed == INT32_MIN) throw std::runtime_error("Token count overflow");
    if (needed == 0) return {};
    size_t cap = static_cast<size_t>(std::abs(needed));
    if (cap > 1048576) throw std::runtime_error("Prompt token limit exceeded");
    std::vector<llama_token> tokens(cap);
    int32_t count = llama_tokenize(vocab, str.c_str(), static_cast<int32_t>(str.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()), addSpecial, true);
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
    const auto historyBackup = messages_;
    try {
    stopRequested_.store(false);
    if (!storeChats_) {
        messages_.erase(std::remove_if(messages_.begin(), messages_.end(),
            [](const auto &m) { return m.first != "system"; }), messages_.end());
    }
    messages_.emplace_back("user", query);
    auto renderConversation = [&]() -> std::vector<llama_token> {
        if (isLfm3_) {
            // LFM3's native chat template includes BOS and an empty thinking
            // section before every assistant answer. llama_chat_apply_template
            // is a heuristic renderer and does not evaluate these Jinja blocks.
            std::string formatted("<|startoftext|>");
            for (const auto &message : messages_) {
                if (message.first != "system" && message.first != "user" &&
                    message.first != "assistant")
                    throw std::invalid_argument("Unsupported LFM3 chat role");
                formatted += "<|im_start|>";
                formatted += message.first;
                formatted += "\n";
                if (message.first == "assistant") formatted += "<think></think>";
                formatted += message.second;
                formatted += "<|im_end|>\n";
            }
            formatted += "<|im_start|>assistant\n<think></think>";
            return tokenize(formatted);
        }
        std::vector<llama_chat_message> chat;
        chat.reserve(messages_.size());
        for (const auto &m : messages_) chat.push_back({m.first.c_str(), m.second.c_str()});
        int32_t needed = llama_chat_apply_template(
            chatTemplate_.c_str(), chat.data(), chat.size(), true, nullptr, 0);
        if (needed < 0 || needed > 1048576)
            throw std::runtime_error("Model chat template is incompatible");
        std::vector<char> buffer(static_cast<size_t>(needed) + 1, '\0');
        const int32_t actual = llama_chat_apply_template(
            chatTemplate_.c_str(), chat.data(), chat.size(), true,
            buffer.data(), static_cast<int32_t>(buffer.size()));
        if (actual < 0 || actual > needed)
            throw std::runtime_error("Chat template rendering failed");
        return tokenize(std::string(buffer.data(), static_cast<size_t>(actual)));
    };

    auto tokens = renderConversation();
    // Reserve room for a useful response rather than filling n_ctx with a
    // prompt that can never produce a complete answer.
    const size_t reserve = std::min<size_t>(
        256, std::max<size_t>(8, static_cast<size_t>(llama_n_ctx(context_)) / 16));
    // Preserve the system instruction and the newest user turn.
    while (!tokens.empty() &&
           tokens.size() + reserve > static_cast<size_t>(llama_n_ctx(context_))) {
        size_t oldest = !messages_.empty() && messages_.front().first == "system" ? 1 : 0;
        if (oldest >= messages_.size() - 1)
            throw std::runtime_error("Latest turn is larger than the GGUF context window");
        size_t remove = 1;
        if (messages_[oldest].first == "user" &&
            oldest + 1 < messages_.size() - 1 &&
            messages_[oldest + 1].first == "assistant") remove = 2;
        messages_.erase(messages_.begin() + oldest, messages_.begin() + oldest + remove);
        tokens = renderConversation();
    }
    if (tokens.empty()) throw std::runtime_error("Cannot tokenize the chat prompt");
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
    beforeGeneration_ = historyBackup;
    generating_ = true;
    } catch (...) {
        messages_ = historyBackup;
        beforeGeneration_.clear();
        generating_ = false;
        response_.clear();
        pendingUtf8_.clear();
        llama_memory_clear(llama_get_memory(context_), false);
        llama_sampler_reset(sampler_);
        throw;
    }
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

void LLMInference::finishGeneration(bool commitResponse) {
    if (!generating_) return;
    generating_ = false;
    pendingUtf8_.clear();
    if (!commitResponse) {
        // Cancelling a turn must not leave a dangling user instruction
        // or a partial assistant response in the next generation.
        messages_ = std::move(beforeGeneration_);
        response_.clear();
    } else {
        if (!response_.empty()) messages_.emplace_back("assistant", response_);
        beforeGeneration_.clear();
    }
}

std::optional<std::string> LLMInference::completionLoop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!generating_) return std::nullopt;
    if (stopRequested_.load()) {
        finishGeneration(false);
        return std::nullopt;
    }
    if (contextUsed_ + 1 >= static_cast<int>(llama_n_ctx(context_))) {
        // This is a truncation, never a successful assistant turn. Keep
        // Room/native history consistent by rolling back the unfinished turn.
        finishGeneration(false);
        throw std::runtime_error("GGUF context window exhausted before response completion");
    }
    int64_t start = llama_time_us();
    llama_token token = llama_sampler_sample(sampler_, context_, -1);
    if (llama_vocab_is_eog(llama_model_get_vocab(model_), token)) {
        finishGeneration(true);
        return std::nullopt;
    }
    std::string piece = tokenToPiece(token);
    // Some GGUF vocabularies do not tag each chat turn delimiter as EOG.
    // Complete this native turn here rather than making Kotlin stop its Flow,
    // which would roll back a valid assistant message in stopCompletion().
    if (piece == "<|im_end|>" || piece == "<|eot_id|>" ||
        piece == "<|endoftext|>" || piece == "<|im_start|>") {
        finishGeneration(true);
        return std::nullopt;
    }
    llama_batch batch = llama_batch_get_one(&token, 1);
    if (llama_decode(context_, batch) != 0) {
        finishGeneration(false);
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
    finishGeneration(false);
}

std::string LLMInference::benchModel(int, int, int, int) {
    // A synthetic benchmark could corrupt in-flight KV state; fail explicitly until
    // a separate benchmark context is allocated and validated.
    throw std::runtime_error("GGUF benchmark requires a separate context (not enabled in this build)");
}

int LLMInference::audioInputSampleRate() {
    std::lock_guard<std::mutex> lock(mutex_);
    return audioCapabilities_ == 3 ? mtmd_get_audio_sample_rate(mmproj_) : -1;
}

int LLMInference::audioCapabilities() {
    std::lock_guard<std::mutex> lock(mutex_);
    return audioCapabilities_;
}

void LLMInference::resetConversation(const char *systemPrompt) {
    if (!systemPrompt) throw std::invalid_argument("Null system prompt");
    std::lock_guard<std::mutex> lock(mutex_);
    if (!context_ || generating_) throw std::runtime_error("Cannot reset while decoding");
    stopRequested_.store(false);
    messages_.clear();
    beforeGeneration_.clear();
    if (*systemPrompt) messages_.emplace_back("system", systemPrompt);
    response_.clear();
    pendingUtf8_.clear();
    contextUsed_ = 0;
    responseTokens_ = 0;
    responseTimeUs_ = 0;
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
}

std::string LLMInference::transcribeAudio(const std::vector<float> &pcm) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || !mtmd_support_audio(mmproj_))
        throw std::runtime_error("Audio input requires a compatible mmproj GGUF");
    if (generating_ || pcm.empty() || pcm.size() > static_cast<size_t>(mtmd_get_audio_sample_rate(mmproj_)) * 90)
        throw std::runtime_error("Audio busy, empty or longer than 90 seconds");
    stopRequested_.store(false);

    // ASR must use the actual LFM3 assistant prefix, not a generic
    // ChatML heuristic that silently drops <think></think>.
    std::string asrPrompt =
        "<|startoftext|><|im_start|>system\n"
        "Perform ASR in Greek.<|im_end|>\n"
        "<|im_start|>user\n";
    asrPrompt += mtmd_default_marker();
    asrPrompt += "<|im_end|>\n<|im_start|>assistant\n<think></think>";
    mtmd_bitmap *bitmap = mtmd_bitmap_init_from_audio(pcm.size(), pcm.data());
    if (!bitmap) throw std::runtime_error("Cannot create audio bitmap");
    std::unique_ptr<mtmd_bitmap, decltype(&mtmd_bitmap_free)> bitmapHolder(bitmap, mtmd_bitmap_free);
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    if (!chunks) throw std::runtime_error("Cannot allocate audio chunks");
    std::unique_ptr<mtmd_input_chunks, decltype(&mtmd_input_chunks_free)> chunkHolder(chunks, mtmd_input_chunks_free);
    mtmd_input_text text = {asrPrompt.c_str(), asrPrompt.size(), false, true};
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
    // ASR should be deterministic. Use a separate greedy sampler so that
    // conversational temperature/top-p settings cannot hallucinate a transcript.
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> asrSampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    if (!asrSampler) throw std::runtime_error("Cannot initialize ASR sampler");
    const llama_vocab *vocab = llama_model_get_vocab(model_);
    std::string transcript;
    bool transcriptionFinished = false;
    const int32_t remaining = static_cast<int32_t>(llama_n_ctx(context_) - nPast - 1);
    const int32_t limit = std::min<int32_t>(2048, remaining);
    for (int32_t i = 0; i < limit; ++i) {
        if (stopRequested_.load()) throw std::runtime_error("Transcription interrupted");
        const llama_token tok = llama_sampler_sample(asrSampler.get(), context_, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            transcriptionFinished = true;
            break;
        }
        if (!llama_vocab_is_control(vocab, tok)) transcript += tokenToPiece(tok);
        llama_token mutableToken = tok;
        if (llama_decode(context_, llama_batch_get_one(&mutableToken, 1)) != 0)
            throw std::runtime_error("ASR decode failed");
        ++nPast;
    }
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
    if (!transcriptionFinished)
        throw std::runtime_error("Audio transcription reached the token or context limit");
    if (transcript.find_first_not_of(" \t\r\n") == std::string::npos)
        throw std::runtime_error("No intelligible audio transcript produced");
    return transcript;
}

std::vector<uint8_t> LLMInference::synthesizeSpeech(const std::string &text) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || audioCapabilities_ != 3)
        throw std::runtime_error("Audio synthesis requires a compatible mmproj GGUF");
    if (generating_ || text.empty() || text.size() > 16384)
        throw std::runtime_error("Speech generation unavailable or input too long");
    if (text.find("<|") != std::string::npos)
        throw std::invalid_argument("TTS text contains reserved LFM3 control tokens");
    // The model's tokenizer.chat_template.tts is verified at loadModel().
    // Its LFM3-specific Jinja evaluates to precisely this sequence for
    // language="el", voice="female" and messages[0].content=text.
    // llama_chat_apply_template() only supports heuristic chat templates and
    // would *not* render the tts Jinja variables correctly.
    if (audioCapabilities_ != 3 || ttsTemplate_.empty())
        throw std::runtime_error("The selected GGUF has no supported Greek TTS template");
    stopRequested_.store(false);
    std::string promptStr =
        "<|startoftext|><|im_start|>system\n"
        "Perform TTS in Greek using a female voice.<|im_end|>\n"
        "<|im_start|>user\n";
    promptStr += text;
    promptStr += "<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>";
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
    // Match upstream llama.cpp TTS's discrete sampling protocol. LFM3's
    // audio mode consumes the hidden-state embedding and ignores this token.
    // llama_sampler_sample() already accepts the sampled token in pinned
    // llama.cpp v0.8.0. Never call llama_sampler_accept() again here.
    llama_token semantic = llama_sampler_sample(sampler_, context_, -1);
    const float *hidden = llama_get_embeddings_ith(context_, -1);
    if (!hidden) throw std::runtime_error("Speech decoder returned no hidden state");
    bool done = false;
    for (int frames = 0; frames < 768 && !done; ++frames) {
        if (stopRequested_.load()) throw std::runtime_error("Speech generation cancelled");
        const float *nextHidden = nullptr;
        if (mtmd_helper_gen_audio_step_gen(gen, semantic, hidden, &nextHidden, &done) != 0)
            throw std::runtime_error("Audio codec generation failed");
        if (!nextHidden) {
            if (!done) throw std::runtime_error("Speech decoder stopped without EOS");
            break;
        }
        hidden = nextHidden;
        semantic = llama_sampler_sample(sampler_, context_, -1);
    }
    if (stopRequested_.load()) throw std::runtime_error("Speech generation cancelled");
    if (!done) throw std::runtime_error("Speech decoder reached frame cap without audio EOS");
    int32_t rate = 0;
    const char *bytes = nullptr;
    size_t size = 0;
    if (mtmd_helper_gen_audio_get_output(gen, &rate, &bytes, &size, nullptr) != 0 ||
        rate <= 0 || !bytes || size < 44 || size > 16 * 1024 * 1024)
        throw std::runtime_error("Invalid synthesized WAV output");
    return std::vector<uint8_t>(reinterpret_cast<const uint8_t *>(bytes),
                                reinterpret_cast<const uint8_t *>(bytes) + size);
}
