package com.google.ai.edge.gallery.runtime

import android.content.Context

/**
 * CPU inference configuration for imported GGUF models.
 *
 * Persisted by model path and read when a native llama.cpp model is loaded.
 * A thread count configures llama.cpp workers, not Android CPU affinity.
 */
object VoiceGgufRuntimeSettings {
    private const val PREFS_NAME = "box_voice_gguf_runtime"
    private const val THREADS_PREFIX = "cpu_threads:"
    private const val MIXED_VOICE_PREFIX = "mixed_voice:"

    fun maxCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 32)

    fun defaultCpuThreads(): Int = maxCpuThreads().coerceAtMost(8)

    fun cpuThreads(context: Context, modelPath: String): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(THREADS_PREFIX + modelPath, defaultCpuThreads())
            .coerceIn(1, maxCpuThreads())

    fun setCpuThreads(context: Context, modelPath: String, threads: Int) {
        require(threads in 1..maxCpuThreads()) {
            "CPU thread count must be between 1 and ${maxCpuThreads()}"
        }
        check(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(THREADS_PREFIX + modelPath, threads)
                .commit()
        ) { "Failed to save GGUF CPU thread settings" }
    }
    /** Opt-in until real-device audio correctness is confirmed. */
    fun mixedVoiceEnabled(context: Context, modelPath: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MIXED_VOICE_PREFIX + modelPath, false)

    fun setMixedVoiceEnabled(context: Context, modelPath: String, enabled: Boolean) {
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MIXED_VOICE_PREFIX + modelPath, enabled).commit()) {
            "Failed to save mixed-voice setting"
        }
    }
}

