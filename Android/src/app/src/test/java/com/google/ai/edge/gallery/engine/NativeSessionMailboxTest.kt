package com.google.ai.edge.gallery.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionMailboxTest {
    @Test fun queuedLoadAndGenerateCannotOverlap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val mailbox = NativeSessionMailbox(scope)
            val load = async {
                mailbox.submit(NativeVoiceCommand.LOAD) {
                    order += "load-begin"
                    entered.complete(Unit)
                    release.await()
                    order += "load-end"
                }
            }
            entered.await()
            val generate = async {
                mailbox.submit(NativeVoiceCommand.GENERATE) {
                    order += "generate"
                }
            }
            assertFalse(generate.isCompleted)
            release.complete(Unit)
            load.await()
            generate.await()
            assertEquals(listOf("load-begin", "load-end", "generate"), order)
        } finally {
            scope.cancel()
        }
    }

    @Test fun activeCancellationReleasesTheNextNativeCommand() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val finallyExited = CompletableDeferred<Unit>()
            val mailbox = NativeSessionMailbox(scope)
            val first = async {
                mailbox.submit(NativeVoiceCommand.APPEND_AUDIO) {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finallyExited.complete(Unit)
                    }
                }
            }
            withTimeout(3_000) { entered.await() }
            first.cancel()
            withTimeout(3_000) { first.join() }
            withTimeout(3_000) { finallyExited.await() }
            val answer = withTimeout(3_000) {
                mailbox.submit(NativeVoiceCommand.GENERATE) { 42 }
            }
            assertEquals(42, answer)
        } finally {
            scope.cancel()
        }
    }

    @Test fun oneFailedNativeCommandDoesNotKillTheOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val mailbox = NativeSessionMailbox(scope)
            try {
                mailbox.submit(NativeVoiceCommand.ASR) {
                    throw IllegalStateException("Expected simulated ASR failure")
                }
            } catch (_: IllegalStateException) {
                // A failed turn must not poison all subsequent commands.
            }
            assertEquals(7, mailbox.submit(NativeVoiceCommand.INSPECT) { 7 })
        } finally {
            scope.cancel()
        }
    }

    @Test fun cancelledQueuedOperationIsNotExecuted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executed = false
            val mailbox = NativeSessionMailbox(scope)
            val load = async {
                mailbox.submit(NativeVoiceCommand.LOAD) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val canceled = async {
                mailbox.submit(NativeVoiceCommand.APPEND_AUDIO) {
                    executed = true
                }
            }
            canceled.cancel()
            release.complete(Unit)
            load.await()
            try { canceled.await() } catch (_: kotlinx.coroutines.CancellationException) {}
            assertTrue(canceled.isCancelled)
            assertFalse(executed)
        } finally {
            scope.cancel()
        }
    }
}
