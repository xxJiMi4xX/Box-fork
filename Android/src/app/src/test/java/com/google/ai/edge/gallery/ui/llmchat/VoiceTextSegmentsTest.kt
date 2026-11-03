package com.google.ai.edge.gallery.ui.llmchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextSegmentsTest {
    @Test
    fun greekSentencesAreBoundedAndAllWordsRetained() {
        val sentence = "Αυτή είναι μία μεγάλη ελληνική πρόταση που πρέπει να ακουστεί σωστά. "
        val source = sentence.repeat(15).trim()
        val parts = VoiceTextSegments.split(source, maxChars = 100)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 100 && it.isNotBlank() })
        assertEquals(source.replace(Regex("\\s+"), " "),
            parts.joinToString(" ").replace(Regex("\\s+"), " "))
    }

    @Test
    fun neverSplitsUnicodeSurrogatePairs() {
        val source = ("γειά σου 🧠🌍 " ).repeat(40).trim()
        val parts = VoiceTextSegments.split(source, maxChars = 85)
        assertTrue(parts.size > 1)
        for (part in parts) {
            assertFalse(part.first().isLowSurrogate())
            assertFalse(part.last().isHighSurrogate())
            assertTrue(part.length <= 85)
        }
    }

    @Test
    fun shortTextAndBlankTextBehavePredictably() {
        assertEquals(listOf("Καλημέρα."), VoiceTextSegments.split("  Καλημέρα.  "))
        assertTrue(VoiceTextSegments.split("  \n  ").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChunkLimit() {
        VoiceTextSegments.split("κείμενο", maxChars = 1)
    }
}
