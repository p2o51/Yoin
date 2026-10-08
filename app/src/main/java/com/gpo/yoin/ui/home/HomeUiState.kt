package com.gpo.yoin.ui.home

import androidx.compose.runtime.Immutable
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind

sealed interface HomeUiState {
    data object Loading : HomeUiState

    data class Content(
        val activities: List<ActivityEvent>,
        // True only when [activities] came from a provider endpoint (Spotify
        // recently-played) rather than the local activity log. The live local
        // observer uses this to decide whether it owns the feed — see
        // HomeViewModel.observeRecentHistory.
        val activitiesFromRemote: Boolean = false,
        // "2024 · 12 songs, 44 min" line for the hero (first) activity when it
        // is an album whose metadata resolved from the detail cache. Null just
        // hides the line — the hero card renders fine without it.
        val activityHeroFootnote: String? = null,
        // Structured halves of [activityHeroFootnote], resolved in the feed.
        // Nulls fall back to the string (previews, screenshots).
        val activityHeroYear: Int? = null,
        val activityHeroSongCount: Int? = null,
        val activityHeroMinutes: Int? = null,
        // The "Jump Back In" 3×4 widget grid: plain recommendations mixed with
        // memory-flavoured cards. Empty hides the section.
        val widgetGrid: List<HomeWidgetCard> = emptyList(),
        // Library items added within the last 30 days (Spotify saved / Subsonic
        // starred), newest first. The Recently Added section splits these into a
        // compact track grid on the left (2×2 on a phone, up to 4×2 on a wide
        // feed) and a horizontally scrolling album shelf on the right (Figma
        // 622:777). Either being empty just drops that half; both empty hides
        // the whole section.
        val recentlyAddedTracks: List<Track> = emptyList(),
        val recentlyAddedAlbums: List<Album> = emptyList(),
        // The header's Memories pill (latest memory + notes count). Null =
        // not resolved yet (or unscoped): the header keeps today's bare
        // chevron instead of flashing an "empty" pill.
        val memoryPill: HomeMemoryPill? = null,
        // Rediscover: albums with a memory (a rating, a review, a note or a
        // rated track) and songs you rated or noted, not played in Yoin for
        // 90+ days; scored ones first, best first, albums and songs in one
        // order. Empty = the section isn't rendered.
        val rediscover: List<HomeRediscoverItem> = emptyList(),
        // Recently Played: the provider's recently played albums, minus any
        // the Activities hero / cards already show. Empty = not rendered.
        val recentlyPlayed: List<Album> = emptyList(),
        // Your Playlists: the library's playlists in the provider's own order
        // (recently changed first on Spotify). Empty = the section isn't rendered.
        val playlists: List<Playlist> = emptyList(),
    ) : HomeUiState

    data class Error(
        val message: String,
        // App copy, resolved in the composable. Null keeps [message] (previews).
        val messageText: UiText? = null,
    ) : HomeUiState
}

/** Where tapping a widget-grid card leads. */
sealed interface HomeWidgetTarget {
    data class AlbumDetail(val albumId: String) : HomeWidgetTarget

    data class PlaylistDetail(val playlistId: String) : HomeWidgetTarget

    data class PlaySong(val song: Track) : HomeWidgetTarget

    /** Open the Memories deck (the pull-down attic) stopped on this memory. */
    data class MemoryFocus(val sessionId: Long) : HomeWidgetTarget
}

/**
 * One card in the home widget grid, following the Figma Widget 1×1 / 1×2
 * components: a cover on an entity-type backdrop shape, expanding to the wide
 * "1×2" variant when it carries a rating/review/note. A phone packs the
 * first 3 columns × 4 rows = 12 cells (a 1×2 spans two cells); a tablet
 * template seats more of the list (HomeJbiTemplate.kt). The expanded count is
 * bounded upstream so the shelf can't balloon.
 */
data class HomeWidgetCard(
    val stableId: String,
    // Drives the backdrop shape: ALBUM → Bun, SONG → Circle, PLAYLIST → Ghostish.
    val entityType: MemoryEntityType,
    val title: String,
    val subtitle: String,
    val coverArtUrl: String?,
    // Formatted score ("7.0") or "N/A"; only surfaced on expanded cards.
    val ratingText: String? = null,
    // What the score rests on: a date ("Jun 26") for a reviewed/noted card, or
    // "Based on 4/5 tracks" for an auto-averaged one. Null hides the line.
    val ratingBasis: String? = null,
    // Review / note copy. Expanded cards only.
    val comment: String? = null,
    // True = comment 是标题性文字（memory 卡的 AI 拟题 → 宋体加大）；
    // false = 用户正文（noted-track 的笔记原文 → 系统黑体）。字体规范 2026-07-26。
    val commentIsHeadline: Boolean = false,
    // A headline in the serif (a title the user or the AI wrote); false = the
    // app face (Yoin's motif, the bare album name).
    val commentSerif: Boolean = true,
    // True renders the wide "1×2" card (2 grid cells), false the "1×1" cover.
    val expanded: Boolean = false,
    val target: HomeWidgetTarget,
    // Localized subtitle. Null keeps [subtitle] (fixtures, previews).
    val subtitleText: UiText? = null,
    // Localized rating basis, when it is app copy rather than a date.
    val ratingBasisText: UiText? = null,
    // Epoch millis for a date basis. The feed formats it with the locale pattern.
    val ratingBasisDateMillis: Long? = null,
    // True shows the localized stand-in for an absent score. [ratingText] stays "N/A".
    val ratingUnavailable: Boolean = false,
)

/**
 * One Rediscover card: an album you kept something on (a rating, a review, a
 * note, a rated track) — or, with [song] set, a song you rated or noted —
 * that hasn't played in Yoin for a while. Every time field comes from play
 * history only (visits never count), which is why the copy says "in Yoin".
 */
@Immutable
data class HomeRediscoverItem(
    // Raw id (legacy `provider:` prefix stripped). An album card's tap →
    // onAlbumClick(albumId.toString(), null). On a song card, the song's
    // album — or the song's own id when it played without one (read
    // [song] for a song card's album).
    val albumId: MediaId,
    // The album card's title; a song card's album line.
    val albumName: String,
    val artistName: String?,
    val coverArtUrl: String?,
    // Album rating, else a covered track average; a song card's track
    // rating. Null = no badge.
    val score: Float?,
    // "%.1f" (Locale.US), the seal's own format; null with [score].
    val scoreText: String?,
    // NONE with no score. Your own score — an album rating, or a song card's
    // track rating — is ALBUM_RATING (the solid sticker); a covered track
    // average is AVERAGE_TRACK_RATING.
    val scoreKind: MemoryScoreKind,
    val lastPlayedAt: Long,
    val firstPlayedAt: Long?,
    val playCount: Int,
    // What was kept — the eyebrow's reason.
    val hasReview: Boolean = false,
    val noteCount: Int = 0,
    val ratedTrackCount: Int = 0,
    // Set on a song card: the track, rebuilt from its play history, that a
    // tap plays alone. Null = an album card.
    val song: Track? = null,
    // A song card's newest non-blank note: its one-line snippet when it has
    // no score.
    val noteSnippet: String? = null,
) {
    /** The card's title: the song's for a song card, else the album's. */
    val title: String get() = song?.title?.takeIf(String::isNotBlank) ?: albumName

    /** The shelf's LazyRow key; songs and albums never collide. */
    val shelfKey: String get() = song?.let { track -> "rediscover-song:${track.id}" } ?: "rediscover:$albumId"
}

/**
 * What the header's Memories pill shows: the most recently written memory
 * (cover + score) and how many notes the profile has kept. [latest] null with
 * a zero [noteCount] = nothing kept yet (the dashed "Memories" pill).
 */
@Immutable
data class HomeMemoryPill(
    val latest: Latest?,
    val noteCount: Int,
    // provider|profile — whose memories these are (the bubble's "seen" mark
    // is kept per profile).
    val scope: String = "",
    // Changes whenever something new is written: the latest memory's write
    // time or the note count. The Memories bubble speaks when it differs from
    // the last one it showed (HomeMemoryBubble.kt).
    val newsKey: String = "",
) {
    @Immutable
    data class Latest(
        // Candidate sessionId → HomeWidgetTarget.MemoryFocus: tapping the pill
        // opens the deck stopped on this album.
        val sessionId: Long,
        val albumId: MediaId,
        val albumName: String,
        val artistName: String?,
        val coverArtUrl: String?,
        // Same rule as the Memories seal: album rating > track average > none.
        val scoreKind: MemoryScoreKind,
        // "%.1f" (Locale.US), the seal's own format; null for NONE.
        val scoreText: String?,
    )
}
