package com.google.ai.edge.gallery.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommittedGgufTurnTest {
    private lateinit var database: BoxChatDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, BoxChatDatabase::class.java).build()
        repository = ChatRepository(
            database.conversationDao(),
            database.messageDao(),
            database,
        )
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun completedDialoguePersistsAsOrderedAtomicPair() = runBlocking {
        val conversation = repository.createConversation(modelName = "Greek GGUF", taskType = "llm_chat")
        repository.saveCommittedTurn(
            conversationId = conversation.id,
            modelName = "Greek GGUF",
            userText = "Καλημέρα",
            assistantText = "Καλημέρα, πώς μπορώ να βοηθήσω;",
            latencyMs = 125L,
        )
        val rows = repository.getMessagesSync(conversation.id)
        assertEquals(2, rows.size)
        assertEquals(listOf("user", "assistant"), rows.map { it.role })
        assertEquals("Καλημέρα", rows[0].content)
        assertEquals(125L, rows[1].latencyMs)
        assertEquals(2, repository.getConversationById(conversation.id)?.messageCount)
    }

    @Test
    fun wrongModelCannotModifyAnExistingConversation() = runBlocking {
        val conversation = repository.createConversation(modelName = "model-A", taskType = "llm_chat")
        try {
            repository.saveCommittedTurn(
                conversationId = conversation.id,
                modelName = "model-B",
                userText = "Πρώτη ερώτηση",
                assistantText = "Απάντηση",
                latencyMs = 1L,
            )
            org.junit.Assert.fail("A cross-model transaction must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("another model") == true)
        }
        assertTrue(repository.getMessagesSync(conversation.id).isEmpty())
        assertEquals(0, repository.getConversationById(conversation.id)?.messageCount)
    }

    @Test
    fun incompleteTurnNeverCreatesAnUnpairedUserMessage() = runBlocking {
        val conversation = repository.createConversation(modelName = "model-A", taskType = "llm_chat")
        try {
            repository.saveCommittedTurn(
                conversationId = conversation.id,
                modelName = "model-A",
                userText = "Ερώτηση χωρίς απάντηση",
                assistantText = "",
                latencyMs = 0L,
            )
            org.junit.Assert.fail("Blank assistant output must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: no write should have been started.
        }
        assertEquals(0, repository.getMessagesSync(conversation.id).size)
    }

    @Test
    fun newEmptyConversationWinsLatestAutorestore() = runBlocking {
        val old = repository.createConversation(modelName = "model-A", taskType = "llm_chat")
        repository.updateConversation(old.copy(updatedAt = 1000L, createdAt = 1000L))
        val replacement = repository.createConversation(modelName = "model-A", taskType = "llm_chat")
        assertEquals(replacement.id, repository.getLatestConversationForModel("model-A")?.id)
        assertTrue(repository.getMessagesSync(replacement.id).isEmpty())
    }
}
