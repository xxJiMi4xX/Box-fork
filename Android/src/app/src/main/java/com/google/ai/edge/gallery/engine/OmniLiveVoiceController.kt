package com.google.ai.edge.gallery.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.sqrt

/**
 * In-foreground voice activity detection with one microphone session.
 *
 * Uses the platform VOICE_COMMUNICATION input with best-effort hardware AEC
 * and noise suppression. The activity is responsible for permission and
 * lifecycle. No foreground service or continuous background recording.
 */
class OmniLiveVoiceController(private val context: Context) {
    companion object {
        /** Used by playback to avoid stealing focus from an active voice capture. */
        val activeMicrophones = AtomicInteger(0)
        const val SAMPLE_RATE = 16000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2
        private const val PRE_ROLL_FRAMES = 15
        private const val START_VOICED_FRAMES = 5
        private const val END_SILENCE_FRAMES = 36
        private const val MIN_UTTERANCE_FRAMES = 17
        private const val MAX_UTTERANCE_FRAMES = 1200
    }

    private val lock = Any()
    private var captureJob: Job? = null
    private var retiringJob: Job? = null
    private var recorder: AudioRecord? = null
    private var focusRequest: AudioFocusRequest? = null

    val isActive: Boolean get() = synchronized(lock) { captureJob?.isActive == true }

    fun stop() {
        val (job, record) = synchronized(lock) {
            val previous = captureJob
            captureJob = null
            if (previous != null) retiringJob = previous
            val old = recorder
            recorder = null
            previous to old
        }
        job?.cancel()
        // Stopping the recorder releases the blocking AudioRecord.read call.
        record?.let {
            runCatching { it.stop() }
        }
    }

    /**
     * Starts listening immediately. Callbacks run on the Main dispatcher.
     * onSpeechStart permits barge-in: cancel the preceding model reply and
     * any speech playback when fresh speech has been detected.
     */
    fun start(
        scope: CoroutineScope,
        onSpeechStart: () -> Unit,
        onUtterance: (ByteArray, Int) -> Unit,
        onError: (String) -> Unit,
        onStopped: () -> Unit,
    ) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) { "Δεν έχει δοθεί άδεια μικροφώνου" }
        stop()
        val retired = synchronized(lock) { retiringJob }
        val job = scope.launch(Dispatchers.IO) {
            // Wait until the previous AudioRecord and audio focus are actually
            // released; Android rejects overlapping capture sessions on some devices.
            retired?.join()
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            var echo: AcousticEchoCanceler? = null
            var suppressor: NoiseSuppressor? = null
            var focus: AudioFocusRequest? = null
            var record: AudioRecord? = null
            var counted = false
            try {
                // Communication playback and microphone share one app-owned focus session.
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop()
                    }
                    .build()
                focus = request
                val focusResult = audioManager.requestAudioFocus(request)
                check(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "Το μικρόφωνο δεν απέκτησε προτεραιότητα ήχου"
                }
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "Το κινητό δεν υποστηρίζει εγγραφή PCM16 στα 16 kHz" }
                val audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, FRAME_BYTES * 4))
                record = audioRecord
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    "Η αρχικοποίηση του μικροφώνου απέτυχε"
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = runCatching {
                        AcousticEchoCanceler.create(audioRecord.audioSessionId)
                    }.getOrNull()
                    runCatching { echo?.enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    suppressor = runCatching {
                        NoiseSuppressor.create(audioRecord.audioSessionId)
                    }.getOrNull()
                    runCatching { suppressor?.enabled = true }
                }
                if (!currentCoroutineContext().isActive) return@launch
                synchronized(lock) {
                    recorder = audioRecord
                    focusRequest = request
                }
                audioRecord.startRecording()
                check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Η καταγραφή ήχου δεν ξεκίνησε"
                }
                activeMicrophones.incrementAndGet()
                counted = true

                val frame = ByteArray(FRAME_BYTES)
                val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_FRAMES + 1)
                val speech = ByteArrayOutputStream(2 * SAMPLE_RATE * 8)
                var noise = 250.0
                var onset = 0
                var voiced = 0
                var silence = 0
                var total = 0
                var inSpeech = false
                var echoTailFrames = 0

                while (currentCoroutineContext().isActive) {
                    // Blocking reads normally fill the request, but Android
                    // permits short reads. Analyze exactly one 20-ms frame.
                    var available = 0
                    while (available < FRAME_BYTES && currentCoroutineContext().isActive) {
                        val n = audioRecord.read(
                            frame, available, FRAME_BYTES - available,
                            AudioRecord.READ_BLOCKING)
                        if (n < 0) throw IllegalStateException("Η ηχογράφηση διακόπηκε: $n")
                        if (n == 0) continue
                        available += n
                    }
                    if (!currentCoroutineContext().isActive) break
                    if (available != FRAME_BYTES) continue

                    // An available headset is NOT necessarily the playback
                    // route. Use AudioTrack.getRoutedDevice() reported by the
                    // active player. Without AEC or private routing, avoid
                    // speaker-driven feedback loops.
                    if (OmniVoicePlayback.activePlayers.get() > 0 &&
                        echo?.enabled != true &&
                        !OmniVoicePlayback.privateOutputRouted.get()) {
                        echoTailFrames = 18
                        preRoll.clear()
                        speech.reset()
                        onset = 0
                        voiced = 0
                        silence = 0
                        total = 0
                        inSpeech = false
                        continue
                    }
                    if (echoTailFrames > 0) {
                        echoTailFrames--
                        continue
                    }
                    var power = 0.0
                    for (i in 0 until available step 2) {
                        val sample = (frame[i].toInt() and 255) or (frame[i + 1].toInt() shl 8)
                        val value = sample.toShort().toInt()
                        power += value.toDouble() * value
                    }
                    val rms = sqrt(power / (available / 2))
                    val threshold = max(550.0, noise * 3.1)
                    val loud = rms > threshold
                    if (!inSpeech) {
                        if (!loud) noise = (0.995 * noise + 0.005 * rms).coerceIn(80.0, 3000.0)
                        if (preRoll.size == PRE_ROLL_FRAMES) preRoll.removeFirst()
                        preRoll.addLast(frame.copyOf(available))
                        onset = if (loud) onset + 1 else 0
                        if (onset >= START_VOICED_FRAMES) {
                            inSpeech = true
                            voiced = onset
                            silence = 0
                            total = 0
                            speech.reset()
                            for (buffer in preRoll) {
                                speech.write(buffer)
                                total++
                            }
                            preRoll.clear()
                            withContext(Dispatchers.Main) { onSpeechStart() }
                        }
                        continue
                    }
                    speech.write(frame, 0, available)
                    total++
                    if (loud) {
                        voiced++
                        silence = 0
                    } else {
                        silence++
                    }
                    if (silence >= END_SILENCE_FRAMES || total >= MAX_UTTERANCE_FRAMES) {
                        if (voiced >= MIN_UTTERANCE_FRAMES &&
                            speech.size() <= MAX_UTTERANCE_FRAMES * FRAME_BYTES + PRE_ROLL_FRAMES * FRAME_BYTES) {
                            val bytes = speech.toByteArray()
                            withContext(Dispatchers.Main) { onUtterance(bytes, SAMPLE_RATE) }
                        }
                        inSpeech = false
                        onset = 0
                        silence = 0
                        voiced = 0
                        total = 0
                        speech.reset()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    onError(error.message ?: "Σφάλμα μικροφώνου")
                }
            } finally {
                if (counted) activeMicrophones.decrementAndGet()
                val finishingJob = currentCoroutineContext()[Job]
                synchronized(lock) {
                    if (recorder === record) recorder = null
                    if (focusRequest === focus) focusRequest = null
                    if (retiringJob === finishingJob) retiringJob = null
                }
                runCatching { echo?.release() }
                runCatching { suppressor?.release() }
                runCatching { record?.stop() }
                runCatching { record?.release() }
                focus?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    onStopped()
                }
            }
        }
        synchronized(lock) { captureJob = job }
    }
}
