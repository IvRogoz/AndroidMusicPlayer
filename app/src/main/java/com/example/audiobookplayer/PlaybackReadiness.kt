package com.example.audiobookplayer

import android.support.v4.media.session.PlaybackStateCompat

object PlaybackReadiness {
    fun canPlay(hasTrack: Boolean, isConnected: Boolean, state: Int?): Boolean {
        return hasTrack && isConnected && state != PlaybackStateCompat.STATE_BUFFERING &&
            state != PlaybackStateCompat.STATE_CONNECTING
    }

    fun isPrepared(actions: Long): Boolean {
        return actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L
    }
}
