/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.gallery.engine.OmniVoicePlayback
import com.google.ai.edge.gallery.engine.WavPcm16
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.local.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.security.SecurityUtils
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageThinking
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AGLlmChatViewModel"

@OptIn(ExperimentalApi::class)
open class LlmChatViewModelBase() : ChatViewModel() {

  // Box: Chat persistence — will be injected by Hilt in concrete subclasses
  override lateinit var chatRepository: ChatRepository
  // Model-scoped session IDs: never share one Room conversation among models.
  private val conversationIds = ConcurrentHashMap<String, String>()
  private val systemPromptsByModel = ConcurrentHashMap<String, String>()
  @Volatile private var selectedConversationModel: String = ""
  @Volatile private var selectedConversationModelObject: Model? = null
  private val conversationMutex = Mutex()
  private var lastCommitJob: Job? = null
  private val historyGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val historyErrors = ConcurrentHashMap<String, String>()
  private val voicePlayback = OmniVoicePlayback()
  private var voicePlaybackJob: Job? = null
  private var activeResponseJob: Job? = null
  private var activeResponseModel: Model? = null
  private val requestEpoch = AtomicLong(0L)

  fun configureVoiceOutput(context: Context) {
    voicePlayback.configure(context.applicationContext)
  }

  /**
   * An ASR, TTS, microphone or audio-focus failure is not evidence that
   * the GGUF model itself is corrupt. Do not unload/reload model weights.
   */
  fun reportVoiceError(model: Model, message: String) {
    Log.w(TAG, "Recoverable voice error for ${model.name}: $message")
    addMessage(model = model, message = ChatMessageError(content = message))
  }


  private val _currentSystemPrompt = MutableStateFlow("")
  val currentSystemPrompt: StateFlow<String> = _currentSystemPrompt.asStateFlow()

  fun setCurrentSystemPrompt(prompt: String) {
    _currentSystemPrompt.value = prompt
    val modelName = selectedConversationModel
    if (modelName.isNotBlank()) systemPromptsByModel[modelName] = prompt
  }

  fun updateSystemPrompt(prompt: String) {
    _currentSystemPrompt.value = prompt
    if (selectedConversationModel.isNotBlank()) {
      systemPromptsByModel[selectedConversationModel] = prompt
    }
    val model = selectedConversationModelObject ?: return
    val modelName = model.name
    val convId = conversationIds[modelName]
    val gguf = model.instance is LlamaCppEngine
    val historyGate = if (gguf) beginHistorySynchronization(model) else null
    if (gguf) {
      // A newly edited system instruction must take effect in the native
      // prompt, not merely in the UI/Room metadata.
      stopResponse(model)
    }
    viewModelScope.launch(Dispatchers.IO) {
      var errorMessage: String? = null
      try {
        lastCommitJob?.join()
        if (convId != null) {
          val conv = chatRepository.getConversationById(convId)
            ?: throw IllegalStateException("Η συνομιλία δεν υπάρχει πλέον")
          check(conv.modelName == modelName) { "Η συνομιλία ανήκει σε άλλο μοντέλο" }
          chatRepository.updateConversation(conv.copy(systemPrompt = prompt))
        }
        if (gguf) {
          val rows = if (convId != null) chatRepository.getMessagesSync(convId) else emptyList()
          restoreGgufHistory(model, prompt, rows.map { it.role to it.content }, historyGate!!)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        errorMessage = error.message ?: "Αδυναμία εφαρμογής system prompt"
        Log.e(TAG, "Failed to apply system prompt", error)
      } finally {
        if (historyGate != null) endHistorySynchronization(model, historyGate, errorMessage)
      }
    }
  }

  suspend fun getConversationById(conversationId: String) =
    chatRepository.getConversationById(conversationId)

  /**
   * Box: Set the current conversation ID for continuing an existing conversation
   */
  fun setCurrentConversationModel(model: Model) {
    selectedConversationModel = model.name
    selectedConversationModelObject = model
    _currentSystemPrompt.value = systemPromptsByModel[model.name] ?: ""
  }

  fun getCurrentConversationId(model: Model): String? = conversationIds[model.name]

  fun setCurrentConversationId(model: Model, conversationId: String) {
    conversationIds[model.name] = conversationId
    selectedConversationModel = model.name
    selectedConversationModelObject = model
  }

  fun beginNewConversation(model: Model) {
    conversationIds.remove(model.name)
    historyErrors.remove(model.name)
  }

  private suspend fun createFreshConversationAfterReset(model: Model) {
    // Let any already completed native turn finish its asynchronous Room
    // commit before creating the fresh conversation. The new row must remain
    // the most recently updated row for auto-resume after a reset.
    lastCommitJob?.join()
    conversationMutex.withLock {
      val fresh = chatRepository.createConversation(
        title = "New Chat",
        taskType = "llm_chat",
        modelName = model.name,
        systemPrompt = systemPromptsByModel[model.name] ?: "",
      )
      conversationIds[model.name] = fresh.id
    }
  }

  fun beginHistorySynchronization(model: Model): CompletableDeferred<Unit> {
    val gate = CompletableDeferred<Unit>()
    historyErrors.remove(model.name)
    historyGates.put(model.name, gate)?.complete(Unit)
    return gate
  }

  fun endHistorySynchronization(
    model: Model,
    gate: CompletableDeferred<Unit>,
    error: String? = null,
  ) {
    // A canceled/obsolete restore is not allowed to complete the gate for
    // a newer reload or a newer selected conversation.
    if (historyGates[model.name] === gate) {
      if (!error.isNullOrBlank()) {
        historyErrors[model.name] = error
        reportVoiceError(model, "Η επαναφορά συνομιλίας απέτυχε: $error")
      }
    }
    gate.complete(Unit)
  }

  /**
   * Replays durable paired messages before inference may use the GGUF. Room
   * may contain legacy unmatched turns from older builds; those are visible
   * in the UI but are not inserted into the native active dialogue.
   */
  suspend fun restoreGgufHistory(
    model: Model,
    prompt: String,
    history: List<Pair<String, String>>,
    gate: CompletableDeferred<Unit>,
  ) {
    val engine = withTimeout(60_000L) {
      while (true) {
        val current = model.instance as? LlamaCppEngine
        if (current != null && current.isModelLoaded.get()) return@withTimeout current
        delay(100)
      }
      error("Unreachable")
    }
    val paired = mutableListOf<Pair<String, String>>()
    var pendingUser: String? = null
    for ((role, text) in history) {
      when (role) {
        "user" -> pendingUser = text
        "assistant" -> {
          val user = pendingUser
          if (user != null) {
            paired += "user" to user
            paired += "assistant" to text
            pendingUser = null
          }
        }
      }
    }
    val window = paired.takeLast(200)
    if (historyGates[model.name] !== gate) return
    val restored = engine.restoreConversation(prompt, window) {
      historyGates[model.name] === gate
    }
    if (restored != window.size) return
    if (window.size != history.size) {
      reportVoiceError(
        model,
        "Το πλήρες ιστορικό παραμένει αποθηκευμένο, αλλά μόνο οι τελευταίοι ολοκληρωμένοι γύροι χρησιμοποιούνται στο ενεργό context."
      )
    }
  }

  private suspend fun requireHistoryReady(model: Model) {
    while (true) {
      val gate = historyGates[model.name] ?: break
      gate.await()
      if (historyGates[model.name] === gate) break
    }
    val failure = historyErrors[model.name]
    check(failure == null) { "Η συνομιλία δεν έχει αποκατασταθεί: $failure" }
  }

  private suspend fun ensureConversationId(model: Model): String =
    conversationMutex.withLock {
      conversationIds[model.name] ?: chatRepository.createConversation(
        title = "New Chat",
        taskType = "llm_chat",
        modelName = model.name,
        systemPrompt = systemPromptsByModel[model.name] ?: "",
      ).id.also { id -> conversationIds[model.name] = id }
    }

  /**
   * Persist one complete native GGUF turn in a single Room transaction.
   * Capturing its conversation ID before inference keeps a late commit in
   * the original conversation even if reset/model switching happens later.
   */
  private fun persistCommittedGgufTurn(
    model: Model,
    conversationId: String,
    user: String,
    assistant: String,
    latencyMs: Long,
  ) {
    val predecessor = lastCommitJob
    lastCommitJob = viewModelScope.launch(Dispatchers.IO) {
      predecessor?.join()
      try {
        conversationMutex.withLock {
          chatRepository.saveCommittedTurn(
            conversationId = conversationId,
            modelName = model.name,
            userText = user,
            assistantText = assistant,
            latencyMs = latencyMs,
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.e(TAG, "Failed to commit GGUF conversation turn", error)
        withContext(Dispatchers.Main) {
          addMessage(
            model = model,
            message = ChatMessageWarning(
              "Η απάντηση εμφανίστηκε, αλλά δεν αποθηκεύτηκε: ${error.message}"
            ),
          )
        }
      }
    }
  }

  /**
   * Box: Look up the most recent conversation for a model (for auto-resume).
   */
  suspend fun getLatestConversationForModel(modelName: String): com.google.ai.edge.gallery.data.local.entities.Conversation? {
    return try {
      chatRepository.getLatestConversationForModel(modelName)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to get latest conversation for model", e)
      null
    }
  }

  /**
   * Box: Load conversation history for continuing a conversation
   */
  suspend fun loadConversationHistory(conversationId: String): List<com.google.ai.edge.gallery.data.local.entities.Message>? {
    val repo = chatRepository ?: return null
    return try {
      repo.getMessagesSync(conversationId)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to load conversation history", e)
      null
    }
  }

  /**
   * Box: Persist a user message to the encrypted database.
   */
  private fun persistUserMessage(model: Model, content: String) {
    val repo = chatRepository ?: return
    Log.d(TAG, "Attempting to persist user message for model: ${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Use Mutex to ensure thread-safe conversation creation
        conversationMutex.withLock {
          // Get or create conversation ID
          val convId = if (conversationIds[model.name] == null) {
            Log.d(TAG, "Creating new conversation for model: ${model.name}")
            val conv = repo.createConversation(
              title = content.take(50),
              taskType = "llm_chat",
              modelName = model.name,
              systemPrompt = systemPromptsByModel[model.name] ?: "",
            )
            conversationIds[model.name] = conv.id
            Log.d(TAG, "Created conversation with ID: ${conv.id}")
            conv.id
          } else {
            conversationIds.getValue(model.name)
          }
          
          // Now save the message with the guaranteed conversation ID
          try {
            repo.saveMessage(
              conversationId = convId,
              role = "user",
              content = SecurityUtils.sanitizePrompt(content),
            )
            Log.d(TAG, "Successfully persisted user message to conversation: $convId")
          } catch (fkException: android.database.sqlite.SQLiteConstraintException) {
            Log.w(TAG, "Foreign key constraint failed, conversation might not be committed yet. Retrying...", fkException)
            // Retry after a short delay to ensure conversation is committed
            kotlinx.coroutines.delay(100)
            repo.saveMessage(
              conversationId = convId,
              role = "user",
              content = SecurityUtils.sanitizePrompt(content),
            )
            Log.d(TAG, "Successfully persisted user message to conversation on retry: $convId")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist user message", e)
      }
    }
  }

  /**
   * Box: Persist an assistant response to the encrypted database.
   */
  private fun persistAssistantMessage(model: Model, content: String, latencyMs: Long = 0) {
    val repo = chatRepository ?: return
    Log.d(TAG, "Attempting to persist assistant message for model: ${model.name}")
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Use Mutex to ensure thread-safe message saving
        conversationMutex.withLock {
          val convId = conversationIds[model.name] ?: chatRepository.createConversation(
            title = "New Chat",
            taskType = "llm_chat",
            modelName = model.name,
            systemPrompt = systemPromptsByModel[model.name] ?: "",
          ).id.also { conversationIds[model.name] = it }
          
          try {
            repo.saveMessage(
              conversationId = convId,
              role = "assistant",
              content = content,
              latencyMs = latencyMs,
            )
            Log.d(TAG, "Successfully persisted assistant message to conversation: $convId")
          } catch (fkException: android.database.sqlite.SQLiteConstraintException) {
            Log.w(TAG, "Foreign key constraint failed for assistant message, conversation might not be committed yet. Retrying...", fkException)
            // Retry after a short delay to ensure conversation is committed
            kotlinx.coroutines.delay(100)
            repo.saveMessage(
              conversationId = convId,
              role = "assistant",
              content = content,
              latencyMs = latencyMs,
            )
            Log.d(TAG, "Successfully persisted assistant message to conversation on retry: $convId")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to persist assistant message", e)
      }
    }
  }

  fun generateResponse(
    model: Model,
    input: String,
    images: List<Bitmap> = listOf(),
    audioMessages: List<ChatMessageAudioClip> = listOf(),
    onFirstToken: (Model) -> Unit = {},
    onDone: () -> Unit = {},
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    val accelerator = model.getStringConfigValue(key = ConfigKeys.ACCELERATOR, defaultValue = "")
    val epoch = requestEpoch.incrementAndGet()
    activeResponseJob?.cancel()
    // The inference engine owns its own coroutine and JNI mutex. Cancelling
    // this ViewModel job alone does not interrupt the old native decoder.
    activeResponseModel?.let { previous ->
      previous.runtimeHelper.stopResponse(previous)
    }
    voicePlaybackJob?.cancel()
    voicePlayback.stop()
    activeResponseModel = model
    activeResponseJob = viewModelScope.launch(Dispatchers.Default) {
      setInProgress(true)
      setPreparing(true)

      // GGUF persists complete committed pairs, never an unpaired prompt.
      // The legacy LiteRT path continues using its original persistence.
      // Loading.
      addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))

      // Wait for instance to be initialized.
      var attempts = 0
      while (model.instance == null && attempts < 1800) {
        delay(100)
        attempts++
      }
      if (model.instance == null) {
        setInProgress(false)
        setPreparing(false)
        if (getLastMessage(model = model)?.type == ChatMessageType.LOADING) {
          removeLastMessage(model = model)
        }
        if (requestEpoch.get() == epoch) onError("Το μοντέλο δεν αρχικοποιήθηκε")
        return@launch
      }
      delay(100)
      if (requestEpoch.get() != epoch) return@launch
      if (model.instance is LlamaCppEngine) {
        try {
          requireHistoryReady(model)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          if (requestEpoch.get() == epoch) {
            setInProgress(false)
            setPreparing(false)
            reportVoiceError(model, error.message ?: "Η συνεδρία δεν είναι έτοιμη")
          }
          return@launch
        }
      }
      if (requestEpoch.get() != epoch) return@launch
      if (input.isNotEmpty() && model.instance !is LlamaCppEngine) {
        persistUserMessage(model, input)
      }

      // Run inference.
      val audioClips: MutableList<ByteArray> = mutableListOf()
      for (audioMessage in audioMessages) {
        audioClips.add(audioMessage.genByteArrayForWav())
      }

      var firstRun = true
      val start = System.currentTimeMillis()

      try {
        // A GGUF voice turn uses the model's own LFM3 audio encoder for ASR,
        // then the normal chat generation path for context-preserving reasoning.
        // For LiteRT models, retain the existing audioClips route unchanged.
        val voiceEngine = (model.instance as? LlamaCppEngine)
            ?.takeIf { audioClips.isNotEmpty() }
        var effectiveInput = input
        var voiceRequested = false
        if (voiceEngine != null) {
          require(audioClips.size == 1) { "Record one voice clip per turn" }
          val transcript = try {
            voiceEngine.transcribeWav(audioClips.single())
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            if (requestEpoch.get() == epoch) {
              withContext(Dispatchers.Main) {
                if (getLastMessage(model = model)?.type == ChatMessageType.LOADING) {
                  removeLastMessage(model = model)
                }
                setPreparing(false)
                setInProgress(false)
                reportVoiceError(model, error.message ?: "Η αναγνώριση ομιλίας απέτυχε")
              }
            }
            return@launch
          }
          if (requestEpoch.get() != epoch) return@launch
          if (getLastMessage(model = model)?.type == ChatMessageType.LOADING) {
            removeLastMessage(model = model)
          }
          addMessage(
            model = model,
            message = ChatMessageText(content = transcript, side = ChatSide.USER)
          )
          addMessage(model = model, message = ChatMessageLoading(accelerator = accelerator))
          effectiveInput = listOf(input, transcript).filter { it.isNotBlank() }.joinToString("\n")
          audioClips.clear()
          voiceRequested = true
        }

        val ggufConversationId = if (model.instance is LlamaCppEngine) {
          try {
            ensureConversationId(model)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            if (requestEpoch.get() == epoch) {
              setPreparing(false)
              setInProgress(false)
              reportVoiceError(model, error.message ?: "Η συνεδρία δεν αποθηκεύτηκε")
            }
            return@launch
          }
        } else null
        val turnInput = effectiveInput

        val resultListener: (String, Boolean, String?) -> Unit =
          { partialResult, done, partialThinkingResult ->
            if (requestEpoch.get() != epoch) {
              // A newer voice turn superseded this one.
            } else if (partialResult.startsWith("<ctrl")) {
              // Do nothing. Ignore control tokens.
            } else {
              // Remove the last message if it is a "loading" message.
              // This will only be done once.
              val lastMessage = getLastMessage(model = model)
              val wasLoading = lastMessage?.type == ChatMessageType.LOADING
              if (wasLoading) {
                removeLastMessage(model = model)
              }

              val thinkingText = partialThinkingResult
              val isThinking = thinkingText != null && thinkingText.isNotEmpty()
              var currentLastMessage = getLastMessage(model = model)

              // If thinking is enabled, add a thinking message.
              if (isThinking) {
                if (currentLastMessage?.type != ChatMessageType.THINKING) {
                  addMessage(
                    model = model,
                    message =
                      ChatMessageThinking(
                        content = "",
                        inProgress = true,
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL,
                      ),
                  )
                }
                updateLastThinkingMessageContentIncrementally(
                  model = model,
                  partialContent = thinkingText!!,
                )
              } else {
                if (currentLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = currentLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                currentLastMessage = getLastMessage(model = model)
                if (
                  currentLastMessage?.type != ChatMessageType.TEXT ||
                    currentLastMessage.side != ChatSide.AGENT
                ) {
                  // Add an empty message that will receive streaming results.
                  addMessage(
                    model = model,
                    message =
                      ChatMessageText(
                        content = "",
                        side = ChatSide.AGENT,
                        accelerator = accelerator,
                        hideSenderLabel =
                          currentLastMessage?.type == ChatMessageType.COLLAPSABLE_PROGRESS_PANEL ||
                            currentLastMessage?.type == ChatMessageType.THINKING,
                      ),
                  )
                }

                // Incrementally update the streamed partial results.
                val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
                if (partialResult.isNotEmpty() || wasLoading || done) {
                  updateLastTextMessageContentIncrementally(
                    model = model,
                    partialContent = partialResult,
                    latencyMs = latencyMs.toFloat(),
                  )
                }
              }

              if (firstRun) {
                firstRun = false
                setPreparing(false)
                onFirstToken(model)
              }

              if (done) {
                val finalLastMessage = getLastMessage(model = model)
                if (finalLastMessage?.type == ChatMessageType.THINKING) {
                  val thinkingMsg = finalLastMessage as ChatMessageThinking
                  if (thinkingMsg.inProgress) {
                    replaceLastMessage(
                      model = model,
                      message =
                        ChatMessageThinking(
                          content = thinkingMsg.content,
                          inProgress = false,
                          side = thinkingMsg.side,
                          accelerator = thinkingMsg.accelerator,
                          hideSenderLabel = thinkingMsg.hideSenderLabel,
                        ),
                      type = ChatMessageType.THINKING,
                    )
                  }
                }
                // Box: Persist assistant response to encrypted DB.
                val assistantMsg = getLastMessageWithTypeAndSide(model, ChatMessageType.TEXT, ChatSide.AGENT)
                if (assistantMsg is ChatMessageText && assistantMsg.content.isNotEmpty()) {
                  if (ggufConversationId != null) {
                    persistCommittedGgufTurn(
                      model, ggufConversationId, turnInput,
                      assistantMsg.content, assistantMsg.latencyMs.toLong()
                    )
                  } else {
                    persistAssistantMessage(model, assistantMsg.content, assistantMsg.latencyMs.toLong())
                  }
                }

                if (voiceRequested && voiceEngine != null &&
                    assistantMsg is ChatMessageText && assistantMsg.content.isNotBlank()) {
                  val spokenAnswer = assistantMsg.content
                  setPreparing(true)
                  voicePlaybackJob?.cancel()
                  voicePlaybackJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                      val accumulated = ByteArrayOutputStream()
                      var clipRate = 0
                      val pieces = VoiceTextSegments.split(spokenAnswer)
                      require(pieces.isNotEmpty()) { "Η φωνητική απάντηση είναι κενή" }
                      for (piece in pieces) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (requestEpoch.get() != epoch) return@launch
                        val wav = voiceEngine.synthesizeSpeech(piece)
                        val audio = WavPcm16.read(wav)
                        if (clipRate == 0) clipRate = audio.sampleRate
                        check(clipRate == audio.sampleRate) {
                          "Η σύνθεση ήχου άλλαξε συχνότητα μεταξύ τμημάτων"
                        }
                        check(accumulated.size().toLong() + audio.pcm16.size <= 8L * 1024 * 1024) {
                          "Η απάντηση ξεπέρασε το όριο μνήμης ηχητικής αναπαραγωγής"
                        }
                        accumulated.write(audio.pcm16)
                        if (requestEpoch.get() != epoch) return@launch
                        // A new voice turn interrupts either synthesis or the
                        // current clip. Never start a stale audio segment.
                        voicePlayback.play(audio)
                      }
                      withContext(Dispatchers.Main) {
                        if (requestEpoch.get() != epoch) return@withContext
                        addMessage(
                          model = model,
                          message = ChatMessageAudioClip(
                            audioData = accumulated.toByteArray(),
                            sampleRate = clipRate,
                            side = ChatSide.AGENT,
                          ),
                        )
                        setPreparing(false)
                        setInProgress(false)
                        onDone()
                      }
                    } catch (cancelled: CancellationException) {
                      throw cancelled
                    } catch (error: Exception) {
                      Log.e(TAG, "Greek speech synthesis/playback failed", error)
                      withContext(Dispatchers.Main) {
                        if (requestEpoch.get() == epoch) {
                          setPreparing(false)
                          setInProgress(false)
                          onDone()
                          reportVoiceError(model, error.message ?: "Η σύνθεση φωνής απέτυχε")
                        }
                      }
                    }
                  }
                } else {
                  setInProgress(false)
                  onDone()
                }
              }
            }
          }

        val cleanUpListener: () -> Unit = {
          if (requestEpoch.get() == epoch) {
            setInProgress(false)
            setPreparing(false)
          }
        }

        val errorListener: (String) -> Unit = { message ->
          if (requestEpoch.get() == epoch) {
            Log.e(TAG, "Error occurred while running inference: $message")
            setInProgress(false)
            setPreparing(false)
            if (model.instance is LlamaCppEngine &&
                (message.contains("context window", ignoreCase = true) ||
                 message.contains("Latest turn is larger", ignoreCase = true))) {
              // Native has already rolled this turn back. Do not reload the
              // GGUF merely because the prompt/context limit was reached.
              val last = getLastMessage(model = model)
              if (last is ChatMessageText && last.side == ChatSide.AGENT) {
                removeLastMessage(model = model)
              }
              reportVoiceError(model, message)
            } else {
              onError(message)
            }
          }
        }

        val enableThinking =
          allowThinking &&
            model.getBooleanConfigValue(key = ConfigKeys.ENABLE_THINKING, defaultValue = false)
        val extraContext = if (enableThinking) mapOf("enable_thinking" to "true") else null

        if (requestEpoch.get() != epoch) return@launch
        model.runtimeHelper.runInference(
          model = model,
          input = effectiveInput,
          images = images,
          audioClips = audioClips,
          resultListener = resultListener,
          cleanUpListener = cleanUpListener,
          onError = errorListener,
          coroutineScope = viewModelScope,
          extraContext = extraContext,
        )
      } catch (e: CancellationException) {
        // Interrupted voice turn; the new request owns the visible UI.
        if (requestEpoch.get() == epoch) {
          setInProgress(false)
          setPreparing(false)
        }
        throw e
      } catch (e: Exception) {
        if (requestEpoch.get() == epoch) {
          Log.e(TAG, "Error occurred while running inference", e)
          if (getLastMessage(model = model)?.type == ChatMessageType.LOADING) {
            removeLastMessage(model = model)
          }
          setInProgress(false)
          setPreparing(false)
          onError(e.message ?: "")
        }
      }
    }
  }

  fun stopResponse(model: Model) {
    requestEpoch.incrementAndGet()
    activeResponseJob?.cancel()
    voicePlaybackJob?.cancel()
    voicePlayback.stop()
    activeResponseModel = null
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    setInProgress(false)
    setPreparing(false)
    model.runtimeHelper.stopResponse(model)
    Log.d(TAG, "Done stopping response")
  }

  fun resetSession(
    task: Task,
    model: Model,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    onDone: () -> Unit = {},
    enableConversationConstrainedDecoding: Boolean = false,
  ) {
    val resetGate = beginHistorySynchronization(model)
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      var resetFailure: String? = null
      try {
        stopResponse(model = model)

        var succeeded = false
        var lastError: Exception? = null
        for (attempt in 1..3) {
          try {
            model.runtimeHelper.resetConversation(
              model = model,
              supportImage = supportImage,
              supportAudio = supportAudio,
              systemInstruction = systemInstruction,
              tools = tools,
              enableConversationConstrainedDecoding = enableConversationConstrainedDecoding,
            )
            succeeded = true
            break
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            lastError = error
            Log.e(TAG, "Failed to reset session ($attempt of 3)", error)
            if (attempt < 3) delay(250L * attempt)
          }
        }
        if (!succeeded) {
          resetFailure = lastError?.message ?: "Άγνωστο σφάλμα native reset"
          addMessage(
            model = model,
            message = ChatMessageError("Η επαναφορά απέτυχε: $resetFailure"),
          )
        } else {
          clearAllMessages(model = model)
          beginNewConversation(model)
          try {
            createFreshConversationAfterReset(model)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (error: Exception) {
            Log.e(TAG, "Native reset succeeded but new Room conversation failed", error)
            reportVoiceError(model, "Η νέα συνομιλία δεν αποθηκεύτηκε: ${error.message}")
          }
          onDone()
        }
      } finally {
        setIsResettingSession(false)
        endHistorySynchronization(model, resetGate, resetFailure)
      }
    }
  }

  fun runAgain(
    model: Model,
    message: ChatMessageText,
    onError: (String) -> Unit,
    allowThinking: Boolean = false,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model,
        input = message.content,
        onError = onError,
        allowThinking = allowThinking,
      )
    }
  }

  fun handleError(
    context: Context,
    task: Task,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    errorMessage: String,
  ) {
    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Show error message.
    addMessage(model = model, message = ChatMessageError(content = errorMessage))

    // Clean up and re-initialize.
    viewModelScope.launch(Dispatchers.Default) {
      modelManagerViewModel.cleanupModel(
        context = context,
        task = task,
        model = model,
        onDone = {
          modelManagerViewModel.initializeModel(context = context, task = task, model = model)

          // Add a warning message for re-initializing the session.
          addMessage(
            model = model,
            message = ChatMessageWarning(content = "Session re-initialized"),
          )
        },
      )
    }
  }
}

@HiltViewModel class LlmChatViewModel @Inject constructor(
  repo: ChatRepository
) : LlmChatViewModelBase() {
  init { chatRepository = repo }
}

@HiltViewModel class LlmAskImageViewModel @Inject constructor(
  repo: ChatRepository
) : LlmChatViewModelBase() {
  init { chatRepository = repo }
}

@HiltViewModel class LlmAskAudioViewModel @Inject constructor(
  repo: ChatRepository
) : LlmChatViewModelBase() {
  init { chatRepository = repo }
}
