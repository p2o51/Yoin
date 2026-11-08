package com.gpo.yoin.ui.memories.showcase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.memories.MemoryOtherProfile
import com.gpo.yoin.ui.settings.AccountMark
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.theme.GoogleSansFlex

/**
 * Beside Go to album on another account's memory: that account's mark (its avatar shape in its service's
 * hue, as Home's other-accounts shelf shows it) and「来自 …」(owner 2026-11-04: only the account, no "this
 * switches accounts"). Off the cover, so nothing sits on the art (Spotify's rule). Reads as one line.
 */
@Composable
internal fun MemoryOtherProfileLine(
    owner: MemoryOtherProfile,
    tones: MemoryPaletteTones,
    modifier: Modifier = Modifier,
    maxWidth: Dp = OtherProfileLineMaxWidth,
) {
    val text = stringResource(R.string.mem_other_profile_from, owner.name)
    val description = stringResource(R.string.mem_cd_other_profile_from, owner.name)
    Row(
        modifier = modifier
            .height(OtherProfileLineHeight)
            .widthIn(max = maxWidth)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AccountMark(shapeIndex = owner.shape, identity = owner.kind.serviceIdentity, size = 16.dp)
        Text(
            text = text,
            style = cardText(GoogleSansFlex, FontWeight.Medium, 13.sp, 1.3f),
            color = tones.ink.copy(alpha = OtherProfileLineAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val OtherProfileLineMaxWidth = 160.dp
internal val OtherProfileLineHeight: Dp = 20.dp

/** Between Go to album and the line beside it (the diary's foot, the spread). */
internal val OtherProfileLineGoGap: Dp = 12.dp

/** Above the line when it sits under the card's buttons. */
internal val OtherProfileLineCardGap: Dp = 8.dp
private const val OtherProfileLineAlpha = 0.72f
