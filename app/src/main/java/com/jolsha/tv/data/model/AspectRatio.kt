package com.jolsha.tv.data.model

enum class AspectRatio(val displayName: String) {
    FIT("Original / Fit"),
    FILL("Fill / Stretch"),
    ZOOM("Zoom / Crop"),
    SIXTEEN_BY_NINE("16:9"),
    FOUR_BY_THREE("4:3");

    fun next(): AspectRatio {
        val values = entries
        val nextIndex = (ordinal + 1) % values.size
        return values[nextIndex]
    }
}
