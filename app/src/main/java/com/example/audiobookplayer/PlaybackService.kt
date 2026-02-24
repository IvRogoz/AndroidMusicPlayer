package com.example.audiobookplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import java.util.concurrent.Executors

class PlaybackService : MediaBrowserServiceCompat() {

    private val preferences by lazy { getSharedPreferences(PlaybackPrefs.PREFS_NAME, MODE_PRIVATE) }
    private lateinit var mediaSession: MediaSessionCompat
    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var currentUri: Uri? = null
    private var currentTitle: String? = null
    private var currentDurationMs: Long = 0L
    private var currentArtwork: Bitmap? = null
    private var isForeground = false
    private val artExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val positionSaveRunnable = object : Runnable {
        override fun run() {
            persistCurrentPosition()
            if (isPrepared && mediaPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        mediaSession = MediaSessionCompat(this, "PlaybackService").apply {
            setCallback(sessionCallback)
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setSessionActivity(createContentIntent())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        stopPositionPersistence()
        persistCurrentPosition()
        stopForegroundNow()
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        artExecutor.shutdownNow()
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
                val extras = Bundle().apply {
                    putString(EXTRA_TRACK_TITLE, preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, null))
                    putBoolean(EXTRA_RESTORE_POSITION, true)
                }
                prepareFromUri(Uri.parse(lastUri), extras, playWhenReady = true)
            } else {
                mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
            }
        }

        override fun onPlayFromUri(uri: Uri, extras: Bundle?) {
            prepareFromUri(uri, extras, playWhenReady = true)
        }

        override fun onPrepareFromUri(uri: Uri, extras: Bundle?) {
            prepareFromUri(uri, extras, playWhenReady = false)
        }

        override fun onPause() {
            val player = mediaPlayer ?: return
            if (!isPrepared) {
                return
            }
            if (player.isPlaying) {
                player.pause()
            }
            stopPositionPersistence()
            savePlaybackPosition(player.currentPosition.toLong())
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, player.currentPosition.toLong())
        }

        override fun onStop() {
            val player = mediaPlayer
            if (player != null && isPrepared) {
                try {
                    player.pause()
                    player.seekTo(0)
                } catch (exception: Exception) {
                }
                stopPositionPersistence()
                savePlaybackPosition(0L)
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
            } else {
                stopPositionPersistence()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
            }
        }

        override fun onSeekTo(pos: Long) {
            val player = mediaPlayer ?: return
            if (!isPrepared) {
                return
            }
            val target = pos.coerceAtLeast(0L).toInt()
            player.seekTo(target)
            savePlaybackPosition(target.toLong())
            val state = if (player.isPlaying) {
                PlaybackStateCompat.STATE_PLAYING
            } else {
                PlaybackStateCompat.STATE_PAUSED
            }
            updatePlaybackState(state, target.toLong())
        }
    }

    private fun prepareFromUri(uri: Uri, extras: Bundle?, playWhenReady: Boolean) {
        releasePlayer()
        currentUri = uri
        currentTitle = extras?.getString(EXTRA_TRACK_TITLE)
            ?: preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_TITLE, null)
        currentArtwork = null
        isPrepared = false
        loadArtworkAsync(uri)
        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.setOnPreparedListener { preparedPlayer ->
            isPrepared = true
            currentDurationMs = preparedPlayer.duration.toLong()
            val restorePosition = extras?.getBoolean(EXTRA_RESTORE_POSITION, false) == true
            val seekPositionMs = extras?.getLong(EXTRA_SEEK_POSITION_MS)
            if (seekPositionMs != null) {
                preparedPlayer.seekTo(seekPositionMs.coerceAtLeast(0L).toInt())
            } else if (restorePosition) {
                val lastUri = preferences.getString(PlaybackPrefs.KEY_LAST_TRACK_URI, null)
                if (uri.toString() == lastUri) {
                    val resumePosition = preferences.getLong(PlaybackPrefs.KEY_LAST_POSITION, 0L).toInt()
                    if (resumePosition in 1 until preparedPlayer.duration) {
                        preparedPlayer.seekTo(resumePosition)
                    }
                }
            }
            updateMetadata()
            if (playWhenReady) {
                startPlayback()
            } else {
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED, preparedPlayer.currentPosition.toLong())
            }
        }
        player.setOnCompletionListener {
            stopPositionPersistence()
            savePlaybackPosition(0L)
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0L)
            mediaSession.sendSessionEvent(EVENT_PLAYBACK_COMPLETED, null)
        }
        player.setOnErrorListener { _, _, _ ->
            stopPositionPersistence()
            releasePlayer()
            updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
            mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
            true
        }
        try {
            player.setDataSource(this, uri)
            player.prepareAsync()
            updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0L)
            saveCurrentTrack(uri, currentTitle)
        } catch (exception: Exception) {
            stopPositionPersistence()
            releasePlayer()
            updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0L)
            mediaSession.sendSessionEvent(EVENT_PLAYBACK_ERROR, null)
        }
    }

    private fun startPlayback() {
        val player = mediaPlayer ?: return
        if (!isPrepared) {
            return
        }
        if (!player.isPlaying) {
            player.start()
        }
        startPositionPersistence()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, player.currentPosition.toLong())
    }

    private fun startPositionPersistence() {
        mainHandler.removeCallbacks(positionSaveRunnable)
        mainHandler.post(positionSaveRunnable)
    }

    private fun stopPositionPersistence() {
        mainHandler.removeCallbacks(positionSaveRunnable)
    }

    private fun persistCurrentPosition() {
        val player = mediaPlayer ?: return
        if (!isPrepared) {
            return
        }
        savePlaybackPosition(player.currentPosition.toLong())
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
            PlaybackStateCompat.ACTION_PLAY_FROM_URI or
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
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)
        currentArtwork?.let { artwork ->
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        val metadata = metadataBuilder.build()
        mediaSession.setMetadata(metadata)
        val state = mediaSession.controller.playbackState?.state ?: PlaybackStateCompat.STATE_NONE
        updatePlaybackNotification(state)
    }

    private fun loadArtworkAsync(uri: Uri) {
        val uriKey = uri.toString()
        artExecutor.execute {
            val bitmap = loadEmbeddedArtwork(uri)
            mainHandler.post {
                if (currentUri?.toString() == uriKey) {
                    currentArtwork = bitmap
                    updateMetadata()
                }
            }
        }
    }

    private fun loadEmbeddedArtwork(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val art = retriever.embeddedPicture ?: return null
            decodeArtwork(art, ART_TARGET_SIZE)
        } catch (exception: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodeArtwork(data: ByteArray, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds, targetSize, targetSize)
            inJustDecodeBounds = false
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
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
        persistCurrentPosition()
        mediaPlayer?.release()
        mediaPlayer = null
        isPrepared = false
    }

    private fun updatePlaybackNotification(state: Int) {
        when (state) {
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_PAUSED -> {
                val notification = buildNotification(state)
                if (state == PlaybackStateCompat.STATE_PLAYING || state == PlaybackStateCompat.STATE_BUFFERING) {
                    if (!isForeground) {
                        startForeground(NOTIFICATION_ID, notification)
                        isForeground = true
                    } else {
                        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
                    }
                } else {
                    if (isForeground) {
                        stopForeground(false)
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
            stopForeground(true)
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
            .setContentText(getString(R.string.app_name))
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

    companion object {
        const val EXTRA_TRACK_TITLE = "com.example.audiobookplayer.extra.TRACK_TITLE"
        const val EXTRA_RESTORE_POSITION = "com.example.audiobookplayer.extra.RESTORE_POSITION"
        const val EXTRA_SEEK_POSITION_MS = "com.example.audiobookplayer.extra.SEEK_POSITION_MS"
        const val EVENT_PLAYBACK_COMPLETED = "com.example.audiobookplayer.event.PLAYBACK_COMPLETED"
        const val EVENT_PLAYBACK_ERROR = "com.example.audiobookplayer.event.PLAYBACK_ERROR"
        private const val ROOT_ID = "root"
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1001
        private const val CONTENT_INTENT_REQUEST_CODE = 1002
        private const val ART_TARGET_SIZE = 512
        private const val POSITION_SAVE_INTERVAL_MS = 2_000L
    }
}
