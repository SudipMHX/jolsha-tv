package com.jolsha.tv.playback

import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.data.model.PlaybackState

/**
 * Interface defining callbacks for playback engine events.
 *
 * Implementations can observe playback state transitions, video resolution changes,
 * audio track availability, volume/mute state updates, and stream errors with retry status.
 */
interface PlaybackListener {

    /**
     * Called when the playback state transitions between Idle, Buffering, Ready, Ended, or Error.
     *
     * @param state The new [PlaybackState].
     */
    fun onPlaybackStateChanged(state: PlaybackState) {}

    /**
     * Called when the video size (width or height) is determined or changes.
     *
     * @param width Video width in pixels.
     * @param height Video height in pixels.
     */
    fun onVideoSizeChanged(width: Int, height: Int) {}

    /**
     * Called when the available audio tracks are updated or the active track changes.
     *
     * @param tracks List of available [AudioTrackInfo] tracks for the current stream.
     */
    fun onAudioTracksChanged(tracks: List<AudioTrackInfo>) {}

    /**
     * Called when the audio mute state changes.
     *
     * @param isMuted True if the audio is muted, false otherwise.
     */
    fun onVolumeMuteChanged(isMuted: Boolean) {}

    /**
     * Called when an unrecoverable or transient error occurs during stream playback.
     *
     * @param error The [PlaybackState.Error] detailing the error message, retry count,
     * and recoverability.
     */
    fun onPlayerError(error: PlaybackState.Error) {}
}
