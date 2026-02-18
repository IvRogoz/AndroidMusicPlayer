package com.example.audiobookplayer.bookmarks

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BookmarkDao {
    @Insert
    fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks ORDER BY createdAtMs DESC")
    fun getAll(): List<BookmarkEntity>
}
