package com.jolsha.tv.data.repository

import android.content.Context
import android.net.Uri
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.data.model.Playlist
import com.jolsha.tv.data.model.PlaylistStats
import com.jolsha.tv.data.parser.M3UParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Repository that manages IPTV playlist loading, caching, category grouping, and search filtering.
 *
 * Sources:
 * 1. Local internal storage cache (`cached_playlist.m3u` / `channels.json`).
 * 2. Storage Access Framework Uri via ContentResolver.
 * 3. Fallback assets (`sample_playlist.m3u`).
 */
class PlaylistRepository(
    private val context: Context? = null,
    val preferencesRepository: UserPreferencesRepository = if (context != null) UserPreferencesRepository(context) else error("Context required when preferencesRepository is omitted"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) {

    /**
     * Secondary constructor for unit testing or context-free operations.
     */
    constructor(
        preferencesRepository: UserPreferencesRepository,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ) : this(
        context = null,
        preferencesRepository = preferencesRepository,
        ioDispatcher = dispatcher,
        coroutineScope = scope
    )

    companion object {
        const val CACHE_FILE_NAME = "cached_playlist.m3u"
        const val CACHE_JSON_NAME = "channels.json"
        const val DEFAULT_ASSET_FILE = "sample_playlist.m3u"
    }

    private val parser = M3UParser()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _playlistStats = MutableStateFlow(PlaylistStats())
    val playlistStats: StateFlow<PlaylistStats> = _playlistStats.asStateFlow()

    private val _currentPlaylist = MutableStateFlow<Playlist?>(null)
    val currentPlaylist: StateFlow<Playlist?> = _currentPlaylist.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var currentSourceDescription: String = "None"

    init {
        // Observe favorites changes and update channels and category counts
        preferencesRepository.favoritesFlow
            .onEach { favs ->
                _channels.update { list ->
                    list.map { it.copy(isFavorite = favs.contains(it.id)) }
                }
                updateCategoriesAndStats()
            }
            .launchIn(coroutineScope)

        // Observe recent channel changes to update category counts
        preferencesRepository.recentChannelsFlow
            .onEach {
                updateCategoriesAndStats()
            }
            .launchIn(coroutineScope)
    }

    // ---------------------------------------------------------------------------------------------
    // PLAYLIST LOADING
    // ---------------------------------------------------------------------------------------------

    /**
     * Loads playlist prioritizing local internal cache, then SAF URI if stored, then assets.
     */
    suspend fun loadPlaylist(): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null

        val cacheFile = getCacheFile()
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val content = cacheFile.readText(StandardCharsets.UTF_8)
                val playlist = parseAndApply(content, sourceName = "Local Cache", sourceUri = cacheFile.absolutePath)
                _isLoading.value = false
                return@withContext Result.success(playlist)
            } catch (e: Exception) {
                // If cache parsing fails, continue to fallback
            }
        }

        // Check if there is a saved SAF URI
        val lastUriStr = preferencesRepository.getLastLoadedSource()
        if (!lastUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(lastUriStr)
                val result = loadFromUriInternal(uri)
                if (result.isSuccess) {
                    _isLoading.value = false
                    return@withContext result
                }
            } catch (_: Exception) {
                // Continue to asset fallback
            }
        }

        // Fallback to assets sample_playlist.m3u
        val assetResult = loadFromAssetsInternal(DEFAULT_ASSET_FILE)
        _isLoading.value = false
        assetResult
    }

    /**
     * Reloads current active source without restarting the app.
     */
    suspend fun reloadPlaylist(): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null

        val lastUriStr = preferencesRepository.getLastLoadedSource()
        if (!lastUriStr.isNullOrBlank()) {
            try {
                val uri = Uri.parse(lastUriStr)
                val result = loadFromUriInternal(uri)
                _isLoading.value = false
                return@withContext result
            } catch (e: Exception) {
                // Fall through to cache or assets
            }
        }

        val cacheFile = getCacheFile()
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                val content = cacheFile.readText(StandardCharsets.UTF_8)
                val playlist = parseAndApply(content, sourceName = "Local Cache", sourceUri = cacheFile.absolutePath)
                _isLoading.value = false
                return@withContext Result.success(playlist)
            } catch (e: Exception) {
                // Fall through to assets
            }
        }

        val result = loadFromAssetsInternal(DEFAULT_ASSET_FILE)
        _isLoading.value = false
        result
    }

    /**
     * Loads playlist from a Storage Access Framework [Uri] (via ContentResolver).
     * Automatically caches the playlist into internal storage.
     */
    suspend fun loadFromUri(uri: Uri): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null
        val result = loadFromUriInternal(uri)
        _isLoading.value = false
        result
    }

    private fun loadFromUriInternal(uri: Uri): Result<Playlist> {
        val ctx = context ?: return Result.failure(IllegalStateException("Context required to load from URI"))
        return try {
            val inputStream: InputStream = ctx.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

            val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val playlist = parseAndApply(content, sourceName = uri.lastPathSegment ?: uri.toString(), sourceUri = uri.toString())

            // Cache to internal storage
            saveCacheFiles(content, playlist.channels)
            preferencesRepository.setLastLoadedSource(uri.toString())

            Result.success(playlist)
        } catch (e: Exception) {
            val msg = "Failed to load from URI: ${e.message}"
            _error.value = msg
            Result.failure(e)
        }
    }

    /**
     * Loads playlist from a local [File].
     */
    suspend fun loadFromFile(file: File): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null
        try {
            val content = file.readText(StandardCharsets.UTF_8)
            val playlist = parseAndApply(content, sourceName = file.name, sourceUri = file.absolutePath)
            saveCacheFiles(content, playlist.channels)
            preferencesRepository.setLastLoadedSource(file.absolutePath)
            _isLoading.value = false
            Result.success(playlist)
        } catch (e: Exception) {
            val msg = "Failed to load from file: ${e.message}"
            _error.value = msg
            _isLoading.value = false
            Result.failure(e)
        }
    }

    /**
     * Loads playlist from the application's assets folder (defaults to [DEFAULT_ASSET_FILE]).
     */
    suspend fun loadFromAssets(fileName: String = DEFAULT_ASSET_FILE): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null
        val result = loadFromAssetsInternal(fileName)
        _isLoading.value = false
        result
    }

    private fun loadFromAssetsInternal(fileName: String): Result<Playlist> {
        val ctx = context ?: return Result.failure(IllegalStateException("Context required to load from assets"))
        return try {
            val content = ctx.assets.open(fileName).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val playlist = parseAndApply(content, sourceName = "Assets: $fileName", sourceUri = "asset://$fileName")
            Result.success(playlist)
        } catch (e: Exception) {
            val msg = "Failed to load asset '$fileName': ${e.message}"
            _error.value = msg
            Result.failure(e)
        }
    }

    /**
     * Directly loads playlist from an in-memory M3U string.
     */
    suspend fun loadFromContent(
        content: String,
        sourceName: String = "Direct Content",
        cacheLocally: Boolean = false
    ): Result<Playlist> = withContext(ioDispatcher) {
        _isLoading.value = true
        _error.value = null
        try {
            val playlist = parseAndApply(content, sourceName = sourceName, sourceUri = null)
            if (cacheLocally) {
                saveCacheFiles(content, playlist.channels)
            }
            _isLoading.value = false
            Result.success(playlist)
        } catch (e: Exception) {
            val msg = "Failed to parse content: ${e.message}"
            _error.value = msg
            _isLoading.value = false
            Result.failure(e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // INTERNAL PARSING & STATE APPLICATION
    // ---------------------------------------------------------------------------------------------

    private fun parseAndApply(content: String, sourceName: String, sourceUri: String?): Playlist {
        val rawChannels = parser.parse(content)
        val favorites = preferencesRepository.getFavorites()

        val channelsWithFavs = rawChannels.map { channel ->
            channel.copy(isFavorite = favorites.contains(channel.id))
        }

        currentSourceDescription = sourceName
        _channels.value = channelsWithFavs

        val categories = buildCategories(channelsWithFavs)
        _categories.value = categories

        val playlist = Playlist(
            channels = channelsWithFavs,
            categories = categories,
            sourceUri = sourceUri,
            sourceName = sourceName,
            lastUpdated = System.currentTimeMillis()
        )

        _currentPlaylist.value = playlist
        _playlistStats.value = playlist.stats

        return playlist
    }

    private fun updateCategoriesAndStats() {
        val currentChannels = _channels.value
        val updatedCategories = buildCategories(currentChannels)
        _categories.value = updatedCategories

        val currentPl = _currentPlaylist.value
        if (currentPl != null) {
            val updatedPlaylist = currentPl.copy(
                channels = currentChannels,
                categories = updatedCategories,
                lastUpdated = System.currentTimeMillis()
            )
            _currentPlaylist.value = updatedPlaylist
            _playlistStats.value = updatedPlaylist.stats
        } else {
            _playlistStats.value = PlaylistStats(
                totalChannels = currentChannels.size,
                categoryCount = updatedCategories.size,
                favoriteCount = currentChannels.count { it.isFavorite },
                lastUpdated = System.currentTimeMillis(),
                source = currentSourceDescription
            )
        }
    }

    /**
     * Builds categories by injecting system categories:
     * - "All Channels" (all channels)
     * - "Favorites" (filtered by user favorites set)
     * - "Recently Watched" (ordered by recent list)
     * followed by playlist groups preserving category counts.
     */
    private fun buildCategories(channelList: List<Channel>): List<Category> {
        val result = mutableListOf<Category>()
        val totalChannels = channelList.size

        var favCount = 0
        val channelIdSet = HashSet<String>(totalChannels)
        // LinkedHashMap preserves order of first appearance of categories in playlist
        val categoryCounts = LinkedHashMap<String, Int>()

        for (ch in channelList) {
            channelIdSet.add(ch.id)
            if (ch.isFavorite) favCount++
            val group = ch.category.trim()
            if (group.isNotEmpty()) {
                categoryCounts[group] = (categoryCounts[group] ?: 0) + 1
            }
        }

        val recentIds = preferencesRepository.getRecentChannelIds()
        var recentCount = 0
        for (rId in recentIds) {
            if (channelIdSet.contains(rId)) recentCount++
        }

        // System Categories
        result.add(Category.all(totalChannels))
        result.add(Category.favorites(favCount))
        result.add(Category.recent(recentCount))

        for ((groupName, count) in categoryCounts) {
            val catId = "cat_${groupName.lowercase().replace(" ", "_")}"
            result.add(
                Category(
                    id = catId,
                    name = groupName,
                    count = count,
                    isSystemCategory = false
                )
            )
        }

        return result
    }

    // ---------------------------------------------------------------------------------------------
    // CHANNELS BY CATEGORY & SEARCH
    // ---------------------------------------------------------------------------------------------

    /**
     * Retrieves channels belonging to a given category ID or category name.
     */
    fun getChannelsForCategory(categoryId: String): List<Channel> {
        return when (categoryId) {
            Category.ID_ALL -> _channels.value
            Category.ID_FAVORITES -> _channels.value.filter { it.isFavorite }
            Category.ID_RECENT -> getRecentChannelsOrdered()
            else -> {
                val cat = _categories.value.find { it.id == categoryId }
                val targetName = cat?.name ?: categoryId
                _channels.value.filter { it.category.equals(targetName, ignoreCase = true) }
            }
        }
    }

    /**
     * Retrieves channels belonging to a given [Category].
     */
    fun getChannelsForCategory(category: Category): List<Channel> {
        return when (category.id) {
            Category.ID_ALL -> _channels.value
            Category.ID_FAVORITES -> _channels.value.filter { it.isFavorite }
            Category.ID_RECENT -> getRecentChannelsOrdered()
            else -> _channels.value.filter { it.category.equals(category.name, ignoreCase = true) }
        }
    }

    private fun getRecentChannelsOrdered(): List<Channel> {
        val idToChannel = _channels.value.associateBy { it.id }
        return preferencesRepository.getRecentChannelIds().mapNotNull { idToChannel[it] }
    }

    /**
     * Fast search filter: case-insensitive match on name, tvgName, category, and channel number.
     */
    fun searchChannels(query: String): List<Channel> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return _channels.value
        return _channels.value.filter { channel ->
            channel.name.contains(trimmed, ignoreCase = true) ||
            channel.tvgName.contains(trimmed, ignoreCase = true) ||
            channel.category.contains(trimmed, ignoreCase = true) ||
            channel.channelNumber.toString().contains(trimmed)
        }
    }

    fun getChannelById(id: String): Channel? {
        return _channels.value.find { it.id == id }
    }

    fun getChannelByNumber(number: Int): Channel? {
        return _channels.value.find { it.channelNumber == number }
    }

    // ---------------------------------------------------------------------------------------------
    // FAVORITE / RECENT ACTIONS
    // ---------------------------------------------------------------------------------------------

    fun toggleFavorite(channelId: String): Boolean {
        return preferencesRepository.toggleFavorite(channelId)
    }

    fun setFavorite(channelId: String, isFavorite: Boolean) {
        preferencesRepository.setFavorite(channelId, isFavorite)
    }

    fun markChannelAsWatched(channelId: String) {
        preferencesRepository.addRecentChannel(channelId)
    }

    // ---------------------------------------------------------------------------------------------
    // LOCAL CACHE FILES
    // ---------------------------------------------------------------------------------------------

    private fun getCacheFile(): File = if (context != null) File(context.filesDir, CACHE_FILE_NAME) else File(CACHE_FILE_NAME)
    private fun getCacheJsonFile(): File = if (context != null) File(context.filesDir, CACHE_JSON_NAME) else File(CACHE_JSON_NAME)

    private fun saveCacheFiles(m3uContent: String, channels: List<Channel>) {
        try {
            val cacheFile = getCacheFile()
            cacheFile.writeText(m3uContent, StandardCharsets.UTF_8)

            val jsonFile = getCacheJsonFile()
            val jsonContent = serializeChannelsToJson(channels)
            jsonFile.writeText(jsonContent, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            // Best effort caching
        }
    }

    private fun serializeChannelsToJson(channelList: List<Channel>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        for (i in channelList.indices) {
            val ch = channelList[i]
            sb.append("  {")
                .append("\"id\":").append(escapeJson(ch.id)).append(",")
                .append("\"name\":").append(escapeJson(ch.name)).append(",")
                .append("\"streamUrl\":").append(escapeJson(ch.streamUrl)).append(",")
                .append("\"category\":").append(escapeJson(ch.category)).append(",")
                .append("\"tvgId\":").append(escapeJson(ch.tvgId)).append(",")
                .append("\"tvgName\":").append(escapeJson(ch.tvgName)).append(",")
                .append("\"logoUrl\":").append(escapeJson(ch.logoUrl)).append(",")
                .append("\"channelNumber\":").append(ch.channelNumber).append(",")
                .append("\"originalIndex\":").append(ch.originalIndex)
                .append("}")
            if (i < channelList.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
