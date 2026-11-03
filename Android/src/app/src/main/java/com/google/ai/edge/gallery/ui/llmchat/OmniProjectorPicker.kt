package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.engine.GgufAudioFiles
import kotlinx.coroutines.launch

/**
 * A model projector is an independent GGUF file: import it explicitly through SAF.
 * Only GGUF models show this UI; LiteRT is never routed through the native backend.
 */
@Composable
fun OmniProjectorPicker(
    model: Model,
    context: Context,
    onProjectorImported: () -> Unit,
) {
    val modelPath = model.getPath(context)
    var importing by remember(modelPath) { mutableStateOf(false) }
    var errorText by remember(modelPath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null && !importing) {
                importing = true
                errorText = null
                scope.launch {
                    try {
                        GgufAudioFiles.importProjector(context, modelPath, uri)
                        onProjectorImported()
                    } catch (error: Exception) {
                        errorText = error.message ?: "Η εισαγωγή του mmproj απέτυχε"
                    } finally {
                        importing = false
                    }
                }
            }
        }
    )
    val existing = GgufAudioFiles.findProjector(context, modelPath) != null
    Row(modifier = Modifier.padding(horizontal = 12.dp)) {
        OutlinedButton(
            enabled = !importing,
            onClick = { launcher.launch(arrayOf("*/*")) },
        ) {
            Text(
                when {
                    importing -> "Εισαγωγή mmproj…"
                    existing -> "Αλλαγή αρχείου mmproj"
                    else -> "Εισαγωγή αρχείου mmproj για φωνή"
                }
            )
        }
    }
    if (errorText != null) Text(errorText!!)
}
