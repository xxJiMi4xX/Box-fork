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
    private const val REALTIME_AUDIO_PREFIX = "realtime_audio:"
    private const val BATCH_THREADS_PREFIX = "batch_threads:"
    private const val GPU_LAYERS_PREFIX = "gpu_layers:"
    private const val GPU_PROJECTOR_PREFIX = "gpu_projector:"
    private const val PERFORMANCE_CORES_PREFIX = "performance_cores:"

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
        require(!performanceCoresOnly(context, modelPath) || threads <= 4) {
            "Four performance cores mode supports at most four decode threads"
        }
        check(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(THREADS_PREFIX + modelPath, threads)
                .commit()
        ) { "Failed to save GGUF CPU thread settings" }
    }
    /** Separate prompt processing and token generation worker counts. */
    fun cpuBatchThreads(context: Context, modelPath: String): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(BATCH_THREADS_PREFIX + modelPath, cpuThreads(context, modelPath))
            .coerceIn(1, maxCpuThreads())

    fun setCpuBatchThreads(context: Context, modelPath: String, threads: Int) {
        require(threads in 1..maxCpuThreads())
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(BATCH_THREADS_PREFIX + modelPath, threads).commit())
    }

    /** 0 = CPU, positive = hybrid layer count, -1 = request all layers on Vulkan. */
    fun gpuLayers(context: Context, modelPath: String): Int =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(GPU_LAYERS_PREFIX + modelPath, 0)
            .let { if (it == -1 || it in 0..128) it else 0 }

    fun setGpuLayers(context: Context, modelPath: String, layers: Int) {
        require(layers == -1 || layers in 0..128)
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(GPU_LAYERS_PREFIX + modelPath, layers).commit())
    }

    fun gpuProjector(context: Context, modelPath: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(GPU_PROJECTOR_PREFIX + modelPath, false)

    fun setGpuProjector(context: Context, modelPath: String, enabled: Boolean) {
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(GPU_PROJECTOR_PREFIX + modelPath, enabled).commit())
    }

    fun performanceCoresOnly(context: Context, modelPath: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PERFORMANCE_CORES_PREFIX + modelPath, false)

    fun setPerformanceCoresOnly(context: Context, modelPath: String, enabled: Boolean) {
        require(!enabled || cpuThreads(context, modelPath) <= 4) {
            "Choose at most four decode threads to use performance cores"
        }
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PERFORMANCE_CORES_PREFIX + modelPath, enabled).commit())
    }

    /** Opt-in until real-device audio correctness is confirmed. */
    fun mixedVoiceEnabled(context: Context, modelPath: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MIXED_VOICE_PREFIX + modelPath, false)

    /** Opt-in until LFM3-Audio sample-rate, memory and cancellation gates pass. */
    fun realtimeAudioEnabled(context: Context, modelPath: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(REALTIME_AUDIO_PREFIX + modelPath, false)

    fun setRealtimeAudioEnabled(context: Context, modelPath: String, enabled: Boolean) {
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(REALTIME_AUDIO_PREFIX + modelPath, enabled).commit()) {
            "Failed to save realtime voice setting"
        }
    }

    fun setMixedVoiceEnabled(context: Context, modelPath: String, enabled: Boolean) {
        check(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MIXED_VOICE_PREFIX + modelPath, enabled).commit()) {
            "Failed to save mixed-voice setting"
        }
    }
}

