package com.google.ai.edge.gallery.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Strict decoder for ordinary RIFF PCM-16 WAV. Input may be mono or stereo;
 * the Android voice UI and LFM3 audio encoder both consume mono samples.
 */
object WavPcm16 {
    data class Mono(val sampleRate: Int, val pcm16: ByteArray) {
        val samples: Int get() = pcm16.size / 2
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 255) or ((data[offset + 1].toInt() and 255) shl 8)

    private fun u32(data: ByteArray, offset: Int): Long =
        (u16(data, offset).toLong() or (u16(data, offset + 2).toLong() shl 16))

    private fun signed16(data: ByteArray, offset: Int): Int =
        (u16(data, offset).toShort()).toInt()

    private fun ascii(data: ByteArray, start: Int, expected: String): Boolean =
        expected.indices.all { data[start + it].toInt() == expected[it].code }

    fun read(wav: ByteArray, maxSeconds: Int = 90): Mono {
        require(wav.size >= 44 && ascii(wav, 0, "RIFF") && ascii(wav, 8, "WAVE")) {
            "Expected a RIFF/WAVE audio recording"
        }
        var channels = 0
        var sampleRate = 0
        var blockAlign = 0
        var bits = 0
        var format = 0
        var dataOffset = -1
        var dataLength = 0
        var offset = 12
        while (offset <= wav.size - 8) {
            val size = u32(wav, offset + 4)
            require(size <= (wav.size - offset - 8).toLong()) { "Invalid WAV chunk size" }
            val body = offset + 8
            when {
                ascii(wav, offset, "fmt ") -> {
                    require(size >= 16) { "Incomplete WAV format chunk" }
                    format = u16(wav, body)
                    channels = u16(wav, body + 2)
                    sampleRate = u32(wav, body + 4).toInt()
                    blockAlign = u16(wav, body + 12)
                    bits = u16(wav, body + 14)
                }
                ascii(wav, offset, "data") -> {
                    dataOffset = body
                    dataLength = size.toInt()
                }
            }
            val next = body.toLong() + size + (size and 1)
            if (next > wav.size) break
            offset = next.toInt()
        }
        require(format == 1 && bits == 16 && channels in 1..2 &&
                sampleRate in 8000..192000 && blockAlign == channels * 2 &&
                dataOffset >= 0 && dataLength > 0 &&
                dataLength % blockAlign == 0) {
            "Only mono/stereo PCM16 WAV with a valid sample rate is supported"
        }
        val frames = dataLength / blockAlign
        require(frames.toLong() <= sampleRate.toLong() * maxSeconds) {
            "Voice recording is longer than ${maxSeconds}s"
        }
        if (channels == 1) {
            return Mono(sampleRate, wav.copyOfRange(dataOffset, dataOffset + dataLength))
        }
        val out = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val p = dataOffset + i * blockAlign
            val avg = (signed16(wav, p) + signed16(wav, p + 2)) / 2
            out[i * 2] = (avg and 255).toByte()
            out[i * 2 + 1] = ((avg shr 8) and 255).toByte()
        }
        return Mono(sampleRate, out)
    }

    fun toFloatMono(wav: ByteArray, targetRate: Int): FloatArray {
        require(targetRate in 8000..192000) { "Unsupported audio encoder sample rate" }
        val input = read(wav)
        val n = input.samples
        val outputSize = max(1L, n.toLong() * targetRate / input.sampleRate).toInt()
        require(outputSize.toLong() <= targetRate.toLong() * 90)
        val output = FloatArray(outputSize)
        for (i in 0 until outputSize) {
            val sourcePos = i.toDouble() * input.sampleRate / targetRate
            val left = min(n - 1, sourcePos.toInt())
            val right = min(n - 1, left + 1)
            val fraction = (sourcePos - left).toFloat()
            val a = signed16(input.pcm16, left * 2) / 32768.0f
            val b = signed16(input.pcm16, right * 2) / 32768.0f
            output[i] = a + (b - a) * fraction
        }
        return output
    }
}
