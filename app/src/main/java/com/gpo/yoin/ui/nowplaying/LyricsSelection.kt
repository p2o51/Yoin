package com.gpo.yoin.ui.nowplaying

import android.content.ClipData
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.launch

/**
 * Select mode of the expanded Lyrics page (the ✓ in the lyric tools, like
 * Spotify's "Select lyrics"): tap lines to pick them, then copy the text or
 * share them as an image card. Transient UI state — one instance per song and
 * lyrics list, so a song change or a lyrics swap simply leaves the mode.
 *
 * Any lines, at most [maxLines] of them (owner W1, 2026-10-05: spotoolfy's
 * free pick and its 15-line poster cap; a pick that skips lines still makes a
 * Card or a Story) — see [nextLyricSelection] for what a tap does.
 *
 * While [active] the page stops following the playhead, tap-to-seek becomes
 * tap-to-select, the pager stops swiping and the lyric tools never auto-hide.
 */
@Stable
internal class LyricsSelectionState(private val maxLines: Int = MaxSelectedLyricLines) {
    var active: Boolean by mutableStateOf(false)
        private set

    /** The picked lines, as indices into the song's lyric lines (title card / up-next rows are not selectable). */
    var selected: Set<Int> by mutableStateOf(emptySet())
        private set

    /**
     * Share was asked for this pick and its sheet is up. The sheet itself
     * renders a frozen [LyricsShareSnapshot] held by [LyricsSelectionHost], so
     * it outlives this per-song state.
     */
    var sharing: Boolean by mutableStateOf(false)
        private set

    fun enter() {
        active = true
        selected = emptySet()
        sharing = false
    }

    fun exit() {
        active = false
        selected = emptySet()
        sharing = false
    }

    /** Applies a tap on line [index]; false when the pick is full and the tap would add to it. */
    fun toggle(index: Int): Boolean {
        if (!active) return false
        val step = nextLyricSelection(selected, index, maxLines)
        selected = step.selected
        return !step.hitLimit
    }

    fun openShare() {
        if (active && selected.isNotEmpty()) sharing = true
    }

    fun closeShare() {
        sharing = false
    }
}

@Composable
internal fun rememberLyricsSelectionState(
    songId: String,
    lyrics: List<LyricLine>,
): LyricsSelectionState = remember(songId, lyrics) { LyricsSelectionState() }

/**
 * Most lines one pick holds — spotoolfy's poster cap (`_selectedCount/15`);
 * past it a share card stops reading as a quote.
 */
internal const val MaxSelectedLyricLines = 15

/** Result of one tap in select mode: the new pick, and whether the cap refused it. */
internal data class LyricSelectionTap(val selected: Set<Int>, val hitLimit: Boolean = false)

/**
 * spotoolfy's free pick, as one tap on line [tapped]: a picked line lets go,
 * any other line joins — refused once [maxLines] are picked.
 */
internal fun nextLyricSelection(
    current: Set<Int>,
    tapped: Int,
    maxLines: Int = MaxSelectedLyricLines,
): LyricSelectionTap = when {
    tapped in current -> LyricSelectionTap(current - tapped)
    current.size >= maxLines -> LyricSelectionTap(current, hitLimit = true)
    else -> LyricSelectionTap(current + tapped)
}

/** How a line reads in select mode, from the pick. */
internal enum class LyricSelectionRole {
    /** Nothing picked yet: every line is an even candidate. */
    Idle,

    /** Part of the pick. */
    Picked,

    /** Not picked, and the pick has room: one tap adds it. */
    Open,

    /** Steps back: the pick is full (a tap is refused), or the line can't be picked at all. */
    Outside,
}

internal fun lyricSelectionRole(
    index: Int,
    selected: Set<Int>,
    maxLines: Int = MaxSelectedLyricLines,
): LyricSelectionRole = when {
    selected.isEmpty() -> LyricSelectionRole.Idle
    index in selected -> LyricSelectionRole.Picked
    selected.size >= maxLines -> LyricSelectionRole.Outside
    else -> LyricSelectionRole.Open
}

/** The picked lines in lyric order; stale indices (lyrics replaced) drop out. */
internal fun selectedLyricLines(lyrics: List<LyricLine>, selected: Set<Int>): List<LyricLine> =
    selected.filter { it in lyrics.indices }.sorted().map(lyrics::get)

/**
 * Where the picked lines ([selectedLyricLines]) skip part of the song: the
 * positions in that list whose line does not follow the previous one. Each
 * starts a new passage on the card and after a blank line in the copy.
 */
internal fun lyricPassageStarts(lyrics: List<LyricLine>, selected: Set<Int>): Set<Int> {
    val picked = selected.filter { it in lyrics.indices }.sorted()
    return picked.indices.filterTo(mutableSetOf()) { i -> i > 0 && picked[i] != picked[i - 1] + 1 }
}

/**
 * Plain-text form for the clipboard: one lyric per line, each followed by its
 * translation when the translation layer is showing, a blank line before each
 * of [passageStarts] (a skipped stretch of the song), then — after a blank
 * line — the credit "— Title · Artist" when the song has a title.
 */
internal fun lyricsClipboardText(
    lines: List<LyricLine>,
    includeTranslation: Boolean,
    songTitle: String? = null,
    artist: String? = null,
    passageStarts: Set<Int> = emptySet(),
): String {
    val body = lines.withIndex().joinToString(separator = "\n") { (i, line) ->
        val translation = line.translation?.takeIf { includeTranslation && it.isNotBlank() }
        val text = if (translation == null) line.text else "${line.text}\n$translation"
        if (i in passageStarts) "\n$text" else text
    }
    val credit = lyricsCredit(songTitle, artist) ?: return body
    return "$body\n\n— $credit"
}

/** "Title · Artist" (or just the title); null without a title. */
internal fun lyricsCredit(songTitle: String?, artist: String?): String? {
    val title = songTitle?.trim().orEmpty()
    if (title.isEmpty()) return null
    val by = artist?.trim().orEmpty()
    return if (by.isEmpty()) title else "$title · $by"
}

internal fun lyricsSelectionLabel(
    count: Int,
    maxLines: Int = MaxSelectedLyricLines,
    // A tap just tried to grow a full run.
    limitNudge: Boolean = false,
): String = when {
    limitNudge -> "Up to $maxLines lines at a time"
    count == 0 -> "Tap lines to select"
    count == 1 -> "1 line selected"
    count >= maxLines -> "$count lines selected · max"
    else -> "$count lines selected"
}

/** Pre-Android 13 copy confirmation (13+ shows its own). */
internal fun lyricsCopiedMessage(count: Int): String =
    if (count == 1) "Copied 1 line" else "Copied $count lines"

/**
 * The lyric tools, or — in select mode — the selection actions, in the same
 * slot and footprint (four keys either way), cross-faded on the effects spring.
 */
@Composable
internal fun LyricsTools(
    state: NowPlayingUiState.Playing,
    selection: LyricsSelectionState,
    canRecenter: Boolean,
    onSearchClick: () -> Unit,
    onTranslateClick: () -> Unit,
    onRecenterClick: () -> Unit,
    onEditLyricsClick: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchModifier: Modifier = Modifier,
    // Runs before select mode opens — the compact stage expands to the full
    // lyrics page first (select mode lives there).
    onBeforeSelect: () -> Unit = {},
    iconSize: Dp = 52.dp,
) {
    val copyLyrics = rememberCopyLyrics(onMessage)
    val haptics = rememberYoinHaptics()
    AnimatedContent(
        targetState = selection.active,
        transitionSpec = {
            (
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                    YoinMotion.scaleIn(role = YoinMotionRole.Standard, initialScale = 0.92f)
                ) togetherWith YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentAlignment = Alignment.CenterStart,
        label = "lyricsTools",
        modifier = modifier,
    ) { selecting ->
        if (selecting) {
            LyricsSelectionBar(
                selectedCount = selection.selected.size,
                onCloseClick = selection::exit,
                onCopyClick = {
                    val lines = selectedLyricLines(state.lyrics, selection.selected)
                    if (lines.isNotEmpty()) {
                        haptics.performConfirm()
                        copyLyrics(
                            lyricsClipboardText(
                                lines = lines,
                                includeTranslation = state.showLyricsTranslation,
                                songTitle = state.songTitle,
                                artist = state.artist,
                                passageStarts = lyricPassageStarts(state.lyrics, selection.selected),
                            ),
                            lines.size,
                        )
                        selection.exit()
                    }
                },
                onShareClick = selection::openShare,
                onEditClick = {
                    selection.exit()
                    onEditLyricsClick()
                },
                iconSize = iconSize,
            )
        } else {
            LyricsActionBar(
                searchModifier = searchModifier,
                actionInFlight = state.lyricsActionInFlight,
                canTranslate = state.lyrics.isNotEmpty(),
                canSelect = state.lyrics.isNotEmpty(),
                canRecenter = canRecenter,
                onSearchClick = onSearchClick,
                onTranslateClick = onTranslateClick,
                onSelectClick = {
                    // No lines yet (none found): the ✓ keeps opening the LRC
                    // editor, the only way to paste lyrics in by hand.
                    if (state.lyrics.isEmpty()) {
                        onEditLyricsClick()
                    } else {
                        onBeforeSelect()
                        selection.enter()
                    }
                },
                onRecenterClick = onRecenterClick,
                iconSize = iconSize,
            )
        }
    }
}

/**
 * What the share sheet shows, frozen the moment it opens: the picked lines and
 * the song they came from. The live pick belongs to one song (a song change
 * starts a fresh [LyricsSelectionState]); the sheet must not — an auto-advance
 * under the open sheet used to close it and drop the pick (2026-10-05 QA).
 */
@Immutable
internal data class LyricsShareSnapshot(
    val songId: String,
    val lines: List<LyricLine>,
    /** Positions in [lines] that start a new passage ([lyricPassageStarts]). */
    val passageStarts: Set<Int>,
    val showTranslation: Boolean,
    val songTitle: String,
    val artist: String,
    val coverArtUrl: String?,
)

/** Freezes the lines [selected] on [state]'s song for the share sheet; null when nothing is picked. */
internal fun lyricsShareSnapshot(state: NowPlayingUiState.Playing, selected: Set<Int>): LyricsShareSnapshot? {
    val lines = selectedLyricLines(state.lyrics, selected)
    if (lines.isEmpty()) return null
    return LyricsShareSnapshot(
        songId = state.songId,
        lines = lines,
        passageStarts = lyricPassageStarts(state.lyrics, selected),
        showTranslation = state.showLyricsTranslation,
        songTitle = state.songTitle,
        artist = state.artist,
        coverArtUrl = state.coverArtUrl,
    )
}

/**
 * The open share sheet and the [LyricsShareSnapshot] it renders. Held by the
 * layout, not per song like the pick, so it outlives a song change under it.
 */
@Stable
internal class LyricsShareSheetState {
    var snapshot: LyricsShareSnapshot? by mutableStateOf(null)
        private set

    fun open(snapshot: LyricsShareSnapshot) {
        this.snapshot = snapshot
    }

    fun close() {
        snapshot = null
    }
}

/**
 * Select mode's side effects, mounted once per layout: leave the mode when the
 * lyrics page stops being on screen, give system back to "leave select mode"
 * while it is on (a transient in-page mode, like the lyrics search — it sits
 * above the stage-back handlers by mount order), and host the share sheet.
 *
 * The pick's Share ([LyricsSelectionState.openShare]) is taken up here: what
 * it shows is frozen into [shareSheet], which stays open — same lines, same
 * song — when the song changes underneath; the live pick still resets for the
 * new song. [sheet] draws it (the real [LyricsShareSheet]; tests swap it).
 */
@Composable
internal fun LyricsSelectionHost(
    state: NowPlayingUiState.Playing,
    selection: LyricsSelectionState,
    lyricsPageOnScreen: Boolean,
    onMessage: (String) -> Unit,
    shareSheet: LyricsShareSheetState = remember { LyricsShareSheetState() },
    sheet: @Composable (snapshot: LyricsShareSnapshot, onShared: () -> Unit, onDismiss: () -> Unit) -> Unit =
        { snapshot, onShared, onDismiss ->
            LyricsShareSheet(
                lines = snapshot.lines,
                passageStarts = snapshot.passageStarts,
                showTranslation = snapshot.showTranslation,
                songTitle = snapshot.songTitle,
                artist = snapshot.artist,
                coverArtUrl = snapshot.coverArtUrl,
                onShared = onShared,
                onDismiss = onDismiss,
                onMessage = onMessage,
            )
        },
) {
    LaunchedEffect(lyricsPageOnScreen) {
        if (!lyricsPageOnScreen) selection.exit()
    }
    BackHandler(enabled = selection.active && !selection.sharing && shareSheet.snapshot == null) {
        selection.exit()
    }
    // Share asked: freeze the pick as it stands on this song, once.
    val latestState by rememberUpdatedState(state)
    LaunchedEffect(selection, selection.sharing) {
        if (selection.sharing && shareSheet.snapshot == null) {
            val snapshot = lyricsShareSnapshot(latestState, selection.selected)
            if (snapshot != null) shareSheet.open(snapshot) else selection.closeShare()
        }
    }
    val open = shareSheet.snapshot ?: return
    sheet(
        open,
        {
            shareSheet.close()
            // The pick of the song it was made on is done; a newer song's is left alone.
            if (selection.sharing) selection.exit()
        },
        {
            shareSheet.close()
            selection.closeShare()
        },
    )
}

@Composable
private fun rememberCopyLyrics(onMessage: (String) -> Unit): (text: String, lineCount: Int) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val latestOnMessage by rememberUpdatedState(onMessage)
    return remember(clipboard, scope) {
        { text, lineCount ->
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText("Lyrics", text).toClipEntry())
                // Android 13+ confirms copies itself.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    latestOnMessage(lyricsCopiedMessage(lineCount))
                }
            }
        }
    }
}
