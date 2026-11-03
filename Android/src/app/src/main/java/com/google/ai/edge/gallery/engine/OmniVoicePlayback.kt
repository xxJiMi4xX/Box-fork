package com.google.ai.edge.gallery.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Interruptible synthesized voice playback with Android audio-focus handling. */
class OmniVoicePlayback {
    companion object {
        val activePlayers = AtomicInteger(0)
        val privateOutputRouted = AtomicBoolean(false)

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

    suspend fun play(wave: WavPcm16.Mono) = withContext(Dispatchers.IO) {
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
            while (offset < wave.pcm16.size && currentCoroutineContext().isActive &&
                synchronized(lock) { active === player }) {
                privateOutputRouted.set(privateDevice(player.routedDevice))
                val count = minOf(8192, wave.pcm16.size - offset)
                val written = player.write(
                    wave.pcm16, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            val maxPlaybackMs = wave.samples.toLong() * 1000 / wave.sampleRate + 5000L
            withTimeoutOrNull(maxPlaybackMs) {
                while (currentCoroutineContext().isActive &&
                    synchronized(lock) { active === player } &&
                    runCatching { player.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false) &&
                    runCatching { player.playbackHeadPosition.toLong() < wave.samples.toLong() }.getOrDefault(false)) {
                    delay(30)
                }
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
