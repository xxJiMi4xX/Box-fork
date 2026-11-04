package com.google.ai.edge.gallery.engine

import kotlin.math.max

/**
 * Streaming mono PCM16LE -> mono float, with global fractional phase and
 * one-sample lookahead. The output is independent of Android's 20-ms framing.
 *
 * Feed immutable frame copies in chronological order. Not thread safe.
 */
internal class LivePcm16Resampler(
    private val sourceRate: Int,
    private val targetRate: Int,
) {
    init {
        require(sourceRate in 8_000..192_000)
        require(targetRate in 8_000..192_000)
    }

    private var inputCount = 0L
    private var outputCount = 0L
    private var lastSample = 0f
    private var closed = false

    fun append(bytes: ByteArray): FloatArray {
        check(!closed) { "PCM input already committed" }
        require(bytes.isNotEmpty() && bytes.size % 2 == 0 &&
            bytes.size <= sourceRate * 4) { "Invalid PCM16 microphone frame" }
        val sampleCount = bytes.size / 2
        val samples = FloatArray(sampleCount) { i ->
            val word = (bytes[2 * i].toInt() and 0xff) or
                (bytes[2 * i + 1].toInt() shl 8)
            word.toShort().toFloat() / 32768.0f
        }
        val start = inputCount
        val end = start + sampleCount
        val maxOutput = max(2, ((sampleCount.toLong() + 2) *
            targetRate / sourceRate + 4).toInt())
        val result = FloatArray(maxOutput)
        var written = 0
        fun at(position: Long): Float =
            if (position == start - 1) lastSample
            else samples[(position - start).toInt()]
        while (true) {
            val numerator = outputCount * sourceRate.toLong()
            val left = numerator / targetRate
            val right = left + 1
            if (right >= end) break
            // The first sample of the next buffer may interpolate with the
            // last sample of the previous one.
            check(left >= start - 1 && written < result.size)
            val fraction = (numerator % targetRate).toFloat() / targetRate
            result[written++] = at(left) * (1f - fraction) + at(right) * fraction
            outputCount++
        }
        lastSample = samples.last()
        inputCount = end
        return result.copyOf(written)
    }

    fun finish(): FloatArray {
        check(!closed)
        closed = true
        if (inputCount == 0L) return FloatArray(0)
        // Same nominal duration as the complete-WAV converter.
        val finalCount = max(1L, inputCount * targetRate / sourceRate)
        val remaining = finalCount - outputCount
        check(remaining in 0..(targetRate.toLong() / sourceRate + 2L)) {
            "Unexpected PCM resampler tail"
        }
        outputCount = finalCount
        // The final fractional position is padded with the last input sample.
        return FloatArray(remaining.toInt()) { lastSample }
    }
}
