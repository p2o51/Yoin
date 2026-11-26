package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.shared.release.CoverBandItem
import com.gpo.yoin.shared.release.CoverBandRole
import com.gpo.yoin.shared.release.YoinReleaseMarks
import kotlin.math.roundToInt

/*
 * Owner 2026-11-26 (PR #343): with the Expressive backdrop shape
 * behind a cover (Home's tiles, Library's grid), the cover sits in the
 * bottom-end [coverFraction] of its square and the shape shows in a band
 * above it and a strip beside it. The album's version tag ("全景声") and its
 * Explicit "E" sit in that top band, start-aligned with the cover's start
 * edge and centred in the band: on the shape, never on the art, and the
 * title under the cover stays clean (no tag, no badge, no extra line or
 * height). The cover's top-end corner is the memory sticker's, so the row
 * stops short of it. A band too short for the tag keeps only the E; one too
 * short for the E shows nothing (TalkBack still reads the title's card).
 */

/** Space kept free at the band's end for the memory sticker on the cover's top-end corner. */
private val StickerRoom = YoinReleaseMarks.bandStickerRoomDp.dp
private val BadgeGap = YoinReleaseMarks.bandGapDp.dp

/** The E's size on the band: about the tag's cap height, a touch larger so it reads at a glance. */
private val BandBadgeTextSize = YoinReleaseMarks.bandBadgeTextSp.sp

@Composable
internal fun CoverBandBadges(
    version: String?,
    explicit: Boolean,
    coverFraction: Float,
    modifier: Modifier = Modifier,
    // The cover's top-end corner holds a memory sticker: the row stops short of it.
    stickerRoom: Boolean = false,
    // The release's kind worth a tag ("EP", "Single"; coverKindTag), first in the row.
    kind: String? = null,
) {
    val tag = version?.trim()?.takeIf(String::isNotEmpty)
    if (tag == null && kind == null && !explicit) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val badgeStyle = MaterialTheme.typography.labelSmall.copy(fontSize = BandBadgeTextSize)
    // Slots: 0 kind, 1 version, 2 E — composed only when present, so map them by role.
    val roles = buildList {
        if (kind != null) add(Role.Kind)
        if (tag != null) add(Role.Version)
        if (explicit) add(Role.Explicit)
    }
    Layout(
        modifier = modifier,
        content = {
            if (kind != null) BandChip(kind)
            if (tag != null) BandChip(tag)
            // In the chips' surface colour, its E cut out to the shape's (the cover's) colour: on a
            // palette-tinted shape the list's onSurfaceVariant square could sink into the tint.
            if (explicit) ExplicitBadge(textStyle = badgeStyle, color = MaterialTheme.colorScheme.surface)
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val band = (h * (1f - coverFraction)).roundToInt()
        val byRole = roles.zip(measurables).toMap()
        // Each mark's natural size (intrinsics: a measurable is measured once, below, at the width the rule
        // gives it), then the rule — :shared's (golden release-marks.json), so HarmonyOS lays the band out the
        // same: start at the cover's start edge, slide toward the shape's top-start lobe only as far as the row
        // needs, stop as short of the shape's top-end curve; kept in the order E, kind, version (the version
        // may be cut, never below MinTagWidth; the others whole or left out).
        fun item(role: Role) = byRole[role]?.let { m ->
            val natural = m.maxIntrinsicWidth(band)
            CoverBandItem(natural, m.maxIntrinsicHeight(natural))
        }
        val rule = YoinReleaseMarks.coverBand(
            width = w,
            height = h,
            coverFraction = coverFraction,
            stickerRoom = if (stickerRoom) StickerRoom.roundToPx() else 0,
            gap = BadgeGap.roundToPx(),
            minTagWidth = MinTagWidth.roundToPx(),
            kind = item(Role.Kind),
            version = item(Role.Version),
            explicit = item(Role.Explicit),
        )
        val placed = rule.placed.map { p ->
            val m = byRole.getValue(Role.valueOf(p.role.name))
            p to m.measure(if (p.role == CoverBandRole.Version) Constraints(maxWidth = p.width) else Constraints())
        }
        layout(w, h) {
            placed.forEach { (p, placeable) ->
                val y = (rule.band - placeable.height) / 2
                // Mirrored by hand: the cover sits bottom-end, so in RTL the band starts at the right.
                placeable.place(if (rtl) w - p.x - placeable.width else p.x, y)
            }
        }
    }
}

private enum class Role { Kind, Version, Explicit }

/** A tag on the band: not the title's tonal tag (on the shape's tint it would vanish) — the page's own surface. */
@Composable
private fun BandChip(text: String) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** Below this a version tag is dropped rather than cut to an ellipsis. */
private val MinTagWidth = YoinReleaseMarks.bandMinTagWidthDp.dp

/**
 * A list row's kind tag ("EP", "Single"; coverKindTag), version tag and "E" at the row's end,
 * vertically centred, so the title stays clean and the row keeps its height. The version tag is
 * capped so it never squeezes the title.
 */
@Composable
fun RowEndBadges(version: String?, explicit: Boolean, modifier: Modifier = Modifier, kind: String? = null) {
    val tag = version?.trim()?.takeIf(String::isNotEmpty)
    if (tag == null && kind == null && !explicit) return
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(BadgeGap),
    ) {
        if (kind != null) AlbumVersionTag(version = kind)
        if (tag != null) {
            AlbumVersionTag(
                version = tag,
                modifier = Modifier.widthIn(max = 88.dp),
            )
        }
        if (explicit) ExplicitBadge(textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = BandBadgeTextSize))
    }
}
