package com.google.ai.edge.gallery.ui.llmchat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.gallery.engine.OmniLiveVoiceController
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatSide

/** Opt-in foreground hands-free mode for the imported LFM3-Omni GGUF. */
@Composable
fun OmniLiveVoicePanel(
    model: Model,
    viewModel: LlmChatViewModelBase,
    useMixedVoice: Boolean = false,
    useNativeRealtimeAudio: Boolean = false,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val currentMixedVoice by rememberUpdatedState(useMixedVoice)
    val currentRealtimeAudio by rememberUpdatedState(useNativeRealtimeAudio)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember(model.name) {
        OmniLiveVoiceController(context.applicationContext)
    }
    var enabled by remember(model.name) { mutableStateOf(false) }
    var status by remember(model.name) { mutableStateOf("Απενεργοποιημένο") }
    val engine = model.instance as? LlamaCppEngine
    val canStart = engine?.isModelLoaded?.get() == true

    val startListening: () -> Unit = {
        try {
            check(canStart) { "Το μοντέλο δεν είναι ακόμη έτοιμο" }
            controller.start(
                scope = scope,
                onSpeechStart = {
                    if (enabled) {
                        status = "Σε ακούω..."
                        // Barge-in stops synthesis, playback and in-flight generation.
                        viewModel.stopResponse(model)
                    }
                },
                onUtterance = { pcm, rate ->
                    if (enabled && pcm.isNotEmpty()) {
                        status = "Επεξεργασία ομιλίας..."
                        val userClip = ChatMessageAudioClip(
                            audioData = pcm, sampleRate = rate, side = ChatSide.USER)
                        viewModel.addMessage(model = model, message = userClip)
                        viewModel.generateResponse(
                            model = model,
                            input = "",
                            audioMessages = listOf(userClip),
                            useMixedVoice = currentMixedVoice,
                            useNativeRealtimeAudio = currentRealtimeAudio,
                            onDone = { if (enabled) status = "Ακούω..." },
                            onError = { message ->
                                if (enabled) status = "Ακούω..."
                                onError(message)
                            },
                        )
                    }
                },
                onError = { reason ->
                    status = "Πρόβλημα μικροφώνου"
                    enabled = false
                    viewModel.stopResponse(model)
                    onError(reason)
                },
                onStopped = {
                    if (!controller.isActive) {
                        viewModel.stopResponse(model)
                        enabled = false
                        status = "Απενεργοποιημένο"
                    }
                },
            )
            enabled = true
            status = "Ακούω..."
        } catch (error: Exception) {
            enabled = false
            status = "Δεν ξεκίνησε"
            onError(error.message ?: "Η συνεχής ακρόαση απέτυχε")
        }
    }

    val permission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) startListening()
            else onError("Χρειάζεται άδεια μικροφώνου για τη συνεχή συνομιλία")
        },
    )

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP ||
                event == Lifecycle.Event.ON_DESTROY) {
                controller.stop()
                viewModel.stopResponse(model)
                enabled = false
                status = "Απενεργοποιημένο"
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
            viewModel.stopResponse(model)
        }
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = enabled || canStart,
                onClick = {
                    if (enabled) {
                        controller.stop()
                        viewModel.stopResponse(model)
                        enabled = false
                        status = "Απενεργοποιημένο"
                    } else if (ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) {
                        startListening()
                    } else {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    contentDescription = null,
                )
                Text(if (enabled) " Διακοπή συνεχούς φωνής" else " Συνεχής φωνή")
            }
        }
        Text(status)
        if (enabled) {
            Text("Το μικρόφωνο λειτουργεί μόνο όσο αυτή η οθόνη παραμένει ανοιχτή.")
        }
    }
}
