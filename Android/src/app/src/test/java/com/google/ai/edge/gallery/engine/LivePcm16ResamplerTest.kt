package com.google.ai.edge.gallery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivePcm16ResamplerTest {
    private fun pcm(n: Int): ByteArray = ByteArray(n * 2) { index ->
        val sample = ((index / 2) * 113 - 12000).toShort().toInt()
        if (index % 2 == 0) sample.toByte() else (sample shr 8).toByte()
    }

    @Test fun framesProduceSameSamplesAsOneBuffer() {
        for (targetRate in listOf(8_000, 16_000, 24_000, 48_000)) {
            val input = pcm(960)
            val one = LivePcm16Resampler(16_000, targetRate)
            val all = one.append(input).toList() + one.finish().toList()

            val chunks = LivePcm16Resampler(16_000, targetRate)
            val split = (0 until 3).flatMap { i ->
                chunks.append(input.copyOfRange(i * 640, (i + 1) * 640)).toList()
            } + chunks.finish().toList()
            assertEquals(all.size, split.size)
            all.zip(split).forEach { (expected, actual) ->
                assertEquals(expected, actual, 0.00001f)
            }
        }
    }

    @Test fun irregularSampleBoundariesDoNotChangeOutput() {
        val input = pcm(960)
        for (sourceRate in listOf(8_000, 16_000, 48_000)) {
            for (targetRate in listOf(8_000, 16_000, 24_000, 48_000)) {
                val whole = LivePcm16Resampler(sourceRate, targetRate)
                val reference = whole.append(input).toList() + whole.finish().toList()
                val cut = LivePcm16Resampler(sourceRate, targetRate)
                val parts = listOf(3, 5, 13, 31, 107, 211, 590)
                var previous = 0
                val actual = mutableListOf<Float>()
                for (next in parts) {
                    if (next <= previous) continue
                    actual.addAll(cut.append(
                        input.copyOfRange(previous * 2, next * 2)).toList())
                    previous = next
                }
                actual.addAll(cut.append(input.copyOfRange(previous * 2, input.size)).toList())
                actual.addAll(cut.finish().toList())
                assertEquals(reference.size, actual.size)
                reference.zip(actual).forEach { (a, b) ->
                    assertEquals(a, b, 0.00001f)
                }
            }
        }
    }

    @Test fun sourceDurationAndAmplitudeArePreserved() {
        val r = LivePcm16Resampler(16_000, 24_000)
        val steady = ByteArray(640) { idx ->
            if (idx % 2 == 0) 0x00.toByte() else 0x20.toByte()
        }
        val result = r.append(steady).toList() + r.finish().toList()
        assertEquals(480, result.size)
        result.forEach { assertEquals(0.25f, it, 0.00001f) }
    }

    @Test fun malformedFramesDoNotEnterNativeCode() {
        val r = LivePcm16Resampler(16_000, 16_000)
        assertThrows(IllegalArgumentException::class.java) { r.append(byteArrayOf(1)) }
        r.append(byteArrayOf(0, 0, 1, 0))
        r.finish()
        assertThrows(IllegalStateException::class.java) { r.append(byteArrayOf(0, 0)) }
    }
}
