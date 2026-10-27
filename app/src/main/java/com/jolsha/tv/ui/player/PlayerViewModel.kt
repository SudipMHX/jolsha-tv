package com.jolsha.tv.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jolsha.tv.JolshaApp
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.model.PlaybackState
import com.jolsha.tv.data.repository.PlaylistRepository
import com.jolsha.tv.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JolshaApp
    val playlistRepository: PlaylistRepository = app.playlistRepository
    val preferencesRepository: UserPreferencesRepository = app.preferencesRepository

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _currentChannel = MutableStateFlow<Channel?>(null)
    val currentChannel: StateFlow<Channel?> = _currentChannel.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _aspectRatio = MutableStateFlow(preferencesRepository.getDefaultAspectRatio())
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Previous channel for recall
    private var previousChannelId: String? = null

    fun initPlayer(channelId: String, categoryId: String) {
        val channelList = playlistRepository.getChannelsForCategory(categoryId).ifEmpty {
            playlistRepository.channels.value
        }
        _channels.value = channelList

        val target = channelList.find { it.id == channelId }
            ?: playlistRepository.getChannelById(channelId)
            ?: channelList.firstOrNull()

        target?.let { playChannelInternal(it) }
    }

    fun playChannel(channel: Channel) {
        playChannelInternal(channel)
    }

    private fun playChannelInternal(channel: Channel) {
        val current = _currentChannel.value
        if (current != null && current.id != channel.id) {
            previousChannelId = current.id
        }

        _currentChannel.value = channel
        _isFavorite.value = channel.isFavorite

        // Mark as watched in history
        playlistRepository.markChannelAsWatched(channel.id)
    }

    fun nextChannel(): Channel? {
        val list = _channels.value
        if (list.isEmpty()) return null
        val current = _currentChannel.value ?: return list.firstOrNull()
        val currentIndex = list.indexOfFirst { it.id == current.id }
        val nextIndex = if (currentIndex != -1) (currentIndex + 1) % list.size else 0
        val next = list[nextIndex]
        playChannelInternal(next)
        return next
    }

    fun previousChannel(): Channel? {
        val list = _channels.value
        if (list.isEmpty()) return null
        val current = _currentChannel.value ?: return list.firstOrNull()
        val currentIndex = list.indexOfFirst { it.id == current.id }
        val prevIndex = if (currentIndex != -1) {
            if (currentIndex - 1 < 0) list.size - 1 else currentIndex - 1
        } else 0
        val prev = list[prevIndex]
        playChannelInternal(prev)
        return prev
    }

    fun recallChannel(): Channel? {
        val recallId = previousChannelId ?: return null
        val list = _channels.value
        val channel = list.find { it.id == recallId } ?: playlistRepository.getChannelById(recallId)
        if (channel != null) {
            playChannelInternal(channel)
        }
        return channel
    }

    fun toggleFavorite(): Boolean {
        val current = _currentChannel.value ?: return false
        val newFav = playlistRepository.toggleFavorite(current.id)
        _isFavorite.value = newFav
        _currentChannel.value = current.copy(isFavorite = newFav)
        return newFav
    }

    fun cycleAspectRatio(): AspectRatio {
        val next = _aspectRatio.value.next()
        _aspectRatio.value = next
        return next
    }

    fun setAspectRatio(ratio: AspectRatio) {
        _aspectRatio.value = ratio
    }

    fun updatePlaybackState(state: PlaybackState) {
        _playbackState.value = state
    }

    fun updateAudioTracks(tracks: List<AudioTrackInfo>) {
        _audioTracks.value = tracks
    }
}
