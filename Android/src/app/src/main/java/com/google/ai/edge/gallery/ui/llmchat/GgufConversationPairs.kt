package com.google.ai.edge.gallery.ui.llmchat

/**
 * Durable Room history can contain legacy orphan rows. Only complete
 * user/assistant pairs are valid for replay into a hot native conversation.
 */
internal object GgufConversationPairs {
    fun completedWindow(
        history: List<Pair<String, String>>,
        maxMessages: Int = 200,
    ): List<Pair<String, String>> {
        require(maxMessages >= 2 && maxMessages % 2 == 0)
        val pairs = ArrayList<Pair<String, String>>(history.size)
        var pendingUser: String? = null
        for ((role, text) in history) {
            when (role) {
                "user" -> pendingUser = text
                "assistant" -> {
                    val user = pendingUser
                    if (user != null && text.isNotBlank()) {
                        pairs.add("user" to user)
                        pairs.add("assistant" to text)
                    }
                    pendingUser = null
                }
            }
        }
        return pairs.takeLast(maxMessages)
    }
}
