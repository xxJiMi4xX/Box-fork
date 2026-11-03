package com.google.ai.edge.gallery.engine

import com.jegly.offlineLLM.smollm.SmolLM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.measureTime

/**
 * Serializes all JNI operations against a single native llama_context.
 * Cancellation signals the native decode loop before waiting for the mutex.
 * LiteRT-LM uses its own independent engine and is unaffected.
 */
class LlamaCppEngine {
    @Volatile private var instance = SmolLM()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeMutex = Mutex()

    var lastLoadParams: SmolLM.InferenceParams? = null
        private set
    var lastModelPath: String? = null
        private set
    var lastSystemPrompt: String = ""
        private set

    @Volatile private var generationJob: Job? = null
    @Volatile private var loadJob: Job? = null

    val isModelLoaded = AtomicBoolean(false)
    @Volatile var isGenerating = false
        private set
    @Volatile var isVoiceBusy = false
        private set

    data class GenerationResult(
        val response: String,
        val tokensPerSecond: Float,
        val durationSeconds: Int,
        val contextLengthUsed: Int,
    )

    fun loadModel(
        modelPath: String,
        params: SmolLM.InferenceParams = SmolLM.InferenceParams(),
        systemPrompt: String = "",
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onSuccess: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        lastLoadParams = params
        lastModelPath = modelPath
        lastSystemPrompt = systemPrompt

        stopGeneration()
        loadJob?.cancel()
        isModelLoaded.set(false)
        loadJob = scope.launch {
            try {
                nativeMutex.withLock {
                    val previous = instance
                    previous.close()
                    val next = SmolLM()
                    instance = next
                    next.load(modelPath, params)
                    if (systemPrompt.isNotBlank()) next.addSystemPrompt(systemPrompt)
                    conversationHistory.forEach { (role, content) ->
                        next.addChatMessage(role, content)
                    }
                    currentCoroutineContext().ensureActive()
                }
                withContext(Dispatchers.Main) {
                    isModelLoaded.set(true)
                    onSuccess()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    isModelLoaded.set(false)
                    onError(error)
                }
            }
        }
    }

    fun unloadModel() {
        stopGeneration()
        loadJob?.cancel()
        isModelLoaded.set(false)
        // Close the captured instance only after any active JNI call releases
        // the native mutex. A subsequent model load cannot be closed by this job.
        val previous = instance
        instance = SmolLM()
        scope.launch(Dispatchers.IO) {
            nativeMutex.withLock { previous.close() }
        }
    }

    fun resetConversation(
        modelPath: String,
        params: SmolLM.InferenceParams = lastLoadParams ?: SmolLM.InferenceParams(),
        systemPrompt: String = "",
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        loadModel(
            modelPath = modelPath,
            params = params,
            systemPrompt = systemPrompt,
            onSuccess = onSuccess,
            onError = onError,
        )
    }

    suspend fun transcribeWav(wav: ByteArray): String = withContext(Dispatchers.IO) {
        require(wav.isNotEmpty()) { "No recorded audio was supplied" }
        nativeMutex.withLock {
            check(isModelLoaded.get()) { "GGUF model is not loaded" }
            isVoiceBusy = true
            try {
                val rate = instance.audioSampleRate()
                check(rate > 0) { "This GGUF requires an audio-capable mmproj projector" }
                val samples = WavPcm16.toFloatMono(wav, rate)
                instance.transcribeAudio(samples).trim()
                    .ifEmpty { throw IllegalStateException("No Greek transcription was produced") }
            } finally {
                isVoiceBusy = false
            }
        }
    }

    suspend fun synthesizeSpeech(text: String): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank())
        nativeMutex.withLock {
            check(isModelLoaded.get()) { "GGUF model is not loaded" }
            isVoiceBusy = true
            try {
                instance.synthesizeSpeech(text)
            } finally {
                isVoiceBusy = false
            }
        }
    }

    fun generateResponse(
        query: String,
        onToken: (String) -> Unit,
        onComplete: (GenerationResult) -> Unit,
        onCancelled: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (!isModelLoaded.get()) {
            onError(IllegalStateException("Model not loaded"))
            return
        }
        stopGeneration()
        generationJob = scope.launch {
            try {
                val result = nativeMutex.withLock {
                    check(isModelLoaded.get()) { "Model was unloaded" }
                    currentCoroutineContext().ensureActive()
                    isGenerating = true
                    val model = instance
                    val stopSequences = listOf(
                        "<|im_end|>", "<|eot_id|>", "<|endoftext|>", "<|im_start|>")
                    val full = StringBuilder()
                    var emittedLength = 0
                    var stopped = false
                    val duration = measureTime {
                        model.getResponseAsFlow(query).collect { piece ->
                            currentCoroutineContext().ensureActive()
                            if (stopped) return@collect
                            full.append(piece)
                            val raw = full.toString()
                            val stopPosition = stopSequences
                                .mapNotNull { seq -> raw.indexOf(seq).takeIf { it >= 0 } }
                                .minOrNull()
                            if (stopPosition != null) {
                                full.setLength(stopPosition)
                                stopped = true
                            }
                            val visible = cleanModelOutput(full.toString(), false)
                            if (visible.length > emittedLength) {
                                val delta = visible.substring(emittedLength)
                                emittedLength = visible.length
                                withContext(Dispatchers.Main) { onToken(delta) }
                            }
                        }
                    }
                    GenerationResult(
                        response = cleanModelOutput(full.toString(), true),
                        tokensPerSecond = model.getResponseGenerationSpeed(),
                        durationSeconds = duration.inWholeSeconds.toInt(),
                        contextLengthUsed = model.getContextLengthUsed(),
                    )
                }
                withContext(Dispatchers.Main) { onComplete(result) }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) { onCancelled() }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { onError(error) }
            } finally {
                isGenerating = false
            }
        }
    }

    private fun cleanModelOutput(raw: String, isFinal: Boolean): String {
        var value = raw
            .replace(Regex("<\\|im_start\\|>(system|user|assistant)\\n?", RegexOption.IGNORE_CASE), "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("<|eot_id|>", "")
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<think>.*", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<start_of_turn>.*?\\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<end_of_turn>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<turn\\|.*?\\|>", RegexOption.IGNORE_CASE), "")
            .replace("System instruction:", "")
        if (isFinal) value = value.replace(Regex("<[^>]*$"), "")
        return value.trim()
    }

    fun stopGeneration() {
        runCatching { instance.requestStop() }
        generationJob?.cancel()
        isGenerating = false
    }

    fun benchModel(pp: Int = 512, tg: Int = 128, pl: Int = 1, nr: Int = 3): String {
        return if (isModelLoaded.get()) {
            instance.benchModel(pp, tg, pl, nr)
        } else "Model not loaded"
    }

    fun isReady(): Boolean = isModelLoaded.get() && !isGenerating && !isVoiceBusy
}
