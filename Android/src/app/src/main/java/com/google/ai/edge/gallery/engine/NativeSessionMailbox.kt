package com.google.ai.edge.gallery.engine

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit per-operation protocol for the process-wide GGUF owner. */
internal enum class NativeVoiceCommand {
    LOAD, UNLOAD, CLEAR, APPEND_TEXT, APPEND_AUDIO,
    ASR, TTS, GENERATE, INSPECT, CANCEL,
}

/**
 * Exactly one FIFO owner processes GGUF requests; the operation executes in
 * the *requesting coroutine's Job*, preserving structured cancellation.
 *
 * CANCEL is also signaled immediately through native requestStop() and
 * closing the audio channel; it never waits for this mailbox to wake a
 * blocked llama_decode. The queued CANCEL performs final cleanup afterward.
 */
internal class NativeSessionMailbox(
    scope: CoroutineScope,
    private val onProgress: (NativeVoiceCommand, Boolean) -> Unit = { _, _ -> },
) {
    private data class Request<T>(
        val type: NativeVoiceCommand,
        val context: CoroutineContext,
        val completion: CompletableDeferred<T>,
        val execute: suspend () -> T,
    )

    private val operations = Channel<Request<*>>(capacity = 64)
    @Suppress("unused")
    private val owner = scope.launch {
        for (queued in operations) {
            @Suppress("UNCHECKED_CAST")
            val cmd = queued as Request<Any?>
            if (!cmd.completion.isActive) continue
            try {
                runCatching { onProgress(cmd.type, true) }
                val output = withContext(cmd.context) { cmd.execute() }
                cmd.completion.complete(output)
            } catch (error: Throwable) {
                cmd.completion.completeExceptionally(error)
            } finally {
                runCatching { onProgress(cmd.type, false) }
            }
        }
    }

    suspend fun <T> submit(
        type: NativeVoiceCommand,
        execute: suspend () -> T,
    ): T {
        val context = coroutineContext
        // IMPORTANT: an exceptionally completed child Deferred with a
        // parent Job would cancel the calling coroutine *even when the
        // failure is caught*, poisoning later turns and unit tests.
        // Own the completion independently, but still propagate caller
        // cancellation through await() and the captured execution context.
        val reply = CompletableDeferred<T>()
        try {
            operations.send(Request(type, context, reply, execute))
            return reply.await()
        } finally {
            // On cancellation, queued operations must be skipped; a running
            // one is interrupted by its captured coroutine context.
            reply.cancel()
        }
    }
}
