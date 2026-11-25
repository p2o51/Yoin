@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.sing.LiteRtSingBackend
import com.gpo.yoin.player.sing.SingModelDownloadStatus
import com.gpo.yoin.player.sing.SingModelOverview
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Yoin Labs row for on-device Lyra-Sing-Lite model inspection, Play AI Pack download/removal,
 * and the Pixel Tensor TPU silicon easter egg.
 */
@Composable
internal fun LyraSingLabsRow(
    overview: SingModelOverview,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val status = overview.status
    val scope = rememberCoroutineScope()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced

    var easterEggOpen by remember { mutableStateOf(false) }
    var eggTapCount by remember { mutableIntStateOf(0) }
    val prismSweep = remember { Animatable(0f) }

    val triggerTpuEgg: () -> Unit = {
        eggTapCount += 1
        easterEggOpen = true
        scope.launch {
            haptics.performTick()
            delay(75L)
            haptics.performConfirm()
        }
        if (!reduced) {
            scope.launch {
                prismSweep.snapTo(0f)
                prismSweep.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing),
                )
            }
        }
    }

    SettingsItem(
        icon = YoinSymbols.Mic,
        title = stringResource(R.string.labs_sing_model_name),
        modifier = modifier,
        summaryContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (statusText, statusColor) = when (status) {
                    SingModelDownloadStatus.Checking ->
                        stringResource(R.string.labs_sing_checking) to MaterialTheme.colorScheme.onSurfaceVariant
                    SingModelDownloadStatus.NotDownloaded ->
                        stringResource(
                            R.string.labs_sing_not_downloaded,
                            SingModelOverview.formatBytes(overview.expectedDownloadBytes),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    is SingModelDownloadStatus.Downloading ->
                        stringResource(
                            R.string.labs_sing_downloading,
                            (status.progress * 100f).roundToInt().coerceIn(0, 100),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    SingModelDownloadStatus.Ready ->
                        stringResource(
                            R.string.labs_sing_ready,
                            SingModelOverview.formatBytes(overview.totalInstalledBytes),
                        ) to MaterialTheme.colorScheme.onSurfaceVariant
                    is SingModelDownloadStatus.Failed ->
                        stringResource(R.string.labs_sing_download_failed, status.message) to MaterialTheme.colorScheme.error
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )

                if (status is SingModelDownloadStatus.Downloading) {
                    LinearProgressIndicator(
                        progress = { status.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                    )
                }

                Text(
                    text = overview.distributionLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                overview.backendLine()?.let { backend ->
                    Text(
                        text = backend,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (overview.isTensorTpu) {
                    Spacer(modifier = Modifier.height(2.dp))
                    TensorTpuBadge(
                        tappedCount = eggTapCount,
                        prismProgress = prismSweep.value,
                        onClick = triggerTpuEgg,
                    )
                    AnimatedVisibility(
                        visible = easterEggOpen,
                        enter = fadeIn(YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard)) +
                            expandVertically(YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard)),
                        exit = fadeOut(YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Standard)) +
                            shrinkVertically(YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Standard)),
                    ) {
                        TensorTpuEasterEggCard(
                            overview = overview,
                            tapCount = eggTapCount,
                            prismProgress = prismSweep.value,
                            reducedMotion = reduced,
                            onPulse = triggerTpuEgg,
                            onDismiss = { easterEggOpen = false },
                        )
                    }
                }
            }
        },
        trailing = {
            when (status) {
                SingModelDownloadStatus.NotDownloaded,
                is SingModelDownloadStatus.Failed,
                -> FilledTonalButton(
                    onClick = {
                        haptics.performContextClick()
                        onDownload()
                    },
                    modifier = Modifier.handPointer(),
                ) {
                    Text(stringResource(R.string.labs_sing_download))
                }
                is SingModelDownloadStatus.Downloading,
                SingModelDownloadStatus.Checking,
                -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                SingModelDownloadStatus.Ready -> TextButton(
                    onClick = {
                        haptics.performContextClick()
                        onRemove()
                    },
                    modifier = Modifier.handPointer(),
                ) {
                    Text(stringResource(R.string.labs_sing_remove))
                }
            }
        },
    )
}

private val TensorPrismColors = listOf(
    Color(0xFF4285F4), // Silicon Blue
    Color(0xFF34A853), // Emerald
    Color(0xFFFBBC05), // Warm Amber
    Color(0xFFEA4335), // Coral
    Color(0xFF4285F4),
)

@Composable
private fun TensorTpuBadge(
    tappedCount: Int,
    prismProgress: Float,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.labs_sing_tpu_badge)
    val hint = stringResource(R.string.labs_sing_tpu_egg_hint)
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    val pulseAlpha = if (prismProgress in 0.01f..0.99f) sin(prismProgress * PI).toFloat() * 0.55f else 0f

    Surface(
        shape = CircleShape,
        color = bg,
        border = if (pulseAlpha > 0.02f) {
            BorderStroke(
                width = 1.25.dp,
                brush = Brush.horizontalGradient(
                    TensorPrismColors.map { it.copy(alpha = pulseAlpha) },
                ),
            )
        } else {
            null
        },
        modifier = Modifier
            .clip(CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = "$label, $hint"
            }
            .clickable(onClick = onClick)
            .handPointer(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TensorChipGlyph(tint = fg, size = 14.dp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = fg,
            )
            if (tappedCount >= 2) {
                Text(
                    text = "×$tappedCount",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = fg.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun TensorTpuEasterEggCard(
    overview: SingModelOverview,
    tapCount: Int,
    prismProgress: Float,
    reducedMotion: Boolean,
    onPulse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val eggResIds = remember {
        listOf(
            R.string.labs_sing_tpu_egg_1,
            R.string.labs_sing_tpu_egg_2,
            R.string.labs_sing_tpu_egg_3,
            R.string.labs_sing_tpu_egg_4,
        )
    }
    val quoteIndex = ((tapCount - 1).coerceAtLeast(0)) % eggResIds.size
    val quote = stringResource(eggResIds[quoteIndex])
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    val bg = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
    val sweepAlpha = if (prismProgress in 0.01f..0.99f) sin(prismProgress * PI).toFloat() * 0.45f else 0.14f

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.linearGradient(TensorPrismColors.map { it.copy(alpha = sweepAlpha) }),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onPulse)
            .handPointer(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (!reducedMotion && prismProgress in 0.01f..0.99f) {
                        val bandX = size.width * (prismProgress * 1.4f - 0.2f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.16f * sin(prismProgress * PI).toFloat()),
                                    Color.Transparent,
                                ),
                                startX = bandX - size.width * 0.28f,
                                endX = bandX + size.width * 0.28f,
                            ),
                        )
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TensorCoreWave(tint = fg, reducedMotion = reducedMotion)
                    val specHeader = buildString {
                        append(overview.activeBuildName)
                        append(" · LiteRT ")
                        append(LiteRtSingBackend.LITERT_VERSION)
                        overview.activeSha256Short?.let { append(" · #$it") }
                    }
                    Text(
                        text = specHeader,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = fg.copy(alpha = 0.85f),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "×",
                        style = MaterialTheme.typography.labelMedium,
                        color = fg.copy(alpha = 0.65f),
                    )
                }
            }
            Text(
                text = quote,
                style = MaterialTheme.typography.bodySmall,
                color = fg,
            )
        }
    }
}

/** Minimal 1.5dp-proportioned Luminous silicon chip glyph (die outline + pins + core dot). */
@Composable
private fun TensorChipGlyph(tint: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = minOf(this.size.width, this.size.height) / 16f
        val stroke = Stroke(width = 1.3f * s, cap = StrokeCap.Round)
        // Outer silicon die
        drawRoundRect(
            color = tint,
            topLeft = Offset(3.5f * s, 3.5f * s),
            size = Size(9f * s, 9f * s),
            cornerRadius = CornerRadius(2.2f * s, 2.2f * s),
            style = stroke,
        )
        // Inner TPU core
        drawRoundRect(
            color = tint,
            topLeft = Offset(6.5f * s, 6.5f * s),
            size = Size(3f * s, 3f * s),
            cornerRadius = CornerRadius(0.9f * s, 0.9f * s),
        )
        // 2 micro-pins on each side
        for (p in listOf(6.2f, 9.8f)) {
            drawLine(tint, Offset(p * s, 1.2f * s), Offset(p * s, 3.2f * s), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
            drawLine(tint, Offset(p * s, 12.8f * s), Offset(p * s, 14.8f * s), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
            drawLine(tint, Offset(1.2f * s, p * s), Offset(3.2f * s, p * s), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
            drawLine(tint, Offset(12.8f * s, p * s), Offset(14.8f * s, p * s), strokeWidth = 1.2f * s, cap = StrokeCap.Round)
        }
    }
}

/** Gentle 5-bar TPU acoustic pulse; renders static balanced bars under AdaptiveReduced. */
@Composable
private fun TensorCoreWave(tint: Color, reducedMotion: Boolean) {
    val phase = if (reducedMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "tensorCoreWave")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2.0 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "tensorCorePhase",
        )
        animated
    }
    Canvas(modifier = Modifier.width(20.dp).height(14.dp)) {
        val bars = 5
        val spacing = size.width / bars
        val cy = size.height / 2f
        val strokeW = 2.dp.toPx()
        for (i in 0 until bars) {
            val factor = if (reducedMotion) {
                listOf(0.45f, 0.85f, 0.6f, 0.95f, 0.5f)[i]
            } else {
                0.35f + 0.65f * ((sin(phase + i * 0.9f) + 1f) * 0.5f)
            }
            val halfH = (size.height * 0.44f * factor).coerceAtLeast(1.5f)
            val x = spacing * (i + 0.5f)
            drawLine(
                color = tint,
                start = Offset(x, cy - halfH),
                end = Offset(x, cy + halfH),
                strokeWidth = strokeW,
                cap = StrokeCap.Round,
            )
        }
    }
}
