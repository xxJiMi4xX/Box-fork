package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.jegly.offlineLLM.smollm.SmolLM
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BoxLlamaCppModelHelper"

/**
 * Box: LlmModelHelper implementation backed by llama.cpp for GGUF models.
 * Routes through the smollm JNI bridge.
 */
object LlamaCppModelHelper : LlmModelHelper {

    // Indexed by model name
    private val engines: MutableMap<String, LlamaCppEngine> = mutableMapOf()

    private data class ConversationSnapshot(
        val history: List<Pair<String, String>>,
        val systemPrompt: String,
    )

    private val pendingSnapshots = ConcurrentHashMap<String, ConversationSnapshot>()

    /** Called by the chat ViewModel immediately before a GGUF response. */
    fun setNextConversationSnapshot(
        model: Model,
        history: List<Pair<String, String>>,
        systemPrompt: String,
    ) {
        pendingSnapshots[model.name] = ConversationSnapshot(history.toList(), systemPrompt)
    }

    override fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
        coroutineScope: CoroutineScope?,
    ) {
        val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature = model.getFloatConfigValue(
            key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE
        )

        val modelPath = model.getPath(context = context)
        Log.d(TAG, "Initializing llama.cpp engine for: $modelPath")

        val engine = LlamaCppEngine()
        engines[model.name] = engine

        val params = SmolLM.InferenceParams(
            temperature = temperature,
            topP = topP,
            topK = topK,
            numThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
            projectorPath = model.projectorFileName.takeIf { it.isNotBlank() }?.let { name ->
                model.getPath(context, "$IMPORTS_DIR/$name")
            },
            maxOutputTokens = model.getIntConfigValue(
                ConfigKeys.MAX_TOKENS,
                model.llmMaxToken.takeIf { it > 0 } ?: DEFAULT_MAX_TOKEN,
            ).coerceIn(1, 4096),
        )

        engine.loadModel(
            modelPath = modelPath,
            params = params,
            onSuccess = {
                // Store a marker so the ViewModel knows the model is ready
                model.instance = engine
                onDone("")
            },
            onError = { e ->
                Log.e(TAG, "Failed to load GGUF model", e)
                onDone(e.message ?: "Failed to load GGUF model")
            }
        )
    }

    override fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
    ) {
        pendingSnapshots.remove(model.name)
        val engine = engines[model.name] ?: return
        Log.d(TAG, "Resetting conversation for ${model.name} (keeping model loaded)")

        engine.resetConversation(
            systemPrompt = engine.lastSystemPrompt,
            onSuccess = {
                // Update model instance reference
                model.instance = engine
                Log.d(TAG, "Conversation reset complete for ${model.name}")
            },
            onError = { e ->
                Log.e(TAG, "Failed to reset conversation for ${model.name}", e)
                throw IllegalStateException("Native GGUF reset failed", e)
            },
        )
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) {
        pendingSnapshots.remove(model.name)
        val engine = engines.remove(model.name)
        engine?.unloadModel()
        model.instance = null
        onDone()
        Log.d(TAG, "Clean up done for ${model.name}")
    }

    override fun stopResponse(model: Model) {
        val engine = engines[model.name]
        engine?.stopGeneration()
    }

    override fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        coroutineScope: CoroutineScope?,
        extraContext: Map<String, String>?,
    ) {
        val engine = engines[model.name]
        if (engine == null || !engine.isModelLoaded.get()) {
            onError("llama.cpp engine not initialized for ${model.name}")
            return
        }

        // Audio input is staged at JNI level but remains hidden from the UI
        // until an integrated on-device validation pass confirms end-to-end use.
        if (images.isNotEmpty() || audioClips.size > 1) {
            onError("GGUF currently accepts at most one audio clip, and no images.")
            return
        }

        val snapshot = pendingSnapshots.remove(model.name)
        engine.generateResponse(
            query = input,
            conversationHistory = snapshot?.history,
            systemPrompt = snapshot?.systemPrompt,
            audioWav = audioClips.singleOrNull(),
            onToken = { partialResponse ->
                resultListener(partialResponse, false, null)
            },
            onComplete = { result ->
                // Send the final delta (empty string) with done=true
                resultListener("", true, null)
            },
            onCancelled = {
                resultListener("", true, null)
            },
            onError = { e ->
                Log.e(TAG, "Inference error", e)
                onError(e.message ?: "Inference error")
            }
        )
    }
}
