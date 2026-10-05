package com.gpo.yoin.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ScoreEmblem
import com.gpo.yoin.ui.component.ScoreEmblemAwardState
import com.gpo.yoin.ui.component.ScoreEmblemColors
import com.gpo.yoin.ui.component.ScoreEmblemKind
import com.gpo.yoin.ui.component.ScoreEmblemSurface
import com.gpo.yoin.ui.component.rememberScoreEmblemAwardState
import com.gpo.yoin.ui.component.rememberScoreEmblemColors
import com.gpo.yoin.ui.component.toScoreEmblemScore
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * The album's score slot, on page 1 (beside Last Play) and page 2 (hanging off the cover's corner): the shared
 * score emblem — Memories' groove record (owner, 2026-10-05: "那个徽章…去替代现在的这个评分的图形，把专辑页面的也
 * 改一下"). One ring per track, rated tracks cut; the label is the album rating, else the track average, else
 * the empty mould. Its colours are read off the cover on Now Playing's palette path (rememberScoreEmblemColors),
 * so one album wears one emblem on every surface.
 */

/** The emblem for [spec], coloured from [coverArtUrl]. Not clickable itself (callers wrap it). */
@Composable
internal fun AlbumScoreMark(
    spec: AlbumEmblemSpec,
    coverArtUrl: String?,
    size: Dp,
    onCover: Boolean,
    modifier: Modifier = Modifier,
    award: ScoreEmblemAwardState? = null,
    previewColors: ScoreEmblemColors? = null,
) {
    val colors = previewColors ?: rememberScoreEmblemColors(coverArtUrl)
    ScoreEmblem(
        score = spec.emblemScore(),
        kind = spec.emblemKind(),
        trackRated = spec.trackRated,
        colors = colors,
        size = size,
        modifier = modifier,
        surface = if (onCover) ScoreEmblemSurface.Artwork else ScoreEmblemSurface.Page,
        award = award,
    )
}

/**
 * The award ceremony for an [AlbumScoreMark] built from the same [spec], [size] and [onCover] (any change is a
 * new controller at rest). The album page plays it without haptics (D3 §11.12: no beat when the stamp lands).
 */
@Composable
internal fun rememberAlbumScoreAward(spec: AlbumEmblemSpec, size: Dp, onCover: Boolean): ScoreEmblemAwardState =
    rememberScoreEmblemAwardState(
        score = spec.emblemScore(),
        kind = spec.emblemKind(),
        trackRated = spec.trackRated,
        size = size,
        surface = if (onCover) ScoreEmblemSurface.Artwork else ScoreEmblemSurface.Page,
        haptics = false,
        tag = "album-score",
    )

internal fun AlbumEmblemSpec.emblemKind(): ScoreEmblemKind = when (score.kind) {
    AlbumScoreKind.UserRating -> ScoreEmblemKind.Album
    AlbumScoreKind.Average -> ScoreEmblemKind.Average
    AlbumScoreKind.None -> ScoreEmblemKind.Unrated
}

/** Through the Float's decimal text, so 9.95f shows "10.0" (not 9.9499…). */
internal fun AlbumEmblemSpec.emblemScore(): Double? =
    if (score.kind == AlbumScoreKind.None) null else score.value.toScoreEmblemScore()

@Preview(showBackground = true)
@Composable
private fun AlbumScoreMarkPreview() {
    val colors = ScoreEmblemColors(base = Color(0xFF3B2D8F), accent = Color(0xFFE2C27A))
    YoinTheme {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val rated = List(10) { it % 3 != 0 }
            AlbumScoreMark(
                spec = AlbumEmblemSpec(AlbumScore(AlbumScoreKind.UserRating, 9f), rated),
                coverArtUrl = null,
                size = 64.dp,
                onCover = false,
                previewColors = colors,
            )
            AlbumScoreMark(
                spec = AlbumEmblemSpec(AlbumScore(AlbumScoreKind.Average, 7.8f), rated),
                coverArtUrl = null,
                size = 64.dp,
                onCover = false,
                previewColors = colors,
            )
            AlbumScoreMark(
                spec = AlbumEmblemSpec(AlbumScore(AlbumScoreKind.None, 0f), List(10) { false }),
                coverArtUrl = null,
                size = 64.dp,
                onCover = true,
                previewColors = colors,
            )
        }
    }
}
