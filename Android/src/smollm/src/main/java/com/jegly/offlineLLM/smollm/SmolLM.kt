package com.jegly.offlineLLM.smollm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SmolLM {
    companion object {
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
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
        val projectorPath: String? = null,
        val maxOutputTokens: Int = 1024,
    )

    object DefaultParams {
        const val CONTEXT_SIZE: Long = 4096L
    }

    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) =
        withContext(Dispatchers.IO) {
            check(nativePtr == 0L) { "A model is already loaded." }
            // The native loader reads model metadata and the model's own chat template.
            // A model-advertised training context may be too large for a mobile device.
            val handle = loadModel(
                modelPath,
                params.minP,
                params.temperature,
                params.topP,
                params.topK,
                params.repeatPenalty,
                params.storeChats,
                params.contextSize ?: DefaultParams.CONTEXT_SIZE,
                params.chatTemplate ?: "",
                params.numThreads,
                params.useMmap,
                params.useMlock,
                params.maxOutputTokens,
            )
            check(handle != 0L) { "Native model loading failed." }
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                nativePtr = handle
            } catch (e: kotlinx.coroutines.CancellationException) {
                close(handle)
                throw e
            }
        }

    data class ProjectorCapabilities(
        val audioInput: Boolean,
        val imageInput: Boolean,
        val audioOutput: Boolean,
        val inputSampleRate: Int,
        val outputSampleRate: Int,
    )

    suspend fun loadProjector(path: String): ProjectorCapabilities =
        withContext(Dispatchers.IO) {
            val handle = nativePtr
            check(handle != 0L) { "Load the GGUF model before its mmproj." }
            val values = loadProjectorNative(handle, path)
            check(values.size == 5) { "Invalid projector capability response." }
            ProjectorCapabilities(
                audioInput = values[0] != 0,
                imageInput = values[1] != 0,
                audioOutput = values[2] != 0,
                inputSampleRate = values[3],
                outputSampleRate = values[4],
            )
        }

    /**
     * Generic method to add a message with a specific role.
     * Essential for models like Gemma that use "model" instead of "assistant".
     */
    fun addChatMessage(role: String, message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, role)
    }

    /** Synchronous model-native TTS; run from a worker thread, never the UI. */
    fun synthesizeSpeech(
        text: String,
        language: String = "Greek",
        voice: String = "female",
        maxFrames: Int = 512,
    ): ByteArray {
        require(text.isNotBlank()) { "TTS text cannot be blank" }
        require(maxFrames in 1..1024) { "TTS maxFrames must be 1..1024" }
        verifyHandle()
        return synthesizeSpeechNative(nativePtr, text, language, voice, maxFrames)
    }

    fun resetSession() {
        verifyHandle()
        resetSessionNative(nativePtr)
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

    fun getResponseAsFlow(query: String, audioWav: ByteArray? = null): Flow<String> = flow {
        val handle = nativePtr
        check(handle != 0L) { "Model is not loaded." }
        if (audioWav == null) startCompletion(handle, query)
        else startCompletionAudio(handle, query, audioWav)
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val piece = completionLoop(handle)
                if (piece == "[EOG]") break
                if (piece.isNotEmpty()) emit(piece)
            }
        } finally {
            try { stopCompletion(handle) } catch (_: IllegalStateException) {}
        }
    }

    fun getResponse(query: String): String {
        val handle = nativePtr
        check(handle != 0L) { "Model is not loaded." }
        startCompletion(handle, query)
        val response = StringBuilder()
        try {
            while (true) {
                val piece = completionLoop(handle)
                if (piece == "[EOG]") break
                response.append(piece)
            }
        } finally {
            try { stopCompletion(handle) } catch (_: IllegalStateException) {}
        }
        return response.toString()
    }

    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }

    fun interruptGeneration() {
        val handle = nativePtr
        if (handle != 0L) {
            try { requestAbort(handle) } catch (_: IllegalStateException) {}
        }
    }

    fun close() {
        val handle = nativePtr
        nativePtr = 0L
        if (handle != 0L) close(handle)
    }

    fun isLoaded(): Boolean = nativePtr != 0L

    private fun verifyHandle() {
        check(nativePtr != 0L) { "Model is not loaded. Call SmolLM.load() first." }
    }

    private external fun loadModel(
        modelPath: String, minP: Float, temperature: Float, topP: Float, topK: Int,
        repeatPenalty: Float, storeChats: Boolean, contextSize: Long, chatTemplate: String,
        nThreads: Int, useMmap: Boolean, useMlock: Boolean, maxOutputTokens: Int
    ): Long

    private external fun loadProjectorNative(modelPtr: Long, mmprojPath: String): IntArray
    private external fun addChatMessage(modelPtr: Long, message: String, role: String)
    private external fun resetSessionNative(modelPtr: Long)
    private external fun synthesizeSpeechNative(
        modelPtr: Long, text: String, language: String, voice: String, maxFrames: Int,
    ): ByteArray
    private external fun getResponseGenerationSpeed(modelPtr: Long): Float
    private external fun getContextSizeUsed(modelPtr: Long): Int
    private external fun close(modelPtr: Long)
    private external fun startCompletion(modelPtr: Long, prompt: String)
    private external fun startCompletionAudio(modelPtr: Long, prompt: String, audioWav: ByteArray)
    private external fun completionLoop(modelPtr: Long): String
    private external fun stopCompletion(modelPtr: Long)
    private external fun requestAbort(modelPtr: Long)
    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
