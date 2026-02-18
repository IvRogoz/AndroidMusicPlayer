package com.example.audiobookplayer.bookmarks

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookTitle: String,
    val trackUri: String,
    val timestampMs: Long,
    val createdAtMs: Long,
    val clipDurationMs: Long?,
    val audioClipPath: String?,
    val coverImagePath: String?
)
