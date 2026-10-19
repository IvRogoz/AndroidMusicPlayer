package com.example.audiobookplayer

import android.support.v4.media.session.PlaybackStateCompat

object PlaybackReadiness {
    fun isPrepared(actions: Long): Boolean {
        return actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L
    }
}
