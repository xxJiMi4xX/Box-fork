package com.google.ai.edge.gallery.engine

import com.jegly.offlineLLM.smollm.SmolLM
import android.os.Looper
import android.os.Debug
import android.util.Log
import java.io.File
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.measureTime

/**
 * Serializes all JNI operations against a single native llama_context.
 * Cancellation signals the native decode loop before waiting for the mutex.
 * LiteRT-LM uses its own independent engine and is unaffected.
 */
class LlamaCppEngine {
    companion object {
        // Releasing a large GGUF must complete before loading the next one,
        // even when old/new engines are different instances.
        private val latestTeardown = AtomicReference<Job?>(null)
        // Loading and unloading model weights share process-wide llama.cpp
        // backend state; serialize ownership transitions across instances.
        private val globalModelMutex = Mutex()
    }

    @Volatile private var instance = SmolLM()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeMutex = Mutex()
    private fun traceMemory(stage: String, epoch: Long) {
        if (!Log.isLoggable("BoxVoiceNative", Log.DEBUG)) return
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val fds = runCatching { File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-1)
        val mappings = runCatching { File("/proc/self/maps").useLines { it.count() } }
            .getOrDefault(-1)
        Log.d("BoxVoiceNative", "event=$stage epoch=$epoch " +
            "pss_kb=${info.totalPss} native_pss_kb=${info.nativePss} " +
            "fd_count=$fds mappings_count=$mappings model_loaded=${isModelLoaded.get()}")
    }
    private val lifecycleEpoch = AtomicLong(0L)
    private val generationEpoch = AtomicLong(0L)

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
    @Volatile var hardwareInfo: String = ""
        private set
    @Volatile var supportsGreekVoice = false
        private set
    @Volatile var isUnloading = false
        private set
    @Volatile var lastLifecycleError: String? = null
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
        val epoch = lifecycleEpoch.incrementAndGet()
        traceMemory("load_requested", epoch)
        lastLifecycleError = null
        isModelLoaded.set(false)
        supportsGreekVoice = false
        hardwareInfo = ""
        val pendingTeardown = latestTeardown.get()
        loadJob = scope.launch {
            try {
                pendingTeardown?.join()
                isUnloading = false
                globalModelMutex.withLock {
                  nativeMutex.withLock {
                    val previous = instance
                    previous.close()
                    val next = SmolLM()
                    instance = next
                    try {
                        next.load(modelPath, params)
                        traceMemory("model_allocated", epoch)
                        supportsGreekVoice = next.audioCapabilities() == 3
                        hardwareInfo = next.hardwareInfo()
                        if (systemPrompt.isNotBlank()) next.addSystemPrompt(systemPrompt)
                        conversationHistory.forEach { (role, content) ->
                            next.addChatMessage(role, content)
                        }
                        currentCoroutineContext().ensureActive()
                        check(lifecycleEpoch.get() == epoch) { "Model load was superseded" }
                    } catch (failure: Throwable) {
                        supportsGreekVoice = false
                        hardwareInfo = ""
                        next.close()
                        if (instance === next) instance = SmolLM()
                        throw failure
                    }
                  }
                }
                withContext(Dispatchers.Main) {
                    if (lifecycleEpoch.get() == epoch) {
                        isModelLoaded.set(true)
                        onSuccess()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (lifecycleEpoch.get() == epoch) {
                        isModelLoaded.set(false)
                        lastLifecycleError = error.message ?: error.javaClass.simpleName
                        onError(error)
                    }
                }
            }
        }
    }

    fun unloadModel(onDone: () -> Unit = {}) {
        stopGeneration()
        loadJob?.cancel()
        val epoch = lifecycleEpoch.incrementAndGet()
        traceMemory("unload_requested", epoch)
        isUnloading = true
        lastLifecycleError = null
        // Do not capture instance before the cancelled load job exits:
        // it may still be constructing a *different* SmolLM handle.
        val loading = loadJob
        val activeGeneration = generationJob
        val preceding = latestTeardown.get()
        val teardown = scope.launch(Dispatchers.IO) {
            try {
                loading?.join()
                activeGeneration?.join()
                preceding?.join()
                globalModelMutex.withLock {
                    nativeMutex.withLock {
                        val previous = instance
                        previous.close()
                        if (instance === previous) instance = SmolLM()
                    }
                }
                if (lifecycleEpoch.get() == epoch) {
                    isModelLoaded.set(false)
                    supportsGreekVoice = false
                    hardwareInfo = ""
                    traceMemory("unload_native_released", epoch)
                }
            } catch (error: Exception) {
                lastLifecycleError = error.message ?: error.javaClass.simpleName
                Log.e("BoxVoiceNative", "Native unload failed", error)
            } finally {
                traceMemory("unload_teardown_settled", epoch)
                withContext(NonCancellable + Dispatchers.Main) {
                    if (lifecycleEpoch.get() == epoch) isUnloading = false
                    onDone()
                }
            }
        }
        latestTeardown.set(teardown)
    }

    /**
     * LlmModelHelper.resetConversation has a synchronous contract. Do not
     * fire-and-forget a full model reload: return only after native history
     * and KV cache are reset. Called from ViewModel Dispatchers.Default.
     */
    fun resetConversation(
        modelPath: String,
        params: SmolLM.InferenceParams = lastLoadParams ?: SmolLM.InferenceParams(),
        systemPrompt: String = "",
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        if (modelPath != lastModelPath) {
            onError(IllegalArgumentException("Cannot reset a different loaded GGUF"))
            return
        }
        // A synchronous bridge on the UI thread could deadlock a generation
        // coroutine that is delivering its final callback to Dispatchers.Main.
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "GGUF conversation reset must be dispatched off the Android main thread"
        }
        val loadedEpoch = lifecycleEpoch.get()
        val activeGeneration = generationJob
        stopGeneration()
        try {
            runBlocking(Dispatchers.IO) {
                activeGeneration?.join()
                nativeMutex.withLock {
                    check(isModelLoaded.get() && !isUnloading && lifecycleEpoch.get() == loadedEpoch) {
                        "The GGUF model changed during conversation reset"
                    }
                    instance.resetConversation(systemPrompt)
                }
            }
            lastSystemPrompt = systemPrompt
            onSuccess()
        } catch (error: Exception) {
            onError(error)
        }
    }

    /**
     * Recreate the native GGUF dialogue from a durable Room conversation.
     * This is ordered under the same mutex as decoding and model teardown.
     * Older turns remain in Room; native prefill prunes them at n_ctx as needed.
     */
    suspend fun restoreConversation(
        systemPrompt: String,
        conversationHistory: List<Pair<String, String>>,
        stillCurrent: () -> Boolean = { true },
    ): Int = withContext(Dispatchers.IO) {
        require(conversationHistory.all { (role, _) -> role == "user" || role == "assistant" }) {
            "Conversation contains an unsupported role"
        }
        if (!stillCurrent()) return@withContext 0
        val epoch = lifecycleEpoch.get()
        stopGeneration()
        val current = generationJob
        current?.join()
        nativeMutex.withLock {
            if (!stillCurrent()) return@withLock 0
            check(isModelLoaded.get() && !isUnloading && epoch == lifecycleEpoch.get()) {
                "Model changed before saved history could be restored"
            }
            val model = instance
            model.resetConversation(systemPrompt)
            // Native prompt construction will drop oldest complete turns
            // if they exceed the configured context window.
            for ((role, content) in conversationHistory) {
                model.addChatMessage(role, content)
            }
            lastSystemPrompt = systemPrompt
            conversationHistory.size
        }
    }

    suspend fun transcribeWav(wav: ByteArray): String = withContext(Dispatchers.IO) {
        require(wav.isNotEmpty()) { "No recorded audio was supplied" }
        nativeMutex.withLock {
            check(isModelLoaded.get() && !isUnloading) { "GGUF model unavailable or unloading" }
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
            check(isModelLoaded.get() && !isUnloading) { "GGUF model unavailable or unloading" }
            isVoiceBusy = true
            try {
                instance.synthesizeSpeech(text)
            } finally {
                isVoiceBusy = false
            }
        }
    }

    /**
     * Stream native LFM3 text and speech from one GGUF context.
     * The callback may suspend to apply bounded audio backpressure.
     * The caller owns cancellation and closes the playback channel.
     */
    suspend fun streamMixedResponse(
        query: String,
        onChunk: suspend (SmolLM.MixedVoiceStep) -> Unit,
    ): GenerationResult {
        check(isModelLoaded.get() && !isUnloading && supportsGreekVoice) {
            "Mixed LFM3 speech requires a loaded voice-capable GGUF"
        }
        stopGeneration()
        val epoch = generationEpoch.incrementAndGet()
        return withContext(Dispatchers.IO) {
            nativeMutex.withLock {
                check(isModelLoaded.get() && !isUnloading && generationEpoch.get() == epoch) {
                    "Mixed generation superseded or model unloaded"
                }
                currentCoroutineContext().ensureActive()
                isGenerating = true
                val model = instance
                val full = StringBuilder()
                try {
                    val duration = measureTime {
                        model.getMixedResponseAsFlow(query).collect { part ->
                            currentCoroutineContext().ensureActive()
                            check(generationEpoch.get() == epoch) {
                                "Mixed generation superseded"
                            }
                            full.append(part.text)
                            onChunk(part)
                        }
                    }
                    GenerationResult(
                        response = full.toString(),
                        tokensPerSecond = model.getResponseGenerationSpeed(),
                        durationSeconds = duration.inWholeSeconds.toInt(),
                        contextLengthUsed = model.getContextLengthUsed(),
                    )
                } finally {
                    if (generationEpoch.get() == epoch) isGenerating = false
                }
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
        if (!isModelLoaded.get() || isUnloading) {
            onError(IllegalStateException("Model not loaded"))
            return
        }
        stopGeneration()
        val epoch = generationEpoch.incrementAndGet()
        generationJob = scope.launch {
            try {
                val result = nativeMutex.withLock {
                    check(isModelLoaded.get() && !isUnloading && generationEpoch.get() == epoch) {
                        "Model was unloaded or this response was superseded"
                    }
                    currentCoroutineContext().ensureActive()
                    isGenerating = true
                    val model = instance
                    val full = StringBuilder()
                    var emittedLength = 0
                    val duration = measureTime {
                        model.getResponseAsFlow(query).collect { piece ->
                            currentCoroutineContext().ensureActive()
                            full.append(piece)
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
                withContext(Dispatchers.Main) {
                    if (generationEpoch.get() == epoch) onComplete(result)
                }
            } catch (_: CancellationException) {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (generationEpoch.get() == epoch) onCancelled()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (generationEpoch.get() == epoch) onError(error)
                }
            } finally {
                if (generationEpoch.get() == epoch) isGenerating = false
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
        generationEpoch.incrementAndGet()
        // Native requestStop writes an atomic flag and is safe while JNI
        // holds its model mutex for a long ASR or synthesis operation.
        runCatching { instance.requestStop() }
        generationJob?.cancel()
        isGenerating = false
    }

    fun benchModel(pp: Int = 512, tg: Int = 128, pl: Int = 1, nr: Int = 3): String {
        return if (isModelLoaded.get()) {
            instance.benchModel(pp, tg, pl, nr)
        } else "Model not loaded"
    }

    fun isReady(): Boolean = isModelLoaded.get() && !isUnloading && !isGenerating && !isVoiceBusy
}
