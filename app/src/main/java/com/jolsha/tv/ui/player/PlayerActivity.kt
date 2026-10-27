package com.jolsha.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.jolsha.tv.R
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.model.PlaybackState
import com.jolsha.tv.databinding.ActivityPlayerBinding
import com.jolsha.tv.playback.PlaybackListener
import com.jolsha.tv.playback.PlayerManager
import com.jolsha.tv.ui.common.FocusHighlightHelper
import com.jolsha.tv.ui.dialog.AudioTracksDialog
import com.jolsha.tv.ui.player.adapter.QuickChannelAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity(), PlaybackListener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var viewModel: PlayerViewModel

    private lateinit var playerManager: PlayerManager
    private lateinit var quickChannelAdapter: QuickChannelAdapter

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsd() }
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30_000L) // Update clock every 30s
        }
    }

    private var isOsdVisible: Boolean = true
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    companion object {
        private const val EXTRA_CHANNEL_ID = "extra_channel_id"
        private const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val OSD_AUTO_HIDE_DELAY_MS = 4000L

        fun createIntent(context: Context, channelId: String, categoryId: String): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_ID, channelId)
                putExtra(EXTRA_CATEGORY_ID, categoryId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PlayerViewModel::class.java]
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playerManager = PlayerManager(this)
        playerManager.listener = this
        playerManager.attachPlayerView(binding.playerView)

        setupQuickChannelGuide()
        setupHudControls()
        observeViewModel()

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
        val categoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: ""
        viewModel.initPlayer(channelId, categoryId)

        // Apply default aspect ratio
        val defaultAspect = viewModel.aspectRatio.value
        playerManager.setAspectRatio(defaultAspect)

        showOsd()
        clockHandler.post(clockRunnable)
    }

    private fun setupHudControls() {
        val focusScale = 1.05f
        val elevation = 8f

        FocusHighlightHelper.attachFocusAnimation(binding.buttonAspectRatio, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonAudioTracks, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonFavorite, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonQuickGuide, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonRetry, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonNextChannel, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonCloseDrawer, 1.1f, 6f)

        // Aspect Ratio Button
        binding.buttonAspectRatio.setOnClickListener {
            resetOsdTimer()
            val nextAspect = viewModel.cycleAspectRatio()
            playerManager.setAspectRatio(nextAspect)
            binding.textAspectAction.text = nextAspect.displayName
            binding.badgeTopAspectRatio.text = nextAspect.displayName
            Toast.makeText(this, "Aspect Ratio: ${nextAspect.displayName}", Toast.LENGTH_SHORT).show()
        }

        // Audio Track Button
        binding.buttonAudioTracks.setOnClickListener {
            resetOsdTimer()
            val tracks = playerManager.getAvailableAudioTracks()
            if (tracks.isEmpty()) {
                Toast.makeText(this, "No alternate audio tracks available", Toast.LENGTH_SHORT).show()
            } else {
                AudioTracksDialog(this, tracks) { selectedTrack ->
                    playerManager.selectAudioTrack(selectedTrack.id)
                    Toast.makeText(this, "Selected: ${selectedTrack.label}", Toast.LENGTH_SHORT).show()
                }.show()
            }
        }

        // Favorite Toggle Button
        binding.buttonFavorite.setOnClickListener {
            resetOsdTimer()
            toggleFavorite()
        }

        // Quick Channel Guide Button
        binding.buttonQuickGuide.setOnClickListener {
            openQuickGuide()
        }

        // Close Drawer Button
        binding.buttonCloseDrawer.setOnClickListener {
            closeQuickGuide()
        }

        // Error Retry Button
        binding.buttonRetry.setOnClickListener {
            binding.playerErrorView.visibility = View.GONE
            playerManager.retry()
        }

        // Error Next Channel Button
        binding.buttonNextChannel.setOnClickListener {
            binding.playerErrorView.visibility = View.GONE
            val next = viewModel.nextChannel()
            next?.let { switchChannel(it) }
        }
    }

    private fun setupQuickChannelGuide() {
        quickChannelAdapter = QuickChannelAdapter { selectedChannel ->
            viewModel.playChannel(selectedChannel)
            switchChannel(selectedChannel)
            closeQuickGuide()
        }

        binding.recyclerQuickChannels.apply {
            adapter = quickChannelAdapter
            layoutManager = LinearLayoutManager(this@PlayerActivity)
            setHasFixedSize(true)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Channel List
                launch {
                    viewModel.channels.collectLatest { channels ->
                        quickChannelAdapter.submitList(channels)
                    }
                }

                // Observe Current Channel
                launch {
                    viewModel.currentChannel.collectLatest { channel ->
                        if (channel != null) {
                            quickChannelAdapter.currentPlayingChannelId = channel.id
                            updateChannelMetadata(channel)
                            switchChannel(channel)
                        }
                    }
                }

                // Observe Favorite state
                launch {
                    viewModel.isFavorite.collectLatest { isFav ->
                        updateFavoriteButton(isFav)
                    }
                }

                // Observe Aspect Ratio
                launch {
                    viewModel.aspectRatio.collectLatest { aspect ->
                        binding.textAspectAction.text = aspect.displayName
                        binding.badgeTopAspectRatio.text = aspect.displayName
                    }
                }
            }
        }
    }

    private fun switchChannel(channel: Channel) {
        binding.playerErrorView.visibility = View.GONE
        playerManager.playChannel(channel)
        showOsd()
    }

    private fun updateChannelMetadata(channel: Channel) {
        val numStr = if (channel.formattedNumber.isNotBlank()) "#${channel.formattedNumber}" else ""
        binding.textTopChannelNumber.text = numStr
        binding.textTopChannelName.text = channel.displayName

        binding.textHudChannelNumber.text = numStr
        binding.textHudChannelName.text = channel.displayName
        binding.textHudCategory.text = channel.category.uppercase()

        val logoUrl = channel.logoUrl.trim()
        if (logoUrl.isNotBlank()) {
            binding.textHudLogoFallback.visibility = View.GONE
            binding.imageHudLogo.visibility = View.VISIBLE
            binding.imageHudLogo.load(logoUrl) {
                crossfade(true)
                transformations(RoundedCornersTransformation(8f))
                listener(
                    onError = { _, _ -> showHudFallbackMonogram(channel) },
                    onSuccess = { _, _ ->
                        binding.textHudLogoFallback.visibility = View.GONE
                        binding.imageHudLogo.visibility = View.VISIBLE
                    }
                )
            }
        } else {
            showHudFallbackMonogram(channel)
        }
    }

    private fun showHudFallbackMonogram(channel: Channel) {
        binding.imageHudLogo.visibility = View.GONE
        binding.textHudLogoFallback.visibility = View.VISIBLE
        val words = channel.displayName.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        binding.textHudLogoFallback.text = when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            words.size == 1 && words[0].length >= 3 -> words[0].take(3).uppercase()
            words.size == 1 -> words[0].uppercase()
            else -> "TV"
        }
    }

    private fun updateFavoriteButton(isFav: Boolean) {
        if (isFav) {
            binding.imageHudFavoriteStar.setImageResource(R.drawable.ic_favorite)
            binding.textHudFavorite.text = "Starred"
        } else {
            binding.imageHudFavoriteStar.setImageResource(R.drawable.ic_favorite_border)
            binding.textHudFavorite.text = "Star"
        }
    }

    private fun toggleFavorite() {
        val isFav = viewModel.toggleFavorite()
        val channel = viewModel.currentChannel.value
        val msg = if (isFav) getString(R.string.favorite_added) else getString(R.string.favorite_removed)
        Toast.makeText(this, "${channel?.displayName}: $msg", Toast.LENGTH_SHORT).show()
    }

    private fun updateClock() {
        binding.textClockTime.text = timeFormat.format(Date())
    }

    // =========================================================================
    // OSD Visibility & Auto-Hide Timer
    // =========================================================================

    private fun showOsd() {
        isOsdVisible = true
        binding.topHudContainer.visibility = View.VISIBLE
        binding.bottomHudContainer.visibility = View.VISIBLE
        resetOsdTimer()
    }

    private fun hideOsd() {
        if (binding.quickGuideDrawer.visibility == View.VISIBLE) return
        isOsdVisible = false
        binding.topHudContainer.visibility = View.GONE
        binding.bottomHudContainer.visibility = View.GONE
    }

    private fun toggleOsd() {
        if (isOsdVisible) {
            hideOsd()
        } else {
            showOsd()
        }
    }

    private fun resetOsdTimer() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, OSD_AUTO_HIDE_DELAY_MS)
    }

    // =========================================================================
    // Quick Channel Guide Drawer
    // =========================================================================

    private fun openQuickGuide() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        binding.quickGuideDrawer.visibility = View.VISIBLE
        // Scroll to currently playing channel
        val currentChannel = viewModel.currentChannel.value
        if (currentChannel != null) {
            val index = viewModel.channels.value.indexOfFirst { it.id == currentChannel.id }
            if (index != -1) {
                binding.recyclerQuickChannels.post {
                    binding.recyclerQuickChannels.scrollToPosition(index)
                    binding.recyclerQuickChannels.layoutManager?.findViewByPosition(index)?.requestFocus()
                }
            }
        }
    }

    private fun closeQuickGuide() {
        binding.quickGuideDrawer.visibility = View.GONE
        showOsd()
    }

    // =========================================================================
    // PlaybackListener Callbacks
    // =========================================================================

    override fun onPlaybackStateChanged(state: PlaybackState) {
        runOnUiThread {
            viewModel.updatePlaybackState(state)
            when (state) {
                is PlaybackState.Buffering -> {
                    binding.playerBufferingView.visibility = View.VISIBLE
                    binding.playerErrorView.visibility = View.GONE
                }
                is PlaybackState.Ready -> {
                    binding.playerBufferingView.visibility = View.GONE
                    binding.playerErrorView.visibility = View.GONE
                }
                is PlaybackState.Error -> {
                    binding.playerBufferingView.visibility = View.GONE
                    binding.playerErrorView.visibility = View.VISIBLE
                    binding.textErrorMessage.text = state.message
                    binding.buttonRetry.requestFocus()
                }
                else -> {
                    binding.playerBufferingView.visibility = View.GONE
                }
            }
        }
    }

    override fun onVideoSizeChanged(width: Int, height: Int) {
        runOnUiThread {
            val resText = when {
                width >= 3840 || height >= 2160 -> "4K UHD"
                width >= 1920 || height >= 1080 -> "1080p FHD"
                width >= 1280 || height >= 720 -> "720p HD"
                height > 0 -> "${height}p SD"
                else -> "HD"
            }
            binding.badgeTopResolution.text = resText
        }
    }

    override fun onAudioTracksChanged(tracks: List<AudioTrackInfo>) {
        runOnUiThread {
            viewModel.updateAudioTracks(tracks)
        }
    }

    override fun onPlayerError(error: PlaybackState.Error) {
        runOnUiThread {
            binding.playerBufferingView.visibility = View.GONE
            binding.playerErrorView.visibility = View.VISIBLE
            binding.textErrorMessage.text = error.message
            binding.buttonRetry.requestFocus()
        }
    }

    // =========================================================================
    // TV Remote Key Event Handling
    // =========================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetOsdTimer()

        when (keyCode) {
            // DPAD_CENTER / OK / ENTER: Show OSD if hidden
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event?.isLongPress == true) {
                    toggleFavorite()
                    return true
                }
                if (!isOsdVisible && binding.quickGuideDrawer.visibility != View.VISIBLE) {
                    showOsd()
                    return true
                }
            }

            // DPAD_UP / DPAD_DOWN: Open Quick Channel Guide if hidden
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.quickGuideDrawer.visibility != View.VISIBLE && !isOsdVisible) {
                    openQuickGuide()
                    return true
                }
            }

            // Channel Switch Buttons
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                val next = viewModel.nextChannel()
                next?.let { switchChannel(it) }
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                val prev = viewModel.previousChannel()
                prev?.let { switchChannel(it) }
                return true
            }

            // Recall / Previous Channel key
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                val recalled = viewModel.recallChannel()
                if (recalled != null) {
                    switchChannel(recalled)
                    Toast.makeText(this, "Recall: ${recalled.displayName}", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            // Yellow key or Info key: Toggle Favorite
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                toggleFavorite()
                return true
            }

            // Aspect Ratio key
            KeyEvent.KEYCODE_WINDOW -> {
                val next = viewModel.cycleAspectRatio()
                playerManager.setAspectRatio(next)
                Toast.makeText(this, "Aspect Ratio: ${next.displayName}", Toast.LENGTH_SHORT).show()
                return true
            }

            // Media Play/Pause
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (playerManager.isPlaying) {
                    playerManager.pause()
                    Toast.makeText(this, "Paused", Toast.LENGTH_SHORT).show()
                } else {
                    playerManager.resume()
                    Toast.makeText(this, "Playing", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            // BACK key
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (binding.quickGuideDrawer.visibility == View.VISIBLE) {
                    closeQuickGuide()
                    return true
                }
                if (isOsdVisible) {
                    hideOsd()
                    return true
                }
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    // =========================================================================
    // Activity Lifecycle
    // =========================================================================

    override fun onResume() {
        super.onResume()
        playerManager.resume()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onStop() {
        super.onStop()
        playerManager.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        osdHandler.removeCallbacks(hideOsdRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        playerManager.release()
    }
}
