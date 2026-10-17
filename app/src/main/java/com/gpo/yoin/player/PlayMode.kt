package com.gpo.yoin.player

import androidx.media3.common.Player

/**
 * Play mode as two independent axes — shuffle (off / on) × repeat (all / one),
 * 2 × 2 = four states (owner, 2026-10-17 launch sprint). The two-segment
 * button shows both axes; a tap moves to [next], which flips exactly one axis
 * per tap (a Gray-code cycle):
 * repeat all → shuffle → shuffle + repeat one → repeat one → repeat all.
 * Repeat all with shuffle off is the default.
 *
 * Every mode writes repeat AND shuffle together, so the result never depends
 * on the backend's previous state or on the order the two writes land in.
 * Spotify App Remote's repeat constants match Media3's (OFF 0 / ONE 1 / ALL 2).
 *
 * The playback layer stays independent of the icon library; the UI maps the
 * repeat axis to `SymbolPlayMode` itself.
 */
enum class PlayMode(val repeatMode: Int, val shuffle: Boolean) {
    RepeatAll(Player.REPEAT_MODE_ALL, shuffle = false),
    Shuffle(Player.REPEAT_MODE_ALL, shuffle = true),
    ShuffleRepeatOne(Player.REPEAT_MODE_ONE, shuffle = true),
    RepeatOne(Player.REPEAT_MODE_ONE, shuffle = false),
    ;

    /** The repeat axis: true when the current track repeats. */
    val repeatOne: Boolean get() = repeatMode == Player.REPEAT_MODE_ONE

    /** The mode the next tap moves to. */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    /** This mode with only the shuffle axis changed (the notification's shuffle button, the split pill). */
    fun withShuffle(on: Boolean): PlayMode = of(repeatMode, on)

    /** This mode with only the repeat axis changed (the split pill's repeat segment). */
    fun withRepeatOne(on: Boolean): PlayMode =
        of(if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL, shuffle)

    companion object {
        /**
         * Reads a backend's reported state back into a mode, one axis at a
         * time: repeat-one is the repeat axis' "one"; everything else —
         * `REPEAT_MODE_OFF` included, which only appears when the setting was
         * changed outside Yoin — reads as repeat all.
         */
        fun of(repeatMode: Int, shuffle: Boolean): PlayMode {
            val one = repeatMode == Player.REPEAT_MODE_ONE
            return when {
                one && shuffle -> ShuffleRepeatOne
                one -> RepeatOne
                shuffle -> Shuffle
                else -> RepeatAll
            }
        }
    }
}
