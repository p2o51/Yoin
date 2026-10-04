package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.model.UNPLAYABLE_APPLE_IMPORT_REASON
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/** Dim level for a row whose track Yoin cannot play (imported Apple Music song without a catalog match). */
const val UnavailableTrackAlpha = 0.55f

/**
 * The small "?" mark on an unplayable track. It sits on the cover's bottom-left
 * corner in list rows; tapping it reveals [UnavailableTrackReason] under the row.
 */
@Composable
fun UnavailableTrackBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClickLabel = "Why can't this play") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** The explanation line that expands under a dimmed row after its "?" is tapped. */
@Composable
fun UnavailableTrackReason(
    visible: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 76.dp, end = 16.dp, bottom = 10.dp),
    reason: String = UNPLAYABLE_APPLE_IMPORT_REASON,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(YoinMotion.spatialSpring()) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
        exit = shrinkVertically(YoinMotion.spatialSpring()) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        modifier = modifier,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Preview
@Composable
private fun UnavailableTrackBadgePreview() {
    YoinTheme { UnavailableTrackBadge(onClick = {}) }
}
