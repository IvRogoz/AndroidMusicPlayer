package com.example.audiobookplayer.bookmarks

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

@Database(entities = [BookmarkEntity::class], version = 1)
abstract class BookmarkDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        @Volatile
        private var instance: BookmarkDatabase? = null

        fun getInstance(context: Context): BookmarkDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BookmarkDatabase::class.java,
                    "bookmarks.db"
                ).build().also { instance = it }
            }
        }
    }
}
