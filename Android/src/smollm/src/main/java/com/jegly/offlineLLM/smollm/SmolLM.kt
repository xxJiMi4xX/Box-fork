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

    private var nativePtr = 0L

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
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
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
                (params.contextSize ?: modelContextSize).coerceIn(256L, 8192L),
                params.chatTemplate ?: modelChatTemplate,
                params.numThreads,
                params.useMmap,
                params.useMlock,
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
            var piece = completionLoop(handle)
            while (piece != "[EOG]") {
                emit(piece)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                piece = completionLoop(handle)
            }
        } finally {
            if (nativePtr == handle) stopCompletion(handle)
        }
    }

    fun getResponse(query: String): String {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        var response = ""
        while (piece != "[EOG]") {
            response += piece
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
        return response
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
        nThreads: Int, useMmap: Boolean, useMlock: Boolean
    ): Long

    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
