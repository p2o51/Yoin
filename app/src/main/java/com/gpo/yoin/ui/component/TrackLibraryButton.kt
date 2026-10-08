package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.LibraryMembership
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.YoinMotionRole

/** A library add/confirmation action, deliberately distinct from the favorite heart. */
@Composable
fun TrackLibraryButton(
    membership: LibraryMembership,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isWorking: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = !isWorking && membership != LibraryMembership.Added,
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = isWorking to membership,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "libraryMembership",
        ) { (working, state) ->
            val addedDescription = stringResource(R.string.cmp_library_cd_added)
            val pendingDescription = stringResource(R.string.cmp_library_cd_pending)
            val addDescription = stringResource(R.string.cmp_library_cd_add)
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = when (state) {
                        LibraryMembership.Added -> YoinSymbols.LibraryAdded
                        LibraryMembership.Pending -> YoinSymbols.Refresh
                        else -> YoinSymbols.LibraryAdd
                    },
                    contentDescription = when (state) {
                        LibraryMembership.Added -> addedDescription
                        LibraryMembership.Pending -> pendingDescription
                        else -> addDescription
                    },
                    tint = if (state == LibraryMembership.Added) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview
@Composable
private fun TrackLibraryButtonPreview() {
    YoinTheme { TrackLibraryButton(LibraryMembership.NotAdded, {}) }
}
