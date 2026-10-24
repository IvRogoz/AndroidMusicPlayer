package com.example.audiobookplayer

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArtworkCacheInstrumentedTest {
    @Test
    fun newLoaderCanDisplayPersistentArtworkWhenTheAudioSourceIsUnavailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uri = Uri.parse("content://unavailable.artwork.test/${System.nanoTime()}")
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        image.recycle()
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cacheFile = File(context.cacheDir, "media_cover_art/$key")
        val loader = AudioArtLoader(context)
        try {
            CoverArtProvider.cacheArtwork(context, uri, bytes)
            assertArrayEquals(bytes, CoverArtProvider.readArtwork(context, uri))
            val callback = CountDownLatch(1)
            var decoded = false
            instrumentation.runOnMainSync {
                loader.load(uri, 32) {
                    decoded = it?.width == 32 && it.height == 32
                    callback.countDown()
                }
            }
            assertTrue("Artwork callback did not arrive", callback.await(5, TimeUnit.SECONDS))
            assertTrue("Cached artwork was not decoded", decoded)
        } finally {
            loader.close()
            cacheFile.delete()
        }
    }
}
