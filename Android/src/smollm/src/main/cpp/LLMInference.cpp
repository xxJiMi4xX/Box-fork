#include "LLMInference.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
#include <sched.h>
#include <fstream>
#include <numeric>
#include <array>
#include <android/log.h>
#include <chrono>
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

// Detect big cores by their hardware maximum frequency, not Linux CPU IDs.
// Android device CPUs are heterogeneous and their indices are not portable.
static std::vector<int> getFastestCores(size_t requested) {
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) return {};
    std::vector<std::pair<long, int>> ranked;
    for (int cpu = 0; cpu < CPU_SETSIZE && cpu < GGML_MAX_N_THREADS; ++cpu) {
        if (!CPU_ISSET(cpu, &allowed)) continue;
        const std::string dir = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/";
        long maxFreq = 0;
        for (const char *filename : {"cpuinfo_max_freq", "scaling_max_freq"}) {
            std::ifstream input(dir + filename);
            if (input >> maxFreq && maxFreq > 0) break;
        }
        if (maxFreq <= 0) return {}; // Do not guess core topology.
        ranked.emplace_back(maxFreq, cpu);
    }
    if (ranked.size() < requested) return {};
    std::sort(ranked.begin(), ranked.end(), [](const auto &a, const auto &b) {
        return a.first == b.first ? a.second < b.second : a.first > b.first;
    });
    std::vector<int> cores;
    for (size_t i = 0; i < requested; ++i) cores.push_back(ranked[i].second);
    return cores;
}

LLMInference::~LLMInference() {
    const bool trace = __android_log_is_loggable(
        ANDROID_LOG_DEBUG, "BoxVoiceNative", ANDROID_LOG_SILENT);
    if (trace) __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
        "event=native_destructor_started");
    stopRequested_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    if (mixedGen_) mtmd_helper_gen_audio_free(mixedGen_);
    if (realtimeInput_) mtmd_audio_stream_free(realtimeInput_);
    if (mmproj_) mtmd_free(mmproj_);
    if (sampler_) llama_sampler_free(sampler_);
    if (context_ && performancePool_) llama_detach_threadpool(context_);
    if (audioContext_) llama_free(audioContext_);
    if (context_) llama_free(context_);
    if (performancePool_) ggml_threadpool_free(performancePool_);
    if (batchPool_) ggml_threadpool_free(batchPool_);
    if (model_) llama_model_free(model_);
    if (trace) __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
        "event=native_destructor_completed");
}

void LLMInference::loadModel(const char *path, float minP, float temperature, float topP,
                             int topK, float repeatPenalty, bool storeChats,
                             long contextSize, const char *chatTemplate,
                             int nThreads, int nThreadsBatch, int nGpuLayers, bool gpuProjector,
                             bool performanceCoresOnly, bool useMmap, bool useMlock, const char *mmprojPath) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!path || !*path || model_) throw std::invalid_argument("Invalid model path or duplicate load");
    if (!std::isfinite(temperature) || !std::isfinite(minP) || !std::isfinite(topP) ||
        !std::isfinite(repeatPenalty)) throw std::invalid_argument("Invalid sampling configuration");
    if (contextSize < 256 || contextSize > 8192 || nThreads < 1 || nThreads > 32 ||
        nThreadsBatch < 1 || nThreadsBatch > 32 || nGpuLayers < -1 || nGpuLayers > 512)
        throw std::invalid_argument("Context must be 256..8192; threads 1..32");
    static std::once_flag initOnce;
    std::call_once(initOnce, [] {
        llama_backend_init();
        ggml_backend_load_all();
    });
    llama_model_params mp = llama_model_default_params();
    // Mobile Vulkan backends may declare IGPU rather than discrete GPU.
    // Pin a single explicitly enumerated device; never silently offload to CPU.
    requestedGpuLayers_ = nGpuLayers;
    selectedGpuDevice_.clear();
    requestedGpuDevices_.fill(nullptr);
    if (nGpuLayers != 0) {
        ggml_backend_dev_t selected = nullptr;
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            auto dev = ggml_backend_dev_get(i);
            const auto type = ggml_backend_dev_type(dev);
            if (type == GGML_BACKEND_DEVICE_TYPE_GPU ||
                type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
                selected = dev;
                break;
            }
        }
        if (!selected) {
            throw std::runtime_error(
                "No Vulkan GPU/IGPU backend detected; CPU mode remains available");
        }
        requestedGpuDevices_[0] = selected;
        mp.devices = requestedGpuDevices_.data();
        selectedGpuDevice_ = ggml_backend_dev_name(selected);
    }
    mp.n_gpu_layers = nGpuLayers;
    mp.load_mode = useMmap
        ? (useMlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP)
        : (useMlock ? LLAMA_LOAD_MODE_MLOCK : LLAMA_LOAD_MODE_NONE);
    model_ = llama_model_load_from_file(path, mp);
    if (!model_) throw std::runtime_error("Cannot load GGUF model");
    if (__android_log_is_loggable(ANDROID_LOG_DEBUG, "BoxVoiceNative", ANDROID_LOG_SILENT)) {
        __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
            "event=native_model_loaded requested_gpu_layers=%d model_layers=%d "
            "model_bytes=%llu quant_ftype=%d device_count=%zu actual_offloaded_layers=unverified",
            nGpuLayers, llama_model_n_layer(model_),
            static_cast<unsigned long long>(llama_model_size(model_)),
            static_cast<int>(llama_model_ftype(model_)), ggml_backend_dev_count());
        for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
            const auto dev = ggml_backend_dev_get(i);
            __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
                "event=backend_device index=%zu type=%d name=%s",
                i, static_cast<int>(ggml_backend_dev_type(dev)),
                ggml_backend_dev_name(dev));
        }
    }
    char architecture[128] = {};
    isLfm3_ = llama_model_meta_val_str(
        model_, "general.architecture", architecture, sizeof(architecture)) > 0 &&
        std::string_view(architecture) == "lfm3";
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(contextSize);
    cp.n_batch = static_cast<uint32_t>(std::min<long>(512, contextSize));
    cp.n_ubatch = static_cast<uint32_t>(std::min<long>(256, contextSize));
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreadsBatch;
    audioThreads_ = nThreads;
    audioBatchThreads_ = nThreadsBatch;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf = true;
    cp.embeddings = true; // Required for LFM3 audio-generation hidden state.
    context_ = llama_init_from_model(model_, cp);
    if (!context_) throw std::runtime_error("Cannot initialize llama context");
    if (performanceCoresOnly) {
        if (nThreads > 4)
            throw std::invalid_argument("Performance cores mode requires 1..4 decode threads");
        const std::vector<int> fastest = getFastestCores(4);
        if (fastest.size() != 4)
            throw std::runtime_error("Cannot verify the four fastest Android CPU cores");
        auto p = ggml_threadpool_params_default(nThreads);
        for (int core : fastest) p.cpumask[core] = true;
        p.strict_cpu = true;
        performancePool_ = ggml_threadpool_new(&p);
        if (!performancePool_)
            throw std::runtime_error("Cannot create performance-core CPU threadpool");
        auto batchParams = ggml_threadpool_params_default(nThreadsBatch);
        batchPool_ = ggml_threadpool_new(&batchParams);
        if (!batchPool_)
            throw std::runtime_error("Cannot create CPU prompt threadpool");
        llama_attach_threadpool(context_, performancePool_, batchPool_);
    }
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
        params.use_gpu = gpuProjector && nGpuLayers != 0;
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

std::string LLMInference::getHardwareInfo() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!model_) throw std::runtime_error("Model not loaded");
    std::ostringstream out;
    out << "ARM NEON=" << ggml_cpu_has_neon()
        << " DOTPROD=" << ggml_cpu_has_dotprod()
        << " I8MM=" << ggml_cpu_has_matmul_int8()
        << " FP16=" << ggml_cpu_has_fp16_va()
        << " SVE=" << ggml_cpu_has_sve()
        << " SVE_BYTES=" << ggml_cpu_get_sve_cnt();
    out << " selected_backend=" << (requestedGpuLayers_ == 0 ? "CPU" : "Vulkan");
    out << " requested_gpu_layers=" << requestedGpuLayers_;
    out << " selected_gpu=" << (selectedGpuDevice_.empty() ? "none" : selectedGpuDevice_);
    out << " actual_offloaded_layers=unverified";
    out << " devices=";
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto dev = ggml_backend_dev_get(i);
        if (i) out << ',';
        out << ggml_backend_dev_name(dev) << ':' << static_cast<int>(ggml_backend_dev_type(dev));
    }
    return out.str();
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
    const auto prefillStarted = std::chrono::steady_clock::now();
    const bool prefixMatches =
        chatPrefixValid_ && decodedChatPrefix_.size() < tokens.size() &&
        std::equal(decodedChatPrefix_.begin(), decodedChatPrefix_.end(), tokens.begin()) &&
        llama_memory_seq_pos_max(llama_get_memory(context_), 0) + 1 ==
            static_cast<llama_pos>(decodedChatPrefix_.size());
    const size_t reusedTokens = prefixMatches ? decodedChatPrefix_.size() : 0;
    if (!prefixMatches) {
        llama_memory_clear(llama_get_memory(context_), false);
        decodedChatPrefix_.clear();
    }
    llama_sampler_reset(sampler_);
    response_.clear();
    pendingUtf8_.clear();
    responseTokens_ = 0;
    responseTimeUs_ = 0;
    contextUsed_ = prefixMatches ? static_cast<int>(decodedChatPrefix_.size()) : 0;
    const size_t batchCap = llama_n_batch(context_);
    if (batchCap == 0) throw std::runtime_error("Invalid batch size");
    for (size_t i = static_cast<size_t>(contextUsed_); i < tokens.size(); i += batchCap) {
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
    if (__android_log_is_loggable(ANDROID_LOG_DEBUG, "BoxVoiceNative", ANDROID_LOG_SILENT)) {
        const auto prefillMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - prefillStarted).count();
        __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
            "event=prefill_done tokens=%zu prefill_ms=%lld n_past=%d n_ctx=%u lfm3=%d",
            tokens.size(), static_cast<long long>(prefillMs), contextUsed_,
            llama_n_ctx(context_), isLfm3_ ? 1 : 0);
    }
    decodedChatPrefix_ = tokens;
    chatPrefixValid_ = true;
    if (__android_log_is_loggable(ANDROID_LOG_DEBUG, "BoxVoiceNative", ANDROID_LOG_SILENT)) {
        __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
            "event=chat_prefix_reuse reused_tokens=%zu total_tokens=%zu",
            reusedTokens, tokens.size());
    }
    beforeGeneration_ = historyBackup;
    generating_ = true;
    } catch (...) {
        messages_ = historyBackup;
        beforeGeneration_.clear();
        chatPrefixValid_ = false;
        decodedChatPrefix_.clear();
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
    if (mixedGen_) {
        // Audio embeddings/hidden state cannot be represented by cached text tokens.
        chatPrefixValid_ = false;
        mtmd_helper_gen_audio_free(mixedGen_);
        mixedGen_ = nullptr;
        mixedHidden_.clear();
    }
    if (!generating_) return;
    generating_ = false;
    pendingUtf8_.clear();
    if (!commitResponse) {
        chatPrefixValid_ = false;
        // Cancelling a turn must not leave a dangling user instruction
        // or a partial assistant response in the next generation.
        messages_ = std::move(beforeGeneration_);
        response_.clear();
    } else {
        if (!response_.empty()) messages_.emplace_back("assistant", response_);
        beforeGeneration_.clear();
    }
}

// The same llama_context generates both text and audio. The pinned mtmd
// LFM3 pipeline supports continuing a conversation with <|mixed_start|>.
// Never create a second loaded GGUF model or an independent TTS context.
void LLMInference::setupMixedGenerationLocked() {
    mixedGen_ = mtmd_helper_gen_audio_init(context_, mmproj_);
    if (!mixedGen_) throw std::runtime_error("Cannot initialize LFM3 mixed text/audio pipeline");
    const std::string marker = "<|mixed_start|>";
    auto inp = mtmd_helper_gen_audio_inp_default();
    inp.prompt = marker.c_str();
    inp.prompt_len = marker.size();
    inp.n_past = contextUsed_;
    inp.seq_id = 0;
    inp.stream = true;
    inp.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    inp.out_mode = MTMD_HELPER_GEN_AUDIO_OUT_AUTO;
    chatPrefixValid_ = false;
    if (mtmd_helper_gen_audio_set_input(mixedGen_, &inp) != 0)
        throw std::runtime_error("LFM3 mixed output is not supported by this GGUF");
    int32_t left = 0;
    while ((left = mtmd_helper_gen_audio_step_prompt(
                mixedGen_, static_cast<int32_t>(llama_n_batch(context_)))) > 0) {
        if (stopRequested_.load()) throw std::runtime_error("Mixed prefill cancelled");
    }
    if (left < 0) throw std::runtime_error("Mixed audio prompt decoding failed");
    const float *hidden = llama_get_embeddings_ith(context_, -1);
    const int n_embd = llama_model_n_embd(model_);
    if (!hidden || n_embd <= 0) throw std::runtime_error("No mixed generation hidden state");
    mixedHidden_.assign(hidden, hidden + n_embd);
}

void LLMInference::startMixedCompletion(const char *query) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!mmproj_ || audioCapabilities_ != 3 || !isLfm3_)
            throw std::runtime_error("LFM3 mixed text/audio requires a compatible voice projector");
    }
    // Reuse the history rollback, context-window checks and prompt rendering.
    startCompletion(query);
    std::lock_guard<std::mutex> lock(mutex_);
    try {
        if (stopRequested_.load()) throw std::runtime_error("Mixed generation cancelled");
        setupMixedGenerationLocked();
    } catch (...) {
        finishGeneration(false);
        llama_memory_clear(llama_get_memory(context_), false);
        llama_sampler_reset(sampler_);
        throw;
    }
}


bool LLMInference::supportsRealtimeAudio() {
    std::lock_guard<std::mutex> lock(mutex_);
    return mmproj_ && isLfm3_ && mtmd_support_audio_stream(mmproj_);
}

void LLMInference::beginRealtimeAudio() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || !isLfm3_ || !mtmd_support_audio_stream(mmproj_))
        throw std::runtime_error("This LFM3 projector has no streaming audio input");
    if (generating_ || realtimeInput_ || realtimeAudioCommitted_)
        throw std::runtime_error("A realtime audio turn is already open");
    stopRequested_.store(false);
    realtimeInput_ = mtmd_audio_stream_init(mmproj_, mtmd_audio_stream_params_default());
    if (!realtimeInput_) throw std::runtime_error("Cannot initialize LFM3 audio stream");
    realtimeInputSamples_ = 0;
    chatPrefixValid_ = false;
}

void LLMInference::pumpRealtimeAudioLocked() {
    if (!realtimeInput_) throw std::runtime_error("Audio stream is not open");
    for (;;) {
        if (stopRequested_.load()) throw std::runtime_error("Realtime audio was interrupted");
        const int32_t ready = mtmd_audio_stream_step(realtimeInput_, LLAMA_TOKEN_NULL);
        if (ready == -2) throw std::runtime_error("LFM3 audio embedding stream failed");
        if (ready <= 0) return;
        if (mtmd_audio_stream_is_seq_start(realtimeInput_) && contextUsed_ > 0)
            throw std::runtime_error(
                "Streaming audio requires a new sequence; hot hybrid state cannot be overwritten");
        const int32_t dim = llama_model_n_embd_inp(model_);
        const float *embd = mtmd_audio_stream_get_output_embd(realtimeInput_);
        if (dim <= 0 || !embd || contextUsed_ + ready + 1 >= static_cast<int32_t>(llama_n_ctx(context_)))
            throw std::runtime_error("LFM3 realtime audio exceeds available context");
        const int32_t batchLimit = static_cast<int32_t>(llama_n_batch(context_));
        for (int32_t i = 0; i < ready; i += batchLimit) {
            const int32_t count = std::min(batchLimit, ready - i);
            llama_batch batch = llama_batch_init(count, dim, 1);
            for (int32_t j = 0; j < count; ++j) {
                std::memcpy(batch.embd + static_cast<size_t>(j) * dim,
                    embd + static_cast<size_t>(i + j) * dim, dim * sizeof(float));
                batch.pos[j] = contextUsed_ + j;
                batch.n_seq_id[j] = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j] = 0;
            }
            batch.n_tokens = count;
            const int decoded = llama_decode(context_, batch);
            llama_batch_free(batch);
            if (decoded != 0) throw std::runtime_error("Cannot decode realtime audio embeddings");
            contextUsed_ += count;
        }
    }
}

void LLMInference::appendRealtimeAudio(const std::vector<float> &pcm) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!realtimeInput_ || pcm.empty()) throw std::runtime_error("No active realtime audio");
    const int32_t rate = mtmd_get_audio_sample_rate(mmproj_);
    if (rate <= 0 || pcm.size() > static_cast<size_t>(rate) * 2 ||
        realtimeInputSamples_ + pcm.size() > static_cast<size_t>(rate) * 90)
        throw std::runtime_error("Realtime audio chunk or turn exceeds limit");
    if (stopRequested_.load()) throw std::runtime_error("Audio cancelled");
    if (mtmd_audio_stream_push(realtimeInput_, pcm.data(), pcm.size()) != 0)
        throw std::runtime_error("LFM3 audio stream refused PCM samples");
    realtimeInputSamples_ += pcm.size();
    pumpRealtimeAudioLocked();
}

void LLMInference::commitRealtimeAudio() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!realtimeInput_ || realtimeInputSamples_ == 0)
        throw std::runtime_error("Cannot commit empty realtime audio");
    if (mtmd_audio_stream_flush(realtimeInput_, true) != 0)
        throw std::runtime_error("Audio encoder flush failed");
    pumpRealtimeAudioLocked();
    mtmd_audio_stream_free(realtimeInput_);
    realtimeInput_ = nullptr;
    realtimeInputSamples_ = 0;
    realtimeAudioCommitted_ = true;
}

void LLMInference::abortRealtimeAudio() {
    // If decoding partially appended embeddings, recover via a full prefill.
    std::lock_guard<std::mutex> lock(mutex_);
    if (realtimeInput_) mtmd_audio_stream_free(realtimeInput_);
    realtimeInput_ = nullptr;
    realtimeInputSamples_ = 0;
    realtimeAudioCommitted_ = false;
    chatPrefixValid_ = false;
}

void LLMInference::startRealtimeMixedCompletion(const char *transcript) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!transcript || !*transcript || !realtimeAudioCommitted_ ||
        !mmproj_ || !isLfm3_ || generating_)
        throw std::runtime_error("Realtime mixed response requires committed audio and transcript");
    if (stopRequested_.load()) throw std::runtime_error("Realtime audio was cancelled");
    realtimeAudioCommitted_ = false;
    beforeGeneration_ = messages_;
    messages_.emplace_back("user", transcript);
    response_.clear();
    pendingUtf8_.clear();
    responseTokens_ = 0;
    responseTimeUs_ = 0;
    llama_sampler_reset(sampler_);
    generating_ = true;
    chatPrefixValid_ = false;
    try {
        setupMixedGenerationLocked();
    } catch (...) {
        finishGeneration(false);
        throw;
    }
}

LLMInference::MixedStep LLMInference::mixedCompletionLoop() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!generating_ || !mixedGen_) return MixedStep{{}, {}, 0, true};
    if (stopRequested_.load()) {
        finishGeneration(false);
        throw std::runtime_error("Mixed speech generation cancelled");
    }
    if (llama_memory_seq_pos_max(llama_get_memory(context_), 0) + 2 >=
        static_cast<llama_pos>(llama_n_ctx(context_))) {
        finishGeneration(false);
        throw std::runtime_error("Mixed speech context window exhausted");
    }
    try {
        MixedStep result;
        const bool isText = mtmd_helper_gen_audio_next_is_text(mixedGen_);
        llama_token sampled = LLAMA_TOKEN_NULL;
        if (isText) {
            sampled = llama_sampler_sample(sampler_, context_, -1);
            llama_sampler_accept(sampler_, sampled);
        }
        const float *nextHidden = nullptr;
        bool done = false;
        if (mtmd_helper_gen_audio_step_gen(mixedGen_, sampled,
                mixedHidden_.data(), &nextHidden, &done) != 0)
            throw std::runtime_error("LFM3 mixed text/audio generation failed");
        if (nextHidden) {
            mixedHidden_.assign(nextHidden, nextHidden + mixedHidden_.size());
        }
        if (isText && !done && sampled != LLAMA_TOKEN_NULL &&
            !llama_vocab_is_control(llama_model_get_vocab(model_), sampled)) {
            pendingUtf8_ += tokenToPiece(sampled);
            if (validUtf8(pendingUtf8_)) {
                result.text = std::move(pendingUtf8_);
                response_ += result.text;
                pendingUtf8_.clear();
                responseTokens_++;
            } else if (pendingUtf8_.size() > 8) {
                throw std::runtime_error("Malformed mixed text token");
            }
        }
        if (done && mtmd_helper_gen_audio_flush(mixedGen_) != 0)
            throw std::runtime_error("LFM3 mixed audio flush failed");
        const char *raw = nullptr;
        size_t bytes = 0;
        int32_t rate = 0;
        if (mtmd_helper_gen_audio_get_output(mixedGen_, &rate, &raw, &bytes, nullptr) != 0)
            throw std::runtime_error("Cannot read mixed audio PCM");
        if (bytes > 0) {
            if (!raw || bytes % sizeof(float) != 0 || bytes > 4 * 1024 * 1024 ||
                rate < 8000 || rate > 192000)
                throw std::runtime_error("Invalid mixed audio PCM frame");
            const size_t n = bytes / sizeof(float);
            result.pcm16.resize(n * 2);
            for (size_t i = 0; i < n; ++i) {
                float sample;
                std::memcpy(&sample, raw + i * sizeof(float), sizeof(sample));
                if (!std::isfinite(sample)) sample = 0.0f;
                const int16_t pcm = static_cast<int16_t>(
                    std::lrint(std::clamp(sample, -1.0f, 1.0f) * 32767.0f));
                result.pcm16[i * 2] = static_cast<uint8_t>(pcm & 255);
                result.pcm16[i * 2 + 1] = static_cast<uint8_t>(
                    (static_cast<uint16_t>(pcm) >> 8) & 255);
            }
            result.sampleRate = rate;
        }
        contextUsed_ = static_cast<int>(
            llama_memory_seq_pos_max(llama_get_memory(context_), 0) + 1);
        result.done = done;
        // Never commit a user turn if no assistant text was produced.
        if (done) finishGeneration(!response_.empty());
        return result;
    } catch (...) {
        finishGeneration(false);
        throw;
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
    if (chatPrefixValid_) decodedChatPrefix_.push_back(token);
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
    if (realtimeInput_) mtmd_audio_stream_free(realtimeInput_);
    realtimeInput_ = nullptr;
    realtimeAudioCommitted_ = false;
    realtimeInputSamples_ = 0;
    chatPrefixValid_ = false;
    decodedChatPrefix_.clear();
    if (*systemPrompt) messages_.emplace_back("system", systemPrompt);
    response_.clear();
    pendingUtf8_.clear();
    contextUsed_ = 0;
    responseTokens_ = 0;
    responseTimeUs_ = 0;
    llama_memory_clear(llama_get_memory(context_), false);
    llama_sampler_reset(sampler_);
}

llama_context *LLMInference::getAudioContextLocked() {
    if (audioContext_) return audioContext_;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = llama_n_ctx(context_);
    cp.n_batch = llama_n_batch(context_);
    cp.n_ubatch = llama_n_ubatch(context_);
    cp.n_threads = audioThreads_;
    cp.n_threads_batch = audioBatchThreads_;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.embeddings = true;
    audioContext_ = llama_init_from_model(model_, cp);
    if (!audioContext_) {
        // Safe memory-constrained fallback: service voice, invalidate hot chat.
        if (__android_log_is_loggable(ANDROID_LOG_WARN, "BoxVoiceNative", ANDROID_LOG_SILENT)) {
            __android_log_print(ANDROID_LOG_WARN, "BoxVoiceNative",
                "event=audio_scratch_unavailable fallback=shared_context cache_invalidated=1");
        }
        return context_;
    }
    if (__android_log_is_loggable(ANDROID_LOG_DEBUG, "BoxVoiceNative", ANDROID_LOG_SILENT)) {
        __android_log_print(ANDROID_LOG_DEBUG, "BoxVoiceNative",
            "event=audio_scratch_ready independent_context=1");
    }
    return audioContext_;
}

std::string LLMInference::transcribeAudio(const std::vector<float> &pcm) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!mmproj_ || !mtmd_support_audio(mmproj_))
        throw std::runtime_error("Audio input requires a compatible mmproj GGUF");
    if (generating_ || pcm.empty() || pcm.size() > static_cast<size_t>(mtmd_get_audio_sample_rate(mmproj_)) * 90)
        throw std::runtime_error("Audio busy, empty or longer than 90 seconds");
    stopRequested_.store(false);
    llama_context *const audioWork = getAudioContextLocked();
    if (audioWork == context_) {
        chatPrefixValid_ = false;
        decodedChatPrefix_.clear();
    }

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
    if (mtmd_helper_get_n_tokens(chunks) + 1 > llama_n_ctx(audioWork))
        throw std::runtime_error("Audio input exceeds context window");

    llama_memory_clear(llama_get_memory(audioWork), false);
    llama_sampler_reset(sampler_);
    llama_pos nPast = 0;
    if (mtmd_helper_eval_chunks(mmproj_, audioWork, chunks, 0, 0,
        static_cast<int32_t>(llama_n_batch(audioWork)), true, &nPast) != 0)
        throw std::runtime_error("Audio encoder/decoder failed");
    // ASR should be deterministic. Use a separate greedy sampler so that
    // conversational temperature/top-p settings cannot hallucinate a transcript.
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> asrSampler(
        llama_sampler_init_greedy(), llama_sampler_free);
    if (!asrSampler) throw std::runtime_error("Cannot initialize ASR sampler");
    const llama_vocab *vocab = llama_model_get_vocab(model_);
    std::string transcript;
    bool transcriptionFinished = false;
    const int32_t remaining = static_cast<int32_t>(llama_n_ctx(audioWork) - nPast - 1);
    const int32_t limit = std::min<int32_t>(2048, remaining);
    for (int32_t i = 0; i < limit; ++i) {
        if (stopRequested_.load()) throw std::runtime_error("Transcription interrupted");
        const llama_token tok = llama_sampler_sample(asrSampler.get(), audioWork, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            transcriptionFinished = true;
            break;
        }
        if (!llama_vocab_is_control(vocab, tok)) transcript += tokenToPiece(tok);
        llama_token mutableToken = tok;
        if (llama_decode(audioWork, llama_batch_get_one(&mutableToken, 1)) != 0)
            throw std::runtime_error("ASR decode failed");
        ++nPast;
    }
    llama_memory_clear(llama_get_memory(audioWork), false);
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
    llama_context *const audioWork = getAudioContextLocked();
    if (audioWork == context_) {
        chatPrefixValid_ = false;
        decodedChatPrefix_.clear();
    }
    std::string promptStr =
        "<|startoftext|><|im_start|>system\n"
        "Perform TTS in Greek using a female voice.<|im_end|>\n"
        "<|im_start|>user\n";
    promptStr += text;
    promptStr += "<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>";
    llama_memory_clear(llama_get_memory(audioWork), false);
    llama_sampler_reset(sampler_);
    auto *gen = mtmd_helper_gen_audio_init(audioWork, mmproj_);
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
            gen, static_cast<int32_t>(llama_n_batch(audioWork)));
        if (left < 0) throw std::runtime_error("Speech prompt encoding failed");
        if (left == 0) break;
    }
    // The LFM3-Audio pipeline consumes the text model's hidden state,
    // not a discrete sampled token, while the prompt selects MODE_AUDIO.
    // Passing LLAMA_TOKEN_NULL avoids irrelevant text decoding/sampling.
    if (mtmd_helper_gen_audio_next_is_text(gen))
        throw std::runtime_error("LFM3 TTS unexpectedly entered text mode");
    const float *hidden = llama_get_embeddings_ith(audioWork, -1);
    if (!hidden) throw std::runtime_error("Speech decoder returned no hidden state");
    bool done = false;
    for (int frames = 0; frames < 768 && !done; ++frames) {
        if (stopRequested_.load()) throw std::runtime_error("Speech generation cancelled");
        const float *nextHidden = nullptr;
        if (mtmd_helper_gen_audio_step_gen(gen, LLAMA_TOKEN_NULL, hidden, &nextHidden, &done) != 0)
            throw std::runtime_error("Audio codec generation failed");
        if (!nextHidden) {
            if (!done) throw std::runtime_error("Speech decoder stopped without EOS");
            break;
        }
        hidden = nextHidden;
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
