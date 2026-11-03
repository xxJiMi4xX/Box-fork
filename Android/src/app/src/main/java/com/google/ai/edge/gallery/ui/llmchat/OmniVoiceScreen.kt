package com.google.ai.edge.gallery.ui.llmchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.engine.GgufAudioFiles
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.CancellationException

/**
 * Dedicated LFM3-Omni hands-free conversation destination.
 *
 * Unlike the former conditional inline control, this screen remains reachable
 * when no projector is present or native ASR/TTS initialization fails. It uses
 * the same GGUF and voice controller as ordinary chat, not a second runtime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniVoiceScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateUp: () -> Unit,
    onImportModelClicked: () -> Unit,
    viewModel: LlmChatViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val manager by modelManagerViewModel.uiState.collectAsState()
    val chatUi by viewModel.uiState.collectAsState()
    val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)
    val models = task?.models.orEmpty().filter { model ->
        GgufAudioFiles.isGguf(model.getPath(context))
    }
    val firstChoice = manager.selectedModel.name.takeIf { name ->
        models.any { it.name == name }
    } ?: models.firstOrNull { it.name.contains("LFM3-Omni", ignoreCase = true) }?.name
        ?: models.firstOrNull()?.name
    var selectedName by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.name == (selectedName ?: firstChoice) }
    val status = selected?.let { manager.modelInitializationStatus[it.name] }
    val isInitialized = status?.status == ModelInitializationStatusType.INITIALIZED
    val engine = selected?.instance as? LlamaCppEngine
    val modelLoaded = isInitialized && engine?.isModelLoaded?.get() == true
    val hasFullAudio = modelLoaded && engine?.supportsGreekVoice == true
    val projector = selected?.let {
        GgufAudioFiles.findProjector(context, it.getPath(context))
    }

    DisposableEffect(viewModel) {
        viewModel.configureVoiceOutput(context.applicationContext)
        onDispose { selected?.let { viewModel.stopResponse(it) } }
    }

    // Keep the model manager's selected model in sync with the dedicated
    // picker. This also guarantees that initialization refers to this model.
    LaunchedEffect(selected?.name) {
        selected?.let { model ->
            if (manager.selectedModel.name != model.name) {
                modelManagerViewModel.selectModel(model)
            }
        }
    }

    val download = selected?.let { manager.modelDownloadStatus[it.name]?.status }
    LaunchedEffect(selected?.name, download) {
        val model = selected
        if (task != null && model != null &&
            download == ModelDownloadStatusType.SUCCEEDED) {
            modelManagerViewModel.initializeModel(
                context = context,
                task = task,
                model = model,
            )
        }
    }

    // Reuse the encrypted chat repository and the native GGUF history gate.
    // The status key changes after a force-reload of the projector.
    LaunchedEffect(selected?.name, status?.status) {
        val model = selected ?: return@LaunchedEffect
        viewModel.setCurrentConversationModel(model)
        if (status?.status != ModelInitializationStatusType.INITIALIZED) {
            return@LaunchedEffect
        }
        val gate = viewModel.beginHistorySynchronization(model)
        var failure: String? = null
        try {
            val latest = viewModel.getLatestConversationForModel(model.name)
            val stored = if (latest != null) {
                check(latest.modelName == model.name)
                viewModel.loadConversationHistory(latest.id)
                    ?: throw IllegalStateException("Αποτυχία ανάγνωσης ιστορικού")
            } else emptyList()
            if (latest != null) {
                viewModel.setCurrentConversationId(model, latest.id)
                viewModel.setCurrentSystemPrompt(latest.systemPrompt)
            } else {
                viewModel.beginNewConversation(model)
                viewModel.setCurrentSystemPrompt("")
            }
            if (chatUi.messagesByModel[model.name].isNullOrEmpty()) {
                stored.forEach { row ->
                    if (row.role == "user" || row.role == "assistant") {
                        viewModel.addMessage(
                            model,
                            ChatMessageText(
                                content = row.content,
                                side = if (row.role == "user") ChatSide.USER else ChatSide.AGENT,
                                latencyMs = row.latencyMs.toFloat(),
                            )
                        )
                    }
                }
            }
            viewModel.restoreGgufHistory(
                model = model,
                prompt = latest?.systemPrompt.orEmpty(),
                history = stored.map { it.role to it.content },
                gate = gate,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "Αποτυχία επαναφοράς συνομιλίας"
        } finally {
            viewModel.endHistorySynchronization(model, gate, failure)
        }
    }

    val goBack = {
        selected?.let { viewModel.stopResponse(it) }
        navigateUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Φωνητική συνομιλία") },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Πίσω")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selected == null) {
                Text("Δεν υπάρχει εισαγμένο μοντέλο GGUF για φωνητική συνομιλία.")
                Button(onClick = onImportModelClicked) { Text("Εισαγωγή μοντέλου GGUF") }
            } else {
                Text("Μοντέλο", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected.name, maxLines = 2)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        models.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.name, maxLines = 2) },
                                onClick = {
                                    expanded = false
                                    if (model.name != selected.name) {
                                        viewModel.stopResponse(selected)
                                        selectedName = model.name
                                    }
                                }
                            )
                        }
                    }
                }

                Text("Κατάσταση ήχου", style = MaterialTheme.typography.titleMedium)
                val diagnostic = when {
                    status?.status == ModelInitializationStatusType.ERROR ->
                        "Αποτυχία φόρτωσης μοντέλου: ${status.error}"
                    download != ModelDownloadStatusType.SUCCEEDED ->
                        "Το μοντέλο δεν είναι διαθέσιμο στη συσκευή."
                    status?.status == ModelInitializationStatusType.INITIALIZING ->
                        "Φορτώνεται το μοντέλο και ελέγχονται οι δυνατότητες ήχου…"
                    !modelLoaded ->
                        "Αναμονή αρχικοποίησης της μηχανής GGUF…"
                    projector == null ->
                        "Το μοντέλο λειτουργεί για κείμενο, αλλά δεν βρέθηκε mmproj για ASR/TTS."
                    !hasFullAudio ->
                        "Το μοντέλο φορτώθηκε, αλλά δεν ενεργοποιήθηκε η πλήρης υποστήριξη ASR και TTS. Ελέγξτε τη συμβατότητα του mmproj."
                    else -> "ASR και TTS διαθέσιμα. Μπορείτε να ξεκινήσετε τη συνομιλία."
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(diagnostic)
                        Text(
                            "mmproj: " + if (projector == null) "Δεν εντοπίστηκε" else "Εντοπίστηκε",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status?.status == ModelInitializationStatusType.INITIALIZING) {
                            CircularProgressIndicator()
                        }
                    }
                }

                OmniProjectorPicker(model = selected, context = context) {
                    task?.let {
                        viewModel.stopResponse(selected)
                        modelManagerViewModel.initializeModel(
                            context = context, task = it, model = selected, force = true
                        )
                    }
                }

                if (hasFullAudio) {
                    OmniLiveVoicePanel(
                        model = selected,
                        viewModel = viewModel,
                        onError = { viewModel.reportVoiceError(selected, it) },
                    )
                } else {
                    Text(
                        "Το μικρόφωνο ενεργοποιείται μόνο όταν επιβεβαιωθεί ότι λειτουργούν και οι δύο δυνατότητες. Η κατάσταση παραμένει ορατή για διάγνωση.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text("Συνομιλία", style = MaterialTheme.typography.titleMedium)
                val allMessages = chatUi.messagesByModel[selected.name].orEmpty()
                val visible = allMessages.filter {
                    it is ChatMessageText || it is ChatMessageError ||
                        it is ChatMessageWarning || it is ChatMessageLoading
                }
                val scroll = rememberLazyListState()
                LaunchedEffect(selected.name, visible.size) {
                    if (visible.isNotEmpty()) scroll.animateScrollToItem(visible.lastIndex)
                }
                if (visible.isEmpty()) {
                    Text(
                        "Όταν ξεκινήσει η φωνητική συνομιλία, οι αναγνωρισμένες φράσεις και οι απαντήσεις θα εμφανιστούν εδώ.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        state = scroll,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible) { message ->
                            VoiceMessageCard(message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageCard(message: ChatMessage) {
    val (sender, content) = when (message) {
        is ChatMessageText ->
            (if (message.side == ChatSide.USER) "Εσύ" else "Μοντέλο") to message.content
        is ChatMessageError -> "Σφάλμα" to message.content
        is ChatMessageWarning -> "Προειδοποίηση" to message.content
        is ChatMessageLoading -> "Κατάσταση" to "Γίνεται επεξεργασία…"
        else -> return
    }
    val background = if (message.side == ChatSide.USER) {
        MaterialTheme.colorScheme.primaryContainer
    } else MaterialTheme.colorScheme.surfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(sender, style = MaterialTheme.typography.labelSmall)
            Text(content)
        }
    }
}
