package com.gpo.yoin.player

import androidx.media3.common.Player

/**
 * Play mode as two independent axes — shuffle (off / on) × repeat (all / one
 * / off). The round button's tap cycles the four modes of the 2 × 2 core
 * (owner, 2026-10-17 launch sprint), flipping exactly one axis per tap (a
 * Gray-code cycle):
 * repeat all → shuffle → shuffle + repeat one → repeat one → repeat all.
 * Repeat all with shuffle off is the default.
 *
 * Repeat off (play to the end of the queue and stop) lives only in the split
 * pill's repeat segment, which steps all → one → off ([nextRepeat]; Gemini
 * review 1.4, owner 2026-10-21), so the round button's cycle stays four long.
 * A tap on the round button in an off mode turns repeat back to all and keeps
 * shuffle as it is.
 *
 * Every mode writes repeat AND shuffle together, so the result never depends
 * on the backend's previous state or on the order the two writes land in.
 * Spotify App Remote's and MusicKit's repeat constants match Media3's
 * (OFF 0 / ONE 1 / ALL 2).
 *
 * The playback layer stays independent of the icon library; the UI maps the
 * repeat axis to `SymbolPlayMode` itself.
 */
enum class PlayMode(val repeatMode: Int, val shuffle: Boolean) {
    RepeatAll(Player.REPEAT_MODE_ALL, shuffle = false),
    Shuffle(Player.REPEAT_MODE_ALL, shuffle = true),
    ShuffleRepeatOne(Player.REPEAT_MODE_ONE, shuffle = true),
    RepeatOne(Player.REPEAT_MODE_ONE, shuffle = false),
    RepeatOff(Player.REPEAT_MODE_OFF, shuffle = false),
    ShuffleRepeatOff(Player.REPEAT_MODE_OFF, shuffle = true),
    ;

    /** The repeat axis: true when the current track repeats. */
    val repeatOne: Boolean get() = repeatMode == Player.REPEAT_MODE_ONE

    /** The repeat axis: true when the queue plays to its end and stops. */
    val repeatOff: Boolean get() = repeatMode == Player.REPEAT_MODE_OFF

    /** The mode the round button's next tap moves to. */
    fun next(): PlayMode = when (this) {
        RepeatAll -> Shuffle
        Shuffle -> ShuffleRepeatOne
        ShuffleRepeatOne -> RepeatOne
        RepeatOne -> RepeatAll
        RepeatOff -> RepeatAll
        ShuffleRepeatOff -> Shuffle
    }

    /** This mode with only the shuffle axis changed (the notification's shuffle button, the split pill). */
    fun withShuffle(on: Boolean): PlayMode = of(repeatMode, on)

    /** The split pill's repeat segment: all → one → off → all, shuffle kept. */
    fun nextRepeat(): PlayMode = of(
        when (repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        },
        shuffle,
    )

    companion object {
        /** Reads a backend's reported state back into a mode, one axis at a time. */
        fun of(repeatMode: Int, shuffle: Boolean): PlayMode = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> if (shuffle) ShuffleRepeatOne else RepeatOne
            Player.REPEAT_MODE_OFF -> if (shuffle) ShuffleRepeatOff else RepeatOff
            else -> if (shuffle) Shuffle else RepeatAll
        }
    }
}
