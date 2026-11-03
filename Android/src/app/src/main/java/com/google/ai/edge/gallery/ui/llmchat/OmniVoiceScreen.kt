package com.google.ai.edge.gallery.ui.llmchat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.engine.GgufAudioFiles
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.gallery.runtime.VoiceGgufRuntimeSettings
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelImportingDialog
import kotlinx.coroutines.launch
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
    var settingsExpanded by remember { mutableStateOf(false) }
    var gpuMenuExpanded by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importInfo by remember { mutableStateOf<ImportedModel?>(null) }
    var importStage by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()
    val ggufPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            if (displayName?.endsWith(".gguf", ignoreCase = true) != true) {
                importError = "Επίλεξε αρχείο μοντέλου με κατάληξη .gguf"
            } else {
                importError = null
                importUri = uri
                importInfo = null
                importStage = 1
            }
        }
    }
    val selected = models.firstOrNull { it.name == (selectedName ?: firstChoice) }
    val maxCpuThreads = remember { VoiceGgufRuntimeSettings.maxCpuThreads() }
    var cpuThreads by remember(selected?.name) {
        mutableStateOf(
            selected?.let {
                VoiceGgufRuntimeSettings.cpuThreads(context, it.getPath(context))
            } ?: VoiceGgufRuntimeSettings.defaultCpuThreads()
        )
    }
    var cpuBatchThreads by remember(selected?.name) {
        mutableStateOf(selected?.let {
            VoiceGgufRuntimeSettings.cpuBatchThreads(context, it.getPath(context))
        } ?: VoiceGgufRuntimeSettings.defaultCpuThreads())
    }
    var gpuLayers by remember(selected?.name) {
        mutableStateOf(selected?.let {
            VoiceGgufRuntimeSettings.gpuLayers(context, it.getPath(context))
        } ?: 0)
    }
    var gpuProjector by remember(selected?.name) {
        mutableStateOf(selected?.let {
            VoiceGgufRuntimeSettings.gpuProjector(context, it.getPath(context))
        } ?: false)
    }
    var performanceCoresOnly by remember(selected?.name) {
        mutableStateOf(selected?.let {
            VoiceGgufRuntimeSettings.performanceCoresOnly(context, it.getPath(context))
        } ?: false)
    }
    var unloading by remember(selected?.name) { mutableStateOf(false) }
    var runtimeError by remember(selected?.name) { mutableStateOf<String?>(null) }
    var useMixedVoice by remember(selected?.name) {
        mutableStateOf(selected?.let {
            VoiceGgufRuntimeSettings.mixedVoiceEnabled(context, it.getPath(context))
        } ?: false)
    }
    val status = selected?.let { manager.modelInitializationStatus[it.name] }
    val isInitialized = status?.status == ModelInitializationStatusType.INITIALIZED
    val engine = selected?.instance as? LlamaCppEngine
    val modelLoaded = isInitialized && engine?.isModelLoaded?.get() == true
    val hasFullAudio = modelLoaded && engine?.supportsGreekVoice == true
    val projector = selected?.let {
        GgufAudioFiles.findProjector(context, it.getPath(context))
    }

    DisposableEffect(viewModel, selected?.name) {
        viewModel.configureVoiceOutput(context.applicationContext)
        val activeModel = selected
        onDispose { activeModel?.let { viewModel.stopResponse(it) } }
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
    // Deliberately do not auto-load when this destination opens: explicitly
    // unloading must remain effective, even after leaving/reopening the screen.

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
                Button(onClick = { ggufPicker.launch(arrayOf("*/*")) }) {
                    Text("Εισαγωγή μοντέλου GGUF")
                }
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

                OutlinedButton(
                    onClick = { settingsExpanded = !settingsExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ρυθμίσεις CPU: $cpuThreads νήματα" + if (settingsExpanded) " ▴" else " ▾") }
                if (settingsExpanded) {
                    Text("Υπολογισμός GGUF", style = MaterialTheme.typography.titleMedium)
                    val gpuLabel = when (gpuLayers) {
                        0 -> "CPU"
                        -1 -> "Vulkan — όλα τα layers"
                        else -> "Vulkan — $gpuLayers layers"
                    }
                    Text("Backend GGUF")
                    Box {
                        OutlinedButton(
                            onClick = { gpuMenuExpanded = true },
                            enabled = !unloading,
                        ) { Text(gpuLabel) }
                        DropdownMenu(
                            expanded = gpuMenuExpanded,
                            onDismissRequest = { gpuMenuExpanded = false },
                        ) {
                            listOf(0, 4, 8, 16, 32, -1).forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(when (option) {
                                        0 -> "CPU — χωρίς GPU"
                                        -1 -> "Vulkan — όλα τα layers"
                                        else -> "Vulkan — $option layers"
                                    }) },
                                    onClick = {
                                        gpuMenuExpanded = false
                                        runCatching {
                                            VoiceGgufRuntimeSettings.setGpuLayers(
                                                context, selected.getPath(context), option)
                                        }.onSuccess {
                                            gpuLayers = option
                                            runtimeError = null
                                        }.onFailure { runtimeError = it.message }
                                    },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = !unloading && gpuLayers != 0,
                        onClick = {
                            val next = !gpuProjector
                            runCatching {
                                VoiceGgufRuntimeSettings.setGpuProjector(
                                    context, selected.getPath(context), next)
                            }.onSuccess {
                                gpuProjector = next
                                runtimeError = null
                            }.onFailure { runtimeError = it.message }
                        },
                    ) {
                        Text(if (gpuProjector) "Audio projector: Vulkan" else
                            "Audio projector: CPU")
                    }
                    Text(
                        "Οι αλλαγές GPU εφαρμόζονται μετά από Επαναφόρτωση. " +
                        "Αν αποτύχει το Vulkan, γίνεται επιστροφή σε CPU.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Νήματα CPU: $cpuThreads / $maxCpuThreads")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            enabled = cpuThreads > 1 && !unloading,
                            onClick = {
                                val value = cpuThreads - 1
                                runCatching {
                                    VoiceGgufRuntimeSettings.setCpuThreads(
                                        context, selected.getPath(context), value
                                    )
                                }.onSuccess {
                                    cpuThreads = value
                                    runtimeError = null
                                }.onFailure { runtimeError = it.message }
                            },
                        ) { Text("−") }
                        OutlinedButton(
                            enabled = cpuThreads < maxCpuThreads && !unloading &&
                                (!performanceCoresOnly || cpuThreads < 4),
                            onClick = {
                                val value = cpuThreads + 1
                                runCatching {
                                    VoiceGgufRuntimeSettings.setCpuThreads(
                                        context, selected.getPath(context), value
                                    )
                                }.onSuccess {
                                    cpuThreads = value
                                    runtimeError = null
                                }.onFailure { runtimeError = it.message }
                            },
                        ) { Text("+") }
                    }
                    OutlinedButton(
                        enabled = !unloading && (!(!performanceCoresOnly && cpuThreads > 4)),
                        onClick = {
                            val next = !performanceCoresOnly
                            runCatching {
                                VoiceGgufRuntimeSettings.setPerformanceCoresOnly(
                                    context, selected.getPath(context), next)
                            }.onSuccess {
                                performanceCoresOnly = next
                                runtimeError = null
                            }.onFailure { runtimeError = it.message }
                        },
                    ) {
                        Text(if (performanceCoresOnly) "CPU: 4 ισχυρότεροι πυρήνες"
                            else "CPU: όλοι οι διαθέσιμοι πυρήνες")
                    }
                    Text("Νήματα CPU για prompt: $cpuBatchThreads / $maxCpuThreads")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            enabled = cpuBatchThreads > 1 && !unloading,
                            onClick = {
                                val next = cpuBatchThreads - 1
                                runCatching {
                                    VoiceGgufRuntimeSettings.setCpuBatchThreads(
                                        context, selected.getPath(context), next)
                                }.onSuccess {
                                    cpuBatchThreads = next
                                    runtimeError = null
                                }.onFailure { runtimeError = it.message }
                            },
                        ) { Text("− prompt") }
                        OutlinedButton(
                            enabled = cpuBatchThreads < maxCpuThreads && !unloading,
                            onClick = {
                                val next = cpuBatchThreads + 1
                                runCatching {
                                    VoiceGgufRuntimeSettings.setCpuBatchThreads(
                                        context, selected.getPath(context), next)
                                }.onSuccess {
                                    cpuBatchThreads = next
                                    runtimeError = null
                                }.onFailure { runtimeError = it.message }
                            },
                        ) { Text("+ prompt") }
                    }
                    val activeThreads = engine?.lastLoadParams?.numThreads
                    if (modelLoaded && activeThreads != null) {
                        Text(
                            "Ενεργό backend: " +
                                if (engine.lastLoadParams?.numGpuLayers == 0) "CPU"
                                else "Vulkan",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Ενεργά prompt threads: " +
                            (engine.lastLoadParams?.numThreadsBatch ?: "—"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (activeThreads == cpuThreads) "Ενεργά CPU threads: $activeThreads"
                            else "Ενεργά CPU threads: $activeThreads. Πάτησε Επαναφόρτωση για να χρησιμοποιηθούν $cpuThreads.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "Η επιλογή εφαρμόζεται στην επόμενη φόρτωση. Τα threads δεν δεσμεύουν συγκεκριμένους πυρήνες.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (runtimeError != null) {
                        Text(runtimeError!!, color = MaterialTheme.colorScheme.error)
                    }
    
                }


                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !unloading,
                    onClick = {
                        val next = !useMixedVoice
                        runCatching {
                            VoiceGgufRuntimeSettings.setMixedVoiceEnabled(
                                context, selected.getPath(context), next)
                        }.onSuccess {
                            useMixedVoice = next
                            runtimeError = null
                        }.onFailure { runtimeError = it.message }
                    },
                ) {
                    Text(if (useMixedVoice) "Ταυτόχρονη φωνή: ενεργή" else
                        "Ταυτόχρονη φωνή: ανενεργή")
                }
                if (useMixedVoice) Text(
                    "Πειραματική παραγωγή κειμένου και PCM μαζί. " +
                    "Απενεργοποίησέ την για να χρησιμοποιήσεις την παλιά φωνή.",
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ggufPicker.launch(arrayOf("*/*")) }) {
                        Text("Εισαγωγή GGUF")
                    }
                    OutlinedButton(
                        enabled = task != null &&
                            download == ModelDownloadStatusType.SUCCEEDED &&
                            status?.status != ModelInitializationStatusType.INITIALIZING && !unloading,
                        onClick = {
                            task?.let {
                                viewModel.stopResponse(selected)
                                modelManagerViewModel.initializeModel(
                                    context = context, task = it, model = selected, force = true,
                                )
                            }
                        },
                    ) { Text(if (modelLoaded) "Επαναφόρτωση" else "Φόρτωση") }
                }

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = task != null && !unloading &&
                        selected.instance is LlamaCppEngine &&
                        status?.status != ModelInitializationStatusType.INITIALIZING,
                    onClick = {
                        task?.let {
                            unloading = true
                            viewModel.stopResponse(selected)
                            modelManagerViewModel.cleanupModel(
                                context = context,
                                task = it,
                                model = selected,
                                onDone = { unloading = false },
                            )
                        }
                    },
                ) { Text(if (unloading) "Αποδέσμευση RAM..." else "Unload μοντέλου / Αποδέσμευση RAM") }

                Text("Κατάσταση ήχου", style = MaterialTheme.typography.titleMedium)
                val diagnostic = when {
                    status?.status == ModelInitializationStatusType.ERROR ->
                        "Αποτυχία φόρτωσης μοντέλου: ${status.error}"
                    download != ModelDownloadStatusType.SUCCEEDED ->
                        "Το μοντέλο δεν είναι διαθέσιμο στη συσκευή."
                    status?.status == ModelInitializationStatusType.INITIALIZING ->
                        "Φορτώνεται το μοντέλο και ελέγχονται οι δυνατότητες ήχου…"
                    unloading -> "Αποδεσμεύεται το GGUF από τη RAM…"
                    !modelLoaded -> "Το μοντέλο δεν είναι φορτωμένο. Επίλεξε Φόρτωση."
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
                if (projector != null && !hasFullAudio &&
                    (status?.status == ModelInitializationStatusType.ERROR || modelLoaded)) {
                    OutlinedButton(
                        onClick = {
                            task?.let {
                                viewModel.stopResponse(selected)
                                modelManagerViewModel.initializeModel(
                                    context = context, task = it,
                                    model = selected, force = true,
                                )
                            }
                        }
                    ) { Text("Επανέλεγχος φόρτωσης μοντέλου") }
                }

                if (hasFullAudio) {
                    OmniLiveVoicePanel(
                        model = selected,
                        viewModel = viewModel,
                        useMixedVoice = useMixedVoice,
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
            if (importError != null) {
                Text(importError!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val uri = importUri
    if (uri != null && importStage == 1) {
        ModelImportDialog(
            uri = uri,
            onDismiss = { importStage = 0 },
            onDone = { info ->
                importInfo = info
                importStage = 2
            },
        )
    }
    if (uri != null && importStage == 2) {
        importInfo?.let { info ->
            ModelImportingDialog(
                uri = uri,
                info = info,
                onDismiss = { importStage = 0 },
                onDone = { completed ->
                    importScope.launch {
                        modelManagerViewModel.addImportedLlmModel(completed)
                        selectedName = completed.fileName
                        importStage = 0
                        importUri = null
                        importInfo = null
                    }
                },
            )
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
