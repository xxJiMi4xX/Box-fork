package com.google.ai.edge.gallery.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
