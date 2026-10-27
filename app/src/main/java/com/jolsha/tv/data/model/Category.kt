package com.jolsha.tv.data.model

import java.io.Serializable

data class Category(
    val id: String,
    val name: String,
    val count: Int = 0,
    val isSystemCategory: Boolean = false
) : Serializable {

    companion object {
        const val ID_ALL = "CAT_ALL"
        const val ID_FAVORITES = "CAT_FAVORITES"
        const val ID_RECENT = "CAT_RECENT"

        fun all(count: Int) = Category(
            id = ID_ALL,
            name = "All Channels",
            count = count,
            isSystemCategory = true
        )

        fun favorites(count: Int) = Category(
            id = ID_FAVORITES,
            name = "Favorites",
            count = count,
            isSystemCategory = true
        )

        fun recent(count: Int) = Category(
            id = ID_RECENT,
            name = "Recently Watched",
            count = count,
            isSystemCategory = true
        )
    }
}
