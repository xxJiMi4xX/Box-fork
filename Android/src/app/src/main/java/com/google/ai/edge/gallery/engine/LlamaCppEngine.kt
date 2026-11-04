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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.ReceiveChannel
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
    enum class SessionState {
        UNLOADED, LOADING, READY, LISTENING, GENERATING, SPEAKING,
        INTERRUPTING, UNLOADING, ERROR
    }
    private val _sessionStatus = MutableStateFlow(SessionState.UNLOADED)
    val sessionStatus: StateFlow<SessionState> = _sessionStatus.asStateFlow()
    val sessionState: SessionState get() = _sessionStatus.value

    private fun publishState(state: SessionState) {
        _sessionStatus.value = state
    }

    private fun markReadyAfterWorker() {
        // An old worker must not overwrite a newer LOADING/UNLOADING/ERROR.
        if (isUnloading) return
        _sessionStatus.update { current ->
            when (current) {
                SessionState.GENERATING, SessionState.SPEAKING,
                SessionState.INTERRUPTING, SessionState.LISTENING -> SessionState.READY
                else -> current
            }
        }
    }

    companion object {
        private val mailbox = NativeSessionMailbox(
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ) { command, started ->
            if (Log.isLoggable("BoxVoiceNative", Log.DEBUG)) {
                Log.d("BoxVoiceNative",
                    "event=native_command command=${command.name} " +
                    "status=${if (started) "started" else "settled"}")
            }
        }
        // Releasing a large GGUF must complete before loading the next one,
        // even when old/new engines are different instances.
        private val latestTeardown = AtomicReference<Job?>(null)
        // A failed JNI close leaves native ownership uncertain. Fail closed:
        // prohibit a second model allocation in this process.
        private val fatalTeardownFailure = AtomicReference<String?>(null)
        fun globalNativeTeardownError(): String? = fatalTeardownFailure.get()
        // Loading and unloading model weights share process-wide llama.cpp
        // backend state; serialize ownership transitions across instances.
        private val globalModelMutex = Mutex()
    }

    @Volatile private var instance = SmolLM()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nativeMutex = Mutex()
    private val sessionMutex = Mutex()

    private suspend fun <T> executeSessionCommand(
        type: NativeVoiceCommand,
        operation: suspend () -> T,
    ): T = mailbox.submit(type) {
        sessionMutex.withLock { operation() }
    }
    // Closing a capture channel is nonblocking and releases a session owner
    // waiting for another AudioRecord frame before model unload/reset.
    private val activeMicrophoneFrames =
        AtomicReference<ReceiveChannel<ByteArray>?>(null)
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
    @Volatile private var hotRoomConversationId: String? = null
    @Volatile private var hotRoomModelEpoch: Long = -1L
    @Volatile private var hotRoomSystemPrompt: String? = null

    fun isRoomHistoryCurrent(conversationId: String, systemPrompt: String): Boolean =
        isModelLoaded.get() && !isUnloading &&
            hotRoomModelEpoch == lifecycleEpoch.get() &&
            hotRoomConversationId == conversationId &&
            hotRoomSystemPrompt == systemPrompt

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
        hotRoomConversationId = null
        hotRoomModelEpoch = -1L
        hotRoomSystemPrompt = null

        stopGeneration()
        activeMicrophoneFrames.get()?.cancel()
        loadJob?.cancel()
        val epoch = lifecycleEpoch.incrementAndGet()
        traceMemory("load_requested", epoch)
        lastLifecycleError = null
        publishState(SessionState.LOADING)
        isModelLoaded.set(false)
        supportsGreekVoice = false
        hardwareInfo = ""
        val pendingTeardown = latestTeardown.get()
        loadJob = scope.launch {
            try {
                pendingTeardown?.join()
                isUnloading = false
                check(fatalTeardownFailure.get() == null) {
                    "Previous native GGUF teardown failed: " +
                        fatalTeardownFailure.get() +
                        ". Restart Box Voice before attempting another load."
                }
                executeSessionCommand(NativeVoiceCommand.LOAD) {
                  globalModelMutex.withLock {
                  nativeMutex.withLock {
                    val previous = instance
                    try {
                        previous.close()
                    } catch (error: Exception) {
                        fatalTeardownFailure.set(error.message ?: error.javaClass.simpleName)
                        throw error
                    }
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
                }
                withContext(Dispatchers.Main) {
                    if (lifecycleEpoch.get() == epoch) {
                        isModelLoaded.set(true)
                        publishState(SessionState.READY)
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
                        publishState(SessionState.ERROR)
                        onError(error)
                    }
                }
            }
        }
    }

    fun unloadModel(onDone: () -> Unit = {}) {
        stopGeneration()
        activeMicrophoneFrames.get()?.cancel()
        loadJob?.cancel()
        val epoch = lifecycleEpoch.incrementAndGet()
        traceMemory("unload_requested", epoch)
        hotRoomConversationId = null
        hotRoomModelEpoch = -1L
        isUnloading = true
        publishState(SessionState.UNLOADING)
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
                executeSessionCommand(NativeVoiceCommand.UNLOAD) {
                    globalModelMutex.withLock {
                    nativeMutex.withLock {
                        val previous = instance
                        previous.close()
                        if (instance === previous) instance = SmolLM()
                    }
                    }
                }
                if (lifecycleEpoch.get() == epoch) {
                    isModelLoaded.set(false)
                    supportsGreekVoice = false
                    hardwareInfo = ""
                    publishState(SessionState.UNLOADED)
                    traceMemory("unload_native_released", epoch)
                }
            } catch (error: Exception) {
                lastLifecycleError = error.message ?: error.javaClass.simpleName
                fatalTeardownFailure.set(lastLifecycleError)
                publishState(SessionState.ERROR)
                Log.e("BoxVoiceNative", "Native unload failed; restart required", error)
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
        hotRoomConversationId = null
        hotRoomModelEpoch = -1L
        val activeGeneration = generationJob
        stopGeneration()
        try {
            runBlocking(Dispatchers.IO) {
                activeGeneration?.join()
                executeSessionCommand(NativeVoiceCommand.CLEAR) {
                nativeMutex.withLock {
                    check(isModelLoaded.get() && !isUnloading && lifecycleEpoch.get() == loadedEpoch) {
                        "The GGUF model changed during conversation reset"
                    }
                    instance.resetConversation(systemPrompt)
                }
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
        conversationId: String? = null,
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
        executeSessionCommand(NativeVoiceCommand.APPEND_TEXT) {
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
            hotRoomConversationId = conversationId
            hotRoomModelEpoch = epoch
            hotRoomSystemPrompt = systemPrompt
            conversationHistory.size
        }
        }
    }

    suspend fun transcribeWav(wav: ByteArray): String = withContext(Dispatchers.IO) {
        require(wav.isNotEmpty()) { "No recorded audio was supplied" }
        executeSessionCommand(NativeVoiceCommand.ASR) {
        nativeMutex.withLock {
            check(isModelLoaded.get() && !isUnloading) { "GGUF model unavailable or unloading" }
            isVoiceBusy = true
            publishState(SessionState.LISTENING)
            try {
                val rate = instance.audioSampleRate()
                check(rate > 0) { "This GGUF requires an audio-capable mmproj projector" }
                val samples = WavPcm16.toFloatMono(wav, rate)
                instance.transcribeAudio(samples).trim()
                    .ifEmpty { throw IllegalStateException("No Greek transcription was produced") }
            } finally {
                isVoiceBusy = false
                markReadyAfterWorker()
            }
        }
        }
    }

    suspend fun synthesizeSpeech(text: String): ByteArray = withContext(Dispatchers.IO) {
        require(text.isNotBlank())
        executeSessionCommand(NativeVoiceCommand.TTS) {
        nativeMutex.withLock {
            check(isModelLoaded.get() && !isUnloading) { "GGUF model unavailable or unloading" }
            isVoiceBusy = true
            publishState(SessionState.GENERATING)
            try {
                instance.synthesizeSpeech(text)
            } finally {
                isVoiceBusy = false
                markReadyAfterWorker()
            }
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
        preparedAudio: Boolean = false,
    ): GenerationResult {
        check(isModelLoaded.get() && !isUnloading && supportsGreekVoice) {
            "Mixed LFM3 speech requires a loaded voice-capable GGUF"
        }
        stopGeneration()
        val epoch = generationEpoch.incrementAndGet()
        return withContext(Dispatchers.IO) {
            executeSessionCommand(NativeVoiceCommand.GENERATE) {
                check(isModelLoaded.get() && !isUnloading && generationEpoch.get() == epoch) {
                    "Mixed generation superseded or model unloaded"
                }
                val model = instance
                val handle = nativeMutex.withLock {
                    if (preparedAudio) {
                        check(model.hasCommittedRealtimeAudio()) {
                            "No intact native audio stream for prepared reply"
                        }
                        model.beginRealtimeMixedResponse(query)
                    } else {
                        model.beginMixedResponse(query)
                    }
                }
                isGenerating = true
                publishState(SessionState.GENERATING)
                val full = StringBuilder()
                try {
                    val duration = measureTime {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(generationEpoch.get() == epoch) {
                                "Mixed generation superseded"
                            }
                            val chunk = nativeMutex.withLock { model.nextMixedResponse(handle) }
                            if (chunk.text.isNotEmpty() || chunk.pcm16.isNotEmpty()) {
                                full.append(chunk.text)
                                if (chunk.pcm16.isNotEmpty()) publishState(SessionState.SPEAKING)
                                // Backpressure and Compose callbacks NEVER hold nativeMutex.
                                onChunk(chunk)
                            }
                            if (chunk.done) break
                        }
                    }
                    GenerationResult(
                        response = full.toString(),
                        tokensPerSecond = nativeMutex.withLock {
                            model.getResponseGenerationSpeed()
                        },
                        durationSeconds = duration.inWholeSeconds.toInt(),
                        contextLengthUsed = nativeMutex.withLock {
                            model.getContextLengthUsed()
                        },
                    )
                } finally {
                    withContext(NonCancellable) {
                        nativeMutex.withLock { model.endMixedResponse(handle) }
                    }
                    // The serialized owner is still held. In-flight native
                    // work has now stopped, even for cancelled epochs.
                    isGenerating = false
                    markReadyAfterWorker()
                }
            }
        }
    }

    /**
     * Consume real microphone frames while AudioRecord is still recording.
     * One serialized native owner spans the full capture turn. The capture
     * callback only trySend()s frames; it never blocks on JNI.
     */
    suspend fun encodeLiveMicrophone(
        frames: ReceiveChannel<ByteArray>,
        sourceRate: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        require(sourceRate in 8000..192000)
        executeSessionCommand(NativeVoiceCommand.APPEND_AUDIO) liveSession@{
            if (!isModelLoaded.get() || isUnloading || !supportsGreekVoice) {
                frames.cancel()
                return@liveSession false
            }
            activeMicrophoneFrames.set(frames)
            val model = instance
            var started = false
            var committed = false
            try {
                val targetRate = nativeMutex.withLock {
                    if (model.supportsRealtimeAudio()) model.audioSampleRate() else 0
                }
                if (targetRate == 0) return@liveSession false
                check(targetRate in 8000..192000) { "Invalid LFM3 input rate" }
                val converter = LivePcm16Resampler(sourceRate, targetRate)
                nativeMutex.withLock {
                    model.beginRealtimeAudio()
                    started = true
                }
                publishState(SessionState.LISTENING)
                for (frame in frames) {
                    currentCoroutineContext().ensureActive()
                    check(isModelLoaded.get() && !isUnloading)
                    val data = converter.append(frame)
                    if (data.isNotEmpty()) {
                        nativeMutex.withLock { model.appendRealtimeAudio(data) }
                    }
                }
                val tail = converter.finish()
                if (tail.isNotEmpty()) {
                    nativeMutex.withLock { model.appendRealtimeAudio(tail) }
                }
                nativeMutex.withLock { model.commitRealtimeAudio() }
                committed = true
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("BoxVoiceNative",
                    "Live PCM staging unavailable; falling back to completed-utterance ASR",
                    error)
                false
            } finally {
                activeMicrophoneFrames.compareAndSet(frames, null)
                frames.cancel()
                if (started && !committed) {
                    withContext(NonCancellable) {
                        nativeMutex.withLock { model.abortRealtimeAudio() }
                    }
                }
                markReadyAfterWorker()
            }
        }
    }

    /**
     * Nonblocking capture abort: the atomic native stop flag is safe while
     * the encoder is inside llama_decode and no Java mutex is acquired.
     */
    fun interruptLiveEncoding() {
        activeMicrophoneFrames.get()?.cancel()
        runCatching { instance.requestStop() }
    }

    /** Inspect pending native audio after independent Greek transcription. */
    suspend fun hasPreparedLiveAudio(): Boolean = withContext(Dispatchers.IO) {
        executeSessionCommand(NativeVoiceCommand.INSPECT) {
            nativeMutex.withLock {
                isModelLoaded.get() && !isUnloading &&
                    instance.hasCommittedRealtimeAudio()
            }
        }
    }

    /**
     * Reject a VAD false-positive, interrupted stream or stale committed
     * utterance. Never perform partial LFM3 seq_rm: clear the incomplete
     * hybrid prefix and replay durable Room pairs when next needed.
     */
    suspend fun discardLiveAudio() = withContext(Dispatchers.IO) {
        executeSessionCommand(NativeVoiceCommand.CANCEL) {
            nativeMutex.withLock {
                if (isModelLoaded.get() && !isUnloading) {
                    instance.abortRealtimeAudio()
                }
            }
            hotRoomConversationId = null
            hotRoomModelEpoch = -1L
            hotRoomSystemPrompt = null
        }
    }

    /** Experimental, opt-in realtime audio route with native incremental encoding.
     *  A real transcript is obtained by the existing Greek ASR before this call;
     *  it is never synthesized from model responses or invented.
     */
    suspend fun supportsRealtimeAudioInput(): Boolean = withContext(Dispatchers.IO) {
        executeSessionCommand(NativeVoiceCommand.INSPECT) {
            nativeMutex.withLock {
                isModelLoaded.get() && !isUnloading &&
                    supportsGreekVoice && instance.supportsRealtimeAudio()
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
                val result = executeSessionCommand(NativeVoiceCommand.GENERATE) {
                    try {
                    nativeMutex.withLock {
                    check(isModelLoaded.get() && !isUnloading && generationEpoch.get() == epoch) {
                        "Model was unloaded or this response was superseded"
                    }
                    currentCoroutineContext().ensureActive()
                    isGenerating = true
                    publishState(SessionState.GENERATING)
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
                    } finally {
                        // State is released while still owning the session mutex.
                        isGenerating = false
                        markReadyAfterWorker()
                    }
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
                // State was released under sessionMutex, not here:
                // a newer turn might already be generating.
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
        activeMicrophoneFrames.get()?.cancel()
        if (isGenerating) {
            if (!isUnloading) publishState(SessionState.INTERRUPTING)
            hotRoomConversationId = null
            hotRoomModelEpoch = -1L
        }
        generationEpoch.incrementAndGet()
        // Native requestStop writes an atomic flag and is safe while JNI
        // holds its model mutex for a long ASR or synthesis operation.
        runCatching { instance.requestStop() }
        generationJob?.cancel()
        // Do not report idle until the serialized JNI operation has exited.
    }

    fun benchModel(pp: Int = 512, tg: Int = 128, pl: Int = 1, nr: Int = 3): String {
        // The benchmark touches the same llama_context as ASR, streaming and
        // generation. Never bypass the process-wide owner or block Compose.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "GGUF benchmark requires a background thread"
        }
        return runBlocking(Dispatchers.IO) {
            executeSessionCommand(NativeVoiceCommand.INSPECT) {
                nativeMutex.withLock {
                    if (!isModelLoaded.get() || isUnloading || isGenerating || isVoiceBusy) {
                        "GGUF model unavailable or busy"
                    } else {
                        instance.benchModel(pp, tg, pl, nr)
                    }
                }
            }
        }
    }

    fun isReady(): Boolean = isModelLoaded.get() && !isUnloading &&
        _sessionStatus.value == SessionState.READY &&
        lastLifecycleError == null && !isGenerating && !isVoiceBusy
}
