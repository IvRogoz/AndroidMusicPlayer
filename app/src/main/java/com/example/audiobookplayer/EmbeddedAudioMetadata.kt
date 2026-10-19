package com.example.audiobookplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

data class EmbeddedAudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val author: String?,
    val genre: String?,
    val trackNumber: Long?,
    val artwork: ByteArray?
) {
    companion object {
        fun read(context: Context, uri: Uri): EmbeddedAudioMetadata? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                EmbeddedAudioMetadata(
                    title = retriever.value(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.value(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.value(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    album = retriever.value(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    author = retriever.value(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                    genre = retriever.value(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    trackNumber = retriever.value(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.substringBefore('/')
                        ?.toLongOrNull(),
                    artwork = retriever.embeddedPicture
                )
            } catch (exception: Exception) {
                Log.w(TAG, "Unable to read embedded metadata from $uri", exception)
                null
            } finally {
                retriever.release()
            }
        }

        fun readArtwork(context: Context, uri: Uri): ByteArray? = read(context, uri)?.artwork

        private fun MediaMetadataRetriever.value(key: Int): String? {
            return extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        }

        private const val TAG = "EmbeddedAudioMetadata"
    }
}
