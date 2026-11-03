package com.google.ai.edge.gallery.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
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

    fun findProjector(context: Context, modelPath: String): String? {
        if (!isGguf(modelPath)) return null
        val internal = internalFile(context, modelPath)
        if (internal.isFile && internal.length() > 0) return internal.absolutePath

        val model = File(modelPath)
        val directory = model.parentFile ?: return null
        val exact = File(directory, "mmproj-${model.name}")
        if (exact.isFile && exact.length() > 0) return exact.absolutePath

        val projectors = directory.listFiles { file ->
            file.isFile && file.length() > 0 &&
                file.name.startsWith("mmproj", ignoreCase = true) &&
                file.name.endsWith(".gguf", ignoreCase = true)
        } ?: emptyArray()
        return projectors.singleOrNull()?.absolutePath
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
                        input.copyTo(output, 256 * 1024)
                    }
                }
                if (temporary.length() == 0L) throw IOException("The mmproj file is empty")
                if (destination.exists() && !destination.delete())
                    throw IOException("Cannot replace the old projector")
                if (!temporary.renameTo(destination))
                    throw IOException("Cannot finalize projector import")
                destination.absolutePath
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
}
