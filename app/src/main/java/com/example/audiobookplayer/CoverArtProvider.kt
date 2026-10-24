package com.example.audiobookplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class CoverArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/*"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Cover artwork is read-only")
        }
        val appContext = context?.applicationContext
            ?: throw FileNotFoundException("Provider context is unavailable")
        val key = uri.lastPathSegment ?: throw FileNotFoundException("Missing artwork key")
        val sourceUri = registeredSources[key]
            ?: appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
            ?: throw FileNotFoundException("Unknown artwork key")
        readArtwork(appContext, Uri.parse(sourceUri))
            ?: throw FileNotFoundException("Audio file has no readable artwork")
        val artworkFile = File(File(appContext.cacheDir, CACHE_DIRECTORY), key)
        return ParcelFileDescriptor.open(artworkFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val PREFS_NAME = "cover_art_sources"
        private const val CACHE_DIRECTORY = "media_cover_art"
        private val registeredSources = ConcurrentHashMap<String, String>()
        private val fileLock = Any()

        fun readArtwork(context: Context, sourceUri: Uri): ByteArray? {
            return readCachedArtwork(context, sourceUri) ?: EmbeddedAudioMetadata.readArtwork(context, sourceUri)
        }

        fun readCachedArtwork(context: Context, sourceUri: Uri): ByteArray? {
            val file = File(File(context.cacheDir, CACHE_DIRECTORY), sha256(sourceUri.toString()))
            val cached = synchronized(fileLock) {
                runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull()
            }
            return cached?.takeIf { isImage(it) }
        }

        fun cacheArtwork(context: Context, sourceUri: Uri, artwork: ByteArray) {
            if (!isImage(artwork)) return
            synchronized(fileLock) {
                val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
                val file = File(directory, sha256(sourceUri.toString()))
                val temporary = File(directory, "${file.name}.tmp")
                try {
                    temporary.outputStream().use { it.write(artwork) }
                    if (!temporary.renameTo(file)) {
                        temporary.copyTo(file, overwrite = true)
                    }
                } catch (exception: Exception) {
                    android.util.Log.w("CoverArtProvider", "Unable to cache artwork for $sourceUri", exception)
                } finally {
                    temporary.delete()
                }
            }
        }

        private fun isImage(data: ByteArray): Boolean {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            return bounds.outWidth > 0 && bounds.outHeight > 0
        }

        fun artworkUri(context: Context, sourceUri: Uri): Uri {
            val source = sourceUri.toString()
            val key = sha256(source)
            registeredSources[key] = source
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, source)
                .apply()
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.coverart")
                .appendPath(key)
                .build()
        }

        internal fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
