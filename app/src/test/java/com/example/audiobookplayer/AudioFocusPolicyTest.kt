package com.example.audiobookplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusPolicyTest {

    @Test
    fun transientLossRetainsRequestForGainCallback() {
        assertFalse(AudioFocusPolicy.shouldReleaseRequestAfterPause(shouldResumeOnGain = true))
    }

    @Test
    fun permanentLossReleasesRequest() {
        assertTrue(AudioFocusPolicy.shouldReleaseRequestAfterPause(shouldResumeOnGain = false))
    }

    @Test
    fun gainOnlyResumesPendingPreparedPausedPlayback() {
        assertTrue(AudioFocusPolicy.shouldResumeOnGain(true, isPrepared = true, isPlaying = false))
        assertFalse(AudioFocusPolicy.shouldResumeOnGain(false, isPrepared = true, isPlaying = false))
        assertFalse(AudioFocusPolicy.shouldResumeOnGain(true, isPrepared = false, isPlaying = false))
        assertFalse(AudioFocusPolicy.shouldResumeOnGain(true, isPrepared = true, isPlaying = true))
    }
}
