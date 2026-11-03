package com.google.ai.edge.gallery.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Plays one synthesized voice answer at a time and can stop it immediately. */
class OmniVoicePlayback {
    private val lock = Any()
    private var active: AudioTrack? = null

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
        require(minimum > 0) { "AudioTrack does not support this rate" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wave.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, 8192))
            .build()
        synchronized(lock) { active = track }
        try {
            track.play()
            var offset = 0
            while (offset < wave.pcm16.size) {
                if (!currentCoroutineContext().isActive) break
                val count = minOf(8192, wave.pcm16.size - offset)
                val written = track.write(
                    wave.pcm16, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            val maxPlaybackMs = (wave.samples.toLong() * 1000 / wave.sampleRate) + 5000L
            withTimeoutOrNull(maxPlaybackMs) {
                while (currentCoroutineContext().isActive &&
                    synchronized(lock) { active === track } &&
                    runCatching { track.playState == AudioTrack.PLAYSTATE_PLAYING }.getOrDefault(false) &&
                    runCatching { track.playbackHeadPosition.toLong() < wave.samples.toLong() }.getOrDefault(false)) {
                    delay(30)
                }
            }
        } finally {
            synchronized(lock) { if (active === track) active = null }
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
    }
}
