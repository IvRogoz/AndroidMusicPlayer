package com.example.audiobookplayer

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import com.example.audiobookplayer.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var treeAdapter: AudioTreeAdapter
    private lateinit var artLoader: AudioArtLoader
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val progressHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var currentFile: AudioFile? = null
    private var audioFiles: List<AudioFile> = emptyList()
    private var currentFileIndex: Int? = null
    private var skipAmountMs: Long = DEFAULT_SKIP_MS
    private var treeRoots: List<AudioTreeNode> = emptyList()
    private val expandedFolders = mutableSetOf<Uri>()

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, PROGRESS_UPDATE_MS)
        }
    }

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

        binding.folderTree.layoutManager = LinearLayoutManager(this)
        binding.folderTree.adapter = treeAdapter

        skipAmountMs = preferences.getLong(KEY_SKIP_MS, DEFAULT_SKIP_MS)
        updateSkipButtonLabels()

        binding.selectFolderButton.setOnClickListener {
            selectFolderLauncher.launch(null)
        }

        binding.openDrawerButton.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
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

        setupPreviewScrub()
        clearSelection()
        restoreLastFolder()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackState()
        releasePlayer()
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
            expandedFolders.clear()
            collectFolderUris(rootNodes, expandedFolders)
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

    private fun collectFolderUris(nodes: List<AudioTreeNode>, output: MutableSet<Uri>) {
        nodes.forEach { node ->
            if (node.isFolder) {
                output.add(node.uri)
                collectFolderUris(node.children, output)
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

    private fun selectTrack(audioFile: AudioFile, restorePosition: Boolean, autoPlay: Boolean) {
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
        binding.backgroundArt.setImageResource(android.R.drawable.ic_media_play)
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
        binding.backgroundArt.setImageResource(android.R.drawable.ic_media_play)
        artLoader.load(audioFile.uri, LARGE_ART_SIZE) { bitmap ->
            if (audioFile.uri == currentFile?.uri) {
                if (bitmap != null) {
                    binding.albumArtLarge.setImageBitmap(bitmap)
                    binding.backgroundArt.setImageBitmap(bitmap)
                } else {
                    binding.albumArtLarge.setImageResource(android.R.drawable.ic_media_play)
                    binding.backgroundArt.setImageResource(android.R.drawable.ic_media_play)
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
            if (restorePosition && audioFile.uri.toString() == preferences.getString(KEY_LAST_TRACK_URI, null)) {
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
            if (autoPlay) {
                preparedPlayer.start()
                startProgressUpdates()
            }
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
        private val SKIP_OPTIONS_MS = listOf(10_000L, 30_000L, 60_000L, 300_000L)
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "m4b")
    }
}
