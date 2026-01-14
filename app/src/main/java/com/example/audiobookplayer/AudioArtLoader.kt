package com.example.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

class AudioArtLoader(private val context: Context) {

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(uri: Uri, targetSize: Int, onLoaded: (Bitmap?) -> Unit) {
        val cacheKey = buildCacheKey(uri, targetSize)
        val cachedBitmap = cache.get(cacheKey)
        if (cachedBitmap != null) {
            onLoaded(cachedBitmap)
            return
        }
        executor.execute {
            val bitmap = loadEmbeddedArt(uri, targetSize)
            if (bitmap != null) {
                cache.put(cacheKey, bitmap)
            }
            mainHandler.post {
                onLoaded(bitmap)
            }
        }
    }

    private fun loadEmbeddedArt(uri: Uri, targetSize: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val art = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(art, 0, art.size, options)
            options.inSampleSize = calculateInSampleSize(options, targetSize, targetSize)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(art, 0, art.size, options)
        } catch (exception: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun buildCacheKey(uri: Uri, targetSize: Int): String {
        return "${uri}_$targetSize"
    }

    companion object {
        private const val MAX_CACHE_SIZE = 4 * 1024 * 1024
    }
}
