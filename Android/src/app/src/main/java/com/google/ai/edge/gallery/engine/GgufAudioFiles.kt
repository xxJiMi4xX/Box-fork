package com.google.ai.edge.gallery.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves an explicitly imported projector or a matching sidecar alongside the GGUF.
 * Never guesses between multiple incompatible mmproj files.
 */
object GgufAudioFiles {
    fun isGguf(modelPath: String): Boolean =
        modelPath.endsWith(".gguf", ignoreCase = true)

    private fun internalFile(context: Context, modelPath: String): File {
        val key = UUID.nameUUIDFromBytes(modelPath.toByteArray(StandardCharsets.UTF_8))
        return File(context.filesDir, "gguf-projectors/$key/mmproj.gguf")
    }

    private fun validProjector(file: File): Boolean {
        if (!file.isFile || file.length() < 32 || file.length() > 4L * 1024 * 1024 * 1024) {
            return false
        }
        return runCatching {
            val magic = ByteArray(4)
            file.inputStream().use { it.read(magic) == 4 &&
                magic.contentEquals(byteArrayOf(71, 71, 85, 70)) }
        }.getOrDefault(false)
    }

    fun findProjector(context: Context, modelPath: String): String? {
        if (!isGguf(modelPath)) return null
        val internal = internalFile(context, modelPath)
        if (validProjector(internal)) return internal.absolutePath

        // Never select an unrelated mmproj simply because it is the only file
        // in the directory. Quantization suffixes may legitimately differ.
        val model = File(modelPath)
        val directory = model.parentFile ?: return null
        val exact = File(directory, "mmproj-${model.name}")
        if (validProjector(exact)) return exact.absolutePath

        val isOmni = model.name.contains("LFM3-Omni-2B", ignoreCase = true)
        if (!isOmni) return null
        val family = directory.listFiles { file ->
            file.name.startsWith("mmproj-LFM3-Omni-2B-", ignoreCase = true) &&
                file.name.endsWith(".gguf", ignoreCase = true) &&
                validProjector(file)
        } ?: emptyArray()
        return family.singleOrNull()?.absolutePath
    }

    /** Imports the sidecar through Android's document provider to app-private storage. */
    suspend fun importProjector(context: Context, modelPath: String, document: Uri): String =
        withContext(Dispatchers.IO) {
            require(isGguf(modelPath)) { "A GGUF model must be selected first" }
            val destination = internalFile(context, modelPath)
            if (!destination.parentFile!!.isDirectory && !destination.parentFile!!.mkdirs()) {
                throw IOException("Cannot create the projector directory")
            }
            val temporary = File(destination.parentFile, "mmproj.download")
            try {
                val source = context.contentResolver.openInputStream(document)
                    ?: throw IOException("Cannot open the selected mmproj")
                source.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        val maximumBytes = 4L * 1024 * 1024 * 1024
                        var totalBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > maximumBytes) {
                                throw IOException("The selected projector exceeds the 4 GiB limit")
                            }
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                }
                if (temporary.length() < 32L) throw IOException("The selected mmproj is too small")
                val header = ByteArray(4)
                temporary.inputStream().use {
                    if (it.read(header) != 4 || !header.contentEquals(byteArrayOf(71, 71, 85, 70)))
                        throw IOException("Selected file is not a GGUF projector")
                }
                try {
                    Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING)
                }
                destination.absolutePath
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
}
