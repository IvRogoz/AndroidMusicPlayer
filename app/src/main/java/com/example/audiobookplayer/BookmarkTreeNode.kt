package com.example.audiobookplayer

import com.example.audiobookplayer.bookmarks.BookmarkEntity

data class BookmarkTreeNode(
    val id: String,
    val title: String,
    val depth: Int,
    val isFolder: Boolean,
    val coverImagePath: String? = null,
    val bookmark: BookmarkEntity? = null,
    val children: List<BookmarkTreeNode> = emptyList()
)
