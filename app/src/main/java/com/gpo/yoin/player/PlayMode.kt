package com.gpo.yoin.player

import androidx.media3.common.Player

/**
 * The single play-mode button's three states, in the order a tap cycles them:
 * repeat all → shuffle → repeat one → repeat all. Repeat all is the default.
 *
 * Every mode writes repeat AND shuffle together, so the result never depends
 * on the backend's previous state or on the order the two writes land in.
 * Spotify App Remote's repeat constants match Media3's (OFF 0 / ONE 1 / ALL 2).
 *
 * The playback layer stays independent of the icon library; the UI maps this
 * to `SymbolPlayMode` itself.
 */
enum class PlayMode(val repeatMode: Int, val shuffle: Boolean) {
    RepeatAll(Player.REPEAT_MODE_ALL, shuffle = false),
    Shuffle(Player.REPEAT_MODE_ALL, shuffle = true),
    RepeatOne(Player.REPEAT_MODE_ONE, shuffle = false),
    ;

    /** The mode the next tap moves to. */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * Reads a backend's reported state back into a mode. Repeat-one wins
         * (even with shuffle on, a single track is what you hear), then
         * shuffle; everything else — `REPEAT_MODE_OFF` included, which only
         * appears when the setting was changed outside Yoin — shows as
         * repeat all.
         */
        fun of(repeatMode: Int, shuffle: Boolean): PlayMode = when {
            repeatMode == Player.REPEAT_MODE_ONE -> RepeatOne
            shuffle -> Shuffle
            else -> RepeatAll
        }
    }
}
