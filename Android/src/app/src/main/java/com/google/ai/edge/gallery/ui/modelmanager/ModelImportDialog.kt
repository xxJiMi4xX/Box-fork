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

package com.google.ai.edge.gallery.ui.modelmanager

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.common.isPixel10
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.BooleanSwitchConfig
import com.google.ai.edge.gallery.data.Config
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.IMPORTS_DIR
import com.google.ai.edge.gallery.data.LabelConfig
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.SegmentedButtonConfig
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.proto.ImportedModel
import com.google.ai.edge.gallery.proto.LlmConfig
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import com.google.ai.edge.gallery.ui.common.ensureValidFileName
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AGModelImportDialog"

private val SUPPORTED_ACCELERATORS: List<Accelerator> =
  if (isPixel10()) {
    listOf(Accelerator.CPU)
  } else {
    listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
  }

private val IMPORT_CONFIGS_LLM: List<Config> =
  listOf(
    LabelConfig(key = ConfigKeys.NAME),
    LabelConfig(key = ConfigKeys.MODEL_TYPE),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_MAX_TOKENS,
      sliderMin = 100f,
      sliderMax = 4096f,
      defaultValue = DEFAULT_MAX_TOKEN.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPK,
      sliderMin = 5f,
      sliderMax = 40f,
      defaultValue = DEFAULT_TOPK.toFloat(),
      valueType = ValueType.INT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TOPP,
      sliderMin = 0.0f,
      sliderMax = 1.0f,
      defaultValue = DEFAULT_TOPP,
      valueType = ValueType.FLOAT,
    ),
    NumberSliderConfig(
      key = ConfigKeys.DEFAULT_TEMPERATURE,
      sliderMin = 0.0f,
      sliderMax = 2.0f,
      defaultValue = DEFAULT_TEMPERATURE,
      valueType = ValueType.FLOAT,
    ),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_IMAGE, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_AUDIO, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_MOBILE_ACTIONS, defaultValue = false),
    BooleanSwitchConfig(key = ConfigKeys.SUPPORT_THINKING, defaultValue = false),
    SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = SUPPORTED_ACCELERATORS[0].label,
      options = SUPPORTED_ACCELERATORS.map { it.label },
      allowMultiple = true,
    ),
  )

@Composable
fun ModelImportDialog(
  uri: Uri,
  onDismiss: () -> Unit,
  onDone: (ImportedModel, Uri?) -> Unit,
  defaultValues: Map<ConfigKey, Any> = emptyMap(),
) {
  val context = LocalContext.current
  val info = remember { getFileSizeAndDisplayNameFromUri(context = context, uri = uri) }
  val fileSize by remember { mutableLongStateOf(info.first) }
  val fileName by remember { mutableStateOf(ensureValidFileName(info.second)) }
  val isGguf = fileName.endsWith(".gguf", ignoreCase = true)
  var projectorUri by remember { mutableStateOf<Uri?>(null) }
  var projectorDisplayName by remember { mutableStateOf("") }
  var projectorSize by remember { mutableLongStateOf(0L) }
  val projectorPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { selected ->
    if (selected != null) {
      val metadata = getFileSizeAndDisplayNameFromUri(context, selected)
      projectorUri = selected
      projectorDisplayName = metadata.second
      projectorSize = metadata.first
    }
  }
  val importConfigs = remember(isGguf) {
    if (!isGguf) IMPORT_CONFIGS_LLM else IMPORT_CONFIGS_LLM.filter { config ->
      config.key != ConfigKeys.COMPATIBLE_ACCELERATORS &&
        config.key != ConfigKeys.SUPPORT_IMAGE &&
        config.key != ConfigKeys.SUPPORT_AUDIO &&
        config.key != ConfigKeys.SUPPORT_MOBILE_ACTIONS &&
        config.key != ConfigKeys.SUPPORT_THINKING
    } + SegmentedButtonConfig(
      key = ConfigKeys.COMPATIBLE_ACCELERATORS,
      defaultValue = Accelerator.CPU.label,
      options = listOf(Accelerator.CPU.label),
      allowMultiple = false,
    )
  }

  val initialValues: Map<String, Any> = remember {
    mutableMapOf<String, Any>().apply {
      for (config in importConfigs) {
        put(config.key.label, config.defaultValue)
      }
      put(ConfigKeys.NAME.label, fileName)
      // TODO: support other types.
      put(ConfigKeys.MODEL_TYPE.label, "LLM")

      for ((key, value) in defaultValues) {
        put(key.label, value)
      }
    }
  }
  val values: SnapshotStateMap<String, Any> = remember {
    mutableStateMapOf<String, Any>().apply { putAll(initialValues) }
  }
  val interactionSource = remember { MutableInteractionSource() }

  Dialog(onDismissRequest = onDismiss) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth().clickable(
          interactionSource = interactionSource,
          indication = null, // Disable the ripple effect
        ) {
          focusManager.clearFocus()
        },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          "Import Model",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          // Default configs for users to set.
          ConfigEditorsPanel(configs = importConfigs, values = values)
          if (isGguf) {
            Text(
              "GGUF uses the CPU in this build. Image, audio, actions and thinking " +
                "will be enabled only after backend validation.",
              style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { projectorPicker.launch(arrayOf("*/*")) }) {
              Text(if (projectorUri == null) "Select matching mmproj GGUF" else "Change mmproj GGUF")
            }
            if (projectorUri != null) {
              Text(projectorDisplayName.ifBlank { "Selected projector" },
                style = MaterialTheme.typography.bodySmall)
              TextButton(onClick = {
                projectorUri = null
                projectorDisplayName = ""
                projectorSize = 0L
              }) { Text("Remove projector") }
            }
            if (projectorUri != null && !projectorDisplayName.endsWith(".gguf", ignoreCase = true)) {
              Text("Select a .gguf projector file", color = MaterialTheme.colorScheme.error)
            }
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          // Cancel button.
          TextButton(onClick = { onDismiss() }) { Text("Cancel") }

          // Import button
          Button(
            enabled = projectorUri == null || projectorDisplayName.endsWith(".gguf", ignoreCase = true),
            onClick = {
              val supportedAccelerators = if (isGguf) listOf(Accelerator.CPU.label) else
                (convertValueToTargetType(
                    value = values.get(ConfigKeys.COMPATIBLE_ACCELERATORS.label)!!,
                    valueType = ValueType.STRING,
                  )
                    as String)
                  .split(",")
              val defaultMaxTokens =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_MAX_TOKENS.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopk =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPK.label)!!,
                  valueType = ValueType.INT,
                )
                  as Int
              val defaultTopp =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TOPP.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val defaultTemperature =
                convertValueToTargetType(
                  value = values.get(ConfigKeys.DEFAULT_TEMPERATURE.label)!!,
                  valueType = ValueType.FLOAT,
                )
                  as Float
              val supportImage = if (isGguf) false else
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_IMAGE.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportAudio = if (isGguf) false else
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_AUDIO.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportMobileActions = if (isGguf) false else
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_MOBILE_ACTIONS.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val supportThinking = if (isGguf) false else
                convertValueToTargetType(
                  value = values.get(ConfigKeys.SUPPORT_THINKING.label)!!,
                  valueType = ValueType.BOOLEAN,
                )
                  as Boolean
              val importedModel: ImportedModel =
                ImportedModel.newBuilder()
                  .setFileName(fileName)
                  .setFileSize(fileSize)
                  .setLlmConfig(
                    LlmConfig.newBuilder()
                      .addAllCompatibleAccelerators(supportedAccelerators)
                      .setDefaultMaxTokens(defaultMaxTokens)
                      .setDefaultTopk(defaultTopk)
                      .setDefaultTopp(defaultTopp)
                      .setDefaultTemperature(defaultTemperature)
                      .setSupportImage(supportImage)
                      .setSupportAudio(supportAudio)
                      .setSupportMobileActions(supportMobileActions)
                      .setSupportThinking(supportThinking)
                      .setProjectorFileName(if (isGguf && projectorUri != null) "$fileName.mmproj.gguf" else "")
                      .setProjectorFileSize(if (isGguf && projectorUri != null) projectorSize else 0L)
                      .build()
                  )
                  .build()
              onDone(importedModel, projectorUri)
            }
          ) {
            Text("Import")
          }
        }
      }
    }
  }
}

@Composable
fun ModelImportingDialog(
  uri: Uri,
  info: ImportedModel,
  projectorUri: Uri?,
  onDismiss: () -> Unit,
  onDone: (ImportedModel) -> Unit,
) {
  var error by remember { mutableStateOf("") }
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var progress by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(Unit) {
    // Import.
    importModel(
      context = context,
      coroutineScope = coroutineScope,
      fileName = info.fileName,
      fileSize = info.fileSize,
      uri = uri,
      projectorUri = projectorUri,
      projectorFileName = info.llmConfig.projectorFileName,
      projectorFileSize = info.llmConfig.projectorFileSize,
      onDone = { onDone(info) },
      onProgress = { progress = it },
      onError = { error = it },
    )
  }

  Dialog(
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    onDismissRequest = onDismiss,
  ) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Title.
        Text(
          "Import Model",
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(bottom = 8.dp),
        )

        // No error.
        if (error.isEmpty()) {
          // Progress bar.
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${info.fileName} (${info.fileSize.humanReadableSize()})",
              style = MaterialTheme.typography.labelSmall,
            )
            val animatedProgress = remember { Animatable(0f) }
            LinearProgressIndicator(
              progress = { animatedProgress.value },
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            LaunchedEffect(progress) {
              animatedProgress.animateTo(progress, animationSpec = tween(150))
            }
          }
        }
        // Has error.
        else {
          Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Icon(
              Icons.Rounded.Error,
              contentDescription = stringResource(R.string.cd_error),
              tint = MaterialTheme.colorScheme.error,
            )
            Text(
              error,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = { onDismiss() }) { Text("Close") }
          }
        }
      }
    }
  }
}

private fun importModel(
  context: Context,
  coroutineScope: CoroutineScope,
  fileName: String,
  fileSize: Long,
  uri: Uri,
  projectorUri: Uri?,
  projectorFileName: String,
  projectorFileSize: Long,
  onDone: () -> Unit,
  onProgress: (Float) -> Unit,
  onError: (String) -> Unit,
) {
  coroutineScope.launch(Dispatchers.IO) {
    val staged = mutableListOf<Pair<File, File>>()
    val backups = mutableListOf<Pair<File, File>>()
    val committedNew = mutableListOf<File>()
    try {
      val baseDir = context.getExternalFilesDir(null) ?: throw IOException("App storage unavailable")
      val importsDir = File(baseDir, IMPORTS_DIR)
      if (!importsDir.isDirectory && !importsDir.mkdirs()) {
        throw IOException("Failed to create imports directory")
      }
      val sources = mutableListOf(Triple(uri, fileName, fileSize))
      if (projectorUri != null) {
        if (projectorFileName.isBlank()) throw IOException("Missing projector name")
        sources.add(Triple(projectorUri, projectorFileName, projectorFileSize))
      }
      val total = sources.sumOf { it.third.coerceAtLeast(0L) }
      if (total > 0 && importsDir.usableSpace < total) {
        throw IOException("Insufficient device storage")
      }
      var copiedTotal = 0L
      for ((sourceUri, targetName, expectedSize) in sources) {
        if (targetName.isBlank() || targetName == "." || targetName == ".." ||
            File(targetName).name != targetName) {
          throw IOException("Unsafe import file name")
        }
        val target = File(importsDir, targetName)
        val temp = File.createTempFile(".box-stage-", ".partial", importsDir)
        staged.add(temp to target)
        var copied = 0L
        val input = context.contentResolver.openInputStream(sourceUri)
          ?: throw IOException("Unable to open selected file")
        input.use { stream ->
          FileOutputStream(temp).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
              val n = stream.read(buffer)
              if (n < 0) break
              if (n == 0) continue
              output.write(buffer, 0, n)
              copied += n
              copiedTotal += n
              if (total > 0L) withContext(Dispatchers.Main) {
                onProgress((copiedTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
              }
            }
            output.fd.sync()
          }
        }
        if (copied == 0L || (expectedSize > 0L && copied != expectedSize)) {
          throw IOException("Incomplete imported file: " + targetName)
        }
      }
      // Stage all selected files before touching existing imports.
      for ((temp, target) in staged) {
        if (target.exists()) {
          val backup = File.createTempFile(".box-backup-", ".bak", importsDir)
          if (!backup.delete() || !target.renameTo(backup)) {
            throw IOException("Failed to back up existing import")
          }
          backups.add(backup to target)
        } else {
          committedNew.add(target)
        }
        if (!temp.renameTo(target)) throw IOException("Failed to commit imported file")
      }
      backups.forEach { (backup, _) -> backup.delete() }
      withContext(Dispatchers.Main) {
        onProgress(1f)
        onDone()
      }
    } catch (e: Exception) {
      committedNew.forEach { if (it.exists()) it.delete() }
      backups.asReversed().forEach { (backup, original) ->
        if (original.exists()) original.delete()
        backup.renameTo(original)
      }
      withContext(Dispatchers.Main) {
        Log.e(TAG, "Import failed", e)
        onError(e.message ?: "Failed to import")
      }
    } finally {
      staged.forEach { (temp, _) -> if (temp.exists()) temp.delete() }
    }
  }
}

private fun getFileSizeAndDisplayNameFromUri(context: Context, uri: Uri): Pair<Long, String> {
  val contentResolver = context.contentResolver
  var fileSize = 0L
  var displayName = ""

  try {
    contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (cursor.moveToFirst()) {
          val sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)
          fileSize = cursor.getLong(sizeIndex)

          val nameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
          displayName = cursor.getString(nameIndex)
        }
      }
  } catch (e: Exception) {
    e.printStackTrace()
    return Pair(0L, "")
  }

  return Pair(fileSize, displayName)
}
