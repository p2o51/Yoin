package com.gpo.yoin.ui.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import com.gpo.yoin.ui.memories.copy.MemoryScores
import com.gpo.yoin.ui.memories.emblem.GrooveAwardMode
import com.gpo.yoin.ui.memories.emblem.GrooveAwardState
import com.gpo.yoin.ui.memories.emblem.GrooveEmblem
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveModel
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.emblem.rememberGrooveAwardState
import com.gpo.yoin.ui.memories.emblem.rememberGrooveHapticPlayer
import com.gpo.yoin.ui.memories.emblem.rememberGrooveTilt
import com.gpo.yoin.ui.memories.showcase.MemoryCoverColors
import com.gpo.yoin.ui.memories.showcase.MemoryPalette
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import com.gpo.yoin.ui.memories.showcase.memoryCoverColors
import com.gpo.yoin.ui.memories.showcase.rememberMemoryCoverColors
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * 唱片刻纹 · the score emblem, shared. A small, stable facade over the Memories groove emblem
 * (ui/memories/emblem) so other surfaces — the album detail page's rating graphic and its second page — draw
 * the very same emblem from plain inputs: a score, album vs average vs unrated, which tracks are rated, the
 * cover's colours and a size. Owner rules carried by the emblem itself: no "Album" caption ("Avg." and
 * "Unrated" stay), flat (no gloss, sheen or shadow), colours read off the cover on Now Playing's palette path
 * and lerped directly (never fromSeed). Memories keeps calling its own internals; both end in GrooveEmblem.
 */

/** Which score the emblem's centre label carries. */
enum class ScoreEmblemKind {
    /** The user's own album rating: a filled Cookie12Sided label with the score alone (no caption). */
    Album,

    /** The average of the rated tracks: an outlined disc label, "Avg." under the score from 88dp. */
    Average,

    /** No score: a dashed empty mould on neutral theme tokens ("Unrated" from 64dp); no ring is cut. */
    Unrated,
}

/** Where the emblem sits; only changes how it separates from what is behind it. */
enum class ScoreEmblemSurface {
    /**
     * Pinned on artwork (Memories' card emblem): the rim is inset 1dp and an unrated emblem gets an opaque
     * surface disc plus a hairline rim, so its silhouette separates from any cover.
     */
    Artwork,

    /**
     * On a plain page or bar (Memories' 48dp diary copy): the rim is inset .5dp and an unrated emblem keeps
     * only a faint ground and no hairline. Album / average draw the same on both.
     */
    Page,
}

/**
 * The two colours the emblem is built from, both read off the album cover by [rememberScoreEmblemColors].
 * Every emblem colour is a direct sRGB lerp of these and a few fixed ends — never a hue rotation, never
 * `fromSeed`. Construct one by hand only for previews and tests.
 *
 * @property base the album's colour: the playback theme's seed swatch (vibrant → dominant → muted). The emblem
 *   holds it inside the ink lightness window and derives its deep / soft anchors from it.
 * @property accent its lighter companion of the same hue family (the rim and label-ring tone).
 */
@Immutable
data class ScoreEmblemColors(val base: Color, val accent: Color)

/**
 * A running award's controller (the tiered "cutting" ceremony with haptics). Get one from
 * [rememberScoreEmblemAwardState]; its methods are documented on [GrooveAwardState]: `play()`, `play(Replay)`,
 * `setPending(true)`, `reset()`, `interrupt()`, `isAnimating`, `tier`.
 */
typealias ScoreEmblemAwardState = GrooveAwardState

/**
 * How [ScoreEmblemAwardState.play] runs: `First` cuts the grooves from frame 0; `Replay` keeps them cut and
 * plays only the climax (disc turn, label, tier-4 flare) and its beats.
 */
typealias ScoreEmblemAwardMode = GrooveAwardMode

/**
 * The score emblem (唱片刻纹): one ring per track, outer = track 1; rated tracks are cut as solid arcs in the
 * album's colour, unrated tracks stay a dotted lattice; the centre label carries the score. It is an exhibit:
 * not clickable (wrap it in your own clickable if it opens an editor) and reports itself as an image
 * ("Album rating 9.5", "Track average 7.8", "Not rated").
 *
 * The score is shown with one decimal, halves up, exactly as Memories shows it (9.95 → "10.0", and the 10.0 award
 * tier); see [scoreEmblemDisplayScore]. Pass Float ratings through [toScoreEmblemScore].
 *
 * @param score the score on the 0–10 scale; null (or a non-finite value) draws the unrated mould whatever [kind]
 *   says. Values outside 0–10 are clamped.
 * @param kind album rating, track average, or unrated. [ScoreEmblemKind.Unrated] ignores [score].
 * @param trackRated one flag per track in album order (track 1 first): true cuts that track's arc. Rings merge
 *   tracks when there are more tracks than the size's ring budget (3 below 64dp, 6 from 64, 8 from 88, 12 from
 *   110dp; fewer when the groove band is too narrow).
 * @param colors the cover's colours, from [rememberScoreEmblemColors].
 * @param size the emblem's edge; drawn natively at this size (thresholds at 60, 64, 88 and 110dp change the
 *   lattice density, captions and label), never scaled from another size.
 * @param surface artwork (default, as Memories' card) or a plain page; see [ScoreEmblemSurface].
 * @param tiltEnabled true lets the device tilt shift the outer ring lines' flat colour (the rotation sensor is
 *   registered only while true and the screen is resumed, and never under reduced motion or adaptive motion
 *   pressure such as battery saver).
 * @param award a controller from [rememberScoreEmblemAwardState] built from the SAME score, kind, trackRated,
 *   size and surface; null draws the finished, resting emblem. A controller built from other inputs is ignored
 *   (the emblem then draws at rest) rather than cutting the wrong rings.
 */
@Composable
fun ScoreEmblem(
    score: Double?,
    kind: ScoreEmblemKind,
    trackRated: List<Boolean>,
    colors: ScoreEmblemColors,
    size: Dp,
    modifier: Modifier = Modifier,
    surface: ScoreEmblemSurface = ScoreEmblemSurface.Artwork,
    tiltEnabled: Boolean = false,
    award: ScoreEmblemAwardState? = null,
) {
    val palette = remember(colors) { colors.toMemoryPalette() }
    val model = remember(score, kind, trackRated, palette) { scoreEmblemModel(score, kind, trackRated, palette) }
    val grooveSurface = surface.toGrooveSurface()
    val tilt = if (tiltEnabled) rememberGrooveTilt(active = true) else null
    GrooveEmblem(
        model = model,
        size = size,
        surface = grooveSurface,
        modifier = modifier,
        tilt = { tilt?.offset ?: Offset.Zero },
        award = award?.takeIf { it.drives(model, size, grooveSurface) },
    )
}

/**
 * The emblem colours of the cover at [coverUrl], read exactly as Memories reads them — which is Now Playing's path
 * (the 200px decode, a 16-colour Palette with the default filter, the vibrant → dominant → muted seed swatch, plus
 * an accent of the seed's hue family) — and cached per URL, so the album page, Memories and Now Playing never show
 * one cover as two colours.
 *
 * Pass the playback theme's URL for the cover (`repository.resolveCoverUrl(coverArt)`; on the album page that is
 * `AlbumDetailUiState.Content.coverArtUrl`). Until the cover is read (or when [coverUrl] is null) the theme's
 * primary stands in; the hand-off animates on the effects spring.
 */
@Composable
fun rememberScoreEmblemColors(coverUrl: String?): ScoreEmblemColors {
    val cover = rememberMemoryCoverColors(coverUrl)
    return remember(cover) { cover.toScoreEmblemColors() }
}

/**
 * The award controller for one [ScoreEmblem] — the tiered ceremony (tier by the displayed score: <6, 6–7.9,
 * 8–9.9, 10.0; unrated has none) with its haptic beats. Build it from the SAME score, kind, trackRated, size and
 * surface you pass to the emblem, then hand it over as `award`. Colours are not an input: a cover's colours
 * arriving mid-award never restart it. A change of any input yields a new controller at rest (finished emblem).
 *
 * ```
 * val award = rememberScoreEmblemAwardState(score, kind, rated, 96.dp)
 * // optional: wait uncut at the ceremony's frame 0 instead of showing the finished disc first
 * LaunchedEffect(award) { award.setPending(true) }
 * // when the emblem is on screen (or a rating commit landed): the tiered ceremony + haptics
 * LaunchedEffect(award, onScreen) { if (onScreen) award.play() }
 * ScoreEmblem(score, kind, rated, colors, 96.dp, award = award)
 * ```
 *
 * `play(ScoreEmblemAwardMode.Replay)` replays an emblem that is already cut (climax only); `interrupt()` settles a
 * running award to rest (e.g. the page is leaving); `reset()` jumps to the finished emblem; `isAnimating` tells
 * whether a run is moving the picture. A controller left pending must be played or reset, or the grooves stay
 * uncut. Because every input change yields a new controller, a score that resolves after the page opens (or a
 * commit) is a new controller too: gate `play()` on what you want celebrated. Under the user's reduced motion
 * ("remove animations") the award develops by alpha in ~200ms with a single beat; battery saver never reduces it.
 *
 * @param haptics false plays the picture only. Beats use touch-feedback usage, so the system's touch-feedback
 *   switch mutes them.
 * @param tag names this emblem's beats in the debug haptic trace.
 */
@Composable
fun rememberScoreEmblemAwardState(
    score: Double?,
    kind: ScoreEmblemKind,
    trackRated: List<Boolean>,
    size: Dp,
    surface: ScoreEmblemSurface = ScoreEmblemSurface.Artwork,
    haptics: Boolean = true,
    tag: String = "score-emblem",
): ScoreEmblemAwardState {
    // the award never reads colours: a fixed palette keeps the key stable while the cover's colours spring in
    val model = remember(score, kind, trackRated) { scoreEmblemModel(score, kind, trackRated, AwardPalette) }
    val player = if (haptics) rememberGrooveHapticPlayer() else null
    return rememberGrooveAwardState(
        model = model,
        size = size,
        surface = surface.toGrooveSurface(),
        haptics = player,
        tag = tag,
    )
}

/**
 * The score the emblem shows: one decimal, halves up, as every Memories surface rounds it (9.95 → 10.0,
 * 9.94 → 9.9), clamped to 0–10; null for null or non-finite input. [ScoreEmblem] applies it itself — call it only
 * to show the same number beside the emblem.
 */
fun scoreEmblemDisplayScore(score: Double?): Double? {
    if (score == null || !score.isFinite()) return null
    return MemoryScores.tenths(score.coerceIn(0.0, MaxScore)) / 10.0
}

/**
 * A Float rating (Room REAL, a slider value) as the emblem's score, read through its shortest decimal form: 9.95f
 * becomes 9.95, so it shows "10.0". A plain `toDouble()` gives 9.9499998…, which shows "9.9".
 */
fun Float.toScoreEmblemScore(): Double = toString().toDouble()

// ---------------------------------------------------------------- mapping (internal, unit-tested)

private const val MaxScore = 10.0

/** Palette of the award's model: never drawn (the award reads geometry and tier only). */
private val AwardPalette = MemoryPaletteSamples.M1

internal fun ScoreEmblemKind.toGrooveKind(): GrooveKind = when (this) {
    ScoreEmblemKind.Album -> GrooveKind.Album
    ScoreEmblemKind.Average -> GrooveKind.Average
    ScoreEmblemKind.Unrated -> GrooveKind.Unrated
}

internal fun ScoreEmblemSurface.toGrooveSurface(): GrooveSurface = when (this) {
    ScoreEmblemSurface.Artwork -> GrooveSurface.Cover
    ScoreEmblemSurface.Page -> GrooveSurface.Bar
}

/**
 * The groove model for the facade's inputs, built the way Memories builds its own (`MemoryEntry.grooveModel`):
 * the displayed one-decimal score, and unrated whenever there is no score.
 */
internal fun scoreEmblemModel(
    score: Double?,
    kind: ScoreEmblemKind,
    trackRated: List<Boolean>,
    palette: MemoryPalette,
): GrooveModel {
    val shown = scoreEmblemDisplayScore(score)?.takeIf { kind != ScoreEmblemKind.Unrated }
    return GrooveModel(
        kind = if (shown == null) GrooveKind.Unrated else kind.toGrooveKind(),
        score = shown,
        trackRated = trackRated,
        palette = palette,
    )
}

/** The four palette anchors the emblem draws with: Memories' direct lerp ([MemoryPalette.fromBackdrop]). */
internal fun ScoreEmblemColors.toMemoryPalette(): MemoryPalette = MemoryPalette.fromBackdrop(base, accent)

internal fun MemoryCoverColors.toScoreEmblemColors(): ScoreEmblemColors =
    ScoreEmblemColors(base = seed, accent = accent)

/** The emblem colours of a generated cover [palette] (Memories' [memoryCoverColors]); null without a seed swatch. */
internal fun scoreEmblemColorsOf(palette: Palette): ScoreEmblemColors? =
    memoryCoverColors(palette)?.toScoreEmblemColors()

/** True when [this] award was built for the emblem it is handed to (colours aside). */
internal fun GrooveAwardState.drives(model: GrooveModel, size: Dp, surface: GrooveSurface): Boolean =
    this.surface == surface &&
        this.size == size.value.toDouble() &&
        this.model.kind == model.kind &&
        this.model.score == model.score &&
        this.model.trackRated == model.trackRated

// ---------------------------------------------------------------- previews

/** Handoff sample m1's anchors (a violet cover with a gold accent). */
private val PreviewColors = ScoreEmblemColors(base = Color(0xFF3B2D8F), accent = Color(0xFFE2C27A))

private data class PreviewSample(val score: Double?, val kind: ScoreEmblemKind, val trackRated: List<Boolean>)

private val PreviewSamples = listOf(
    PreviewSample(9.5, ScoreEmblemKind.Album, List(10) { it < 4 }),
    PreviewSample(7.8, ScoreEmblemKind.Average, List(8) { it < 6 }),
    PreviewSample(null, ScoreEmblemKind.Unrated, List(12) { false }),
)

@Composable
private fun ScoreEmblemPreviewGrid() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PreviewSamples.forEach { sample ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreEmblem(sample.score, sample.kind, sample.trackRated, PreviewColors, 96.dp)
                ScoreEmblem(sample.score, sample.kind, sample.trackRated, PreviewColors, 48.dp)
                ScoreEmblem(
                    sample.score,
                    sample.kind,
                    sample.trackRated,
                    PreviewColors,
                    48.dp,
                    surface = ScoreEmblemSurface.Page,
                )
            }
        }
    }
}

@Preview(name = "Score emblem · light · album / average / unrated · 96 / 48 / 48 page")
@Composable
private fun ScoreEmblemLightPreview() {
    YoinTheme(darkTheme = false) {
        Surface { ScoreEmblemPreviewGrid() }
    }
}

@Preview(
    name = "Score emblem · dark · album / average / unrated · 96 / 48 / 48 page",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ScoreEmblemDarkPreview() {
    YoinTheme(darkTheme = true) {
        Surface { ScoreEmblemPreviewGrid() }
    }
}
