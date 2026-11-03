package com.google.ai.edge.gallery.ui.llmchat

/**
 * Bounded text units for sequential GGUF speech synthesis. Segments prefer
 * sentence punctuation, then whitespace, and never split a UTF-16 surrogate
 * pair. The units can be spoken independently without exceeding TTS text caps.
 */
internal object VoiceTextSegments {
    fun split(text: String, maxChars: Int = 200): List<String> {
        require(maxChars in 80..512)
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) {
                result.add(remaining)
                break
            }
            val candidate = remaining.substring(0, maxChars)
            val punctuation = candidate.indexOfLast {
                it == '.' || it == '!' || it == '?' || it == ';' || it == '\n'
            }
            val space = candidate.indexOfLast { it.isWhitespace() }
            var boundary = when {
                punctuation >= maxChars / 3 -> punctuation + 1
                space >= maxChars / 3 -> space + 1
                else -> maxChars
            }
            // An arbitrary maxChars boundary may bisect an emoji or another
            // non-BMP code point represented as a UTF-16 surrogate pair.
            if (boundary < remaining.length && boundary > 0 &&
                Character.isHighSurrogate(remaining[boundary - 1]) &&
                Character.isLowSurrogate(remaining[boundary])) {
                boundary--
            }
            check(boundary > 0)
            val segment = remaining.substring(0, boundary).trim()
            if (segment.isNotEmpty()) result.add(segment)
            remaining = remaining.substring(boundary).trimStart()
        }
        return result
    }
}
