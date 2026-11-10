package com.gpo.yoin.ui.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import java.time.LocalDate
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
 *
 * [updated] is the day what the tip shows last changed (owner 2026-11-06: new
 * features get a tip, and every tip says when it was last updated). A tip
 * read before that day is unread again, so the bubble brings the change up.
 * A wording change alone keeps the day. The copy itself is Gemini's (owner
 * 2026-11-06: one short line per tip, written by Gemini, never by Claude).
 */
internal enum class HomeBubbleTip(
    @StringRes val title: Int,
    @StringRes val text: Int,
    val updated: LocalDate,
    private val symbol: () -> ImageVector,
) {
    Labs(R.string.home_tip_labs_title, R.string.home_tip_labs, day(2026, 11, 9), { YoinSymbols.Insights }),
    LyricHighlight(R.string.home_tip_lyric_highlight_title, R.string.home_tip_lyric_highlight, day(2026, 11, 8), { YoinSymbols.MusicNote }),
    ScreenCorners(R.string.home_tip_screen_corners_title, R.string.home_tip_screen_corners, day(2026, 11, 8), { YoinSymbols.Smartphone }),
    MemoriesAllAccounts(R.string.home_tip_memories_all_accounts_title, R.string.home_tip_memories_all_accounts, day(2026, 11, 8), { YoinSymbols.Memory }),
    SpotifySpeakers(R.string.home_tip_spotify_speakers_title, R.string.home_tip_spotify_speakers, day(2026, 11, 8), { YoinSymbols.Speaker }),
    AccountQuickSwitch(R.string.home_tip_account_quick_switch_title, R.string.home_tip_account_quick_switch, day(2026, 11, 6), { YoinSymbols.Person }),
    MiniPlayerMenu(R.string.home_tip_mini_player_menu_title, R.string.home_tip_mini_player_menu, day(2026, 11, 4), { YoinSymbols.PlayCircle }),
    FocusCover(R.string.home_tip_focus_cover_title, R.string.home_tip_focus_cover, day(2026, 11, 3), { YoinSymbols.OpenInFull }),
    Downloads(R.string.home_tip_downloads_title, R.string.home_tip_downloads, day(2026, 11, 4), { YoinSymbols.Download }),
    EditHome(R.string.home_tip_edit_home_title, R.string.home_tip_edit_home, day(2026, 10, 17), { YoinSymbols.DragHandle }),
    PullForMemories(R.string.home_tip_pull_memories_title, R.string.home_tip_pull_memories, day(2026, 10, 17), { YoinSymbols.Memory }),
    CoverMenu(R.string.home_tip_cover_menu_title, R.string.home_tip_cover_menu, day(2026, 11, 4), { YoinSymbols.Album }),
    SongMenu(R.string.home_tip_song_menu_title, R.string.home_tip_song_menu, day(2026, 11, 7), { YoinSymbols.Queue }),
    PlayModes(R.string.home_tip_play_modes_title, R.string.home_tip_play_modes, day(2026, 10, 21), { YoinSymbols.Shuffle }),
    NoteMoments(R.string.home_tip_note_moments_title, R.string.home_tip_note_moments, day(2026, 10, 17), { YoinSymbols.EditNote }),
    LibrarySearch(R.string.home_tip_library_search_title, R.string.home_tip_library_search, day(2026, 10, 17), { YoinSymbols.Search }),
    KeepScreenOn(R.string.home_tip_keep_screen_on_title, R.string.home_tip_keep_screen_on, day(2026, 10, 17), { YoinSymbols.LightMode }),
    ScrollEdge(R.string.home_tip_scroll_edge_title, R.string.home_tip_scroll_edge, day(2026, 10, 17), { YoinSymbols.Settings }),
    Name(R.string.home_tip_name_title, R.string.home_tip_name, day(2026, 10, 17), { YoinSymbols.Sparkle }),
    ;

    val icon: ImageVector get() = symbol()
}

private fun day(year: Int, month: Int, dayOfMonth: Int): LocalDate = LocalDate.of(year, month, dayOfMonth)

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
