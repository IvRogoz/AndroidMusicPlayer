package androidx.documentfile.provider

import android.net.Uri
import com.example.audiobookplayer.AudioDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDocumentsTest {
    @Test
    fun autoplayUsesTheSameSortedDepthFirstOrderAsTheLibrary() {
        val root = Document("root", children = listOf(
            Document("c.m4b"),
            Document("B", children = listOf(Document("2.mp3"), Document("1.mp3"))),
            Document("a.m4b"),
            Document("cover.jpg", mime = "image/jpeg")
        ))
        assertEquals(listOf("a.m4b", "1.mp3", "2.mp3", "c.m4b"),
            AudioDocuments.audioFiles(root).map { it.name })
    }

    @Test
    fun recognizesMislabeledAudiobooksAndExtensionlessAudio() {
        val root = Document("root", children = listOf(
            Document("book.M4B", mime = "application/octet-stream"),
            Document("recording", mime = "audio/mpeg"),
            Document("notes.txt", mime = "text/plain")
        ))
        assertEquals(listOf("book.M4B", "recording"), AudioDocuments.audioFiles(root).map { it.name })
    }

    @Test
    fun emptyFoldersDoNotProduceAutoplayEntries() {
        assertTrue(AudioDocuments.audioFiles(Document("root", children = listOf(
            Document("empty", children = emptyList())
        ))).isEmpty())
    }

    private class Document(
        private val title: String,
        private val mime: String = "application/octet-stream",
        private val children: List<Document>? = null
    ) : DocumentFile(null) {
        override fun getName(): String = title
        override fun getType(): String = mime
        override fun isDirectory(): Boolean = children != null
        override fun isFile(): Boolean = children == null
        override fun isVirtual(): Boolean = false
        override fun listFiles(): Array<DocumentFile> = children.orEmpty().toTypedArray()
        override fun getUri(): Uri = error("Traversal must not open documents or read their URIs")
        override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
        override fun createDirectory(displayName: String): DocumentFile? = null
        override fun lastModified(): Long = 0L
        override fun length(): Long = 0L
        override fun canRead(): Boolean = true
        override fun canWrite(): Boolean = false
        override fun delete(): Boolean = false
        override fun exists(): Boolean = true
        override fun renameTo(displayName: String): Boolean = false
    }
}
