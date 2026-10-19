package com.example.audiobookplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.Executors

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaBrowserServiceCompat() {

    private val preferences by lazy { getSharedPreferences(PlaybackPrefs.PREFS_NAME, MODE_PRIVATE) }
    private lateinit var mediaSession: MediaSessionCompat
    private var exoPlayer: ExoPlayer? = null
    private var isPrepared = false
    private var currentUri: Uri? = null
    private var currentTitle: String? = null
    private var currentDurationMs: Long = 0L
    private var currentArtwork: Bitmap? = null
    private var currentArtworkUri: Uri? = null
    private var currentArtist: String? = null
    private var currentAlbum: String? = null
    private var currentAuthor: String? = null
    private var currentGenre: String? = null
    private var currentTrackNumber: Long? = null
    private var isForeground = false
    @Volatile
    private var isDestroyed = false
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var audioFocusRequestActive = false
    private var resumeOnAudioFocusGain = false
    private var noisyReceiverRegistered = false
    private var prepareTimeoutRunnable: Runnable? = null
    private val artExecutor = Executors.newSingleThreadExecutor()
    private val browseExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseForExternalAudioChange(shouldResumeOnGain = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                pauseForExternalAudioChange(shouldResumeOnGain = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setPlayerVolume(DUCKED_VOLUME)
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioFocusRequestActive = true
                setPlayerVolume(NORMAL_VOLUME)
                if (AudioFocusPolicy.shouldResumeOnGain(
                        resumePending = resumeOnAudioFocusGain,
                        isPrepared = isPrepared,
                        isPlaying = exoPlayer?.isPlaying == true
                    )
                ) {
                    resumeOnAudioFocusGain = false
                    startPlayback()
                }
            }
        }
    }
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseForExternalAudioChange(shouldResumeOnGain = false)
            }
        }
    }
    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            persistCurrentPosition()
            if (isPrepared && exoPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        mediaSession = MediaSessionCompat(this, "PlaybackService").apply {
            setCallback(sessionCallback)
            setSessionActivity(createContentIntent())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_NOT_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        browseExecutor.execute {
            val children = runCatching { loadBrowseChildren(parentId) }
                .onFailure { Log.w(TAG, "Unable to load media browser children", it) }
                .getOrDefault(mutableListOf())
            mainHandler.post {
                if (!isDestroyed) {
                    result.sendResult(children)
                }
            }
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        stopPositionPersistence()
        persistCurrentPosition()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        stopForegroundNow()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        artExecutor.shutdownNow()
        browseExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistCurrentPosition()
        super.onTaskRemoved(rootIntent)
    }

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (isPrepared) {
                startPlayback()
                return
            }
            val lastUri = preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_URI, null)
            if (lastUri != null) {
                val savedPosition = preferences.getLong(PlaybackPrefs.KEY_LAST_POSITION, 0L)
                val extras = Bundle().apply {
                    putString(EXTRA_TRACK_TITLE, preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, null))
                    putBoolean(EXTRA_RESTORE_POSITION, true)
                    if (savedPosition > 0L) {
                        putLong(EXTRA_SEEK_POSITION_MS, savedPosition)
                    }
                }
                prepareFromUri(Uri.parse(lastUri), extras, playWhenReady = true)
            } else {
                mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
            }
        }

        override fun onPlayFromUri(uri: Uri, extras: Bundle?) {
            prepareFromUri(uri, extras, playWhenReady = true)
        }

        override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
            playFromMediaId(mediaId, extras, playWhenReady = true)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val normalizedQuery = query?.trim().orEmpty()
            if (normalizedQuery.isBlank()) {
                onPlay()
                return
            }
            browseExecutor.execute {
                val match = runCatching { findAudioByTitle(normalizedQuery) }
                    .onFailure { Log.w(TAG, "Unable to search the media library", it) }
                    .getOrNull()
                mainHandler.post {
                    if (isDestroyed) {
                        return@post
                    }
                    if (match == null) {
                        mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
                    } else {
                        val resolvedExtras = Bundle(extras ?: Bundle()).apply {
                            putString(EXTRA_TRACK_TITLE, match.name)
                        }
                        prepareFromUri(match.uri, resolvedExtras, playWhenReady = true)
                    }
                }
            }
        }

        override fun onPrepareFromUri(uri: Uri, extras: Bundle?) {
            prepareFromUri(uri, extras, playWhenReady = false)
        }

        override fun onPrepareFromMediaId(mediaId: String, extras: Bundle?) {
            playFromMediaId(mediaId, extras, playWhenReady = false)
        }

        override fun onPause() {
            val player = exoPlayer ?: return
            if (!isPrepared) {
                return
            }
            if (player.isPlaying) {
                player.pause()
            }
            stopPositionPersistence()
            savePlaybackPosition(player.currentPosition)
            resumeOnAudioFocusGain = false
            unregisterNoisyReceiver()
            abandonAudioFocus()
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, player.currentPosition)
        }

        override fun onStop() {
            val position = exoPlayer?.takeIf { isPrepared }?.currentPosition?.coerceAtLeast(0L) ?: 0L
            if (isPrepared) {
                savePlaybackPosition(position)
            }
            releasePlayer()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, position)
            stopSelf()
        }

        override fun onSeekTo(pos: Long) {
            val player = exoPlayer ?: return
            if (!isPrepared) {
                return
            }
            val target = pos.coerceAtLeast(0L)
            player.seekTo(target)
            savePlaybackPosition(target)
            val state = if (player.isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            updatePlaybackState(state, target)
        }
    }

    private fun playFromMediaId(mediaId: String, extras: Bundle?, playWhenReady: Boolean) {
        if (!mediaId.startsWith(TRACK_ID_PREFIX)) {
            mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
            return
        }
        val uri = Uri.parse(mediaId.removePrefix(TRACK_ID_PREFIX))
        val title = DocumentFile.fromSingleUri(this, uri)?.name
        val resolvedExtras = Bundle(extras ?: Bundle()).apply {
            if (getString(EXTRA_TRACK_TITLE).isNullOrBlank() && !title.isNullOrBlank()) {
                putString(EXTRA_TRACK_TITLE, title)
            }
        }
        prepareFromUri(uri, resolvedExtras, playWhenReady)
    }

    private fun loadBrowseChildren(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val directory = when {
            parentId == ROOT_ID -> {
                val rootUri = preferences.getString(PlaybackPrefs.KEY_TREE_URI, null) ?: return mutableListOf()
                DocumentFile.fromTreeUri(this, Uri.parse(rootUri))
            }
            parentId.startsWith(FOLDER_ID_PREFIX) -> {
                DocumentFile.fromSingleUri(this, Uri.parse(parentId.removePrefix(FOLDER_ID_PREFIX)))
            }
            else -> null
        } ?: return mutableListOf()

        return directory.listFiles()
            .filter { it.isDirectory || AudioDocuments.isAudioFile(it) }
            .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase().orEmpty() }))
            .map { document ->
                val isDirectory = document.isDirectory
                val description = MediaDescriptionCompat.Builder()
                    .setMediaId((if (isDirectory) FOLDER_ID_PREFIX else TRACK_ID_PREFIX) + document.uri)
                    .setTitle(document.name ?: getString(R.string.unknown_track))
                    .apply {
                        if (!isDirectory) {
                            setMediaUri(document.uri)
                            setIconUri(CoverArtProvider.artworkUri(this@PlaybackService, document.uri))
                        }
                    }
                    .build()
                MediaBrowserCompat.MediaItem(
                    description,
                    if (isDirectory) MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    else MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            }
            .toMutableList()
    }

    private fun findAudioByTitle(query: String): DocumentFile? {
        val rootUri = preferences.getString(PlaybackPrefs.KEY_TREE_URI, null) ?: return null
        val root = DocumentFile.fromTreeUri(this, Uri.parse(rootUri)) ?: return null
        val directories = ArrayDeque<DocumentFile>().apply { add(root) }
        while (directories.isNotEmpty()) {
            directories.removeFirst().listFiles().forEach { document ->
                when {
                    document.isDirectory -> directories.addLast(document)
                    AudioDocuments.isAudioFile(document) && document.name?.contains(query, ignoreCase = true) == true -> {
                        return document
                    }
                }
            }
        }
        return null
    }

    private fun prepareFromUri(uri: Uri, extras: Bundle?, playWhenReady: Boolean) {
        releasePlayer()
        currentUri = uri
        currentTitle = extras?.getString(EXTRA_TRACK_TITLE)?.takeIf { it.isNotBlank() }
        currentArtwork = null
        currentArtworkUri = null
        currentArtist = null
        currentAlbum = null
        currentAuthor = null
        currentGenre = null
        currentTrackNumber = null
        isPrepared = false
        loadMetadataAsync(uri)
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        player.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        player.playWhenReady = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer !== player) {
                    return
                }
                when (playbackState) {
                    Player.STATE_READY -> handlePlayerReady(player, extras, playWhenReady)
                    Player.STATE_ENDED -> handlePlaybackCompleted()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (exoPlayer !== player) {
                    return
                }
                Log.e(TAG, "ExoPlayer error ${error.errorCodeName} for $uri", error)
                stopPositionPersistence()
                resumeOnAudioFocusGain = false
                unregisterNoisyReceiver()
                abandonAudioFocus()
                releasePlayer()
                updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
                sendPlaybackError(error)
            }
        })
        try {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            schedulePrepareTimeout(player)
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0L)
            saveCurrentTrack(uri, currentTitle)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to prepare audio source $uri", exception)
            stopPositionPersistence()
            resumeOnAudioFocusGain = false
            unregisterNoisyReceiver()
            abandonAudioFocus()
            releasePlayer()
            updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
            sendPlaybackError(null, null)
        }
    }

    private fun handlePlayerReady(player: ExoPlayer, extras: Bundle?, playWhenReady: Boolean) {
        if (isPrepared) {
            return
        }
        cancelPrepareTimeout()
        isPrepared = true
        currentDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        val restorePosition = extras?.getBoolean(EXTRA_RESTORE_POSITION, false) == true
        val seekPositionMs = extras?.getLong(EXTRA_SEEK_POSITION_MS)
        if (seekPositionMs != null) {
            player.seekTo(seekPositionMs.coerceAtLeast(0L))
        } else if (restorePosition) {
            val resumePosition = preferences.getLong(PlaybackPrefs.KEY_LAST_POSITION, 0L)
            if (resumePosition in 1L until currentDurationMs) {
                player.seekTo(resumePosition)
            }
        }
        updateMetadata()
        if (playWhenReady) {
            startPlayback()
        } else {
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, player.currentPosition)
        }
    }

    private fun handlePlaybackCompleted() {
        stopPositionPersistence()
        savePlaybackPosition(0L)
        resumeOnAudioFocusGain = false
        unregisterNoisyReceiver()
        abandonAudioFocus()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
        mediaSession.sendSessionEvent(EVENT_PLAYBACK_COMPLETED, null)
    }

    private fun schedulePrepareTimeout(player: ExoPlayer) {
        cancelPrepareTimeout()
        val timeoutRunnable = Runnable {
            prepareTimeoutRunnable = null
            if (exoPlayer === player && !isPrepared && !isDestroyed) {
                Log.e(TAG, "Timed out preparing audio source $currentUri")
                stopPositionPersistence()
                resumeOnAudioFocusGain = false
                unregisterNoisyReceiver()
                abandonAudioFocus()
                releasePlayer()
                updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
                sendPlaybackError(null, MediaPlayer.MEDIA_ERROR_TIMED_OUT)
            }
        }
        prepareTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, PREPARE_TIMEOUT_MS)
    }

    private fun cancelPrepareTimeout() {
        prepareTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        prepareTimeoutRunnable = null
    }

    private fun sendPlaybackError(what: Int?, extra: Int?) {
        val extras = Bundle()
        if (what != null) {
            extras.putInt(EXTRA_ERROR_CODE, what)
        }
        if (extra != null) {
            extras.putInt(EXTRA_ERROR_DETAIL, extra)
        }
        mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, extras)
    }

    private fun sendPlaybackError(error: PlaybackException) {
        val mediaErrorCode = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> MediaPlayer.MEDIA_ERROR_UNSUPPORTED

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> MediaPlayer.MEDIA_ERROR_MALFORMED

            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> MediaPlayer.MEDIA_ERROR_IO

            else -> MediaPlayer.MEDIA_ERROR_UNKNOWN
        }
        val detail = if (error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
            MediaPlayer.MEDIA_ERROR_TIMED_OUT
        } else {
            null
        }
        sendPlaybackError(mediaErrorCode, detail)
    }

    private fun startPlayback() {
        val player = exoPlayer ?: return
        if (!isPrepared) {
            return
        }
        ensureServiceStartedForPlayback()
        if (!ensureAudioFocus()) {
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, player.currentPosition)
            return
        }
        setPlayerVolume(NORMAL_VOLUME)
        resumeOnAudioFocusGain = false
        if (!player.isPlaying) {
            player.play()
        }
        registerNoisyReceiver()
        startPositionPersistence()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, player.currentPosition)
    }

    private fun pauseForExternalAudioChange(shouldResumeOnGain: Boolean) {
        val player = exoPlayer ?: return
        if (!isPrepared) {
            return
        }
        resumeOnAudioFocusGain = shouldResumeOnGain
        if (player.isPlaying) {
            player.pause()
        }
        stopPositionPersistence()
        val currentPosition = player.currentPosition.coerceAtLeast(0L)
        savePlaybackPosition(currentPosition)
        if (AudioFocusPolicy.shouldReleaseRequestAfterPause(shouldResumeOnGain)) {
            unregisterNoisyReceiver()
            abandonAudioFocus()
        }
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, currentPosition)
    }

    private fun setPlayerVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    private fun startPositionPersistence() {
        mainHandler.removeCallbacks(positionSaveRunnable)
        mainHandler.post(positionSaveRunnable)
    }

    private fun stopPositionPersistence() {
        mainHandler.removeCallbacks(positionSaveRunnable)
    }

    private fun persistCurrentPosition() {
        val player = exoPlayer ?: return
        if (!isPrepared) {
            return
        }
        runCatching { player.currentPosition }
            .onSuccess(::savePlaybackPosition)
            .onFailure { Log.w(TAG, "Unable to read the current playback position", it) }
    }

    private fun updatePlaybackState(state: Int, position: Long) {
        val actions = buildPlaybackActions()
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, position, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f, SystemClock.elapsedRealtime())
            .build()
        mediaSession.setPlaybackState(playbackState)
        updatePlaybackNotification(state)
    }

    private fun buildPlaybackActions(): Long {
        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
            PlaybackStateCompat.ACTION_PLAY_FROM_URI or
            PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PREPARE_FROM_URI or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        if (isPrepared) {
            actions = actions or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO
        }
        return actions
    }

    private fun updateMetadata() {
        val title = currentTitle ?: currentUri?.lastPathSegment.orEmpty()
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, currentUri?.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, currentUri?.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        currentArtist?.let {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        currentAlbum?.let { metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it) }
        currentAuthor?.let { metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, it) }
        currentGenre?.let { metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_GENRE, it) }
        currentTrackNumber?.let { metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, it) }
        currentArtwork?.let { artwork ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        currentArtworkUri?.toString()?.let { artworkUri ->
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUri)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, artworkUri)
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, artworkUri)
        }
        val metadata = metadataBuilder.build()
        mediaSession.setMetadata(metadata)
        val state = mediaSession.controller.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        updatePlaybackNotification(state)
    }

    private fun loadMetadataAsync(uri: Uri) {
        val uriKey = uri.toString()
        artExecutor.execute {
            val embeddedMetadata = EmbeddedAudioMetadata.read(this, uri)
            val bitmap = embeddedMetadata?.artwork?.let { decodeArtwork(it, ART_TARGET_SIZE) }
            mainHandler.post {
                if (!isDestroyed && currentUri?.toString() == uriKey) {
                    if (currentTitle.isNullOrBlank()) {
                        currentTitle = embeddedMetadata?.title ?: uri.lastPathSegment
                    }
                    currentArtist = embeddedMetadata?.artist
                    currentAlbum = embeddedMetadata?.album
                    currentAuthor = embeddedMetadata?.author
                    currentGenre = embeddedMetadata?.genre
                    currentTrackNumber = embeddedMetadata?.trackNumber
                    currentArtwork = bitmap
                    currentArtworkUri = if (embeddedMetadata?.artwork != null) {
                        CoverArtProvider.artworkUri(this, uri)
                    } else {
                        null
                    }
                    saveCurrentTrack(uri, currentTitle)
                    updateMetadata()
                }
            }
        }
    }

    private fun decodeArtwork(data: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = BitmapUtils.calculateInSampleSize(bounds, targetSize, targetSize)
            inJustDecodeBounds = false
        }
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
        val largestDimension = maxOf(decoded.width, decoded.height)
        if (largestDimension <= targetSize) {
            return decoded
        }
        val scale = targetSize.toFloat() / largestDimension.toFloat()
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        ).also { scaled ->
            if (scaled !== decoded) {
                decoded.recycle()
            }
        }
    }

    private fun savePlaybackPosition(positionMs: Long) {
        val uri = currentUri?.toString() ?: return
        preferences.edit()
            .putString(PlaybackPrefs.KEY_LAST_TRACK_URI, uri)
            .putLong(PlaybackPrefs.KEY_LAST_POSITION, positionMs)
            .apply()
    }

    private fun saveCurrentTrack(uri: Uri, title: String?) {
        preferences.edit()
            .putString(PlaybackPrefs.KEY_LAST_TRACK_URI, uri.toString())
            .putString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, title)
            .apply()
    }

    private fun releasePlayer() {
        stopPositionPersistence()
        cancelPrepareTimeout()
        persistCurrentPosition()
        resumeOnAudioFocusGain = false
        unregisterNoisyReceiver()
        abandonAudioFocus()
        exoPlayer?.release()
        exoPlayer = null
        isPrepared = false
    }

    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) {
            return true
        }
        if (audioFocusRequestActive) {
            return false
        }
        val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return when (focusResult) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasAudioFocus = true
                audioFocusRequestActive = true
                resumeOnAudioFocusGain = false
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasAudioFocus = false
                audioFocusRequestActive = true
                resumeOnAudioFocusGain = true
                false
            }
            else -> {
                hasAudioFocus = false
                audioFocusRequestActive = false
                false
            }
        }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusRequestActive) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        audioFocusRequestActive = false
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) {
            return
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(becomingNoisyReceiver, filter)
        }
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            return
        }
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Noisy-audio receiver was already unregistered", exception)
        }
        noisyReceiverRegistered = false
    }

    private fun updatePlaybackNotification(state: Int) {
        when (state) {
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_PAUSED -> {
                val notification = buildNotification(state)
                if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
                    if (!isForeground) {
                        try {
                            startForeground(NOTIFICATION_ID, notification)
                            isForeground = true
                        } catch (exception: Exception) {
                            Log.w(TAG, "Unable to enter the foreground for playback notification", exception)
                        }
                    } else {
                        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                    }
                } else {
                    if (isForeground) {
                        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                        isForeground = false
                    }
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                }
            }

            else -> {
                stopForegroundNow()
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            }
        }
    }

    private fun stopForegroundNow() {
        if (isForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
    }

    private fun buildNotification(state: Int): android.app.Notification {
        val title = currentTitle ?: getString(R.string.app_name)
        val isPlaying = state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING
        val playPauseAction = NotificationCompat.Action(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            getString(if (isPlaying) R.string.pause_button else R.string.play_button),
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                if (isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
            )
        )
        val stopAction = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(R.string.stop_button),
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bookmark)
            .setContentTitle(title)
            .setContentText(currentArtist ?: currentAlbum ?: getString(R.string.app_name))
            .setLargeIcon(currentArtwork)
            .setContentIntent(createContentIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

    private fun createContentIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, CONTENT_INTENT_REQUEST_CODE, launchIntent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.playback_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun ensureServiceStartedForPlayback() {
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_START_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            @Suppress("DEPRECATION")
            startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_TRACK_TITLE = "com.example.audiobookplayer.extra.TRACK_TITLE"
        const val EXTRA_RESTORE_POSITION = "com.example.audiobookplayer.extra.RESTORE_POSITION"
        const val EXTRA_SEEK_POSITION_MS = "com.example.audiobookplayer.extra.SEEK_POSITION_MS"
        const val EXTRA_ERROR_CODE = "com.example.audiobookplayer.extra.ERROR_CODE"
        const val EXTRA_ERROR_DETAIL = "com.example.audiobookplayer.extra.ERROR_DETAIL"
        const val EVENT_PLAYBACK_COMPLETED = "com.example.audiobookplayer.event.PLAYBACK_COMPLETED"
        const val EVENT_PLAYBACK_ERROR = "com.example.audiobookplayer.event.PLAYBACK_ERROR"
        const val ACTION_START_PLAYBACK = "com.example.audiobookplayer.action.START_PLAYBACK"
        private const val ROOT_ID = "root"
        private const val FOLDER_ID_PREFIX = "folder:"
        private const val TRACK_ID_PREFIX = "track:"
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val PREPARE_TIMEOUT_MS = 20_000L
        private const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 1002
        private const val ART_TARGET_SIZE = 512
        private const val POSITION_SAVE_INTERVAL_MS = 2_000L
        private const val NORMAL_VOLUME = 1f
        private const val DUCKED_VOLUME = 0.25f
        private const val TAG = "PlaybackService"
    }
}
