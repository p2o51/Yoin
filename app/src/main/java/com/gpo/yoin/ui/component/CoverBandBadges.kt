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
private val StickerRoom = 14.dp
private val BadgeGap = 4.dp

/** The E's size on the band: about the tag's cap height, a touch larger so it reads at a glance. */
private val BandBadgeTextSize = 17.sp

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
        val coverStart = (w * (1f - coverFraction)).roundToInt()
        // The furthest start the row may take when the cover's edge leaves too little: just inside
        // the shape's top-start lobe.
        val minStart = (w * BandInsetFraction).roundToInt()
        val gap = BadgeGap.roundToPx()
        // The row stops short of the shape's top-end curve as it starts inside its top-start lobe:
        // a badge running to the box's edge would hang half off the shape (its cut-out E then shows
        // the page on one side and the shape on the other).
        val end = w - maxOf(minStart, if (stickerRoom) StickerRoom.roundToPx() else 0)
        var room = (end - minStart).coerceAtLeast(0)
        val byRole = roles.zip(measurables).toMap()
        val placed = mutableMapOf<Role, androidx.compose.ui.layout.Placeable>()
        // Kept in this order when the band is short: the E, then the kind, then the version (which may
        // be cut, but never below MinTagWidth; the others are whole or left out).
        for (role in listOf(Role.Explicit, Role.Kind, Role.Version)) {
            val m = byRole[role] ?: continue
            val need = if (placed.isEmpty()) 0 else gap
            val natural = m.maxIntrinsicWidth(band)
            val left = room - need
            val p = when (role) {
                Role.Version -> if (left >= minOf(natural, MinTagWidth.roundToPx())) m.measure(Constraints(maxWidth = left)) else null
                else -> if (natural <= left) m.measure(Constraints()) else null
            }?.takeIf { it.height <= band } ?: continue
            placed[role] = p
            room -= p.width + need
        }
        val row = listOf(Role.Kind, Role.Version, Role.Explicit).mapNotNull { placed[it] }
        val total = row.sumOf { it.width } + gap * (row.size - 1).coerceAtLeast(0)
        // Start at the cover's start edge; slide toward the shape's lobe only as far as the row needs.
        val start = (end - total).coerceIn(minStart, coverStart.coerceAtLeast(minStart))
        layout(w, h) {
            var x = start
            row.forEach { p ->
                val y = (band - p.height) / 2
                // Mirrored by hand: the cover sits bottom-end, so in RTL the band starts at the right.
                p.place(if (rtl) w - x - p.width else x, y)
                x += p.width + gap
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

/** How far in from the shape's start the row may begin when it needs more than the cover's width. */
private const val BandInsetFraction = 0.08f

/** Below this a version tag is dropped rather than cut to an ellipsis. */
private val MinTagWidth = 40.dp

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
