package com.jegly.offlineLLM.smollm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GGUFReader : AutoCloseable {
    companion object {
        init { System.loadLibrary("ggufreader") }
    }

    private var nativeHandle: Long = 0L

    suspend fun load(modelPath: String) = withContext(Dispatchers.IO) {
        close()
        nativeHandle = getGGUFContextNativeHandle(modelPath)
        check(nativeHandle != 0L) { "Could not read GGUF metadata" }
    }

    fun getContextSize(): Long? {
        check(nativeHandle != 0L) { "GGUF reader has not been loaded" }
        return getContextSize(nativeHandle).takeIf { it > 0L }
    }

    fun getChatTemplate(): String? {
        check(nativeHandle != 0L) { "GGUF reader has not been loaded" }
        return getChatTemplate(nativeHandle).ifEmpty { null }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            releaseContext(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun getGGUFContextNativeHandle(modelPath: String): Long
    private external fun getContextSize(nativeHandle: Long): Long
    private external fun getChatTemplate(nativeHandle: Long): String
    private external fun releaseContext(nativeHandle: Long)
}
