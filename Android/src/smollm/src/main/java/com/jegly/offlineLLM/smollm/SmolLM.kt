package com.jegly.offlineLLM.smollm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class SmolLM {
    companion object {
        private const val TAG = "SmolLM"

        init { System.loadLibrary("smollm") }
    }

    @Volatile private var nativePtr = 0L

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40,
        val repeatPenalty: Float = 1.1f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val numThreadsBatch: Int = numThreads,
        val numGpuLayers: Int = 0,
        val gpuProjector: Boolean = false,
        val performanceCoresOnly: Boolean = false,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        val mmprojPath: String? = null,
    )

    object DefaultParams {
        const val CONTEXT_SIZE: Long = 4096L
        const val CHAT_TEMPLATE: String =
            "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system You are a helpful AI assistant.<|im_end|> ' }}{% endif %}{{'<|im_start|>' + message['role'] + ' ' + message['content'] + '<|im_end|>' + ' '}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant ' }}{% endif %}"
    }

    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) =
        withContext(Dispatchers.IO) {
            val ggufReader = GGUFReader()
            try {
                ggufReader.load(modelPath)
                val modelContextSize = ggufReader.getContextSize() ?: DefaultParams.CONTEXT_SIZE
                val modelChatTemplate = ggufReader.getChatTemplate() ?: DefaultParams.CHAT_TEMPLATE
                nativePtr = loadModel(
                modelPath,
                params.minP,
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                params.storeChats,
                (params.contextSize ?: DefaultParams.CONTEXT_SIZE)
                    .coerceIn(256L, minOf(8192L, modelContextSize.coerceAtLeast(256L))),
                params.chatTemplate ?: modelChatTemplate,
                params.numThreads,
                params.numThreadsBatch,
                params.numGpuLayers,
                params.gpuProjector,
                params.performanceCoresOnly,
                params.useMmap,
                params.useMlock,
                params.mmprojPath ?: "",
                )
            } finally {
                ggufReader.close()
            }
        }

    /**
     * Generic method to add a message with a specific role.
     * Essential for models like Gemma that use "model" instead of "assistant".
     */
    fun addChatMessage(role: String, message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, role)
    }

    fun addUserMessage(message: String) {
        addChatMessage("user", message)
    }

    fun addSystemPrompt(prompt: String) {
        addChatMessage("system", prompt)
    }

    fun addAssistantMessage(message: String) {
        addChatMessage("assistant", message)
    }

    fun requestStop() {
        val handle = nativePtr
        if (handle != 0L) nativeRequestStop(handle)
    }

    fun hardwareInfo(): String {
        verifyHandle()
        return nativeHardwareInfo(nativePtr)
    }

    fun audioCapabilities(): Int {
        verifyHandle()
        return nativeAudioCapabilities(nativePtr)
    }

    fun resetConversation(systemPrompt: String) {
        verifyHandle()
        nativeResetConversation(nativePtr, systemPrompt)
    }

    fun audioSampleRate(): Int {
        verifyHandle()
        return nativeAudioSampleRate(nativePtr)
    }

    fun transcribeAudio(pcm: FloatArray): String {
        verifyHandle()
        return nativeTranscribeAudio(nativePtr, pcm)
    }

    fun synthesizeSpeech(text: String): ByteArray {
        verifyHandle()
        return nativeSynthesizeSpeech(nativePtr, text)
    }

    data class MixedVoiceStep(
        val text: String,
        val pcm16: ByteArray,
        val sampleRate: Int,
        val done: Boolean,
    )

    fun supportsRealtimeAudio(): Boolean {
        verifyHandle()
        return nativeRealtimeAudioSupported(nativePtr)
    }

    fun hasCommittedRealtimeAudio(): Boolean {
        verifyHandle()
        return nativeHasCommittedRealtimeAudio(nativePtr)
    }

    fun beginRealtimeAudio() {
        verifyHandle()
        nativeBeginRealtimeAudio(nativePtr)
    }

    fun appendRealtimeAudio(pcm: FloatArray) {
        verifyHandle()
        require(pcm.isNotEmpty())
        nativeAppendRealtimeAudio(nativePtr, pcm)
    }

    fun commitRealtimeAudio() {
        verifyHandle()
        nativeCommitRealtimeAudio(nativePtr)
    }

    fun abortRealtimeAudio() {
        if (nativePtr != 0L) nativeAbortRealtimeAudio(nativePtr)
    }

    fun beginRealtimeMixedResponse(transcript: String): Long {
        verifyHandle()
        require(transcript.isNotBlank())
        val handle = nativePtr
        nativeStartRealtimeMixedCompletion(handle, transcript)
        return handle
    }

    // Only called by the exclusive LlamaCppEngine session owner.
    fun beginMixedResponse(query: String): Long {
        verifyHandle()
        val handle = nativePtr
        nativeStartMixedCompletion(handle, query)
        return handle
    }

    fun nextMixedResponse(handle: Long): MixedVoiceStep {
        check(handle != 0L && nativePtr == handle) { "Mixed model handle changed" }
        return nativeMixedCompletionLoop(handle)
    }

    fun endMixedResponse(handle: Long) {
        if (handle != 0L && nativePtr == handle) stopCompletion(handle)
    }

    fun getMixedResponseAsFlow(query: String): Flow<MixedVoiceStep> = flow {
        verifyHandle()
        val handle = nativePtr
        try {
            nativeStartMixedCompletion(handle, query)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val item = nativeMixedCompletionLoop(handle)
                if (item.text.isNotEmpty() || item.pcm16.isNotEmpty()) emit(item)
                if (item.done) break
            }
        } finally {
            if (nativePtr == handle) stopCompletion(handle)
        }
    }

    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }

    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }

    fun getResponseAsFlow(query: String): Flow<String> = flow {
        verifyHandle()
        val handle = nativePtr
        try {
            startCompletion(handle, query)
            while (true) {
                val piece = completionLoop(handle) ?: break
                emit(piece)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
            }
        } finally {
            if (nativePtr == handle) stopCompletion(handle)
        }
    }

    fun getResponse(query: String): String {
        verifyHandle()
        val handle = nativePtr
        try {
            startCompletion(handle, query)
            return buildString {
                while (true) {
                    val piece = completionLoop(handle) ?: break
                    append(piece)
                }
            }
        } finally {
            if (nativePtr == handle) stopCompletion(handle)
        }
    }

    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }

    fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0L
        }
    }

    fun isLoaded(): Boolean = nativePtr != 0L

    private fun verifyHandle() {
        check(nativePtr != 0L) { "Model is not loaded. Call SmolLM.load() first." }
    }

    private external fun loadModel(
        modelPath: String, minP: Float, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, storeChats: Boolean, contextSize: Long, chatTemplate: String,
        nThreads: Int, nThreadsBatch: Int, nGpuLayers: Int,
        gpuProjector: Boolean, performanceCoresOnly: Boolean,
        useMmap: Boolean, useMlock: Boolean, mmprojPath: String
    ): Long

    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun nativeRealtimeAudioSupported(modelPtr: Long): Boolean
    private external fun nativeHasCommittedRealtimeAudio(modelPtr: Long): Boolean
    private external fun nativeBeginRealtimeAudio(modelPtr: Long)
    private external fun nativeAppendRealtimeAudio(modelPtr: Long, pcm: FloatArray)
    private external fun nativeCommitRealtimeAudio(modelPtr: Long)
    private external fun nativeAbortRealtimeAudio(modelPtr: Long)
    private external fun nativeStartRealtimeMixedCompletion(modelPtr: Long, transcript: String)
    private external fun nativeStartMixedCompletion(modelPtr: Long, prompt: String)
    private external fun nativeMixedCompletionLoop(modelPtr: Long): MixedVoiceStep
    private external fun completionLoop(modelPtr: Long): String?
    private external fun stopCompletion(modelPtr: Long)
    private external fun nativeRequestStop(modelPtr: Long)
    private external fun nativeAudioSampleRate(modelPtr: Long): Int
    private external fun nativeAudioCapabilities(modelPtr: Long): Int
    private external fun nativeHardwareInfo(modelPtr: Long): String
    private external fun nativeResetConversation(modelPtr: Long, systemPrompt: String)
    private external fun nativeTranscribeAudio(modelPtr: Long, pcm: FloatArray): String
    private external fun nativeSynthesizeSpeech(modelPtr: Long, text: String): ByteArray
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
