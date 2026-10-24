package com.example.audiobookplayer

import android.support.v4.media.session.PlaybackStateCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReadinessTest {

    @Test
    fun selectedTrackCanBeRestartedAfterStopOrFailure() {
        assertTrue(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_STOPPED))
        assertTrue(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_NONE))
        assertTrue(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_ERROR))
    }

    @Test
    fun unpreparedTrackCannotStartAnotherRequestWhileLoading() {
        assertFalse(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_BUFFERING))
        assertFalse(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_CONNECTING))
    }

    @Test
    fun playbackRequiresBothSelectionAndServiceConnection() {
        assertFalse(PlaybackReadiness.canPlay(false, true, PlaybackStateCompat.STATE_NONE))
        assertFalse(PlaybackReadiness.canPlay(true, false, PlaybackStateCompat.STATE_PAUSED))
        assertTrue(PlaybackReadiness.canPlay(true, true, PlaybackStateCompat.STATE_PAUSED))
    }

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
