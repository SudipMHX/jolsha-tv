package com.jolsha.tv.data.model

import java.io.Serializable

data class Playlist(
    val channels: List<Channel> = emptyList(),
    val categories: List<Category> = emptyList(),
    val sourceUri: String? = null,
    val sourceName: String = "Default",
    val lastUpdated: Long = System.currentTimeMillis()
) : Serializable {

    val totalChannels: Int
        get() = channels.size

    val totalCategories: Int
        get() = categories.size

    val stats: PlaylistStats
        get() = PlaylistStats(
            totalChannels = totalChannels,
            categoryCount = totalCategories,
            favoriteCount = channels.count { it.isFavorite },
            lastUpdated = lastUpdated,
            source = sourceName
        )
}

data class PlaylistStats(
    val totalChannels: Int = 0,
    val categoryCount: Int = 0,
    val favoriteCount: Int = 0,
    val lastUpdated: Long = 0L,
    val source: String = ""
) : Serializable
