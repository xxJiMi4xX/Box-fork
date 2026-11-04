package com.google.ai.edge.gallery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceAudioBufferPolicyTest {
    @Test fun monoPcmWindowsTrackActualSampleRate() {
        assertEquals(1_280, VoiceAudioBufferPolicy.windowBytes(16_000))
        assertEquals(1_920, VoiceAudioBufferPolicy.windowBytes(24_000))
        assertEquals(3_840, VoiceAudioBufferPolicy.windowBytes(48_000))
    }

    @Test fun boundedQueueHasExplicitMillisecondCapacity() {
        assertEquals(40, VoiceAudioBufferPolicy.WINDOW_MS)
        assertEquals(32, VoiceAudioBufferPolicy.QUEUED_WINDOWS)
        assertEquals(1_280, VoiceAudioBufferPolicy.MAX_QUEUED_MS)
    }

    @Test fun adaptIncreasesOnlyWhenUnderrunsObserved() {
        assertEquals(460, VoiceAudioBufferPolicy.adaptPrebuffer(360, 2))
        assertEquals(330, VoiceAudioBufferPolicy.adaptPrebuffer(360, 0))
        assertEquals(850, VoiceAudioBufferPolicy.adaptPrebuffer(830, 1))
        assertEquals(250, VoiceAudioBufferPolicy.adaptPrebuffer(260, 0))
    }

    @Test fun invalidSampleRateIsRejected() {
        try {
            VoiceAudioBufferPolicy.windowBytes(7_999)
            fail("Expected sample rate rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("sample rate") == true)
        }
    }
}
