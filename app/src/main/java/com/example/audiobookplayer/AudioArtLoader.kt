package com.example.audiobookplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Log
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioArtLoader(context: Context) {

    private val context = context.applicationContext

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_SIZE) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)

    fun load(uri: Uri, targetSize: Int, onLoaded: (Bitmap?) -> Unit) {
        load(uri, targetSize, retry = true, onLoaded = onLoaded)
    }

    private fun load(uri: Uri, targetSize: Int, retry: Boolean, onLoaded: (Bitmap?) -> Unit) {
        if (closed.get()) {
            return
        }
        val cacheKey = buildCacheKey(uri, targetSize)
        val cachedBitmap = cache.get(cacheKey)
        if (cachedBitmap != null) {
            onLoaded(cachedBitmap)
            return
        }
        try {
            executor.execute {
                val bitmap = loadEmbeddedArt(uri, targetSize)
                if (bitmap != null) {
                    cache.put(cacheKey, bitmap)
                }
                mainHandler.post {
                    if (!closed.get()) {
                        if (bitmap == null && retry) {
                            mainHandler.postDelayed({
                                load(uri, targetSize, retry = false, onLoaded = onLoaded)
                            }, 250L)
                        } else {
                            onLoaded(bitmap)
                        }
                    }
                }
            }
        } catch (exception: RejectedExecutionException) {
            Log.d(TAG, "Ignored artwork request after loader shutdown", exception)
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            mainHandler.removeCallbacksAndMessages(null)
            executor.shutdownNow()
            cache.evictAll()
        }
    }

    private fun loadEmbeddedArt(uri: Uri, targetSize: Int): Bitmap? {
        return try {
            val art = CoverArtProvider.readArtwork(context, uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(art, 0, art.size, options)
            options.inSampleSize = BitmapUtils.calculateInSampleSize(options, targetSize, targetSize)
            options.inJustDecodeBounds = false
            BitmapFactory.decodeByteArray(art, 0, art.size, options)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to read embedded artwork from $uri", exception)
            null
        }
    }

    private fun buildCacheKey(uri: Uri, targetSize: Int): String {
        return "${uri}_$targetSize"
    }

    companion object {
        private const val MAX_CACHE_SIZE = 4 * 1024 * 1024
        private const val TAG = "AudioArtLoader"
    }
}
