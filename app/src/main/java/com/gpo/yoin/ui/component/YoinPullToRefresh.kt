package com.gpo.yoin.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Pull to refresh over a list or a grid, with the app's own loading indicator:
 * Material's Expressive pull indicator in [YoinLoadingIndicator]'s colours,
 * flat. It morphs with the pull and turns once the refresh runs.
 *
 * The content never moves: only the indicator comes down, from under the
 * box's top edge. So whatever [content] does at that edge (Library's seam,
 * [seamDissolveViewport], which only acts on content scrolled into it) is
 * left as it is, and nothing above the box is pushed or covered.
 *
 * Its settles (back up after a pull let go short, down to rest as a refresh
 * starts, away as it ends) run on [YoinMotion.spatialSpring]. Not [enabled]:
 * a pull does nothing and the content scrolls as if this weren't here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YoinPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val settle = YoinMotion.spatialSpring<Float>()
    val state = remember { YoinPullToRefreshState(settle) }
    SideEffect { state.settle = settle }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        enabled = enabled,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
        content = content,
    )
}

/** Material's pull state with the settle spring handed in ([YoinMotion]) instead of its default. */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
private class YoinPullToRefreshState(var settle: AnimationSpec<Float>) : PullToRefreshState {
    private val pulled = Animatable(0f)

    // A spring may swing a hair past hidden: the indicator's shape reads this as its progress.
    override val distanceFraction: Float get() = pulled.value.coerceAtLeast(0f)

    override val isAnimating: Boolean get() = pulled.isRunning

    override suspend fun animateToThreshold() {
        pulled.animateTo(1f, settle)
    }

    override suspend fun animateToHidden() {
        pulled.animateTo(0f, settle)
    }

    override suspend fun snapTo(targetValue: Float) {
        pulled.snapTo(targetValue)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun YoinPullToRefreshBoxPreview() {
    YoinTheme {
        YoinPullToRefreshBox(isRefreshing = true, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(12) { row ->
                    Text(
                        text = "Album ${row + 1}",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
