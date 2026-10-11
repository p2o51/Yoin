@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.memory.AlbumMemoryCandidate
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.ui.component.ExpressiveBackdropColors
import com.gpo.yoin.ui.component.dashedOutline
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.theme.YoinMotion

// ── Album memory marks (owner 2026-10-11, T4: "JBI 往下全是光封面") ──────────
//
// A plain album cover the user kept something on — an album rating, rated
// tracks, notes — wears a small memory mark on its cover's top-end corner:
// the Memories pill's cookie sticker (PillCover), no number, no word, no
// outline round the cover. Solid = your album rating, a line = your track
// average, dashed = notes only. Jump Back In's plain album covers, Recently
// Added's and Recently Played's album cards wear it; songs, playlists and the
// 1×2 signal cards (which carry their score already) never do. The data is
// the memory signals' own candidate build (MemorySignals.pool) keyed by raw
// album id — no query of its own — so an album outside that build stays bare.

/**
 * One album's memory mark. [kind]: ALBUM_RATING (solid), AVERAGE_TRACK_RATING
 * (a line) or NONE (dashed: notes only). [scoreText] ("%.1f", the seal's own
 * format; null for NONE) and [noteCount] are for TalkBack — the cover shows
 * neither.
 */
@Immutable
data class HomeAlbumMemoryMark(
    val kind: MemoryScoreKind,
    val scoreText: String?,
    val noteCount: Int
)

/**
 * The mark for [candidate], or null when nothing is kept on it: your album
 * rating first, else the average of your rated tracks (the Memories seal's
 * rule, no coverage gate — the cover shows no number), else its notes.
 * Reviews and plays alone don't count.
 */
internal fun albumMemoryMarkOf(candidate: AlbumMemoryCandidate): HomeAlbumMemoryMark? {
    val notes = candidate.noteCount.coerceAtLeast(0)
    val albumRating = candidate.albumRating?.takeIf { rating -> rating > 0f }
    val average = candidate.averageSongRating?.takeIf { rating -> rating > 0f && candidate.ratedTrackCount > 0 }
    val (kind, score) = when {
        albumRating != null -> MemoryScoreKind.ALBUM_RATING to albumRating
        average != null -> MemoryScoreKind.AVERAGE_TRACK_RATING to average
        notes > 0 -> MemoryScoreKind.NONE to null
        else -> return null
    }
    return HomeAlbumMemoryMark(kind = kind, scoreText = score?.let(::rediscoverScoreText), noteCount = notes)
}

/**
 * Raw album id → its mark, for every album in [pool] (the memory signals'
 * candidate build, ineligible albums included) that has one. Legacy
 * `provider:`-prefixed ids are stripped. One album can come back as two
 * candidates — a legacy `provider:raw` album rating row and the raw id its
 * plays seed — and the build's order doesn't put the rated one first (the
 * raw one has the plays and the rated tracks), so the two merge: the
 * stronger kind with its own score, and the most notes either counted.
 */
internal fun homeAlbumMemoryMarks(pool: List<AlbumMemoryCandidate>): Map<String, HomeAlbumMemoryMark> {
    val marks = LinkedHashMap<String, HomeAlbumMemoryMark>()
    pool.forEach { candidate ->
        val rawId = MediaId.storedRawId(candidate.provider, candidate.albumId)
        if (rawId.isBlank()) return@forEach
        val mark = albumMemoryMarkOf(candidate) ?: return@forEach
        marks[rawId] = marks[rawId]?.mergedWith(mark) ?: mark
    }
    return marks
}

/** Two candidates of one album: the stronger kind (the earlier one on a tie) and the larger note count. */
private fun HomeAlbumMemoryMark.mergedWith(other: HomeAlbumMemoryMark): HomeAlbumMemoryMark {
    val stronger = if (other.kind.markStrength > kind.markStrength) other else this
    return stronger.copy(noteCount = maxOf(noteCount, other.noteCount))
}

/** Your album rating outranks your track average, which outranks notes alone. */
private val MemoryScoreKind.markStrength: Int
    get() = when (this) {
        MemoryScoreKind.ALBUM_RATING -> 2
        MemoryScoreKind.AVERAGE_TRACK_RATING -> 1
        MemoryScoreKind.NONE -> 0
    }

/**
 * The mark a Jump Back In card wears: plain (1×1) album covers only. Songs,
 * playlists and the 1×2 signal cards never wear one.
 */
internal fun HomeWidgetCard.albumMemoryMark(marks: Map<String, HomeAlbumMemoryMark>): HomeAlbumMemoryMark? {
    if (marks.isEmpty() || expanded || entityType != MemoryEntityType.ALBUM) return null
    val albumId = (target as? HomeWidgetTarget.AlbumDetail)?.albumId ?: return null
    val rawId = MediaId.parseOrNull(albumId)?.rawId ?: return null
    return marks[rawId]
}

/** The mark a shelf's album card (Recently Added, Recently Played) wears. */
internal fun Album.albumMemoryMark(marks: Map<String, HomeAlbumMemoryMark>): HomeAlbumMemoryMark? =
    if (marks.isEmpty()) null else marks[id.rawId]

// The Memories pill's cover sticker, same sizes: a 12dp cookie on a 16dp
// page-coloured disc that parts it from the cover and the backdrop.
private val AlbumMemoryMarkBacking = 16.dp
private val AlbumMemoryMarkCookie = 12.dp
private val AlbumMemoryMarkLine = 1.25.dp
private val AlbumMemoryMarkDash = 2.dp
private val AlbumMemoryMarkGap = 1.5.dp

/** How far the disc rides above the cover's top edge: half of it, so it sits on the corner. */
internal val AlbumMemoryMarkLift = AlbumMemoryMarkBacking / 2

/**
 * The sticker for [mark], inked from its cover's [backdrop] colours
 * ([albumMemoryMarkInk]). Nothing is laid out for a cover that never had a
 * mark. A mark landing after the cover fades in on the effects spring; one
 * that goes leaves as itself; a change of kind inks in or out (fill, line,
 * dashes) on the same spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AlbumMemoryMarkSticker(
    mark: HomeAlbumMemoryMark?,
    backdrop: ExpressiveBackdropColors,
    modifier: Modifier = Modifier
) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val effects = if (reduced) snap() else YoinMotion.effectsSpring<Float>()
    val appear = animateFloatAsState(if (mark != null) 1f else 0f, effects, label = "albumMemoryMarkAppear")
    val shown = rememberLastNonNull(mark) ?: return
    val ink = albumMemoryMarkInk(backdrop)
    val kind = shown.kind
    val fill = animateFloatAsState(
        targetValue = if (kind == MemoryScoreKind.ALBUM_RATING) 1f else 0f,
        animationSpec = effects,
        label = "albumMemoryMarkFill"
    )
    val line = animateFloatAsState(
        targetValue = when (kind) {
            MemoryScoreKind.ALBUM_RATING -> 0f
            MemoryScoreKind.AVERAGE_TRACK_RATING -> 1f
            MemoryScoreKind.NONE -> PillOutlineAlpha
        },
        animationSpec = effects,
        label = "albumMemoryMarkLine"
    )
    // Dashes close into a line as notes turn into a score ("the mould inks in").
    val solidity = animateFloatAsState(
        targetValue = if (kind == MemoryScoreKind.NONE) 0f else 1f,
        animationSpec = effects,
        label = "albumMemoryMarkSolidity"
    )
    val description = albumMemoryMarkDescription(shown)
    val cookie = MaterialShapes.Cookie12Sided.toShape()
    Box(
        modifier = modifier
            .size(AlbumMemoryMarkBacking)
            .clearAndSetSemantics { if (mark != null) contentDescription = description }
            .graphicsLayer { alpha = appear.value }
            .background(MaterialTheme.colorScheme.background, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(AlbumMemoryMarkCookie)
                .drawWithCache {
                    val outline = cookie.createOutline(size, layoutDirection, this)
                    onDrawBehind {
                        val alpha = fill.value
                        if (alpha > 0f) drawOutline(outline, ink.copy(alpha = ink.alpha * alpha))
                    }
                }
                .dashedOutline(
                    shape = cookie,
                    color = { ink.copy(alpha = ink.alpha * line.value) },
                    width = AlbumMemoryMarkLine,
                    dash = AlbumMemoryMarkDash,
                    gap = AlbumMemoryMarkGap,
                    solidity = { solidity.value }
                )
        )
    }
}

/**
 * The sticker's ink. Once the cover's palette is read: its accent in dark,
 * its base in light (the JBI score rule). Until then (Home's warm-up, a
 * fling, a cover that has none) the Memories pill's own fallback ink —
 * tertiary in dark, secondary in light — not the backdrop's pale container
 * fallbacks, which all but vanish on the page-coloured disc. The hand-off
 * rides the effects spring, as the palette's own colours do (a colour, so
 * reduced motion keeps it too).
 */
@Composable
internal fun albumMemoryMarkInk(backdrop: ExpressiveBackdropColors): Color {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) backdrop.accentColor else backdrop.baseColor
    val theme = if (dark) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    val read by animateFloatAsState(
        targetValue = if (backdrop.isResolvedFromPalette) 1f else 0f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "albumMemoryMarkInk"
    )
    return lerp(theme, palette, read)
}

/** TalkBack for a mark: "Your rating 9.0, 3 notes" / "Track average 8.4" / "2 notes". */
@Composable
private fun albumMemoryMarkDescription(mark: HomeAlbumMemoryMark): String {
    val resources = LocalContext.current.resources
    return buildList {
        mark.scoreText?.let { score -> add(rediscoverBadgeDescription(score, mark.kind, resources)) }
        if (mark.noteCount > 0) {
            add(resources.getQuantityString(R.plurals.home_rediscover_notes, mark.noteCount, mark.noteCount))
        }
    }.joinToString(separator = ", ")
}
