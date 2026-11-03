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
    private var recorder: AudioRecord? = null
    private var focusRequest: AudioFocusRequest? = null

    val isActive: Boolean get() = synchronized(lock) { captureJob?.isActive == true }

    fun stop() {
        val (job, record) = synchronized(lock) {
            val previous = captureJob
            captureJob = null
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
        val job = scope.launch(Dispatchers.IO) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            var echo: AcousticEchoCanceler? = null
            var suppressor: NoiseSuppressor? = null
            var focus: AudioFocusRequest? = null
            var record: AudioRecord? = null
            try {
                focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener { change ->
                        if (change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                    }
                    .build()
                val focusResult = audioManager.requestAudioFocus(focus)
                check(focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    "Το μικρόφωνο δεν απέκτησε προτεραιότητα ήχου"
                }
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "Το κινητό δεν υποστηρίζει εγγραφή PCM16 στα 16 kHz" }
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuffer, FRAME_BYTES * 4))
                check(record.state == AudioRecord.STATE_INITIALIZED) {
                    "Η αρχικοποίηση του μικροφώνου απέτυχε"
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(record.audioSessionId)
                    runCatching { echo?.enabled = true }
                }
                if (NoiseSuppressor.isAvailable()) {
                    suppressor = NoiseSuppressor.create(record.audioSessionId)
                    runCatching { suppressor?.enabled = true }
                }
                synchronized(lock) {
                    if (!currentCoroutineContext().isActive) return@launch
                    recorder = record
                    focusRequest = focus
                }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Η καταγραφή ήχου δεν ξεκίνησε"
                }

                val frame = ByteArray(FRAME_BYTES)
                val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_FRAMES + 1)
                val speech = ByteArrayOutputStream(2 * SAMPLE_RATE * 8)
                var noise = 250.0
                var onset = 0
                var voiced = 0
                var silence = 0
                var total = 0
                var inSpeech = false

                while (currentCoroutineContext().isActive) {
                    val read = record.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) throw IllegalStateException("Η ηχογράφηση διακόπηκε: $read")
                    if (read < 2) continue
                    val available = read and -2
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
                synchronized(lock) {
                    if (recorder === record) recorder = null
                    if (focusRequest === focus) focusRequest = null
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
