package com.example.audiobookplayer

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import com.example.audiobookplayer.bookmarks.BookmarkDatabase
import com.example.audiobookplayer.bookmarks.BookmarkEntity
import com.example.audiobookplayer.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var treeAdapter: AudioTreeAdapter
    private lateinit var bookmarkAdapter: BookmarkTreeAdapter
    private lateinit var artLoader: AudioArtLoader
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val bookmarkDatabase by lazy {
        Room.databaseBuilder(applicationContext, BookmarkDatabase::class.java, "bookmarks.db").build()
    }
    private val progressHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var currentFile: AudioFile? = null
    private var audioFiles: List<AudioFile> = emptyList()
    private var currentFileIndex: Int? = null
    private var skipAmountMs: Long = DEFAULT_SKIP_MS
    private var treeRoots: List<AudioTreeNode> = emptyList()
    private val expandedFolders = mutableSetOf<Uri>()
    private var bookmarkRoots: List<BookmarkTreeNode> = emptyList()
    private val expandedBookmarkIds = mutableSetOf<String>()
    private var currentCoverBitmap: Bitmap? = null
    private var pendingBookmarkSeekMs: Long? = null
    private var pendingBookmarkAutoPlay = false
    private var bookmarkPlayer: MediaPlayer? = null
    private var playingBookmarkId: Long? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previousUri = preferences.getString(KEY_TREE_URI, null)
            if (previousUri != uri.toString()) {
                expandedFolders.clear()
            }
            preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
            loadAudioTree(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        artLoader = AudioArtLoader(this)
        treeAdapter = AudioTreeAdapter(artLoader) { node ->
            if (node.isFolder) {
                toggleFolder(node)
            } else {
                node.audioFile?.let {
                    selectTrack(it, restorePosition = true, autoPlay = true)
                    closeDrawer()
                }
            }
        }
        bookmarkAdapter = BookmarkTreeAdapter(
            onNodeSelected = { node ->
                if (node.isFolder) {
                    toggleBookmarkFolder(node)
                } else {
                    node.bookmark?.let {
                        openBookmark(it)
                    }
                }
            },
            onClipToggle = { bookmark ->
                toggleBookmarkClip(bookmark)
            }
        )

        binding.folderTree.layoutManager = LinearLayoutManager(this)
        binding.folderTree.adapter = treeAdapter
        binding.bookmarkList.layoutManager = LinearLayoutManager(this)
        binding.bookmarkList.adapter = bookmarkAdapter

        skipAmountMs = preferences.getLong(KEY_SKIP_MS, DEFAULT_SKIP_MS)
        updateSkipButtonLabels()

        binding.selectFolderButton.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        binding.openDrawerButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.openBookmarksButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }

        binding.playPauseButton.setOnClickListener {
            togglePlayback()
        }

        binding.stopButton.setOnClickListener {
            stopPlayback()
        }

        binding.previousButton.setOnClickListener {
            playPreviousTrack()
        }

        binding.nextButton.setOnClickListener {
            playNextTrack()
        }

        binding.skipBackButton.setOnClickListener {
            skipBy(-skipAmountMs)
        }

        binding.timeJumpButton.setOnClickListener {
            showTimeJumpDialog()
        }

        binding.skipForwardButton.setOnClickListener {
            skipBy(skipAmountMs)
        }

        binding.skipBackButton.setOnLongClickListener {
            showSkipMenu(it)
            true
        }

        binding.skipForwardButton.setOnLongClickListener {
            showSkipMenu(it)
            true
        }

        binding.autoplaySwitch.isChecked = preferences.getBoolean(KEY_AUTOPLAY, false)
        binding.autoplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(KEY_AUTOPLAY, isChecked).apply()
        }

        setupBookmarkActions()
        setupPreviewScrub()
        clearSelection()
        restoreLastFolder()
        refreshBookmarks()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackState()
        releasePlayer()
        stopBookmarkClipPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun restoreLastFolder() {
        val uriString = preferences.getString(KEY_TREE_URI, null)
        if (uriString != null) {
            loadAudioTree(Uri.parse(uriString))
        } else {
            binding.folderLabel.text = getString(R.string.no_folder_selected)
        }
    }

    private fun loadAudioTree(treeUri: Uri) {
        val root = DocumentFile.fromTreeUri(this, treeUri)
        binding.folderLabel.text = root?.name ?: getString(R.string.no_folder_selected)
        ioExecutor.execute {
            val audioItems = mutableListOf<AudioFile>()
            val rootNodes = if (root != null) {
                buildTreeNodes(root, 0, audioItems)
            } else {
                emptyList()
            }
            val lastTrackUri = preferences.getString(KEY_LAST_TRACK_URI, null)
            runOnUiThread {
                treeRoots = rootNodes
                updateTreeList()
                audioFiles = audioItems
                val lastTrack = audioItems.firstOrNull { it.uri.toString() == lastTrackUri }
                if (lastTrack != null) {
                    selectTrack(lastTrack, restorePosition = true, autoPlay = false)
                } else {
                    clearSelection()
                }
            }
        }
    }

    private fun buildTreeNodes(
        folder: DocumentFile,
        depth: Int,
        audioItems: MutableList<AudioFile>
    ): List<AudioTreeNode> {
        val children = folder.listFiles().sortedBy { it.name?.lowercase(Locale.getDefault()) }
        return children.mapNotNull { file ->
            when {
                file.isDirectory -> {
                    AudioTreeNode(
                        uri = file.uri,
                        title = file.name ?: getString(R.string.unknown_track),
                        depth = depth,
                        isFolder = true,
                        children = buildTreeNodes(file, depth + 1, audioItems)
                    )
                }
                isAudioFile(file) -> {
                    val audioFile = buildAudioFile(file)
                    audioItems.add(audioFile)
                    AudioTreeNode(
                        uri = file.uri,
                        title = audioFile.title,
                        depth = depth,
                        isFolder = false,
                        audioFile = audioFile
                    )
                }
                else -> null
            }
        }
    }

    private fun updateTreeList() {
        val visibleNodes = flattenTree(treeRoots, expandedFolders)
        treeAdapter.expandedUris = expandedFolders.toSet()
        treeAdapter.submitList(visibleNodes)
    }

    private fun flattenTree(nodes: List<AudioTreeNode>, expanded: Set<Uri>): List<AudioTreeNode> {
        val visible = mutableListOf<AudioTreeNode>()
        nodes.forEach { node ->
            visible.add(node)
            if (node.isFolder && expanded.contains(node.uri)) {
                visible.addAll(flattenTree(node.children, expanded))
            }
        }
        return visible
    }

    private fun toggleFolder(node: AudioTreeNode) {
        if (expandedFolders.contains(node.uri)) {
            expandedFolders.remove(node.uri)
        } else {
            expandedFolders.add(node.uri)
        }
        updateTreeList()
    }

    private fun refreshBookmarks() {
        ioExecutor.execute {
            val bookmarks = bookmarkDatabase.bookmarkDao().getAll()
            val roots = buildBookmarkTree(bookmarks)
            val rootIds = roots.map { it.id }.toSet()
            runOnUiThread {
                expandedBookmarkIds.retainAll(rootIds)
                bookmarkRoots = roots
                updateBookmarkList()
            }
        }
    }

    private fun buildBookmarkTree(bookmarks: List<BookmarkEntity>): List<BookmarkTreeNode> {
        val grouped = bookmarks.groupBy { it.trackUri }
        return grouped.map { (trackUri, items) ->
            val sortedItems = items.sortedBy { it.timestampMs }
            val bookTitle = sortedItems.firstOrNull()?.bookTitle?.ifBlank { trackUri } ?: trackUri
            val coverPath = sortedItems.firstOrNull { it.coverImagePath != null }?.coverImagePath
            val children = sortedItems.map { bookmark ->
                BookmarkTreeNode(
                    id = "bookmark:${bookmark.id}",
                    title = formatBookmarkTitle(bookmark),
                    depth = 1,
                    isFolder = false,
                    bookmark = bookmark
                )
            }
            BookmarkTreeNode(
                id = "book:$trackUri",
                title = bookTitle,
                depth = 0,
                isFolder = true,
                coverImagePath = coverPath,
                children = children
            )
        }.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    private fun updateBookmarkList() {
        val visibleNodes = flattenBookmarkTree(bookmarkRoots, expandedBookmarkIds)
        bookmarkAdapter.expandedIds = expandedBookmarkIds
        bookmarkAdapter.playingBookmarkId = playingBookmarkId
        bookmarkAdapter.submitList(visibleNodes)
    }

    private fun flattenBookmarkTree(
        nodes: List<BookmarkTreeNode>,
        expandedIds: Set<String>
    ): List<BookmarkTreeNode> {
        val visible = mutableListOf<BookmarkTreeNode>()
        nodes.forEach { node ->
            visible.add(node)
            if (node.isFolder && expandedIds.contains(node.id)) {
                visible.addAll(node.children)
            }
        }
        return visible
    }

    private fun toggleBookmarkFolder(node: BookmarkTreeNode) {
        if (expandedBookmarkIds.contains(node.id)) {
            expandedBookmarkIds.remove(node.id)
        } else {
            expandedBookmarkIds.add(node.id)
        }
        updateBookmarkList()
    }

    private fun openBookmark(bookmark: BookmarkEntity) {
        val trackUri = Uri.parse(bookmark.trackUri)
        val audioFile = audioFiles.firstOrNull { it.uri == trackUri }
        if (audioFile == null) {
            Toast.makeText(this, getString(R.string.bookmark_missing_track), Toast.LENGTH_SHORT).show()
            return
        }
        val targetMs = bookmark.timestampMs.coerceAtLeast(0L)
        if (currentFile?.uri == trackUri && isPrepared) {
            val player = mediaPlayer
            if (player != null) {
                val durationMs = player.duration.toLong().coerceAtLeast(0L)
                val clamped = targetMs.coerceIn(0L, durationMs)
                player.seekTo(clamped.toInt())
                if (!player.isPlaying) {
                    player.start()
                    startProgressUpdates()
                }
                updateProgress()
                updatePlayPauseButton()
            }
            pendingBookmarkSeekMs = null
            pendingBookmarkAutoPlay = false
        } else {
            pendingBookmarkSeekMs = targetMs
            pendingBookmarkAutoPlay = true
            selectTrack(audioFile, restorePosition = false, autoPlay = false, useBookmarkSeek = true)
        }
        closeBookmarksDrawer()
    }

    private fun toggleBookmarkClip(bookmark: BookmarkEntity) {
        val clipPath = bookmark.audioClipPath
        if (clipPath.isNullOrBlank()) {
            return
        }
        val isPlaying = playingBookmarkId == bookmark.id
        if (isPlaying) {
            stopBookmarkClipPlayback()
        } else {
            startBookmarkClipPlayback(bookmark)
        }
    }

    private fun startBookmarkClipPlayback(bookmark: BookmarkEntity) {
        stopBookmarkClipPlayback()
        val clipPath = bookmark.audioClipPath ?: return
        val player = MediaPlayer()
        bookmarkPlayer = player
        playingBookmarkId = bookmark.id
        updateBookmarkList()
        try {
            player.setDataSource(clipPath)
            player.setOnCompletionListener {
                stopBookmarkClipPlayback()
            }
            player.prepare()
            player.start()
        } catch (exception: Exception) {
            stopBookmarkClipPlayback()
        }
    }

    private fun stopBookmarkClipPlayback() {
        bookmarkPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (exception: Exception) {
            }
            try {
                player.release()
            } catch (exception: Exception) {
            }
        }
        bookmarkPlayer = null
        if (playingBookmarkId != null) {
            playingBookmarkId = null
            updateBookmarkList()
        }
    }

    private fun formatBookmarkTitle(bookmark: BookmarkEntity): String {
        val timestamp = formatDuration(bookmark.timestampMs)
        val clipDurationMs = bookmark.clipDurationMs
        return if (clipDurationMs != null && clipDurationMs > 0L) {
            "$timestamp (+${clipDurationMs / 1000}s)"
        } else {
            timestamp
        }
    }

    private fun isAudioFile(file: DocumentFile): Boolean {
        val type = file.type
        if (type != null && type.startsWith("audio/")) {
            return true
        }
        val name = file.name ?: return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return extension in AUDIO_EXTENSIONS
    }

    private fun buildAudioFile(file: DocumentFile): AudioFile {
        val title = file.name ?: getString(R.string.unknown_track)
        val duration = readDuration(file.uri)
        return AudioFile(file.uri, title, duration)
    }

    private fun readDuration(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (exception: Exception) {
            0L
        } finally {
            retriever.release()
        }
    }

    private fun selectTrack(
        audioFile: AudioFile,
        restorePosition: Boolean,
        autoPlay: Boolean,
        useBookmarkSeek: Boolean = false
    ) {
        if (!useBookmarkSeek) {
            pendingBookmarkSeekMs = null
            pendingBookmarkAutoPlay = false
        }
        if (currentFile?.uri == audioFile.uri) {
            if (autoPlay) {
                togglePlayback()
            }
            return
        }
        savePlaybackState()
        currentFile = audioFile
        currentFileIndex = audioFiles.indexOfFirst { it.uri == audioFile.uri }.takeIf { it >= 0 }
        treeAdapter.selectedUri = audioFile.uri
        binding.bookmarkButton.isEnabled = true
        binding.trackTitle.text = audioFile.title
        binding.previewView.setWaveformSeed(audioFile.uri.hashCode())
        binding.previewView.setProgress(0f)
        binding.currentTime.text = formatDuration(0L)
        binding.duration.text = formatDuration(audioFile.durationMs)
        binding.previewView.isEnabled = false
        binding.playPauseButton.isEnabled = false
        binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        binding.playPauseButton.contentDescription = getString(R.string.play_button)
        binding.stopButton.isEnabled = false
        updateTrackNavigationButtons()
        binding.skipBackButton.isEnabled = false
        binding.timeJumpButton.isEnabled = false
        binding.skipForwardButton.isEnabled = false
        loadAlbumArt(audioFile)
        preparePlayer(audioFile, restorePosition, autoPlay)
    }

    private fun clearSelection() {
        currentFile = null
        currentFileIndex = null
        treeAdapter.selectedUri = null
        binding.trackTitle.text = getString(R.string.no_track_selected)
        binding.albumArtLarge.setImageResource(android.R.drawable.ic_media_play)
        binding.backgroundArt.setImageDrawable(null)
        binding.backgroundArt.alpha = 0f
        binding.backgroundTint.alpha = 0f
        binding.bookmarkButton.isEnabled = false
        currentCoverBitmap = null
        pendingBookmarkSeekMs = null
        pendingBookmarkAutoPlay = false
        binding.currentTime.text = formatDuration(0L)
        binding.duration.text = formatDuration(0L)
        binding.previewView.isEnabled = false
        binding.playPauseButton.isEnabled = false
        binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        binding.playPauseButton.contentDescription = getString(R.string.play_button)
        binding.stopButton.isEnabled = false
        binding.previousButton.isEnabled = false
        binding.nextButton.isEnabled = false
        binding.skipBackButton.isEnabled = false
        binding.timeJumpButton.isEnabled = false
        binding.skipForwardButton.isEnabled = false
        binding.previewView.setProgress(0f)
    }

    private fun loadAlbumArt(audioFile: AudioFile) {
        binding.albumArtLarge.setImageResource(android.R.drawable.ic_media_play)
        binding.backgroundArt.setImageDrawable(null)
        binding.backgroundArt.alpha = 0f
        binding.backgroundTint.alpha = 0f
        currentCoverBitmap = null
        artLoader.load(audioFile.uri, LARGE_ART_SIZE) { bitmap ->
            if (audioFile.uri == currentFile?.uri) {
                if (bitmap != null) {
                    binding.albumArtLarge.setImageBitmap(bitmap)
                    binding.backgroundArt.setImageBitmap(bitmap)
                    binding.backgroundArt.alpha = BACKGROUND_ART_ALPHA_WITH_ART
                    binding.backgroundTint.alpha = BACKGROUND_TINT_ALPHA_WITH_ART
                    currentCoverBitmap = bitmap
                } else {
                    binding.albumArtLarge.setImageResource(android.R.drawable.ic_media_play)
                    binding.backgroundArt.setImageDrawable(null)
                    binding.backgroundArt.alpha = 0f
                    binding.backgroundTint.alpha = 0f
                    currentCoverBitmap = null
                }
            }
        }
    }

    private fun preparePlayer(audioFile: AudioFile, restorePosition: Boolean, autoPlay: Boolean) {
        releasePlayer()
        val player = MediaPlayer()
        mediaPlayer = player
        isPrepared = false
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.setOnPreparedListener { preparedPlayer ->
            isPrepared = true
            val duration = preparedPlayer.duration
            binding.duration.text = formatDuration(duration.toLong())
            val pendingSeekMs = pendingBookmarkSeekMs
            if (pendingSeekMs == null && restorePosition &&
                audioFile.uri.toString() == preferences.getString(KEY_LAST_TRACK_URI, null)
            ) {
                val resumePosition = preferences.getLong(KEY_LAST_POSITION, 0L).toInt()
                if (resumePosition in 1 until duration) {
                    preparedPlayer.seekTo(resumePosition)
                }
            }
            binding.previewView.isEnabled = true
            binding.playPauseButton.isEnabled = true
            binding.stopButton.isEnabled = true
            binding.skipBackButton.isEnabled = true
            binding.timeJumpButton.isEnabled = true
            binding.skipForwardButton.isEnabled = true
            updateTrackNavigationButtons()
            if (pendingSeekMs != null) {
                val clampedSeek = pendingSeekMs.coerceIn(0L, duration.toLong())
                preparedPlayer.seekTo(clampedSeek.toInt())
            }
            val shouldAutoPlay = if (pendingSeekMs != null) {
                pendingBookmarkAutoPlay
            } else {
                autoPlay
            }
            if (shouldAutoPlay) {
                preparedPlayer.start()
                startProgressUpdates()
            }
            pendingBookmarkSeekMs = null
            pendingBookmarkAutoPlay = false
            updateProgress()
            updatePlayPauseButton()
        }
        player.setOnCompletionListener {
            savePlaybackState(true)
            stopProgressUpdates()
            if (binding.autoplaySwitch.isChecked) {
                playNextTrack()
            } else {
                mediaPlayer?.seekTo(0)
                updateProgress()
                updatePlayPauseButton()
            }
        }
        player.setOnErrorListener { _, _, _ ->
            releasePlayer()
            true
        }
        player.setDataSource(this, audioFile.uri)
        player.prepareAsync()
    }

    private fun togglePlayback() {
        val player = mediaPlayer
        if (player == null || !isPrepared) {
            return
        }
        if (player.isPlaying) {
            player.pause()
            stopProgressUpdates()
            updateProgress()
        } else {
            player.start()
            startProgressUpdates()
        }
        updatePlayPauseButton()
    }

    private fun stopPlayback() {
        val player = mediaPlayer ?: return
        if (!isPrepared) {
            return
        }
        player.pause()
        player.seekTo(0)
        stopProgressUpdates()
        updateProgress()
        updatePlayPauseButton()
        savePlaybackState(true)
    }

    private fun playNextTrack() {
        val nextIndex = currentFileIndex?.plus(1) ?: return
        if (nextIndex in audioFiles.indices) {
            selectTrack(audioFiles[nextIndex], restorePosition = false, autoPlay = true)
        } else {
            updateProgress()
            updatePlayPauseButton()
        }
    }

    private fun playPreviousTrack() {
        val previousIndex = currentFileIndex?.minus(1) ?: return
        if (previousIndex in audioFiles.indices) {
            selectTrack(audioFiles[previousIndex], restorePosition = false, autoPlay = true)
        } else {
            mediaPlayer?.seekTo(0)
            updateProgress()
            updatePlayPauseButton()
        }
    }

    private fun skipBy(deltaMs: Long) {
        val player = mediaPlayer ?: return
        if (!isPrepared) {
            return
        }
        val current = player.currentPosition.toLong()
        val duration = player.duration.toLong()
        val target = (current + deltaMs).coerceIn(0, duration)
        player.seekTo(target.toInt())
        updateProgress()
    }

    private fun updateSkipButtonLabels() {
        val label = formatSkipLabel(skipAmountMs)
        binding.skipBackButton.contentDescription = getString(R.string.skip_back_format, label)
        binding.skipForwardButton.contentDescription = getString(R.string.skip_forward_format, label)
    }

    private fun showSkipMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        SKIP_OPTIONS_MS.forEach { option ->
            popup.menu.add(0, option.toInt(), 0, formatSkipLabel(option))
        }
        popup.setOnMenuItemClickListener { item ->
            skipAmountMs = item.itemId.toLong()
            preferences.edit().putLong(KEY_SKIP_MS, skipAmountMs).apply()
            updateSkipButtonLabels()
            true
        }
        popup.show()
    }

    private fun setupBookmarkActions() {
        binding.bookmarkButton.setOnClickListener {
            saveBookmark(null)
        }
        binding.bookmarkButton.setOnLongClickListener { view ->
            showBookmarkClipMenu(view)
            true
        }
    }

    private fun showBookmarkClipMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        BOOKMARK_CLIP_OPTIONS_MS.forEach { option ->
            popup.menu.add(0, option.toInt(), 0, formatClipLabel(option))
        }
        popup.setOnMenuItemClickListener { item ->
            saveBookmark(item.itemId.toLong())
            true
        }
        popup.show()
    }

    private fun formatClipLabel(milliseconds: Long): String {
        return "${milliseconds / 1000}s"
    }

    private fun saveBookmark(clipDurationMs: Long?) {
        val audioFile = currentFile
        if (audioFile == null) {
            Toast.makeText(this, getString(R.string.bookmark_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val positionMs = if (isPrepared) {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } else {
            0L
        }
        val createdAtMs = System.currentTimeMillis()
        val coverBitmap = currentCoverBitmap
        val availableDurationMs = if (audioFile.durationMs > 0L) {
            (audioFile.durationMs - positionMs).coerceAtLeast(0L)
        } else {
            null
        }
        val resolvedClipDurationMs = clipDurationMs?.let { duration ->
            availableDurationMs?.let { minOf(duration, it) } ?: duration
        }
        ioExecutor.execute {
            val coverPath = saveCoverBitmap(coverBitmap, createdAtMs)
            val clipPath = if (resolvedClipDurationMs != null && resolvedClipDurationMs > 0L) {
                saveAudioClip(audioFile.uri, positionMs, resolvedClipDurationMs, createdAtMs)
            } else {
                null
            }
            val storedClipDurationMs = if (clipPath != null) resolvedClipDurationMs else null
            val bookmark = BookmarkEntity(
                bookTitle = audioFile.title,
                trackUri = audioFile.uri.toString(),
                timestampMs = positionMs,
                createdAtMs = createdAtMs,
                clipDurationMs = storedClipDurationMs,
                audioClipPath = clipPath,
                coverImagePath = coverPath
            )
            bookmarkDatabase.bookmarkDao().insert(bookmark)
            val updatedRoots = buildBookmarkTree(bookmarkDatabase.bookmarkDao().getAll())
            val updatedRootIds = updatedRoots.map { it.id }.toSet()
            val messageRes = when {
                clipDurationMs == null -> R.string.bookmark_saved
                clipPath != null -> R.string.bookmark_saved_clip
                else -> R.string.bookmark_saved_clip_failed
            }
            runOnUiThread {
                expandedBookmarkIds.retainAll(updatedRootIds)
                bookmarkRoots = updatedRoots
                updateBookmarkList()
                Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCoverBitmap(bitmap: Bitmap?, createdAtMs: Long): String? {
        if (bitmap == null) {
            return null
        }
        val directory = File(filesDir, "bookmarks/covers")
        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }
        val coverFile = File(directory, "cover_${createdAtMs}.jpg")
        return try {
            FileOutputStream(coverFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            coverFile.absolutePath
        } catch (exception: Exception) {
            coverFile.delete()
            null
        }
    }

    private fun saveAudioClip(
        sourceUri: Uri,
        startMs: Long,
        durationMs: Long,
        createdAtMs: Long
    ): String? {
        val directory = File(filesDir, "bookmarks/audio")
        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }
        val clipFile = File(directory, "clip_${createdAtMs}.m4a")
        val saved = writeAudioClipToFile(sourceUri, startMs, durationMs, clipFile)
        return if (saved) {
            clipFile.absolutePath
        } else {
            clipFile.delete()
            null
        }
    }

    private fun writeAudioClipToFile(
        sourceUri: Uri,
        startMs: Long,
        durationMs: Long,
        outputFile: File
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var wroteSample = false
        return try {
            extractor.setDataSource(this, sourceUri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(index)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = index
                    audioFormat = trackFormat
                    break
                }
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                return false
            }
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return false
            if (mime !in BOOKMARK_CLIP_MIME_TYPES) {
                return false
            }
            extractor.selectTrack(audioTrackIndex)
            val startUs = startMs * 1000
            val endUs = (startMs + durationMs) * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()
            started = true

            val maxInputSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_CLIP_BUFFER_SIZE
            }
            val buffer = ByteBuffer.allocate(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    bufferInfo.size = 0
                    break
                }
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0 || sampleTimeUs > endUs) {
                    break
                }
                if (sampleTimeUs < startUs) {
                    extractor.advance()
                    continue
                }
                bufferInfo.presentationTimeUs = (sampleTimeUs - startUs).coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                wroteSample = true
                extractor.advance()
            }
            wroteSample
        } catch (exception: Exception) {
            false
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (exception: Exception) {
                }
            }
            try {
                muxer?.release()
            } catch (exception: Exception) {
            }
            extractor.release()
        }
    }

    private fun updateTrackNavigationButtons() {
        val index = currentFileIndex
        binding.previousButton.isEnabled = index != null && index > 0
        binding.nextButton.isEnabled = index != null && index < audioFiles.lastIndex
    }

    private fun formatSkipLabel(milliseconds: Long): String {
        return if (milliseconds >= ONE_MINUTE_MS) {
            "${milliseconds / ONE_MINUTE_MS}m"
        } else {
            "${milliseconds / 1000}s"
        }
    }

    private fun updatePlayPauseButton() {
        val isPlaying = mediaPlayer?.isPlaying == true
        if (isPlaying) {
            binding.playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            binding.playPauseButton.contentDescription = getString(R.string.pause_button)
        } else {
            binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            binding.playPauseButton.contentDescription = getString(R.string.play_button)
        }
    }

    private fun updateProgress() {
        val player = mediaPlayer
        if (player == null || !isPrepared) {
            binding.currentTime.text = formatDuration(0L)
            binding.previewView.setProgress(0f)
            return
        }
        val position = player.currentPosition
        val duration = maxOf(player.duration, 1)
        binding.currentTime.text = formatDuration(position.toLong())
        binding.previewView.setProgress(position.toFloat() / duration)
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun savePlaybackState(resetPosition: Boolean = false) {
        val currentUri = currentFile?.uri?.toString() ?: return
        val position = if (resetPosition) {
            0L
        } else {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        }
        preferences.edit()
            .putString(KEY_LAST_TRACK_URI, currentUri)
            .putLong(KEY_LAST_POSITION, position)
            .apply()
    }

    private fun closeDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun closeBookmarksDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        }
    }

    private fun releasePlayer() {
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
    }

    private fun setupPreviewScrub() {
        binding.previewView.setOnTouchListener { view, event ->
            val player = mediaPlayer
            if (player == null || !isPrepared || !binding.previewView.isEnabled) {
                return@setOnTouchListener false
            }
            val width = view.width
            if (width <= 0) {
                return@setOnTouchListener false
            }
            val ratio = (event.x / width).coerceIn(0f, 1f)
            val target = (player.duration * ratio).toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    stopProgressUpdates()
                    binding.previewView.setProgress(ratio)
                    binding.currentTime.text = formatDuration(target.toLong())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    player.seekTo(target)
                    if (player.isPlaying) {
                        startProgressUpdates()
                    }
                    updateProgress()
                    true
                }
                else -> false
            }
        }
    }

    private fun showTimeJumpDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.seek_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.time_jump_title))
            .setView(editText)
            .setNegativeButton(getString(R.string.time_jump_cancel), null)
            .setPositiveButton(getString(R.string.time_jump_confirm)) { _, _ ->
                val value = editText.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) {
                    seekToValue(value)
                }
            }
            .show()
    }

    private fun seekToValue(input: String) {
        val player = mediaPlayer
        if (player == null || !isPrepared) {
            Toast.makeText(this, getString(R.string.seek_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val millis = parseTimeInput(input)
        if (millis == null) {
            Toast.makeText(this, getString(R.string.seek_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val target = millis.coerceIn(0L, player.duration.toLong())
        player.seekTo(target.toInt())
        if (player.isPlaying) {
            startProgressUpdates()
        }
        updateProgress()
    }

    private fun parseTimeInput(value: String): Long? {
        val parts = value.split(":").map { it.trim() }
        if (parts.any { it.isEmpty() }) {
            return null
        }
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        return when (numbers.size) {
            1 -> numbers[0] * 1000L
            2 -> (numbers[0] * 60 + numbers[1]) * 1000L
            3 -> (numbers[0] * 3600 + numbers[1] * 60 + numbers[2]) * 1000L
            else -> null
        }
    }

    companion object {
        private const val PREFS_NAME = "audio_player_prefs"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_LAST_TRACK_URI = "last_track_uri"
        private const val KEY_LAST_POSITION = "last_position"
        private const val KEY_AUTOPLAY = "autoplay_next"
        private const val KEY_SKIP_MS = "skip_ms"
        private const val PROGRESS_UPDATE_MS = 500L
        private const val LARGE_ART_SIZE = 600
        private const val DEFAULT_SKIP_MS = 10_000L
        private const val ONE_MINUTE_MS = 60_000L
        private const val BACKGROUND_ART_ALPHA_WITH_ART = 0.3f
        private const val BACKGROUND_TINT_ALPHA_WITH_ART = 0.8f
        private const val DEFAULT_CLIP_BUFFER_SIZE = 256 * 1024
        private val BOOKMARK_CLIP_OPTIONS_MS = listOf(10_000L, 15_000L, 30_000L, 60_000L)
        private val BOOKMARK_CLIP_MIME_TYPES = setOf("audio/mp4a-latm", "audio/aac")
        private val SKIP_OPTIONS_MS = listOf(10_000L, 30_000L, 60_000L, 300_000L)
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "m4b")
    }
}
