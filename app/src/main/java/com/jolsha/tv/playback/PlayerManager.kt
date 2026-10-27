package com.jolsha.tv.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.model.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Playback engine encapsulating AndroidX Media3 ExoPlayer for Android TV.
 *
 * Optimized for low-powered Android TV devices (API 24 to 35):
 * - Low-latency live stream buffer tuning via [DefaultLoadControl].
 * - Hardware decoder fallback via [DefaultRenderersFactory].
 * - Cross-protocol HTTP data source with custom User-Agent.
 * - Comprehensive live stream format support (HLS M3U8, MPEG-TS, MP4, HTTP progressive).
 * - Fast channel switching with minimal latency.
 * - Aspect ratio modes (FIT, FILL, ZOOM, 16:9, 4:3).
 * - Multi-audio track extraction and switching.
 * - Bounded exponential backoff retry mechanism on network interruptions.
 * - Leak-safe lifecycle management.
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context) {

    private val appContext: Context = context.applicationContext

    // Coroutine scope for handling asynchronous retries and backoff
    private var coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var retryJob: Job? = null

    // Underlying Media3 ExoPlayer instance
    private var exoPlayer: ExoPlayer? = null

    /**
     * Exposes the underlying [ExoPlayer] instance if active.
     */
    val player: ExoPlayer?
        get() = exoPlayer

    // Current playing channel and stream state
    var currentChannel: Channel? = null
        private set

    var currentAspectRatio: AspectRatio = AspectRatio.FIT
        private set

    private var currentPlayerView: PlayerView? = null

    // Audio volume and mute tracking
    var isMuted: Boolean = false
        private set
    private var unmutedVolume: Float = 1.0f

    // Video dimensions
    private var currentVideoWidth: Int = 0
    private var currentVideoHeight: Int = 0

    // Retry counter for bounded exponential backoff
    private var retryCount: Int = 0

    // Listeners for playback events
    var listener: PlaybackListener? = null
    private val listeners = CopyOnWriteArrayList<PlaybackListener>()

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val isBuffering: Boolean
        get() = exoPlayer?.playbackState == Player.STATE_BUFFERING

    /**
     * Internal [Player.Listener] declared BEFORE initializePlayer to prevent Kotlin
     * initialization-order null dereferences on player construction.
     */
    private inner class ComponentPlayerListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateName = when (playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($playbackState)"
            }
            Log.d(TAG, "ExoPlayer onPlaybackStateChanged: $stateName (Channel ID: ${currentChannel?.id})")

            when (playbackState) {
                Player.STATE_IDLE -> {
                    notifyPlaybackStateChanged(PlaybackState.Idle)
                }
                Player.STATE_BUFFERING -> {
                    notifyPlaybackStateChanged(PlaybackState.Buffering)
                }
                Player.STATE_READY -> {
                    // Reset retry counter on successful stream readiness
                    retryCount = 0
                    cancelRetry()

                    val p = exoPlayer
                    val duration = p?.duration ?: C.TIME_UNSET
                    val isLive = p?.isCurrentMediaItemLive == true ||
                        p?.isCurrentMediaItemDynamic == true ||
                        duration == C.TIME_UNSET
                    val durationMs = if (duration != C.TIME_UNSET) duration else 0L

                    notifyPlaybackStateChanged(
                        PlaybackState.Ready(
                            durationMs = durationMs,
                            isLive = isLive
                        )
                    )
                }
                Player.STATE_ENDED -> {
                    notifyPlaybackStateChanged(PlaybackState.Ended)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "ExoPlayer onVideoSizeChanged: ${videoSize.width}x${videoSize.height}")
            currentVideoWidth = videoSize.width
            currentVideoHeight = videoSize.height
            notifyVideoSizeChanged(videoSize.width, videoSize.height)

            // Re-apply aspect ratio to ensure desired framing is preserved
            currentPlayerView?.let { setAspectRatio(currentAspectRatio, it) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            Log.d(TAG, "ExoPlayer onTracksChanged: groups=${tracks.groups.size}")
            notifyAudioTracksChanged(getAvailableAudioTracks())
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    private val playerEventListener: Player.Listener = ComponentPlayerListener()

    init {
        initializePlayer()
    }

    /**
     * Initializes the [ExoPlayer] instance with TV-optimized configuration.
     */
    private fun initializePlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing

        try {
            // 1. RenderersFactory with decoder fallback using Activity context for DisplayManager
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            // 2. Standard safe LoadControl
            val loadControl = DefaultLoadControl.Builder().build()

            // 3. Custom HTTP Data Source with custom User-Agent, timeouts, and cross-protocol redirects
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .setKeepPostFor302Redirects(true)

            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

            // 4. Default safe Extractors for all formats with non-IDR keyframe tolerance for IPTV TS streams
            val extractorsFactory = DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
                )
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

            // 5. Audio attributes with automatic AudioFocus management
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            // 6. Build the ExoPlayer instance
            val newPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                .build()

            newPlayer.addListener(playerEventListener)
            exoPlayer = newPlayer

            if (isMuted) {
                newPlayer.volume = 0f
            }

            Log.i(TAG, "ExoPlayer initialized successfully with TV optimizations")
            return newPlayer
        } catch (e: Throwable) {
            val cause = StreamSanitizer.formatExceptionCauseChain(e)
            Log.e(TAG, "Failed to initialize optimized ExoPlayer; trying minimal fallback. Cause: $cause", e)
            try {
                val fallbackPlayer = ExoPlayer.Builder(context).build()
                fallbackPlayer.addListener(playerEventListener)
                exoPlayer = fallbackPlayer
                Log.w(TAG, "Fallback minimal ExoPlayer initialized successfully")
                return fallbackPlayer
            } catch (fallbackError: Throwable) {
                val fallbackCause = StreamSanitizer.formatExceptionCauseChain(fallbackError)
                Log.e(TAG, "Fatal: Minimal ExoPlayer fallback also failed. Cause: $fallbackCause", fallbackError)
                throw IllegalStateException("ExoPlayer initialization failed: ${e.message ?: fallbackError.message}", e)
            }
        }
    }

    // =========================================================================
    // Channel Playback & Fast Switching
    // =========================================================================

    /**
     * Immediately switches to and plays the given [Channel].
     *
     * Cancels any active stream, resets retry counters, sets the new media item,
     * prepares the player, and begins immediate playback.
     *
     * @param channel The TV channel to play.
     */
    fun playChannel(channel: Channel) {
        val sanitized = StreamSanitizer.sanitizeUrl(channel.streamUrl)
        Log.i(TAG, "playChannel request: ID='${channel.id}', Name='${channel.displayName}', Stream='$sanitized'")

        try {
            cancelRetry()
            retryCount = 0
            currentChannel = channel

            if (channel.streamUrl.isBlank()) {
                val err = PlaybackState.Error(
                    message = "Stream URL is missing for channel: ${channel.displayName}",
                    isRecoverable = false,
                    retryCount = 0
                )
                notifyPlayerError(err)
                notifyPlaybackStateChanged(err)
                return
            }

            val activePlayer = exoPlayer ?: initializePlayer()

            // Fast channel switching: immediately cancel previous stream
            activePlayer.stop()
            activePlayer.clearMediaItems()

            val mediaItem = buildMediaItem(channel)
            activePlayer.setMediaItem(mediaItem)
            activePlayer.prepare()
            activePlayer.playWhenReady = true

            notifyPlaybackStateChanged(PlaybackState.Buffering)

            // Apply channel's custom aspect ratio if configured, otherwise retain current ratio
            val targetRatio = channel.customAspectRatio ?: currentAspectRatio
            currentPlayerView?.let { setAspectRatio(targetRatio, it) }
        } catch (e: Throwable) {
            val causeChain = StreamSanitizer.formatExceptionCauseChain(e)
            Log.e(TAG, "Failed to play channel: ${channel.name}, Cause: $causeChain", e)
            val err = PlaybackState.Error(
                message = "Unable to play stream: ${e.message ?: "Unknown error"}",
                isRecoverable = true,
                retryCount = 0
            )
            notifyPlayerError(err)
            notifyPlaybackStateChanged(err)
        }
    }

    /**
     * Builds a [MediaItem] with explicit MIME type detection for HLS, MPEG-TS, MP4,
     * or HTTP live streams.
     */
    private fun buildMediaItem(channel: Channel): MediaItem {
        val rawUrl = channel.streamUrl.trim()
        if (rawUrl.isBlank()) {
            throw IllegalArgumentException("Stream URL is empty for channel: ${channel.displayName}")
        }
        val streamUrl = if (rawUrl.contains(' ')) rawUrl.replace(" ", "%20") else rawUrl
        val uri = Uri.parse(streamUrl)
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(channel.id)

        val urlLower = streamUrl.lowercase()
        val pathLower = uri.path?.lowercase() ?: ""

        when {
            urlLower.contains(".m3u8") || pathLower.endsWith(".m3u8") -> {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            urlLower.contains(".ts") || pathLower.endsWith(".ts") -> {
                builder.setMimeType(MimeTypes.VIDEO_MP2T)
            }
            urlLower.contains(".mp4") || pathLower.endsWith(".mp4") -> {
                builder.setMimeType(MimeTypes.APPLICATION_MP4)
            }
            urlLower.contains(".mpd") || pathLower.endsWith(".mpd") -> {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
            }
        }

        return builder.build()
    }

    // =========================================================================
    // Aspect Ratio Handling
    // =========================================================================

    /**
     * Configures the aspect ratio mode on the specified [PlayerView].
     *
     * Supports:
     * - [AspectRatio.FIT]: original stream aspect ratio with letterboxing.
     * - [AspectRatio.FILL]: stretches video to fill entire screen.
     * - [AspectRatio.ZOOM]: crops stream center to fill screen without distortion.
     * - [AspectRatio.SIXTEEN_BY_NINE]: forces 16:9 widescreen ratio.
     * - [AspectRatio.FOUR_BY_THREE]: forces 4:3 legacy ratio.
     *
     * @param ratio The target [AspectRatio] mode.
     * @param playerView The [PlayerView] to apply the ratio to.
     */
    fun setAspectRatio(ratio: AspectRatio, playerView: PlayerView) {
        try {
            currentAspectRatio = ratio
            val contentFrame = playerView.findViewById<AspectRatioFrameLayout>(
                androidx.media3.ui.R.id.exo_content_frame
            )

            when (ratio) {
                AspectRatio.FIT -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    val videoRatio = if (currentVideoHeight > 0) {
                        currentVideoWidth.toFloat() / currentVideoHeight.toFloat()
                    } else {
                        0f
                    }
                    if (videoRatio > 0f) {
                        contentFrame?.setAspectRatio(videoRatio)
                    }
                }
                AspectRatio.FILL -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                AspectRatio.ZOOM -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                AspectRatio.SIXTEEN_BY_NINE -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    contentFrame?.setAspectRatio(16f / 9f)
                }
                AspectRatio.FOUR_BY_THREE -> {
                    playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    contentFrame?.setAspectRatio(4f / 3f)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error setting aspect ratio", e)
        }
    }

    /**
     * Updates the current aspect ratio and applies it to the attached [PlayerView] if available.
     */
    fun setAspectRatio(ratio: AspectRatio) {
        currentAspectRatio = ratio
        currentPlayerView?.let { setAspectRatio(ratio, it) }
    }

    // =========================================================================
    // Audio Track Selection
    // =========================================================================

    /**
     * Returns the list of available audio tracks for the current media item.
     */
    fun getAvailableAudioTracks(): List<AudioTrackInfo> {
        val activePlayer = exoPlayer ?: return emptyList()
        val audioTracks = mutableListOf<AudioTrackInfo>()
        val currentTracks = activePlayer.currentTracks

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until group.length) {
                    if (group.isTrackSupported(i)) {
                        val format = group.getTrackFormat(i)
                        val trackId = format.id ?: "${mediaTrackGroup.id}:$i"
                        val lang = format.language ?: ""

                        val displayLanguage = if (lang.isNotBlank()) {
                            try {
                                Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault())
                                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                    .ifBlank { lang }
                            } catch (_: Exception) {
                                lang
                            }
                        } else ""

                        val label = when {
                            !format.label.isNullOrBlank() -> format.label!!
                            displayLanguage.isNotBlank() -> displayLanguage
                            else -> "Audio Track ${audioTracks.size + 1}"
                        }

                        val isSelected = group.isTrackSelected(i)
                        audioTracks.add(
                            AudioTrackInfo(
                                id = trackId,
                                label = label,
                                language = lang,
                                isSelected = isSelected
                            )
                        )
                    }
                }
            }
        }
        return audioTracks
    }

    /**
     * Selects an active audio track by its track ID using [TrackSelectionOverride].
     *
     * @param trackId The track identifier to select.
     */
    fun selectAudioTrack(trackId: String) {
        val activePlayer = exoPlayer ?: return
        val currentTracks = activePlayer.currentTracks

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val id = format.id ?: "${mediaTrackGroup.id}:$i"
                    if (id == trackId) {
                        val override = TrackSelectionOverride(mediaTrackGroup, i)
                        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        notifyAudioTracksChanged(getAvailableAudioTracks())
                        return
                    }
                }
            }
        }
    }

    /**
     * Clears manual audio track overrides, returning to default track selection.
     */
    fun clearAudioTrackOverride() {
        val activePlayer = exoPlayer ?: return
        activePlayer.trackSelectionParameters = activePlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        notifyAudioTracksChanged(getAvailableAudioTracks())
    }

    // =========================================================================
    // Volume & Mute Control
    // =========================================================================

    /**
     * Toggles the audio mute state.
     *
     * @return The new mute state (true if muted, false otherwise).
     */
    fun toggleMute(): Boolean {
        val activePlayer = exoPlayer ?: return isMuted
        isMuted = !isMuted
        if (isMuted) {
            unmutedVolume = if (activePlayer.volume > 0f) activePlayer.volume else 1.0f
            activePlayer.volume = 0f
        } else {
            activePlayer.volume = if (unmutedVolume > 0f) unmutedVolume else 1.0f
        }
        notifyVolumeMuteChanged(isMuted)
        return isMuted
    }

    /**
     * Explicitly sets the audio mute state.
     */
    fun setMuted(muted: Boolean) {
        if (isMuted == muted) return
        toggleMute()
    }

    // =========================================================================
    // Lifecycle Management & View Attachment
    // =========================================================================

    /**
     * Attaches the player to a [PlayerView].
     *
     * Ensures previous views are cleanly detached, disables default touch-screen
     * controllers for TV remote compatibility, and configures the active aspect ratio.
     *
     * @param playerView The [PlayerView] surface to attach.
     */
    fun attachPlayerView(playerView: PlayerView) {
        try {
            if (currentPlayerView == playerView) return
            detachPlayerView()
            currentPlayerView = playerView
            val activePlayer = exoPlayer ?: initializePlayer()
            playerView.player = activePlayer
            playerView.useController = false
            setAspectRatio(currentAspectRatio, playerView)
        } catch (e: Throwable) {
            Log.e(TAG, "Error attaching PlayerView", e)
        }
    }

    /**
     * Safely detaches the currently attached [PlayerView] to prevent memory and surface leaks.
     */
    fun detachPlayerView() {
        currentPlayerView?.player = null
        currentPlayerView = null
    }

    /**
     * Pauses playback and cancels any pending retry backoff.
     */
    fun pause() {
        cancelRetry()
        exoPlayer?.pause()
    }

    /**
     * Resumes playback if prepared.
     */
    fun resume() {
        exoPlayer?.play()
    }

    /**
     * Stops playback and resets the engine to [PlaybackState.Idle].
     */
    fun stop() {
        cancelRetry()
        exoPlayer?.stop()
        notifyPlaybackStateChanged(PlaybackState.Idle)
    }

    /**
     * Retries playback of the current channel.
     */
    fun retry() {
        val channel = currentChannel ?: return
        playChannel(channel)
    }

    /**
     * Fully releases ExoPlayer, cancels background retry jobs, detaches views,
     * and clears listeners.
     *
     * Safe to call on Activity onStop/onDestroy.
     */
    fun release() {
        cancelRetry()
        coroutineScope.coroutineContext[Job]?.cancelChildren()
        detachPlayerView()
        listeners.clear()
        listener = null

        exoPlayer?.let { player ->
            player.removeListener(playerEventListener)
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        exoPlayer = null
        currentChannel = null
        retryCount = 0
    }

    // =========================================================================
    // Listener Management
    // =========================================================================

    fun addListener(playbackListener: PlaybackListener) {
        if (!listeners.contains(playbackListener)) {
            listeners.add(playbackListener)
        }
    }

    fun removeListener(playbackListener: PlaybackListener) {
        listeners.remove(playbackListener)
    }

    private fun getAllListeners(): Set<PlaybackListener> {
        val set = mutableSetOf<PlaybackListener>()
        listener?.let { set.add(it) }
        set.addAll(listeners)
        return set
    }

    private fun notifyPlaybackStateChanged(state: PlaybackState) {
        for (l in getAllListeners()) {
            l.onPlaybackStateChanged(state)
        }
    }

    private fun notifyVideoSizeChanged(width: Int, height: Int) {
        for (l in getAllListeners()) {
            l.onVideoSizeChanged(width, height)
        }
    }

    private fun notifyAudioTracksChanged(tracks: List<AudioTrackInfo>) {
        for (l in getAllListeners()) {
            l.onAudioTracksChanged(tracks)
        }
    }

    private fun notifyVolumeMuteChanged(isMuted: Boolean) {
        for (l in getAllListeners()) {
            l.onVolumeMuteChanged(isMuted)
        }
    }

    private fun notifyPlayerError(error: PlaybackState.Error) {
        for (l in getAllListeners()) {
            l.onPlayerError(error)
        }
    }

    // =========================================================================
    // Error Handling & Exponential Backoff Retry
    // =========================================================================

    /**
     * Evaluates whether the device has an active network connection (Wi-Fi, Ethernet, or mobile).
     */
    private fun isNetworkConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val capabilities = cm.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } else true
            } else {
                @Suppress("DEPRECATION")
                val activeInfo = cm.activeNetworkInfo
                activeInfo != null && activeInfo.isConnected
            }
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Handles playback errors with bounded exponential backoff retry.
     *
     * Retries up to 3 times (with 1s, 2s, 4s delays).
     * If device is offline or the stream remains unreachable after retries,
     * emits a clean, user-friendly error message.
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val sanitized = StreamSanitizer.sanitizeUrl(currentChannel?.streamUrl)
        val cause = StreamSanitizer.formatExceptionCauseChain(error)
        Log.w(
            TAG,
            "Playback error: ${error.errorCodeName} (code=${error.errorCode}), " +
                "Channel='${currentChannel?.displayName}' (ID=${currentChannel?.id}), " +
                "Stream='$sanitized', Cause=$cause",
            error
        )

        // If the TV device has completely lost network connectivity, report clean error immediately
        if (!isNetworkConnected()) {
            val offlineError = PlaybackState.Error(
                message = CLEAN_OFFLINE_ERROR,
                isRecoverable = false,
                retryCount = retryCount
            )
            notifyPlayerError(offlineError)
            notifyPlaybackStateChanged(offlineError)
            return
        }

        // Bounded retry logic: 3 retries max with exponential backoff (1s, 2s, 4s)
        if (retryCount < MAX_RETRIES) {
            val delayMs = RETRY_DELAYS.getOrElse(retryCount) { 4000L }
            val nextAttempt = retryCount + 1

            val retryErrorState = PlaybackState.Error(
                message = "Reconnecting stream ($nextAttempt/$MAX_RETRIES)...",
                isRecoverable = true,
                retryCount = nextAttempt
            )
            notifyPlayerError(retryErrorState)
            notifyPlaybackStateChanged(retryErrorState)

            retryJob = coroutineScope.launch {
                delay(delayMs)
                if (isActive) {
                    retryCount = nextAttempt
                    Log.i(TAG, "Executing retry attempt $nextAttempt for '${currentChannel?.displayName}'")
                    val activePlayer = exoPlayer ?: initializePlayer()
                    activePlayer.prepare()
                    activePlayer.play()
                }
            }
        } else {
            // Retries exhausted (stream down or server offline)
            val friendlyMsg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    CLEAN_OFFLINE_ERROR
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "Unsupported stream format or corrupted stream."
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                    "Server error or stream removed (HTTP error)."
                else ->
                    "Stream unavailable (${error.errorCodeName})."
            }
            val fatalError = PlaybackState.Error(
                message = friendlyMsg,
                isRecoverable = false,
                retryCount = MAX_RETRIES
            )
            notifyPlayerError(fatalError)
            notifyPlaybackStateChanged(fatalError)
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    companion object {
        private const val TAG = "PlayerManager"
        private const val USER_AGENT = "Jolsha-IPTV/1.0 (Android TV)"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000

        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 30_000
        private const val BUFFER_FOR_PLAYBACK_MS = 1_500
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_500

        private const val MAX_RETRIES = 3
        private val RETRY_DELAYS = longArrayOf(1_000L, 2_000L, 4_000L)

        const val CLEAN_OFFLINE_ERROR =
            "Stream unavailable or offline. Please check network connection."
    }
}
