package com.example.audiobookplayer

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReadinessTest {

    @Test
    fun stoppedReleasedPlayerIsNotPrepared() {
        val releasedActions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_STOP
        assertFalse(PlaybackReadiness.isPrepared(releasedActions))
    }

    @Test
    fun completedButSeekablePlayerRemainsPrepared() {
        val preparedActions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SEEK_TO
        assertTrue(PlaybackReadiness.isPrepared(preparedActions))
    }
}
