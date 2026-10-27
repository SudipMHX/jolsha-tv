package com.jolsha.tv.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jolsha.tv.JolshaApp
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.repository.PlaylistRepository
import com.jolsha.tv.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as JolshaApp
    val playlistRepository: PlaylistRepository = app.playlistRepository
    val preferencesRepository: UserPreferencesRepository = app.preferencesRepository

    val categories: StateFlow<List<Category>> = playlistRepository.categories

    private val _selectedCategory = MutableStateFlow<Category>(Category.all(0))
    val selectedCategory: StateFlow<Category> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val isLoading: StateFlow<Boolean> = playlistRepository.isLoading
    val error: StateFlow<String?> = playlistRepository.error

    // Focus memory: remember last focused channel position when returning from player
    var lastFocusedChannelPosition: Int = 0

    val displayedChannels: StateFlow<List<Channel>> = combine(
        playlistRepository.channels,
        _selectedCategory,
        _searchQuery
    ) { allChannels, category, query ->
        val filteredByCategory = when (category.id) {
            Category.ID_ALL -> allChannels
            Category.ID_FAVORITES -> allChannels.filter { it.isFavorite }
            Category.ID_RECENT -> {
                val idToChannel = allChannels.associateBy { it.id }
                preferencesRepository.getRecentChannelIds().mapNotNull { idToChannel[it] }
            }
            else -> allChannels.filter { it.category.equals(category.name, ignoreCase = true) }
        }

        if (query.isBlank()) {
            filteredByCategory
        } else {
            val trimmed = query.trim()
            filteredByCategory.filter { ch ->
                ch.name.contains(trimmed, ignoreCase = true) ||
                ch.tvgName.contains(trimmed, ignoreCase = true) ||
                ch.category.contains(trimmed, ignoreCase = true) ||
                ch.channelNumber.toString().contains(trimmed)
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalChannelCount: StateFlow<Int> = playlistRepository.channels
        .combine(_selectedCategory) { channels, _ -> channels.size }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        loadInitialPlaylist()
    }

    fun loadInitialPlaylist() {
        viewModelScope.launch {
            playlistRepository.loadPlaylist()
        }
    }

    fun reloadPlaylist() {
        viewModelScope.launch {
            playlistRepository.reloadPlaylist()
        }
    }

    fun loadSamplePlaylist() {
        viewModelScope.launch {
            playlistRepository.loadFromAssets(PlaylistRepository.DEFAULT_ASSET_FILE)
        }
    }

    fun loadFromUri(uri: Uri) {
        viewModelScope.launch {
            playlistRepository.loadFromUri(uri)
        }
    }

    fun selectCategory(category: Category) {
        _selectedCategory.value = category
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun toggleFavorite(channel: Channel): Boolean {
        return playlistRepository.toggleFavorite(channel.id)
    }

    fun clearWatchHistory() {
        preferencesRepository.clearRecentChannels()
    }

    fun resetFavorites() {
        val favs = preferencesRepository.getFavorites()
        for (id in favs) {
            preferencesRepository.setFavorite(id, false)
        }
    }

    fun getDefaultAspectRatio(): AspectRatio = preferencesRepository.getDefaultAspectRatio()

    fun setDefaultAspectRatio(ratio: AspectRatio) {
        preferencesRepository.setDefaultAspectRatio(ratio)
    }
}
