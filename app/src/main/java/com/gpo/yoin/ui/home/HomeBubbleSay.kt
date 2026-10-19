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

/**
 * The tips, in the order the bubble goes through them. The bubble shows the
 * [title]; a tap opens the tip on its own page with the [text] (owner
 * 2026-10-18: a short line read cut off in the bubble), where it can be
 * marked read. Read tips no longer come up (ui/tips/TipReadStore).
 */
internal enum class HomeBubbleTip(
    @StringRes val title: Int,
    @StringRes val text: Int,
    private val symbol: () -> ImageVector,
) {
    EditHome(R.string.home_tip_edit_home_title, R.string.home_tip_edit_home, { YoinSymbols.DragHandle }),
    PullForMemories(R.string.home_tip_pull_memories_title, R.string.home_tip_pull_memories, { YoinSymbols.Memory }),
    CoverMenu(R.string.home_tip_cover_menu_title, R.string.home_tip_cover_menu, { YoinSymbols.Album }),
    SongMenu(R.string.home_tip_song_menu_title, R.string.home_tip_song_menu, { YoinSymbols.Queue }),
    PlayModes(R.string.home_tip_play_modes_title, R.string.home_tip_play_modes, { YoinSymbols.Shuffle }),
    NoteMoments(R.string.home_tip_note_moments_title, R.string.home_tip_note_moments, { YoinSymbols.EditNote }),
    LibrarySearch(R.string.home_tip_library_search_title, R.string.home_tip_library_search, { YoinSymbols.Search }),
    KeepScreenOn(R.string.home_tip_keep_screen_on_title, R.string.home_tip_keep_screen_on, { YoinSymbols.LightMode }),
    ScrollEdge(R.string.home_tip_scroll_edge_title, R.string.home_tip_scroll_edge, { YoinSymbols.Settings }),
    Name(R.string.home_tip_name_title, R.string.home_tip_name, { YoinSymbols.Sparkle }),
    ;

    val icon: ImageVector get() = symbol()
}

/**
 * What the idle bubble says on one of its turns: Memories and tips take turns
 * while there are memories to recall ([memoryTurn] steps through
 * [memoryBubbleSubject]); with none, it is tips only. [tipTurn] steps through
 * the [unreadTips] in order, so every one comes round before one repeats.
 * With every tip read it is memories only; with neither, nothing (null).
 */
internal fun idleBubbleSay(
    pill: HomeMemoryPill?,
    sayMemory: Boolean,
    memoryTurn: Int,
    tipTurn: Int,
    unreadTips: List<HomeBubbleTip> = HomeBubbleTip.entries,
): BubbleSay? {
    val memory = pill.hasSomethingToSay()
    if (memory && (sayMemory || unreadTips.isEmpty())) {
        return BubbleSay.Memory(memoryBubbleSubject(pill, BubbleReason.Idle, memoryTurn))
    }
    if (unreadTips.isEmpty()) return null
    return BubbleSay.Tip(unreadTips[Math.floorMod(tipTurn, unreadTips.size)])
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
