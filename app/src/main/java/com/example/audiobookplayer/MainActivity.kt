package com.example.audiobookplayer

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
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
    private val preferences by lazy { getSharedPreferences(PlaybackPrefs.PREFS_NAME, MODE_PRIVATE) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val bookmarkDatabase by lazy {
        Room.databaseBuilder(applicationContext, BookmarkDatabase::class.java, "bookmarks.db").build()
    }
    private val progressHandler = Handler(Looper.getMainLooper())

    private var mediaBrowser: MediaBrowserCompat? = null
    private var mediaController: MediaControllerCompat? = null
    private var playbackState: PlaybackStateCompat? = null
    private var isPrepared = false
    private var currentDurationMs = 0L
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
    private var pendingPlaybackFile: AudioFile? = null
    private var pendingPlaybackRestore = false
    private var pendingPlaybackAutoPlay = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previousUri = preferences.getString(PlaybackPrefs.KEY_TREE_URI, null)
            if (previousUri != uri.toString()) {
                expandedFolders.clear()
            }
            preferences.edit().putString(PlaybackPrefs.KEY_TREE_URI, uri.toString()).apply()
            loadAudioTree(uri)
        }
    }

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            playbackState = state
            val prepared = state != null &&
                state.state != PlaybackStateCompat.STATE_NONE &&
                state.state != PlaybackStateCompat.STATE_BUFFERING
            if (prepared != isPrepared) {
                isPrepared = prepared
                setPlaybackControlsEnabled(prepared)
            }
            if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
                startProgressUpdates()
            } else {
                stopProgressUpdates()
            }
            updateProgress()
            updatePlayPauseButton()
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            val duration = metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0L
            if (duration > 0L) {
                currentDurationMs = duration
                binding.duration.text = formatDuration(duration)
            }
            setPlaybackControlsEnabled(isPrepared)
            updateProgress()
        }

        override fun onSessionEvent(event: String?, extras: Bundle?) {
            when (event) {
                PlaybackService.EVENT_PLAYBACK_COMPLETED -> handlePlaybackCompleted()
                PlaybackService.EVENT_PLAYBACK_ERROR -> {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.playback_resume_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val mediaBrowserConnection = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val browser = mediaBrowser ?: return
            val controller = MediaControllerCompat(this@MainActivity, browser.sessionToken)
            mediaController = controller
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            controller.registerCallback(mediaControllerCallback)
            playbackState = controller.playbackState
            val prepared = playbackState?.state != PlaybackStateCompat.STATE_NONE &&
                playbackState?.state != PlaybackStateCompat.STATE_BUFFERING
            isPrepared = prepared
            setPlaybackControlsEnabled(prepared)
            controller.metadata?.let { metadata ->
                val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                if (duration > 0L) {
                    currentDurationMs = duration
                    binding.duration.text = formatDuration(duration)
                }
            }
            if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                startProgressUpdates()
            } else {
                stopProgressUpdates()
            }
            updatePlayPauseButton()
            updateProgress()
            val pendingFile = pendingPlaybackFile
            if (pendingFile != null) {
                pendingPlaybackFile = null
                val restore = pendingPlaybackRestore
                val autoPlay = pendingPlaybackAutoPlay
                pendingPlaybackRestore = false
                pendingPlaybackAutoPlay = false
                preparePlayer(pendingFile, restore, autoPlay)
            }
        }

        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(mediaControllerCallback)
            mediaController = null
            playbackState = null
            isPrepared = false
            setPlaybackControlsEnabled(false)
            stopProgressUpdates()
        }

        override fun onConnectionFailed() {
            mediaController = null
            playbackState = null
            isPrepared = false
            setPlaybackControlsEnabled(false)
            stopProgressUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, PlaybackService::class.java),
            mediaBrowserConnection,
            null
        )
        mediaBrowser?.connect()

        artLoader = AudioArtLoader(this)
        treeAdapter = AudioTreeAdapter(artLoader) { node ->
            if (node.isFolder) {
                toggleFolder(node)
            } else {
                node.audioFile?.let {
                    selectTrack(it, restorePosition = false, autoPlay = true)
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

        skipAmountMs = preferences.getLong(PlaybackPrefs.KEY_SKIP_MS, DEFAULT_SKIP_MS)
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

        binding.autoplaySwitch.isChecked = preferences.getBoolean(PlaybackPrefs.KEY_AUTOPLAY, false)
        binding.autoplaySwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit().putBoolean(PlaybackPrefs.KEY_AUTOPLAY, isChecked).apply()
        }

        setupBookmarkActions()
        setupPreviewScrub()
        clearSelection()
        restoreLastFolder()
        refreshBookmarks()
    }

    override fun onStart() {
        super.onStart()
        if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
            startProgressUpdates()
        }
        updateProgress()
        updatePlayPauseButton()
    }

    override fun onStop() {
        super.onStop()
        stopProgressUpdates()
        stopBookmarkClipPlayback()
    }

    override fun onDestroy() {
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = null
        playbackState = null
        mediaBrowser?.disconnect()
        mediaBrowser = null
        super.onDestroy()
        ioExecutor.shutdown()
    }

    private fun restoreLastFolder() {
        val uriString = preferences.getString(PlaybackPrefs.KEY_TREE_URI, null)
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
            val lastTrackUri = preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_URI, null)
            val lastTrackTitle = preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, null)
            runOnUiThread {
                treeRoots = rootNodes
                updateTreeList()
                audioFiles = audioItems
                val lastTrack = findRestorableTrack(audioItems, lastTrackUri, lastTrackTitle)
                if (lastTrack != null) {
                    selectTrack(lastTrack, restorePosition = true, autoPlay = false)
                } else {
                    clearSelection()
                }
            }
        }
    }

    private fun findRestorableTrack(
        items: List<AudioFile>,
        lastTrackUri: String?,
        lastTrackTitle: String?
    ): AudioFile? {
        if (items.isEmpty()) {
            return null
        }
        if (!lastTrackUri.isNullOrBlank()) {
            val byExactUri = items.firstOrNull { it.uri.toString() == lastTrackUri }
            if (byExactUri != null) {
                return byExactUri
            }
            val parsedUri = runCatching { Uri.parse(lastTrackUri) }.getOrNull()
            val uriFileName = parsedUri?.lastPathSegment
                ?.substringAfterLast(':')
                ?.lowercase(Locale.getDefault())
            if (!uriFileName.isNullOrBlank()) {
                val byFileName = items.firstOrNull { audioFile ->
                    audioFile.uri.lastPathSegment
                        ?.substringAfterLast(':')
                        ?.lowercase(Locale.getDefault()) == uriFileName
                }
                if (byFileName != null) {
                    return byFileName
                }
            }
        }
        if (!lastTrackTitle.isNullOrBlank()) {
            val titleLower = lastTrackTitle.lowercase(Locale.getDefault())
            return items.firstOrNull { it.title.lowercase(Locale.getDefault()) == titleLower }
        }
        return null
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
            val controller = mediaController
            if (controller != null) {
                val durationMs = currentDurationMs.coerceAtLeast(0L)
                val clamped = targetMs.coerceIn(0L, durationMs)
                controller.transportControls.seekTo(clamped)
                controller.transportControls.play()
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
        currentFile = audioFile
        currentFileIndex = audioFiles.indexOfFirst { it.uri == audioFile.uri }.takeIf { it >= 0 }
        treeAdapter.selectedUri = audioFile.uri
        binding.bookmarkButton.isEnabled = true
        binding.trackTitle.text = audioFile.title
        binding.previewView.setWaveformSeed(audioFile.uri.hashCode())
        binding.previewView.setProgress(0f)
        binding.currentTime.text = formatDuration(0L)
        currentDurationMs = audioFile.durationMs
        binding.duration.text = formatDuration(audioFile.durationMs)
        binding.previewView.isEnabled = false
        binding.playPauseButton.isEnabled = false
        binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        binding.playPauseButton.contentDescription = getString(R.string.play_button)
        binding.stopButton.isEnabled = false
        isPrepared = false
        updateTrackNavigationButtons()
        binding.skipBackButton.isEnabled = false
        binding.timeJumpButton.isEnabled = false
        binding.skipForwardButton.isEnabled = false
        preferences.edit()
            .putString(PlaybackPrefs.KEY_LAST_TRACK_URI, audioFile.uri.toString())
            .putString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, audioFile.title)
            .apply()
        loadAlbumArt(audioFile)
        preparePlayer(audioFile, restorePosition, autoPlay)
    }

    private fun clearSelection() {
        currentFile = null
        currentFileIndex = null
        treeAdapter.selectedUri = null
        binding.trackTitle.text = getString(R.string.no_track_selected)
        binding.albumArtLarge.setImageDrawable(null)
        binding.backgroundArt.setImageDrawable(null)
        clearBackgroundBlur()
        binding.backgroundArt.alpha = 0f
        binding.backgroundTint.alpha = 0f
        binding.bookmarkButton.isEnabled = false
        currentCoverBitmap = null
        pendingBookmarkSeekMs = null
        pendingBookmarkAutoPlay = false
        isPrepared = false
        currentDurationMs = 0L
        pendingPlaybackFile = null
        pendingPlaybackRestore = false
        pendingPlaybackAutoPlay = false
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
        stopProgressUpdates()
    }

    private fun loadAlbumArt(audioFile: AudioFile) {
        binding.albumArtLarge.setImageDrawable(null)
        binding.backgroundArt.setImageDrawable(null)
        clearBackgroundBlur()
        binding.backgroundArt.alpha = 0f
        binding.backgroundTint.alpha = 0f
        currentCoverBitmap = null
        artLoader.load(audioFile.uri, LARGE_ART_SIZE) { bitmap ->
            if (audioFile.uri == currentFile?.uri) {
                if (bitmap != null) {
                    binding.albumArtLarge.setImageBitmap(bitmap)
                    binding.backgroundArt.setImageBitmap(bitmap)
                    applyBackgroundBlur()
                    binding.backgroundArt.alpha = BACKGROUND_ART_ALPHA_WITH_ART
                    binding.backgroundTint.alpha = BACKGROUND_DIM_ALPHA_WITH_ART
                    currentCoverBitmap = bitmap
                } else {
                    binding.albumArtLarge.setImageDrawable(null)
                    binding.backgroundArt.setImageDrawable(null)
                    clearBackgroundBlur()
                    binding.backgroundArt.alpha = 0f
                    binding.backgroundTint.alpha = 0f
                    currentCoverBitmap = null
                }
            }
        }
    }

    private fun applyBackgroundBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.backgroundArt.setRenderEffect(
                RenderEffect.createBlurEffect(
                    BACKGROUND_BLUR_RADIUS,
                    BACKGROUND_BLUR_RADIUS,
                    Shader.TileMode.CLAMP
                )
            )
        }
    }

    private fun clearBackgroundBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.backgroundArt.setRenderEffect(null)
        }
    }

    private fun preparePlayer(audioFile: AudioFile, restorePosition: Boolean, autoPlay: Boolean) {
        val controller = mediaController
        if (controller == null) {
            pendingPlaybackFile = audioFile
            pendingPlaybackRestore = restorePosition
            pendingPlaybackAutoPlay = autoPlay
            return
        }
        isPrepared = false
        setPlaybackControlsEnabled(false)
        currentDurationMs = audioFile.durationMs
        binding.duration.text = formatDuration(currentDurationMs)
        val pendingSeekMs = pendingBookmarkSeekMs
        val extras = Bundle().apply {
            putString(PlaybackService.EXTRA_TRACK_TITLE, audioFile.title)
            putBoolean(PlaybackService.EXTRA_RESTORE_POSITION, restorePosition)
            if (pendingSeekMs != null) {
                putLong(PlaybackService.EXTRA_SEEK_POSITION_MS, pendingSeekMs)
            } else if (restorePosition) {
                val savedPosition = preferences.getLong(PlaybackPrefs.KEY_LAST_POSITION, 0L)
                if (savedPosition > 0L) {
                    putLong(PlaybackService.EXTRA_SEEK_POSITION_MS, savedPosition)
                }
            }
        }
        val shouldAutoPlay = if (pendingSeekMs != null) {
            pendingBookmarkAutoPlay
        } else {
            autoPlay
        }
        if (shouldAutoPlay) {
            controller.transportControls.playFromUri(audioFile.uri, extras)
        } else {
            controller.transportControls.prepareFromUri(audioFile.uri, extras)
        }
        pendingBookmarkSeekMs = null
        pendingBookmarkAutoPlay = false
    }

    private fun togglePlayback() {
        val controller = mediaController
        if (controller == null || !isPrepared) {
            return
        }
        val state = controller.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    private fun stopPlayback() {
        val controller = mediaController ?: return
        if (!isPrepared) {
            return
        }
        controller.transportControls.stop()
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
            mediaController?.transportControls?.seekTo(0L)
        }
    }

    private fun skipBy(deltaMs: Long) {
        val controller = mediaController ?: return
        if (!isPrepared) {
            return
        }
        val current = getCurrentPlaybackPosition()
        val duration = currentDurationMs.coerceAtLeast(0L)
        val target = (current + deltaMs).coerceIn(0L, duration)
        controller.transportControls.seekTo(target)
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
            preferences.edit().putLong(PlaybackPrefs.KEY_SKIP_MS, skipAmountMs).apply()
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
            getCurrentPlaybackPosition()
        } else {
            0L
        }
        val createdAtMs = System.currentTimeMillis()
        val coverBitmap = currentCoverBitmap
        val trackDurationMs = if (audioFile.durationMs > 0L) {
            audioFile.durationMs
        } else {
            currentDurationMs
        }
        val availableDurationMs = if (trackDurationMs > 0L) {
            (trackDurationMs - positionMs).coerceAtLeast(0L)
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
        val isPlaying = playbackState?.state == PlaybackStateCompat.STATE_PLAYING
        if (isPlaying) {
            binding.playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            binding.playPauseButton.contentDescription = getString(R.string.pause_button)
        } else {
            binding.playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            binding.playPauseButton.contentDescription = getString(R.string.play_button)
        }
    }

    private fun updateProgress() {
        if (!isPrepared) {
            binding.currentTime.text = formatDuration(0L)
            binding.previewView.setProgress(0f)
            return
        }
        val position = getCurrentPlaybackPosition()
        val duration = maxOf(currentDurationMs, 1L)
        val clamped = position.coerceIn(0L, duration)
        binding.currentTime.text = formatDuration(clamped)
        binding.previewView.setProgress(clamped.toFloat() / duration.toFloat())
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun getCurrentPlaybackPosition(): Long {
        val state = playbackState ?: return 0L
        val position = state.position
        return if (state.state == PlaybackStateCompat.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            (position + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
        } else {
            position
        }
    }

    private fun setPlaybackControlsEnabled(enabled: Boolean) {
        binding.previewView.isEnabled = enabled && currentDurationMs > 0L
        binding.playPauseButton.isEnabled = enabled
        binding.stopButton.isEnabled = enabled
        binding.skipBackButton.isEnabled = enabled
        binding.timeJumpButton.isEnabled = enabled
        binding.skipForwardButton.isEnabled = enabled
        updateTrackNavigationButtons()
    }

    private fun handlePlaybackCompleted() {
        stopProgressUpdates()
        if (binding.autoplaySwitch.isChecked) {
            playNextTrack()
        } else {
            updateProgress()
            updatePlayPauseButton()
        }
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

    private fun setupPreviewScrub() {
        var isScrubbing = false
        binding.previewView.setOnTouchListener { view, event ->
            val controller = mediaController
            if (controller == null || !isPrepared || !binding.previewView.isEnabled) {
                isScrubbing = false
                return@setOnTouchListener false
            }
            val width = view.width
            if (width <= 0) {
                isScrubbing = false
                return@setOnTouchListener false
            }
            val ratio = (event.x / width).coerceIn(0f, 1f)
            val target = (currentDurationMs * ratio).toLong()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isScrubbing = true
                    stopProgressUpdates()
                    binding.previewView.setProgress(ratio)
                    binding.currentTime.text = formatDuration(target)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isScrubbing) {
                        return@setOnTouchListener false
                    }
                    stopProgressUpdates()
                    binding.previewView.setProgress(ratio)
                    binding.currentTime.text = formatDuration(target)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isScrubbing) {
                        return@setOnTouchListener false
                    }
                    isScrubbing = false
                    controller.transportControls.seekTo(target)
                    if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                        startProgressUpdates()
                    }
                    updateProgress()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!isScrubbing) {
                        return@setOnTouchListener false
                    }
                    isScrubbing = false
                    if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
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
        val controller = mediaController
        if (controller == null || !isPrepared) {
            Toast.makeText(this, getString(R.string.seek_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        val millis = parseTimeInput(input)
        if (millis == null) {
            Toast.makeText(this, getString(R.string.seek_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val target = millis.coerceIn(0L, currentDurationMs)
        controller.transportControls.seekTo(target)
        if (playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
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
        private const val PROGRESS_UPDATE_MS = 500L
        private const val LARGE_ART_SIZE = 600
        private const val DEFAULT_SKIP_MS = 10_000L
        private const val ONE_MINUTE_MS = 60_000L
        private const val BACKGROUND_ART_ALPHA_WITH_ART = 0.9f
        private const val BACKGROUND_DIM_ALPHA_WITH_ART = 0.25f
        private const val BACKGROUND_BLUR_RADIUS = 64f
        private const val DEFAULT_CLIP_BUFFER_SIZE = 256 * 1024
        private val BOOKMARK_CLIP_OPTIONS_MS = listOf(10_000L, 15_000L, 30_000L, 60_000L)
        private val BOOKMARK_CLIP_MIME_TYPES = setOf("audio/mp4a-latm", "audio/aac")
        private val SKIP_OPTIONS_MS = listOf(10_000L, 30_000L, 60_000L, 300_000L)
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "m4b")
    }
}
