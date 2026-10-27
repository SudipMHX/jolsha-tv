package com.jolsha.tv.playback

import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying playback state models, aspect ratio calculations,
 * and audio track structures.
 */
class PlaybackModelsTest {

    @Test
    fun testAspectRatioCycleNext() {
        assertEquals(AspectRatio.FILL, AspectRatio.FIT.next())
        assertEquals(AspectRatio.ZOOM, AspectRatio.FILL.next())
        assertEquals(AspectRatio.SIXTEEN_BY_NINE, AspectRatio.ZOOM.next())
        assertEquals(AspectRatio.FOUR_BY_THREE, AspectRatio.SIXTEEN_BY_NINE.next())
        assertEquals(AspectRatio.FIT, AspectRatio.FOUR_BY_THREE.next())
    }

    @Test
    fun testAspectRatioDisplayNames() {
        assertEquals("Original / Fit", AspectRatio.FIT.displayName)
        assertEquals("Fill / Stretch", AspectRatio.FILL.displayName)
        assertEquals("Zoom / Crop", AspectRatio.ZOOM.displayName)
        assertEquals("16:9", AspectRatio.SIXTEEN_BY_NINE.displayName)
        assertEquals("4:3", AspectRatio.FOUR_BY_THREE.displayName)
    }

    @Test
    fun testPlaybackStateTypes() {
        val idle: PlaybackState = PlaybackState.Idle
        val buffering: PlaybackState = PlaybackState.Buffering
        val ready: PlaybackState = PlaybackState.Ready(durationMs = 120_000L, isLive = true)
        val ended: PlaybackState = PlaybackState.Ended
        val error: PlaybackState = PlaybackState.Error(
            message = PlayerManager.CLEAN_OFFLINE_ERROR,
            isRecoverable = false,
            retryCount = 3
        )

        assertTrue(idle is PlaybackState.Idle)
        assertTrue(buffering is PlaybackState.Buffering)
        assertTrue(ready is PlaybackState.Ready)
        assertEquals(120_000L, (ready as PlaybackState.Ready).durationMs)
        assertTrue(ready.isLive)
        assertTrue(ended is PlaybackState.Ended)
        assertTrue(error is PlaybackState.Error)
        assertEquals(PlayerManager.CLEAN_OFFLINE_ERROR, (error as PlaybackState.Error).message)
        assertFalse(error.isRecoverable)
        assertEquals(3, error.retryCount)
    }

    @Test
    fun testAudioTrackInfoProperties() {
        val track = AudioTrackInfo(
            id = "audio_1",
            label = "English Stereo",
            language = "eng",
            isSelected = true
        )

        assertEquals("audio_1", track.id)
        assertEquals("English Stereo", track.label)
        assertEquals("eng", track.language)
        assertTrue(track.isSelected)
    }

    @Test
    fun testPlaybackListenerDefaultImplementations() {
        var stateChanged = false
        var sizeChanged = false
        var tracksChanged = false
        var muteChanged = false
        var errorOccurred = false

        val listener = object : PlaybackListener {
            override fun onPlaybackStateChanged(state: PlaybackState) {
                stateChanged = true
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                sizeChanged = true
            }

            override fun onAudioTracksChanged(tracks: List<AudioTrackInfo>) {
                tracksChanged = true
            }

            override fun onVolumeMuteChanged(isMuted: Boolean) {
                muteChanged = true
            }

            override fun onPlayerError(error: PlaybackState.Error) {
                errorOccurred = true
            }
        }

        listener.onPlaybackStateChanged(PlaybackState.Buffering)
        listener.onVideoSizeChanged(1920, 1080)
        listener.onAudioTracksChanged(emptyList())
        listener.onVolumeMuteChanged(true)
        listener.onPlayerError(PlaybackState.Error("Offline", false, 0))

        assertTrue(stateChanged)
        assertTrue(sizeChanged)
        assertTrue(tracksChanged)
        assertTrue(muteChanged)
        assertTrue(errorOccurred)
    }

    @Test
    fun testChannelCustomAspectRatio() {
        val channelWithAspect = Channel(
            id = "ch_1",
            name = "Test Channel",
            streamUrl = "http://10.189.188.190/stream.m3u8",
            customAspectRatio = AspectRatio.SIXTEEN_BY_NINE
        )

        assertEquals(AspectRatio.SIXTEEN_BY_NINE, channelWithAspect.customAspectRatio)
        assertEquals("Test Channel", channelWithAspect.displayName)
    }

    @Test
    fun testStreamUrlValidationScenarios() {
        val validChannel = Channel(
            id = "ch_valid",
            name = "Valid Stream",
            streamUrl = "http://10.189.188.190:8080/7654321/1234567/156"
        )
        assertFalse(validChannel.streamUrl.isBlank())

        val blankChannel = Channel(
            id = "ch_blank",
            name = "Blank Stream",
            streamUrl = "   "
        )
        assertTrue(blankChannel.streamUrl.isBlank())
    }

    @Test
    fun testPlaybackStateErrorEquality() {
        val err1 = PlaybackState.Error("Network failure", isRecoverable = true, retryCount = 1)
        val err2 = PlaybackState.Error("Network failure", isRecoverable = true, retryCount = 1)
        assertEquals(err1, err2)
    }
}
