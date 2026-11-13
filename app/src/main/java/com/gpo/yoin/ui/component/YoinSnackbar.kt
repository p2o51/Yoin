package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.delay

/*
 * Yoin's snackbar everywhere (owner 2026-10-29: the stock M3 bar — square corners, flat — looked dated).
 * A floating pill on the inverse surface: an optional symbol, the message, and the action as a text button
 * in the inverse primary. It rises in on the spatial spring and sinks back out; one at a time, timed like
 * M3's own host (Short 4s, Long 10s, stretched by the accessibility timeout when the user asked for it).
 */

/** [SnackbarVisuals] with an optional leading [icon]; plain `showSnackbar(message)` shows none. */
@Stable
class YoinSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    override val withDismissAction: Boolean = false,
    val icon: ImageVector? = null,
) : SnackbarVisuals

/** Shows a Yoin snackbar with an [icon]; returns how it ended, like [SnackbarHostState.showSnackbar]. */
suspend fun SnackbarHostState.showYoinSnackbar(
    message: String,
    actionLabel: String? = null,
    icon: ImageVector? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
): SnackbarResult = showSnackbar(
    YoinSnackbarVisuals(message = message, actionLabel = actionLabel, duration = duration, icon = icon),
)

/** The host every screen uses in place of M3's SnackbarHost: same queue, Yoin's look and motion. */
@Composable
fun YoinSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val current = hostState.currentSnackbarData
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        val visuals = current.visuals
        val base = when (visuals.duration) {
            SnackbarDuration.Short -> SHORT_MS
            SnackbarDuration.Long -> LONG_MS
            SnackbarDuration.Indefinite -> Long.MAX_VALUE
        }
        val millis = accessibility?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = base,
            containsIcons = true,
            containsText = true,
            containsControls = visuals.actionLabel != null,
        ) ?: base
        delay(millis)
        current.dismiss()
    }
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val rise = YoinMotion.slideInVertically(YoinMotionRole.Expressive) { it } +
                YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.9f, transformOrigin = BottomCenter) +
                YoinMotion.fadeIn(YoinMotionRole.Expressive)
            val sink = YoinMotion.slideOutVertically(YoinMotionRole.Standard) { it / 2 } +
                YoinMotion.scaleOut(YoinMotionRole.Standard, targetScale = 0.9f, transformOrigin = BottomCenter) +
                YoinMotion.fadeOut(YoinMotionRole.Standard)
            (rise togetherWith sink).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.BottomCenter,
        contentKey = { it },
        modifier = modifier,
        label = "yoinSnackbar",
    ) { data ->
        if (data == null) Box(Modifier) else YoinSnackbar(data)
    }
}

/** One snackbar: the pill itself. Usually shown by [YoinSnackbarHost]. */
@Composable
fun YoinSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val visuals = data.visuals
    val icon = (visuals as? YoinSnackbarVisuals)?.icon
    val action = visuals.actionLabel
    val dismissLabel = stringResource(R.string.cmp_snackbar_dismiss)
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = MaxWidth)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                dismiss(label = dismissLabel) {
                    data.dismiss()
                    true
                }
            },
        shape = YoinShapeTokens.ExtraLarge,
        color = scheme.inverseSurface,
        contentColor = scheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(
                    start = if (icon != null) 18.dp else 24.dp,
                    end = if (action != null || visuals.withDismissAction) 8.dp else 24.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = visuals.message,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 10.dp),
            )
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = data::performAction,
                    shape = YoinShapeTokens.Full,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.inversePrimary),
                    modifier = Modifier.handPointer(),
                ) {
                    Text(text = action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                }
            }
            if (visuals.withDismissAction) {
                IconButton(
                    onClick = data::dismiss,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.inverseOnSurface),
                    modifier = Modifier.handPointer(),
                ) {
                    Icon(imageVector = YoinSymbols.Close, contentDescription = dismissLabel)
                }
            }
        }
    }
}

private const val SHORT_MS = 4_000L
private const val LONG_MS = 10_000L
private val MaxWidth = 560.dp
private val BottomCenter = TransformOrigin(0.5f, 1f)
