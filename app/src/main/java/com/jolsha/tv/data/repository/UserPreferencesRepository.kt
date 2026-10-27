package com.jolsha.tv.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.jolsha.tv.data.model.AspectRatio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository responsible for user preferences persistence (favorites, recent channels, aspect ratio).
 * Uses Android SharedPreferences and exposes reactive StateFlows for UI and repository observation.
 */
class UserPreferencesRepository(
    private val context: Context? = null,
    private val prefs: SharedPreferences = context!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
) {

    /**
     * Secondary constructor for unit testing or custom SharedPreferences injection.
     */
    constructor(prefs: SharedPreferences) : this(null, prefs)

    companion object {
        const val PREFS_NAME = "jolsha_user_preferences"
        const val KEY_FAVORITES = "pref_favorites"
        const val KEY_RECENT_CHANNELS = "pref_recent_channels"
        const val KEY_DEFAULT_ASPECT_RATIO = "pref_default_aspect_ratio"
        const val KEY_LAST_LOADED_SOURCE = "pref_last_loaded_source"
        const val MAX_RECENT_CHANNELS = 30
    }

    private val _favoritesFlow = MutableStateFlow<Set<String>>(loadFavorites())
    val favoritesFlow: StateFlow<Set<String>> = _favoritesFlow.asStateFlow()

    private val _recentChannelsFlow = MutableStateFlow<List<String>>(loadRecentChannels())
    val recentChannelsFlow: StateFlow<List<String>> = _recentChannelsFlow.asStateFlow()

    private val _aspectRatioFlow = MutableStateFlow<AspectRatio>(loadAspectRatio())
    val aspectRatioFlow: StateFlow<AspectRatio> = _aspectRatioFlow.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    // FAVORITES (Set<String>)
    // ---------------------------------------------------------------------------------------------

    private fun loadFavorites(): Set<String> {
        val raw = prefs.getStringSet(KEY_FAVORITES, null)
        return raw?.toSet() ?: emptySet()
    }

    fun getFavorites(): Set<String> = _favoritesFlow.value

    fun isFavorite(channelId: String): Boolean = _favoritesFlow.value.contains(channelId)

    fun toggleFavorite(channelId: String): Boolean {
        val current = _favoritesFlow.value.toMutableSet()
        val isNowFavorite = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        saveFavorites(current)
        return isNowFavorite
    }

    fun setFavorite(channelId: String, isFavorite: Boolean) {
        val current = _favoritesFlow.value.toMutableSet()
        val changed = if (isFavorite) {
            current.add(channelId)
        } else {
            current.remove(channelId)
        }
        if (changed) {
            saveFavorites(current)
        }
    }

    fun addFavorite(channelId: String) = setFavorite(channelId, true)

    fun removeFavorite(channelId: String) = setFavorite(channelId, false)

    private fun saveFavorites(newFavorites: Set<String>) {
        _favoritesFlow.value = newFavorites
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(newFavorites)).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // RECENTLY WATCHED (List<String>, max 30, LRU)
    // ---------------------------------------------------------------------------------------------

    private fun loadRecentChannels(): List<String> {
        val raw = prefs.getString(KEY_RECENT_CHANNELS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun getRecentChannelIds(): List<String> = _recentChannelsFlow.value

    /**
     * Adds a channel ID to the top of the recently watched list (LRU order).
     * If already present, moves it to the front. Trims list to [MAX_RECENT_CHANNELS].
     */
    fun addRecentChannel(channelId: String) {
        if (channelId.isBlank()) return
        val current = _recentChannelsFlow.value.toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        val trimmed = if (current.size > MAX_RECENT_CHANNELS) {
            current.take(MAX_RECENT_CHANNELS)
        } else {
            current
        }
        _recentChannelsFlow.value = trimmed
        prefs.edit().putString(KEY_RECENT_CHANNELS, trimmed.joinToString(",")).apply()
    }

    fun clearRecentChannels() {
        _recentChannelsFlow.value = emptyList()
        prefs.edit().remove(KEY_RECENT_CHANNELS).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // DEFAULT ASPECT RATIO
    // ---------------------------------------------------------------------------------------------

    private fun loadAspectRatio(): AspectRatio {
        val name = prefs.getString(KEY_DEFAULT_ASPECT_RATIO, AspectRatio.FIT.name)
        return try {
            AspectRatio.valueOf(name ?: AspectRatio.FIT.name)
        } catch (_: Exception) {
            AspectRatio.FIT
        }
    }

    fun getDefaultAspectRatio(): AspectRatio = _aspectRatioFlow.value

    fun setDefaultAspectRatio(aspectRatio: AspectRatio) {
        _aspectRatioFlow.value = aspectRatio
        prefs.edit().putString(KEY_DEFAULT_ASPECT_RATIO, aspectRatio.name).apply()
    }

    // ---------------------------------------------------------------------------------------------
    // PLAYLIST SOURCE TRACKING
    // ---------------------------------------------------------------------------------------------

    fun getLastLoadedSource(): String? = prefs.getString(KEY_LAST_LOADED_SOURCE, null)

    fun setLastLoadedSource(source: String?) {
        if (source == null) {
            prefs.edit().remove(KEY_LAST_LOADED_SOURCE).apply()
        } else {
            prefs.edit().putString(KEY_LAST_LOADED_SOURCE, source).apply()
        }
    }
}
