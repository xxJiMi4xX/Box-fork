package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.gallery.engine.GgufAudioFiles
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
    private val engines = ConcurrentHashMap<String, LlamaCppEngine>()

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
        engines.put(model.name, engine)?.unloadModel()

        val projector = GgufAudioFiles.findProjector(context, modelPath)
        Log.d(TAG, "Selected GGUF mmproj: ${projector ?: "not installed"}")
        val params = SmolLM.InferenceParams(
            temperature = temperature,
            topP = topP,
            topK = topK,
            numThreads = VoiceGgufRuntimeSettings.cpuThreads(context, modelPath),
            mmprojPath = projector,
        )

        engine.loadModel(
            modelPath = modelPath,
            params = params,
            onSuccess = {
                if (engines[model.name] === engine) {
                    model.instance = engine
                    onDone("")
                } else {
                    engine.unloadModel()
                }
            },
            onError = { e ->
                if (engines[model.name] === engine) {
                    Log.e(TAG, "Failed to load GGUF model", e)
                    onDone(e.message ?: "Failed to load GGUF model")
                }
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
        val engine = engines[model.name]
            ?: throw IllegalStateException("No loaded GGUF engine to reset")
        val modelPath = engine.lastModelPath
            ?: throw IllegalStateException("No loaded GGUF model path")

        Log.d(TAG, "Resetting conversation for ${model.name} (keeping model loaded)")

        engine.resetConversation(
            modelPath = modelPath,
            params = engine.lastLoadParams ?: SmolLM.InferenceParams(),
            systemPrompt = engine.lastSystemPrompt,
            onSuccess = {
                if (engines[model.name] === engine) {
                    model.instance = engine
                    Log.d(TAG, "Conversation reset complete for ${model.name}")
                }
            },
            onError = { e ->
                Log.e(TAG, "Failed to reset conversation for ${model.name}", e)
                throw e
            },
        )
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) {
        val engine = engines.remove(model.name)
        // Block new turns immediately while the native resources drain.
        model.instance = null
        if (engine == null) {
            onDone()
            return
        }
        engine.unloadModel(onDone = {
            Log.d(TAG, "Clean up done for ${model.name}")
            onDone()
        })
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

        // Do not silently discard multimodal inputs: this GGUF adapter is text-only
        // until a matching mmproj and the libmtmd audio/image pipeline are integrated.
        if (images.isNotEmpty() || audioClips.isNotEmpty()) {
            val inputTypes = when {
                images.isNotEmpty() && audioClips.isNotEmpty() -> "image and audio"
                images.isNotEmpty() -> "image"
                else -> "audio"
            }
            val message = "GGUF $inputTypes input is not supported by this build; it was not processed."
            Log.w(TAG, message)
            onError(message)
            return
        }

        engine.generateResponse(
            query = input,
            onToken = { partialResponse ->
                resultListener(partialResponse, false, null)
            },
            onComplete = { result ->
                // Send the final delta (empty string) with done=true
                resultListener("", true, null)
            },
            onCancelled = {
                // A canceled turn was rolled back in native history. Never
                // tell the ViewModel it was a completed answer (or trigger TTS).
                cleanUpListener()
            },
            onError = { e ->
                Log.e(TAG, "Inference error", e)
                onError(e.message ?: "Inference error")
            }
        )
    }
}
