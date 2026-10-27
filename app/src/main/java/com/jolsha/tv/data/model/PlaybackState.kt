package com.jolsha.tv.data.model

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Buffering : PlaybackState
    data class Ready(val durationMs: Long = 0L, val isLive: Boolean = true) : PlaybackState
    data object Ended : PlaybackState
    data class Error(val message: String, val isRecoverable: Boolean = true, val retryCount: Int = 0) : PlaybackState
}

data class AudioTrackInfo(
    val id: String,
    val label: String,
    val language: String,
    val isSelected: Boolean
)
