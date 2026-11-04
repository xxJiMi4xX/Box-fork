package com.google.ai.edge.gallery.engine

/**
 * PCM16 mono buffering is bounded by duration, not model-specific chunk size.
 * Keep these calculations independent of Android for JVM regression tests.
 */
object VoiceAudioBufferPolicy {
    const val WINDOW_MS = 40
    const val QUEUED_WINDOWS = 32
    const val MAX_QUEUED_MS = WINDOW_MS * QUEUED_WINDOWS
    const val INITIAL_PREBUFFER_MS = 360
    const val MIN_PREBUFFER_MS = 250
    const val MAX_PREBUFFER_MS = 850

    fun windowBytes(sampleRate: Int): Int {
        require(sampleRate in 8_000..192_000) { "Invalid PCM sample rate" }
        val frames = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
        return frames * 2
    }

    fun adaptPrebuffer(currentMs: Int, underruns: Int): Int {
        require(currentMs >= MIN_PREBUFFER_MS && currentMs <= MAX_PREBUFFER_MS)
        require(underruns >= 0)
        return if (underruns > 0) (currentMs + 100).coerceAtMost(MAX_PREBUFFER_MS)
        else (currentMs - 30).coerceAtLeast(MIN_PREBUFFER_MS)
    }
}
