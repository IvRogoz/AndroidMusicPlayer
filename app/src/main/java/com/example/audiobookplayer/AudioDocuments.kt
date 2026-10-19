package com.example.audiobookplayer

import androidx.documentfile.provider.DocumentFile

object AudioDocuments {
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "m4b")

    fun isAudioFile(document: DocumentFile): Boolean {
        if (document.type?.startsWith("audio/") == true) {
            return true
        }
        val extension = document.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in AUDIO_EXTENSIONS
    }
}
