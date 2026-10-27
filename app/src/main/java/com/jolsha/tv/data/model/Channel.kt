package com.jolsha.tv.data.model

import java.io.Serializable

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val category: String = "ALL CHANNELS",
    val tvgId: String = "",
    val tvgName: String = "",
    val logoUrl: String = "",
    val channelNumber: Int = 0,
    val originalIndex: Int = 0,
    val isFavorite: Boolean = false,
    val customAspectRatio: AspectRatio? = null
) : Serializable {

    val displayName: String
        get() = name.ifBlank { tvgName.ifBlank { "Channel $channelNumber" } }

    val formattedNumber: String
        get() = if (channelNumber > 0) String.format("%03d", channelNumber) else ""
}
