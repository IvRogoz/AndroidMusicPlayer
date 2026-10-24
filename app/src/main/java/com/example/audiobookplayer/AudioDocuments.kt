package com.example.audiobookplayer

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object AudioDocuments {
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "m4b")

    fun sortedChildren(directory: DocumentFile): List<DocumentFile> =
        directory.listFiles().sortedBy { it.name?.lowercase(Locale.getDefault()) }

    fun audioFiles(directory: DocumentFile): List<DocumentFile> = sortedChildren(directory).flatMap {
        when {
            it.isDirectory -> audioFiles(it)
            isAudioFile(it) -> listOf(it)
            else -> emptyList()
        }
    }

    fun isAudioFile(document: DocumentFile): Boolean {
        if (document.type?.startsWith("audio/") == true) {
            return true
        }
        val extension = document.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in AUDIO_EXTENSIONS
    }
}
