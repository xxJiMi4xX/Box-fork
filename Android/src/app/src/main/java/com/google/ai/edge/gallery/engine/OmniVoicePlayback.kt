package com.google.ai.edge.gallery.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.ReceiveChannel

/** Interruptible synthesized voice playback with Android audio-focus handling. */
class OmniVoicePlayback {
    companion object {
        val activePlayers = AtomicInteger(0)
        val privateOutputRouted = AtomicBoolean(false)
        private val targetPrebufferMs = AtomicInteger(VoiceAudioBufferPolicy.INITIAL_PREBUFFER_MS)
        val lastStreamUnderruns = AtomicInteger(-1)
        val lastStreamPrebufferMs = AtomicInteger(-1)

        private fun privateDevice(device: AudioDeviceInfo?): Boolean =
            when (device?.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> true
                else -> false
            }
    }

    private val lock = Any()
    private var active: AudioTrack? = null
    @Volatile private var audioManager: AudioManager? = null

    /** Only retain the system AudioManager, not a screen/activity context. */
    fun configure(context: Context) {
        audioManager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun stop() {
        synchronized(lock) {
            val old = active
            active = null
            if (old != null) {
                runCatching { old.pause() }
                runCatching { old.flush() }
                runCatching { old.release() }
            }
        }
    }

    data class PcmChunk(val sampleRate: Int, val bytes: ByteArray)

    /**
     * One long-lived AudioTrack for LFM3 interleaved output. Unlike play(),
     * this consumes frames as the model generates them and never waits for a WAV.
     */
    suspend fun playPcmStream(
        chunks: ReceiveChannel<PcmChunk>,
        onFirstPcmPlayed: (() -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val first = chunks.receiveCatching().getOrNull() ?: return@withContext
        require(first.sampleRate in 8000..192000 && first.bytes.isNotEmpty() &&
            first.bytes.size % 2 == 0 &&
            first.bytes.size <= VoiceAudioBufferPolicy.windowBytes(first.sampleRate)) {
            "Invalid first streaming PCM chunk; expected at most one 40-ms window"
        }

        stop()
        val rate = first.sampleRate
        val prebufferMs = targetPrebufferMs.get()
        val startChunks = ArrayDeque<PcmChunk>()
        startChunks.addLast(first)
        var queuedFrames = first.bytes.size / 2L
        val timeoutAt = SystemClock.elapsedRealtime() + 600L
        while (queuedFrames * 1000L / rate < prebufferMs) {
            val remaining = timeoutAt - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            val next = withTimeoutOrNull(remaining) { chunks.receiveCatching().getOrNull() }
                ?: break
            check(next.sampleRate == rate && next.bytes.isNotEmpty() &&
                next.bytes.size % 2 == 0 &&
                next.bytes.size <= VoiceAudioBufferPolicy.windowBytes(rate)) {
                "Mixed output changed PCM format or exceeded a 40-ms window"
            }
            startChunks.addLast(next)
            queuedFrames += next.bytes.size / 2L
            check(queuedFrames <= rate * 4L) { "Prebuffer exceeded four seconds" }
        }
        val minimum = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Unsupported streaming PCM sample rate" }
        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()
        val manager = audioManager
        val focus = if (manager != null && OmniLiveVoiceController.activeMicrophones.get() == 0) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                }
                .build()
        } else null
        if (focus != null) {
            check(manager!!.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Failed to acquire streaming audio focus"
            }
        }

        var track: AudioTrack? = null
        var counted = false
        try {
            val player = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, rate * 2 * (prebufferMs + 250) / 1000))
                .build()
            track = player
            check(player.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
            synchronized(lock) { active = player }
            activePlayers.incrementAndGet()
            counted = true
            privateOutputRouted.set(privateDevice(player.routedDevice))
            var firstPlayed = false
            var totalSamples = 0L
            var maxPlaybackWaitMs = 0L
            val firstPlaybackNs = SystemClock.elapsedRealtimeNanos()

            suspend fun writeChunk(chunk: PcmChunk) {
                check(chunk.sampleRate == rate && chunk.bytes.isNotEmpty() &&
                    chunk.bytes.size % 2 == 0 &&
                    chunk.bytes.size <= VoiceAudioBufferPolicy.windowBytes(rate)) {
                    "Mixed audio sample-rate, alignment or 40-ms window invalid"
                }
                var offset = 0
                while (offset < chunk.bytes.size && currentCoroutineContext().isActive &&
                    synchronized(lock) { active === player }) {
                    privateOutputRouted.set(privateDevice(player.routedDevice))
                    val size = minOf(8192, chunk.bytes.size - offset)
                    val written = player.write(chunk.bytes, offset, size, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "Streaming AudioTrack write failed: $written" }
                    offset += written
                    totalSamples += written / 2L
                    if (!firstPlayed && player.playbackHeadPosition > 0) {
                        firstPlayed = true
                        onFirstPcmPlayed?.invoke()
                    }
                }
            }
            for (chunk in startChunks) writeChunk(chunk)
            player.play()
            var lastChunkNs = SystemClock.elapsedRealtimeNanos()
            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()
                if (!synchronized(lock) { active === player }) break
                val arrivedNs = SystemClock.elapsedRealtimeNanos()
                maxPlaybackWaitMs = maxOf(maxPlaybackWaitMs,
                    (arrivedNs - lastChunkNs) / 1_000_000L)
                writeChunk(chunk)
                lastChunkNs = SystemClock.elapsedRealtimeNanos()
            }

            val deadlineMs = totalSamples * 1000L / rate + 5000L
            withTimeoutOrNull(deadlineMs) {
                while (currentCoroutineContext().isActive &&
                    synchronized(lock) { active === player } &&
                    player.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    player.playbackHeadPosition.toLong() < totalSamples) {
                    if (!firstPlayed && player.playbackHeadPosition > 0) {
                        firstPlayed = true
                        onFirstPcmPlayed?.invoke()
                    }
                    delay(20)
                }
            }
            val underruns = player.underrunCount
            lastStreamUnderruns.set(underruns)
            lastStreamPrebufferMs.set(prebufferMs)
            targetPrebufferMs.updateAndGet { current ->
                VoiceAudioBufferPolicy.adaptPrebuffer(current, underruns)
            }
            if (Log.isLoggable("BoxVoiceNative", Log.DEBUG)) {
                Log.d("BoxVoiceNative", "event=pcm_stream_end prebuffer_ms=$prebufferMs " +
                    "sample_rate_hz=$rate total_frames=$totalSamples " +
                    "max_playback_wait_ms=$maxPlaybackWaitMs " +
                    "elapsed_ms=${(SystemClock.elapsedRealtimeNanos() - firstPlaybackNs) / 1_000_000L} " +
                    "underruns=${player.underrunCount} buffer_frames=${player.bufferSizeInFrames}")
            }
        } finally {
            if (counted && activePlayers.decrementAndGet() == 0) {
                privateOutputRouted.set(false)
            }
            synchronized(lock) { if (active === track) active = null }
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            if (focus != null) runCatching { manager!!.abandonAudioFocusRequest(focus) }
        }
    }

    suspend fun play(wave: WavPcm16.Mono, onFirstPcmPlayed: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        require(wave.pcm16.isNotEmpty())
        stop()
        val minimum = AudioTrack.getMinBufferSize(
            wave.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Το AudioTrack δεν υποστηρίζει αυτό το sample rate" }

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .build()

        // The continuous listening session already holds audio focus.
        // Do not issue a second focus request that would interrupt its mic.
        val manager = audioManager
        val wantsFocus = manager != null &&
            OmniLiveVoiceController.activeMicrophones.get() == 0
        val focus = if (wantsFocus) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
                }
                .build()
        } else null

        if (focus != null) {
            val result = manager!!.requestAudioFocus(focus)
            check(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Δεν αποκτήθηκε προτεραιότητα αναπαραγωγής ήχου"
            }
        }

        var track: AudioTrack? = null
        var counted = false
        try {
            val player = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(wave.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, 8192))
                .build()
            track = player
            check(player.state == AudioTrack.STATE_INITIALIZED) {
                "Η αναπαραγωγή ήχου δεν αρχικοποιήθηκε"
            }
            synchronized(lock) { active = player }
            player.play()
            privateOutputRouted.set(privateDevice(player.routedDevice))
            activePlayers.incrementAndGet()
            counted = true
            var offset = 0
            var firstPcmReported = false
            while (offset < wave.pcm16.size && currentCoroutineContext().isActive &&
                synchronized(lock) { active === player }) {
                privateOutputRouted.set(privateDevice(player.routedDevice))
                val count = minOf(8192, wave.pcm16.size - offset)
                val written = player.write(
                    wave.pcm16, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
                if (!firstPcmReported && player.playbackHeadPosition > 0) {
                    firstPcmReported = true
                    onFirstPcmPlayed?.invoke()
                }
            }
            val maxPlaybackMs = wave.samples.toLong() * 1000 / wave.sampleRate + 5000L
            withTimeoutOrNull(maxPlaybackMs) {
                while (currentCoroutineContext().isActive &&
                    synchronized(lock) { active === player } &&
                    runCatching { player.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false) &&
                    runCatching { player.playbackHeadPosition.toLong() < wave.samples.toLong() }.getOrDefault(false)) {
                    if (!firstPcmReported && player.playbackHeadPosition > 0) {
                        firstPcmReported = true
                        onFirstPcmPlayed?.invoke()
                    }
                    delay(30)
                }
            }
            if (!firstPcmReported && player.playbackHeadPosition > 0 &&
                currentCoroutineContext().isActive && synchronized(lock) { active === player }) {
                onFirstPcmPlayed?.invoke()
            }
        } finally {
            if (counted && activePlayers.decrementAndGet() == 0) {
                privateOutputRouted.set(false)
            }
            synchronized(lock) { if (active === track) active = null }
            track?.let {
                runCatching { it.pause() }
                runCatching { it.flush() }
                runCatching { it.release() }
            }
            if (focus != null) runCatching { manager!!.abandonAudioFocusRequest(focus) }
        }
    }
}
