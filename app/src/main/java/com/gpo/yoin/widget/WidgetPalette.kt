package com.gpo.yoin.widget

/** A cover's palette as plain ARGB ints (RemoteViews / Canvas), from the same extractor the deck uses. */
internal data class WidgetPalette(val base: Int, val accent: Int, val deep: Int, val soft: Int) {
    /** Light: the page tint. Dark: deep pulled toward black (Figma `cover/bg`). */
    val bgLight: Int get() = soft
    val bgDark: Int get() = blendWidgetArgb(deep, 0xFF000000.toInt(), 0.38f)
    val inkLight: Int get() = deep
    val inkDark: Int get() = soft
    val subLight: Int get() = blendWidgetArgb(deep, soft, 0.3f)
    val subDark: Int get() = blendWidgetArgb(soft, deep, 0.32f)

    companion object {
        /** Neutral fallback when a cover can't be read. */
        val Fallback = WidgetPalette(
            base = 0xFF6B5B95.toInt(),
            accent = 0xFFF1D9A7.toInt(),
            deep = 0xFF2C2540.toInt(),
            soft = 0xFFE9E2F2.toInt(),
        )
    }
}

/** Same channel interpolation/truncation as ColorUtils.blendARGB, without Android graphics calls. */
private fun blendWidgetArgb(from: Int, to: Int, ratio: Float): Int {
    val inverse = 1f - ratio
    fun channel(shift: Int): Int =
        (((from ushr shift) and 0xFF) * inverse + ((to ushr shift) and 0xFF) * ratio).toInt()

    return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
