package com.gpo.yoin.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.home.WidgetBackdropArtwork
import com.gpo.yoin.ui.home.WidgetShapeKind
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
internal fun AlbumCard(
    coverArtUrl: String?,
    title: String,
    subtitle: String? = null,
    metaLabel: String? = null,
    // The album's version, shown as a tag after the title (AlbumVersionTitle).
    version: String? = null,
    // The release is marked explicit: the "E" badge follows the title (ExplicitBadge).
    explicit: Boolean = false,
    onClick: () -> Unit,
    extractBackdropColors: Boolean = true,
    modifier: Modifier = Modifier,
    fixedWidth: androidx.compose.ui.unit.Dp? = 156.dp,
    showIndication: Boolean = false,
    // What a missing cover shows: the kind of thing the card is (a playlist
    // card in Library's All view shows the playlist mark).
    fallbackIcon: ImageVector = YoinSymbols.Album,
    // PROTOTYPE (owner 2026-11-26): Home's Expressive backdrop shape behind the cover (Bun for an
    // album, Ghostish for a playlist), with the version tag and the "E" on the shape's band above the
    // cover (CoverBandBadges) instead of after the title. Null keeps the bare full-bleed cover.
    backdropKind: WidgetShapeKind? = WidgetShapeKind.Album,
    // PROTOTYPE: an EP or a single says so on the same band ("EP", "Single").
    releaseType: com.gpo.yoin.data.model.ReleaseType? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Surface intentionally has no `shape` (defaults to RectangleShape).
    // A rounded outer shape used to clip the inner Column, which chewed
    // into the metaPill / title / subtitle at the corners — most visible
    // as the "left-bottom corner being cut off" in the Library albums
    // grid and ArtistDetail. The cover artwork is clipped to its own
    // shape internally by `ExpressiveBackdropArtwork`, so the outer
    // container doesn't need to clip anything.
    Surface(
        modifier = modifier.then(
            if (fixedWidth != null) {
                Modifier.width(fixedWidth)
            } else {
                Modifier
            },
        ),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        // Spacing rhythm: full-bleed cover (the sub-1f fill fractions and the
        // end/bottom inset were placeholders for the removed animated backdrop
        // shape — pure ghost margins now), one deliberate 5dp gap to the text,
        // and the title + subtitle flush so they read as a single cluster. No
        // reserved min-height: a one-line card ends where its text ends.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .handPointer()
                .clickable(
                    interactionSource = interactionSource,
                    indication = if (showIndication) ripple() else null,
                    role = Role.Button,
                    onClick = onClick,
                ),
        ) {
            if (backdropKind != null) {
                // One static clip per cover (no press morph): a Library grid holds dozens of them.
                WidgetBackdropArtwork(
                    model = coverArtUrl,
                    kind = backdropKind,
                    contentDescription = title,
                    extractBackdropColors = extractBackdropColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    interactionSource = interactionSource,
                    version = version,
                    explicit = explicit,
                    kindTag = coverKindTag(releaseType),
                    pressMorph = false,
                )
            } else {
                ExpressiveBackdropArtwork(
                    model = coverArtUrl,
                    contentDescription = title,
                    variant = ExpressiveBackdropVariant.Bun,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .seamDissolve(),
                    shape = YoinArtworkShapes.Cover,
                    fallbackIcon = fallbackIcon,
                    interactionSource = interactionSource,
                    fillFraction = 1f,
                    tonalElevation = 0.dp,
                    extractBackdropColors = extractBackdropColors,
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            if (!metaLabel.isNullOrBlank()) {
                ExpressiveMetaPill(text = metaLabel, modifier = Modifier.seamFade())
                Spacer(modifier = Modifier.height(3.dp))
            }
            // One line only — overflow scrolls (marquee) instead of wrapping,
            // so long titles never make one card taller than its row.
            // With the backdrop the tag and the E live on the art; the title is the name alone.
            AlbumVersionTitle(
                name = if (backdropKind != null) {
                    nameWithoutKindSuffix(com.gpo.yoin.data.model.albumNameWithoutVersion(title, version), releaseType)
                } else {
                    title
                },
                version = if (backdropKind != null) null else version,
                titleStyle = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().seamFade(),
                nameEllipsizes = false,
                explicit = explicit && backdropKind == null,
            ) { text, titleModifier ->
                MarqueeText(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = titleModifier,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.seamFade(),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun AlbumCardPreview() {
    YoinTheme {
        AlbumCard(
            coverArtUrl = null,
            title = "Random Access Memories",
            subtitle = "Daft Punk",
            metaLabel = "2013",
            onClick = {},
            fixedWidth = null,
        )
    }
}
