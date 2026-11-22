package com.gpo.yoin.lyra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.edition.EditionFeatures
import com.gpo.yoin.symbols.YoinSymbols

/**
 * Floating top-end Lyra Hub / Live Activity pill mounted in the `lyra` flavor shell
 * (zero edits to `ui/home/`). Shows active library-add job count in real time and opens
 * [LyraHubActivity] on tap.
 */
@Composable
internal fun LyraShellOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? YoinApplication ?: return
    val session = EditionFeatures.sessionManager(context, app.container)
    val state by session.state.collectAsState()
    val activeCount = state.activeJobs.size
    val cd = stringResource(R.string.lyra_cd_open_hub)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopEnd,
    ) {
        AnimatedVisibility(
            visible = visible && state.isConnected,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 12.dp, end = 64.dp),
        ) {
            val containerColor = if (activeCount > 0) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            }
            val contentColor = if (activeCount > 0) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Surface(
                onClick = { context.startActivity(LyraHubActivity.intent(context)) },
                shape = CircleShape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.semantics { contentDescription = cd },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (activeCount > 0) YoinSymbols.Download else YoinSymbols.Cloud,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (activeCount > 0) {
                            stringResource(R.string.lyra_pill_active_jobs, activeCount)
                        } else {
                            stringResource(R.string.lyra_pill_label)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
