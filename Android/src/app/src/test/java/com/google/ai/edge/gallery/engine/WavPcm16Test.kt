package com.google.ai.edge.gallery.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WavPcm16Test {
    private fun pcmWave(rate: Int, channels: Int, samples: ShortArray): ByteArray {
        require(channels in 1..2)
        require(samples.size % channels == 0)
        val n = samples.size * 2
        val data = ByteBuffer.allocate(44 + n).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray(Charsets.US_ASCII))
        data.putInt(36 + n)
        data.put("WAVE".toByteArray(Charsets.US_ASCII))
        data.put("fmt ".toByteArray(Charsets.US_ASCII))
        data.putInt(16)
        data.putShort(1)
        data.putShort(channels.toShort())
        data.putInt(rate)
        data.putInt(rate * channels * 2)
        data.putShort((channels * 2).toShort())
        data.putShort(16)
        data.put("data".toByteArray(Charsets.US_ASCII))
        data.putInt(n)
        samples.forEach(data::putShort)
        return data.array()
    }

    @Test
    fun monoWavPreservesPcmBytesAndSampleRate() {
        val data = pcmWave(16_000, 1, shortArrayOf(0, 1024, -32768, 32767))
        val decoded = WavPcm16.read(data)
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(4, decoded.samples)
        assertEquals(data.sliceArray(44 until data.size).toList(), decoded.pcm16.toList())
    }

    @Test
    fun stereoWavDownmixesInSignedSpace() {
        val data = pcmWave(16_000, 2, shortArrayOf(3000, -1000, -32768, 32766))
        val decoded = WavPcm16.read(data)
        assertEquals(2, decoded.samples)
        val buffer = ByteBuffer.wrap(decoded.pcm16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000, buffer.short.toInt())
        assertEquals(-1, buffer.short.toInt())
    }

    @Test
    fun resamplingProducesExpectedLengthAndMidpoint() {
        val data = pcmWave(16_000, 1, shortArrayOf(0, 16_384, 0, 0))
        val samples = WavPcm16.toFloatMono(data, 32_000)
        assertEquals(8, samples.size)
        assertEquals(0f, samples[0], 1e-5f)
        assertEquals(0.25f, samples[1], 1e-5f)
        assertEquals(0.5f, samples[2], 1e-5f)
    }

    @Test
    fun rejectNonPcm16OrInvalidChunk() {
        val wav = pcmWave(16_000, 1, shortArrayOf(0, 0))
        val badFormat = wav.clone()
        badFormat[20] = 3 // IEEE float is not PCM16
        try {
            WavPcm16.read(badFormat)
            fail("Should reject non-PCM16 WAV")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("PCM16") == true)
        }

        val badLength = wav.clone()
        badLength[40] = 0x7f
        badLength[41] = 0x7f
        badLength[42] = 0x7f
        badLength[43] = 0x7f
        try {
            WavPcm16.read(badLength)
            fail("Should reject an invalid RIFF data length")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("chunk") == true)
        }
    }
}
