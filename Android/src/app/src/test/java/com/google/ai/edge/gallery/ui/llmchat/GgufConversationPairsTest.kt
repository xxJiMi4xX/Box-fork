package com.google.ai.edge.gallery.ui.llmchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufConversationPairsTest {
    @Test fun discardsUnpairedTurnsAndUntrustedRoles() {
        val records = listOf(
            "assistant" to "orphan",
            "user" to "orphaned user",
            "user" to "actual user",
            "system" to "must not replace user",
            "assistant" to "committed answer",
            "user" to "incomplete last turn",
        )
        assertEquals(
            listOf("user" to "actual user", "assistant" to "committed answer"),
            GgufConversationPairs.completedWindow(records),
        )
    }

    @Test fun tenTurnsRestoreExactlyTenPairedTurns() {
        val rows = (1..10).flatMap { idx ->
            listOf("user" to "ερώτηση $idx", "assistant" to "απάντηση $idx")
        }
        assertEquals(rows, GgufConversationPairs.completedWindow(rows))
    }

    @Test fun completePairsArePrunedAtomically() {
        val rows = (1..12).flatMap { idx ->
            listOf("user" to "U$idx", "assistant" to "A$idx")
        }
        val result = GgufConversationPairs.completedWindow(rows, maxMessages = 10)
        assertEquals(10, result.size)
        assertEquals("user" to "U8", result.first())
        assertEquals("assistant" to "A12", result.last())
    }

    @Test fun partialAndEmptyHistoryAreSafe() {
        assertTrue(GgufConversationPairs.completedWindow(emptyList()).isEmpty())
        assertTrue(GgufConversationPairs.completedWindow(listOf("user" to "hello")).isEmpty())
    }
}
