#pragma once

#include "llama.h"
#include "mtmd.h"

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

class LLMInference {
public:
    LLMInference() = default;
    ~LLMInference();

    LLMInference(const LLMInference &) = delete;
    LLMInference &operator=(const LLMInference &) = delete;

    void loadModel(const char *path, float minP, float temperature, float topP, int topK,
                   float repeatPenalty, bool storeChats, long contextSize,
                   const char *chatTemplate, int nThreads, bool useMmap, bool useMlock, const char *mmprojPath);
    void addChatMessage(const char *message, const char *role);
    float getResponseGenerationTime();
    int getContextSizeUsed();
    void startCompletion(const char *query);
    std::string completionLoop();
    void requestStop() noexcept;
    void stopCompletion();
    std::string benchModel(int pp, int tg, int pl, int nr);
    int audioInputSampleRate();
    int audioCapabilities();
    void resetConversation(const char *systemPrompt);
    std::string transcribeAudio(const std::vector<float> &pcm);
    std::vector<uint8_t> synthesizeSpeech(const std::string &text);

private:
    std::mutex mutex_;
    std::atomic<bool> stopRequested_{false};
    llama_model *model_ = nullptr;
    llama_context *context_ = nullptr;
    llama_sampler *sampler_ = nullptr;
    mtmd_context *mmproj_ = nullptr;
    std::string ttsTemplate_;
    int audioCapabilities_ = 0;
    std::vector<std::pair<std::string,std::string>> beforeGeneration_;
    std::string chatTemplate_;
    std::vector<std::pair<std::string, std::string>> messages_;
    std::string response_;
    std::string pendingUtf8_;
    bool storeChats_ = true;
    bool generating_ = false;
    int contextUsed_ = 0;
    int responseTokens_ = 0;
    int64_t responseTimeUs_ = 0;

    std::vector<llama_token> tokenize(const std::string &str);
    std::string tokenToPiece(llama_token token);
    void finishGeneration();
    static bool validUtf8(const std::string &bytes);
};
