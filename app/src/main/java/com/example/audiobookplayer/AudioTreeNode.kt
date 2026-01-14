package com.example.audiobookplayer

import android.net.Uri

data class AudioTreeNode(
    val uri: Uri,
    val title: String,
    val depth: Int,
    val isFolder: Boolean,
    val audioFile: AudioFile? = null,
    val children: List<AudioTreeNode> = emptyList()
)
