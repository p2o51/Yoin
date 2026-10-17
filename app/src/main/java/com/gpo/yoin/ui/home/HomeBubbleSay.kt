package com.gpo.yoin.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import kotlin.random.Random

/**
 * What Home's bubble says (owner, 2026-10-17 launch sprint): a Memory, as
 * before, or — besides Memories — a short tip: a gesture the app hides, or a
 * detail about it. Tips are true to the app; each names one thing and how to
 * reach it, nothing more.
 */
@Immutable
internal sealed interface BubbleSay {
    /** A memory ([latest]); null = notes kept but no memory yet ("Memories"). */
    data class Memory(val latest: HomeMemoryPill.Latest?) : BubbleSay

    data class Tip(val tip: HomeBubbleTip) : BubbleSay
}

/** The tips, in the order the bubble goes through them. */
internal enum class HomeBubbleTip(@StringRes val text: Int, private val symbol: () -> ImageVector) {
    EditHome(R.string.home_tip_edit_home, { YoinSymbols.DragHandle }),
    PullForMemories(R.string.home_tip_pull_memories, { YoinSymbols.Memory }),
    CoverMenu(R.string.home_tip_cover_menu, { YoinSymbols.Album }),
    SongMenu(R.string.home_tip_song_menu, { YoinSymbols.Queue }),
    PlayModes(R.string.home_tip_play_modes, { YoinSymbols.Shuffle }),
    NoteMoments(R.string.home_tip_note_moments, { YoinSymbols.EditNote }),
    LibrarySearch(R.string.home_tip_library_search, { YoinSymbols.Search }),
    KeepScreenOn(R.string.home_tip_keep_screen_on, { YoinSymbols.LightMode }),
    ScrollEdge(R.string.home_tip_scroll_edge, { YoinSymbols.Settings }),
    Name(R.string.home_tip_name, { YoinSymbols.Sparkle }),
    ;

    val icon: ImageVector get() = symbol()
}

/**
 * What the idle bubble says on one of its turns: Memories and tips take turns
 * while there are memories to recall ([memoryTurn] steps through
 * [memoryBubbleSubject]); with none, it is tips only. [tipTurn] steps through
 * [HomeBubbleTip] in order, so every tip comes round before one repeats.
 */
internal fun idleBubbleSay(pill: HomeMemoryPill?, sayMemory: Boolean, memoryTurn: Int, tipTurn: Int): BubbleSay {
    if (sayMemory && pill.hasSomethingToSay()) {
        return BubbleSay.Memory(memoryBubbleSubject(pill, BubbleReason.Idle, memoryTurn))
    }
    val tips = HomeBubbleTip.entries
    return BubbleSay.Tip(tips[Math.floorMod(tipTurn, tips.size)])
}

/**
 * The bubble's idle rhythm (owner 2026-10-17: 4–5 s 不动就出现，2–3 s 消失，
 * 再过 4–5 s，带一点随机的脉冲): untouched this long, it speaks…
 */
internal val MemoryBubbleIdleRangeMs = 4_000L..5_000L

/** …holds what it said this long, then flies back into the arrow… */
internal val MemoryBubbleChatterDwellRangeMs = 2_000L..3_000L

/**
 * …or, this often, turns into the next thing to say before it goes — the way
 * a line is translated in place — and holds that one too.
 */
internal const val MemoryBubbleChainChance = 0.35f

internal fun LongRange.pick(random: Random): Long = random.nextLong(first, last + 1)
