package com.example.audiobookplayer

object AudioFocusPolicy {
    fun shouldReleaseRequestAfterPause(shouldResumeOnGain: Boolean): Boolean = !shouldResumeOnGain

    fun shouldResumeOnGain(resumePending: Boolean, isPrepared: Boolean, isPlaying: Boolean): Boolean {
        return resumePending && isPrepared && !isPlaying
    }
}
