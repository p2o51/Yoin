package com.gpo.yoin.ui.detail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.StagedReveal
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.nowplaying.dotDissolve
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * The playlist hero's sleeves (歌单封套, owner 2026-11-06; prototype artifact
 * Va9P7ZE4MkAt4Pd75JRtEP): two of the playlist's own album covers fanned
 * behind its cover, like a small stack of record sleeves. They replace the
 * stacked V the page drew there before.
 *
 *   - Which: the rows' own album covers (the same 320px urls, so the image
 *     cache already has them), first two distinct ones that aren't the hero.
 *   - Where: 0.86 × the cover side, centred on the cover, ±0.20 × side across
 *     and 0.07 × side down, tilted ∓6.3°; the cover's corner radius; flat.
 *   - Knock-out (Yoin Symbols' layering rule): a ring of the page surface,
 *     max(3dp, 2.5% of the side), around the cover and the front sleeve, drawn
 *     only where it lies over a sheet behind, so the three read as separate
 *     sheets and nothing grows a halo over the page background.
 *   - Never cut: the sheets are drawn whole (no clip on them); a caller shrinks
 *     the fan ([playlistSleeveSpread]) where its box is narrow.
 *   - Leaving (the pull-up): they stay where they are and break up into fine
 *     dots, like the album's butterfly and the artist's ring (owner 2026-11-06).
 *
 * Everything per frame (reveal, leaving, the moving cover) is read in placement
 * or draw only, so a pull-up or the entrance never recomposes the page.
 */

/** At most this many sleeves behind the cover. */
internal const val PlaylistSleeveCount = 2

/** Sleeve side, × the cover side. */
internal const val PlaylistSleeveScale = 0.86f

/** The full fan's horizontal offset of each sleeve from the cover centre, × the cover side. */
internal const val PlaylistSleeveSpread = 0.20f

/** The fan's downward offset, × the cover side. */
internal const val PlaylistSleeveDrop = 0.07f

/** The full fan's tilt: the start sleeve leans toward the start, the other toward the end. */
internal const val PlaylistSleeveTiltDegrees = 6.3f

/** The pull-up has broken the sleeves up into dots by this progress (leaving = smoothStep(0, this, progress)). */
internal const val PlaylistSleeveLeaveEnd = 0.7f

/** Knock-out ring width, × the cover side, never under [PlaylistSleeveMinGap]. */
private const val PlaylistSleeveGapFraction = 0.025f
private val PlaylistSleeveMinGap = 3.dp

/** How close a sheet may come to the edge of the box it lives in. */
internal val PlaylistSleeveEdgeClearance = 8.dp

// Entrance: each sleeve opens from behind the cover, grown from 0.6, a beat
// after the cover and 50ms after the one before it (left, then right).
private const val PlaylistSleeveEnterScaleFrom = 0.6f
private const val PlaylistSleeveFadeInRate = 1.4f
private const val PlaylistSleeveStaggerMillis = 50L

// The rows' url size (PlaylistDetailViewModel resolves every row's cover at
// 320), so a sleeve decodes the very file the rows already fetched.
private const val PlaylistSleeveRequestPx = 320

/**
 * Half the horizontal extent of a sleeve tilted the full [PlaylistSleeveTiltDegrees], × the cover side.
 * A sleeve with no spread stays inside the cover (this is under 0.5).
 */
internal val PlaylistSleeveHalfExtent: Float = run {
    val radians = PlaylistSleeveTiltDegrees * PI.toFloat() / 180f
    PlaylistSleeveScale / 2f * (cos(radians) + sin(radians))
}

/**
 * The covers to fan behind the playlist's cover, start sleeve first: the songs'
 * own covers in playlist order ([songCovers], the rows' urls), distinct, none
 * equal to [heroCover] (the hero resolved at the rows' size, so the two compare
 * like for like), and not the first song's when the hero only stands in with
 * the first track's cover ([heroIsFirstTrackCover]). At most [max]; an empty
 * playlist, or one whose songs all share the hero's album, gets none.
 */
internal fun playlistSleeveCovers(
    songCovers: List<String?>,
    heroCover: String?,
    heroIsFirstTrackCover: Boolean,
    max: Int = PlaylistSleeveCount,
): List<String> {
    if (max <= 0) return emptyList()
    val skip = buildSet {
        heroCover?.takeIf { it.isNotBlank() }?.let(::add)
        if (heroIsFirstTrackCover) songCovers.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val picked = LinkedHashSet<String>()
    for (url in songCovers) {
        if (url.isNullOrBlank() || url in skip) continue
        picked += url
        if (picked.size == max) break
    }
    return picked.toList()
}

/**
 * The fan's horizontal offset (× cover side) that keeps a fully opened, tilted
 * sleeve within [sideRoom] px of the cover's side edges (the room to the box
 * edge or the neighbour, less any clearance): the full [PlaylistSleeveSpread]
 * where there is room, less on a narrow box, 0 when there is none.
 */
internal fun playlistSleeveSpread(coverSide: Float, sideRoom: Float): Float {
    if (coverSide <= 0f) return 0f
    return (sideRoom / coverSide + 0.5f - PlaylistSleeveHalfExtent).coerceIn(0f, PlaylistSleeveSpread)
}

/** One sleeve as drawn: centre, side and tilt (degrees, clockwise), in the stage's px. */
@Immutable
internal data class PlaylistSleevePose(
    val center: Offset,
    val side: Float,
    val rotation: Float,
) {
    val rect: Rect get() = Rect(center, side / 2f)
}

/**
 * Where sleeve [index] (0 = the start one) sits behind a cover at [cover].
 * [open] runs the fan from closed behind the cover (0) to full (1); [grow] is
 * the entrance's scale; [spread] comes from [playlistSleeveSpread].
 */
internal fun playlistSleevePose(
    cover: Rect,
    index: Int,
    open: Float,
    grow: Float,
    spread: Float,
    rtl: Boolean,
): PlaylistSleevePose {
    val onLeft = (index == 0) != rtl
    val direction = if (onLeft) -1f else 1f
    val side = cover.width
    return PlaylistSleevePose(
        center = Offset(
            cover.center.x + direction * spread * side * open,
            cover.center.y + PlaylistSleeveDrop * side * open,
        ),
        side = side * PlaylistSleeveScale * grow,
        rotation = direction * PlaylistSleeveTiltDegrees * open,
    )
}

/** The knock-out ring's width for a cover [coverSide] px wide. */
internal fun playlistSleeveGap(coverSide: Float, minGapPx: Float): Float =
    max(minGapPx, coverSide * PlaylistSleeveGapFraction)

/**
 * Where [Modifier.stagedBeat][com.gpo.yoin.ui.component.stagedBeat] shows [rect]
 * at beat [progress]: risen [rise] px from below and grown from [scaleFrom].
 */
internal fun stagedBeatRect(rect: Rect, progress: Float, rise: Float, scaleFrom: Float): Rect {
    val p = progress.coerceIn(0f, 1f)
    if (p >= 1f) return rect
    val scale = scaleFrom + (1f - scaleFrom) * p
    val cx = rect.center.x
    val cy = rect.center.y + (1f - p) * rise
    val halfW = rect.width * scale / 2f
    val halfH = rect.height * scale / 2f
    return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/**
 * The sleeves' entrance, one beat each: they open from behind the cover after
 * the hero's own staged beat starts, 50ms apart, on the Expressive slow spatial
 * spring (the same spring [StagedReveal] runs). Settled from the start when the
 * hero already was (a returning page never replays it) and in previews.
 */
@Stable
internal class PlaylistSleeveReveal internal constructor(
    internal val beats: List<Animatable<Float, AnimationVector1D>>,
) {
    fun progress(index: Int): Float = beats.getOrNull(index)?.value ?: 1f
}

@Composable
internal fun rememberPlaylistSleeveReveal(stagedReveal: StagedReveal): PlaylistSleeveReveal {
    val inspection = LocalInspectionMode.current
    val reveal = remember(stagedReveal) {
        // Not observed: the hero beat animates, and this read must not recompose the page.
        val settled = inspection || Snapshot.withoutReadObservation { stagedReveal.hero >= 1f }
        PlaylistSleeveReveal(List(PlaylistSleeveCount) { Animatable(if (settled) 1f else 0f) })
    }
    val spec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(reveal) {
        if (reveal.beats.all { it.value >= 1f }) return@LaunchedEffect
        snapshotFlow { stagedReveal.hero > 0f }.first { it }
        reveal.beats.forEachIndexed { index, beat ->
            launch {
                delay(PlaylistSleeveStaggerMillis * (index + 1))
                beat.animateTo(1f, spec)
            }
        }
    }
    return reveal
}

/**
 * The sleeves behind a hero cover, in a stage box whose px [cover] is given in
 * (the caller draws the cover itself on top, after this). Composes nothing
 * for a playlist that never had [covers]. A sleeve that leaves (a refresh
 * changed the songs) fades out on the effects spring; one that arrives fades in.
 *
 * @param restingSide the cover's side at rest, px: the sleeves are measured
 *   from it once and only moved and scaled after (read in measure).
 * @param cover the cover's rect now (read in placement and draw).
 * @param corner the cover's on-screen corner radius now, px; the sleeves match it.
 * @param spread the fan offset, from [playlistSleeveSpread].
 * @param leaving 0 = whole, 1 = broken up into dots (the pull-up).
 * @param fade multiplies the sleeves' opacity (reduced motion's pull-up).
 * @param coverRing the cover's visible rect for its knock-out ring, when it
 *   differs from [cover] (the hero's own staged beat).
 * @param coverAlpha the cover's opacity: its ring fades with it.
 */
@Composable
internal fun PlaylistSleeves(
    covers: List<String>,
    reveal: PlaylistSleeveReveal,
    restingSide: () -> Float,
    cover: () -> Rect,
    corner: () -> Float,
    spread: () -> Float,
    modifier: Modifier = Modifier,
    leaving: () -> Float = { 0f },
    fade: () -> Float = { 1f },
    reduced: Boolean = false,
    coverRing: () -> Rect = cover,
    coverAlpha: () -> Float = { 1f },
    artworkModifier: Modifier = Modifier,
) {
    // One slot per sleeve (PlaylistSleeveCount = 2), in a fixed call order.
    val slots = listOf(rememberSleeveSlot(covers.getOrNull(0)), rememberSleeveSlot(covers.getOrNull(1)))
    if (slots.none { it.url != null }) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val minGapPx = with(LocalDensity.current) { PlaylistSleeveMinGap.toPx() }
    // Read while drawing only: a theme change repaints the rings, it never recomposes.
    val surface = animateColorAsState(
        MaterialTheme.colorScheme.surface,
        YoinMotion.effectsSpring(),
        label = "playlistSleeveGap",
    )
    val clip = remember { Path() }

    fun pose(index: Int): PlaylistSleevePose? {
        val now = cover()
        if (now.width <= 0f) return null
        val beat = reveal.progress(index)
        val open = if (reduced) 1f else beat
        val grow = if (reduced) 1f else PlaylistSleeveEnterScaleFrom + (1f - PlaylistSleeveEnterScaleFrom) * beat
        return playlistSleevePose(now, index, open, grow, spread(), rtl)
    }
    fun opacity(index: Int): Float =
        (reveal.progress(index) * PlaylistSleeveFadeInRate).coerceIn(0f, 1f) *
            fade().coerceIn(0f, 1f) *
            slots[index].presence.value

    Box(modifier.dotDissolve { if (reduced) 0f else leaving().coerceIn(0f, 1f) }) {
        slots.forEachIndexed { index, slot ->
            val url = slot.url ?: return@forEachIndexed
            if (index > 0 && slots[index - 1].url != null) {
                // This sleeve's ring, cut into the sleeve behind it.
                Spacer(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val front = pose(index) ?: return@drawBehind
                            val behind = pose(index - 1) ?: return@drawBehind
                            drawKnockOut(
                                front = front.rect,
                                frontRotation = front.rotation,
                                frontCorner = corner(),
                                behind = behind,
                                behindCorner = corner(),
                                gap = playlistSleeveGap(cover().width, minGapPx),
                                color = surface.value,
                                alpha = minOf(opacity(index), opacity(index - 1)),
                                path = clip,
                            )
                        },
                )
            }
            SleeveSheet(
                url = url,
                restingSide = restingSide,
                pose = { pose(index) },
                corner = corner,
                opacity = { opacity(index) },
                artworkModifier = artworkModifier,
            )
        }
        // The cover's ring, cut into whichever sleeves lie under it.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val ring = coverRing()
                    if (ring.width <= 0f) return@drawBehind
                    val gap = playlistSleeveGap(cover().width, minGapPx)
                    slots.indices.forEach { index ->
                        if (slots[index].url == null) return@forEach
                        val behind = pose(index) ?: return@forEach
                        drawKnockOut(
                            front = ring,
                            frontRotation = 0f,
                            frontCorner = corner(),
                            behind = behind,
                            behindCorner = corner(),
                            gap = gap,
                            color = surface.value,
                            alpha = minOf(opacity(index), coverAlpha().coerceIn(0f, 1f)),
                            path = clip,
                        )
                    }
                },
        )
    }
}

/**
 * The sleeves behind a cover that rests in its own [coverSide] box (Medium,
 * Wide, landscape handset): [modifier] sizes the stage to that box. [sideRoom]
 * is the free width beside the cover on its tighter side; the fan shrinks to
 * keep [PlaylistSleeveEdgeClearance] of it. Reduced motion keeps the fan open
 * and leaves the fading to the hero around it.
 */
@Composable
internal fun PlaylistCoverSleeves(
    covers: List<String>,
    reveal: PlaylistSleeveReveal,
    coverSide: Dp,
    sideRoom: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    leaving: () -> Float = { 0f },
    artworkModifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val sidePx = with(density) { coverSide.toPx() }
    val cornerPx = with(density) { corner.toPx() }
    val spread = playlistSleeveSpread(sidePx, with(density) { (sideRoom - PlaylistSleeveEdgeClearance).toPx() })
    // The stage reaches past the cover's box (centred on it), so the dissolve's
    // layer holds the fanned sheets whole.
    val stage = coverSide * PlaylistCoverSleeveStage
    val inset = with(density) { (stage - coverSide).toPx() } / 2f
    val rect = Rect(inset, inset, inset + sidePx, inset + sidePx)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        PlaylistSleeves(
            covers = covers,
            reveal = reveal,
            restingSide = { sidePx },
            cover = { rect },
            corner = { cornerPx },
            spread = { spread },
            modifier = Modifier.requiredSize(stage),
            leaving = leaving,
            reduced = reduced,
            artworkModifier = artworkModifier,
        )
    }
}

// The cover-box stage's side, × the cover side: room for the fanned sheets (half
// extent + spread + drop, all under 0.8 of the side from the centre).
private const val PlaylistCoverSleeveStage = 1.6f

/** A slot's url: the last one it showed stays while it fades out. */
@Stable
private class SleeveSlot(val url: String?, val presence: State<Float>)

@Composable
private fun rememberSleeveSlot(url: String?): SleeveSlot {
    // A plain holder, not state: a url change recomposes this slot anyway.
    val lastUrl = remember { arrayOfNulls<String>(1) }
    if (url != null) lastUrl[0] = url
    val presence = remember { Animatable(if (url != null) 1f else 0f) }
    val spec = YoinMotion.effectsSpring<Float>()
    val present = url != null
    LaunchedEffect(present) { presence.animateTo(if (present) 1f else 0f, spec) }
    return SleeveSlot(url = lastUrl[0], presence = presence.asState())
}

/**
 * One sleeve: measured once from [restingSide], then moved, scaled and tilted
 * in its layer, clipped to the cover's corner radius. Not read aloud — the
 * cover above it carries the page's description.
 */
@Composable
private fun SleeveSheet(
    url: String,
    restingSide: () -> Float,
    pose: () -> PlaylistSleevePose?,
    corner: () -> Float,
    opacity: () -> Float,
    artworkModifier: Modifier,
) {
    Layout(
        content = {
            ExpressiveMediaArtwork(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(artworkModifier)
                    .clearAndSetSemantics {},
                shape = RectangleShape,
                fallbackIcon = YoinSymbols.Album,
                // Flat, like the cover: no shadow, no outline.
                border = null,
                shadowElevation = 0.dp,
                tonalElevation = 3.dp,
                requestSizePx = PlaylistSleeveRequestPx,
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val side = (restingSide() * PlaylistSleeveScale).roundToInt().coerceAtLeast(1)
        val placeable = measurables.single().measure(Constraints.fixed(side, side))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else side
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else side
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                val p = pose()
                if (p == null || p.side <= 0f) {
                    alpha = 0f
                    return@placeWithLayer
                }
                val scale = p.side / side
                transformOrigin = TransformOrigin.Center
                translationX = p.center.x - side / 2f
                translationY = p.center.y - side / 2f
                scaleX = scale
                scaleY = scale
                rotationZ = p.rotation
                alpha = opacity()
                shape = RoundedCornerShape(corner() / scale)
                clip = true
            }
        }
    }
}

/**
 * The knock-out: a [color] rounded rect [gap] px larger than the [front] sheet
 * (tilted [frontRotation]°), painted only inside the sheet [behind] it. Clips
 * the ring, never a sheet.
 */
private fun DrawScope.drawKnockOut(
    front: Rect,
    frontRotation: Float,
    frontCorner: Float,
    behind: PlaylistSleevePose,
    behindCorner: Float,
    gap: Float,
    color: Color,
    alpha: Float,
    path: Path,
) {
    if (alpha <= 0f || behind.side <= 0f) return
    path.reset()
    path.addRoundRect(RoundRect(behind.rect, CornerRadius(behindCorner)))
    withTransform({ rotate(behind.rotation, behind.center) }) {
        clipPath(path) {
            withTransform({
                rotate(-behind.rotation, behind.center)
                rotate(frontRotation, front.center)
            }) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(front.left - gap, front.top - gap),
                    size = Size(front.width + 2f * gap, front.height + 2f * gap),
                    cornerRadius = CornerRadius(frontCorner + gap),
                    alpha = alpha,
                )
            }
        }
    }
}

@Preview(name = "Playlist sleeves", widthDp = 360, heightDp = 340, showBackground = true)
@Composable
private fun PlaylistSleevesPreview() {
    YoinTheme {
        val side = 240.dp
        Box(modifier = Modifier.padding(start = 60.dp, top = 40.dp).size(side)) {
            PlaylistCoverSleeves(
                covers = listOf("preview://sleeve-a", "preview://sleeve-b"),
                reveal = remember { PlaylistSleeveReveal(List(PlaylistSleeveCount) { Animatable(1f) }) },
                coverSide = side,
                sideRoom = 60.dp,
                corner = 8.dp,
                modifier = Modifier.fillMaxSize(),
            )
            ExpressiveMediaArtwork(
                model = null,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = YoinArtworkShapes.Hero,
                fallbackIcon = YoinSymbols.Playlist,
                border = null,
                shadowElevation = 0.dp,
                tonalElevation = 3.dp,
            )
        }
    }
}
