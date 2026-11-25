@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.player.sing.TensorGeneration
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.detail.DetailBackButton
import com.gpo.yoin.ui.detail.findActivityOrNull
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hidden full-screen Google Tensor G5 / G6 easter egg opened by tapping the Lyra Sing
 * row 10 times in Yoin Labs on Tensor devices.
 */
@Composable
internal fun TensorEasterEggPage(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val overview by (context.applicationContext as YoinApplication)
        .container
        .singModelManager
        .state
        .collectAsState()
    val generation = overview.tensorGeneration ?: TensorGeneration.G6
    TensorDarkSystemBarsEffect()
    TensorEasterEggContent(
        generation = generation,
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

@Composable
private fun TensorDarkSystemBarsEffect() {
    val view = LocalView.current
    val window = view.context.findActivityOrNull()?.window ?: return
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

private val StudioBackdropTop = Color(0xFF151923)
private val StudioBackdropBottom = Color(0xFF07080B)
private val SpinEasing = CubicBezierEasing(0.20f, 0.98f, 0.34f, 1.0f)

@Composable
internal fun TensorEasterEggContent(
    generation: TensorGeneration,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val scope = rememberCoroutineScope()

    val spinY = remember { Animatable(0f) }
    val pitchX = remember { Animatable(0f) }
    val liftScale = remember { Animatable(1f) }
    val waveProgress = remember { Animatable(0f) }
    val reducedPulse = remember { Animatable(0f) }

    var tapCount by remember { mutableIntStateOf(0) }
    var waveMode by remember { mutableIntStateOf(1) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    // Play a gentle introductory dot-matrix sweep shortly after opening.
    LaunchedEffect(reduced) {
        if (!reduced) {
            delay(260L)
            waveMode = 1
            waveProgress.snapTo(0f)
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1350, easing = LinearEasing),
            )
            waveProgress.snapTo(0f)
        }
    }

    val onTapChip: () -> Unit = {
        tapCount += 1
        activeJob?.cancel()
        if (reduced) {
            haptics.performConfirm()
            activeJob = scope.launch {
                reducedPulse.snapTo(1f)
                reducedPulse.animateTo(0f, tween(durationMillis = 480, easing = FastOutSlowInEasing))
            }
        } else {
            val nextMode = (tapCount - 1) % 4
            waveMode = nextMode
            val targetAngle = ((spinY.value / 360f).toInt() + 1) * 360f
            haptics.performTensorFlip()
            activeJob = scope.launch {
                waveProgress.snapTo(0f)
                val pitchJob = launch {
                    pitchX.animateTo(13f, tween(durationMillis = 220, easing = FastOutSlowInEasing))
                    pitchX.animateTo(-5f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
                    pitchX.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
                }
                val scaleJob = launch {
                    liftScale.animateTo(1.06f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
                    liftScale.animateTo(0.985f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
                    liftScale.animateTo(1f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
                }
                spinY.animateTo(
                    targetValue = targetAngle,
                    animationSpec = tween(durationMillis = 720, easing = SpinEasing),
                )
                pitchJob.join()
                scaleJob.join()
                // After landing, send a sequential flowing & scaling wave across the dot matrix with subtle haptics.
                haptics.performTensorWave()
                waveProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 1380, easing = LinearEasing),
                )
                waveProgress.snapTo(0f)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(StudioBackdropTop, StudioBackdropBottom),
                    radius = 1200f,
                ),
            ),
    ) {
        DetailBackButton(
            onClick = onBackClick,
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White.copy(alpha = 0.88f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 12.dp),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val dieSize = minOf(maxWidth * 0.72f, maxHeight * 0.42f, 276.dp).coerceAtLeast(180.dp)
            val chipCd = stringResource(R.string.labs_sing_tensor_chip_cd, generation.displayName)
            val density = LocalDensity.current.density

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Ambient obsidian studio floor shadow reacting to the 3D turn.
                    val cosTurn = abs(cos(spinY.value * PI / 180.0)).toFloat().coerceIn(0.18f, 1f)
                    Canvas(
                        modifier = Modifier
                            .size(width = dieSize * 1.12f, height = dieSize * 1.12f),
                    ) {
                        val glowRadius = size.minDimension * 0.56f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF9FB4D4).copy(alpha = 0.14f * cosTurn + 0.08f * reducedPulse.value),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = center,
                        )
                    }

                    TensorSiliconDie(
                        chipCode = generation.chipCode,
                        rotationYDeg = spinY.value,
                        waveProgress = waveProgress.value,
                        waveMode = waveMode,
                        highlightBoost = reducedPulse.value,
                        modifier = Modifier
                            .size(dieSize)
                            .graphicsLayer {
                                rotationY = spinY.value
                                rotationX = pitchX.value
                                scaleX = liftScale.value
                                scaleY = liftScale.value
                                cameraDistance = 14f * density
                            }
                            .semantics {
                                role = Role.Button
                                contentDescription = chipCd
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onTapChip,
                            )
                            .handPointer(),
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = generation.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp,
                    ),
                    color = Color.White.copy(alpha = 0.84f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.labs_sing_model_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.42f),
                )
            }
        }
    }
}

@Composable
private fun TensorSiliconDie(
    chipCode: String,
    rotationYDeg: Float,
    waveProgress: Float,
    waveMode: Int,
    highlightBoost: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val normDeg = ((rotationYDeg % 360f) + 360f) % 360f
        val showingBack = normDeg in 90f..270f
        val angleRad = (normDeg * PI / 180.0).toFloat()
        val corner = CornerRadius(size.minDimension * 0.028f)

        if (showingBack) {
            scale(scaleX = -1f, scaleY = 1f, pivot = center) {
                drawSiliconBackFace(
                    chipCode = chipCode,
                    corner = corner,
                    textMeasurer = textMeasurer,
                )
            }
            return@Canvas
        }

        // Subtle metallic machined slab edge visibility as the chip rotates in 3D.
        val edgeShift = sin(angleRad) * size.width * 0.036f
        if (abs(edgeShift) > 0.5f) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF9EA8B8), Color(0xFF39414E), Color(0xFF778191)),
                ),
                topLeft = Offset(edgeShift, 0f),
                size = size,
                cornerRadius = corner,
            )
        }

        // Brushed obsidian & titanium diagonal metallic surface matching the Google Tensor silicon die.
        val glintOffset = sin(angleRad) * size.width * 0.45f
        drawRoundRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFC5CDD9),
                    0.22f to Color(0xFF838E9E),
                    0.44f to Color(0xFF0E1219),
                    0.56f to Color(0xFF07090D),
                    0.74f to Color(0xFF232A36),
                    0.90f to Color(0xFF8E98A8),
                    1.00f to Color(0xFFBDC6D4),
                ),
                start = Offset(size.width * 0.08f + glintOffset, 0f),
                end = Offset(size.width * 0.92f + glintOffset, size.height),
            ),
            size = size,
            cornerRadius = corner,
        )

        // Subtle sweeping specular sheen during 3D rotation or reduced-motion pulse.
        val sheenAlpha = (abs(sin(angleRad)) * 0.22f + highlightBoost * 0.14f).coerceIn(0f, 0.28f)
        if (sheenAlpha > 0.01f) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = sheenAlpha),
                        Color.Transparent,
                    ),
                    start = Offset(size.width * 0.1f + glintOffset, 0f),
                    end = Offset(size.width * 0.7f + glintOffset, size.height),
                ),
                size = size,
                cornerRadius = corner,
            )
        }

        // Precision machined outer chamfer bevel highlight.
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.55f),
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.36f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            size = size,
            cornerRadius = corner,
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // 25 x 25 luminous dot-matrix ring surrounding the central glossy obsidian G5 / G6 window.
        drawTensorDotMatrix(
            waveProgress = waveProgress,
            waveMode = waveMode,
            highlightBoost = highlightBoost,
        )

        // Centered geometric G5 / G6 emblem inside the obsidian cutout.
        val labelFontSize = (size.minDimension * 0.205f).toSp()
        val style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = labelFontSize,
            letterSpacing = (-1.5).sp,
            color = Color(0xFFF4F6FA).copy(alpha = (0.94f + highlightBoost * 0.06f).coerceAtMost(1f)),
        )
        val layout = textMeasurer.measure(chipCode, style = style)
        val textTopLeft = Offset(
            x = (size.width - layout.size.width) * 0.5f,
            y = (size.height - layout.size.height) * 0.5f,
        )
        drawText(textLayoutResult = layout, topLeft = textTopLeft)
    }
}

private fun DrawScope.drawTensorDotMatrix(
    waveProgress: Float,
    waveMode: Int,
    highlightBoost: Float,
) {
    val grid = 25
    val margin = size.minDimension * 0.11f
    val span = size.minDimension - margin * 2f
    val step = span / (grid - 1)
    val baseRadius = step * 0.27f
    val waveActive = waveProgress > 0.001f && waveProgress < 0.999f
    val front = waveProgress * 1.48f - 0.24f
    val half = (grid - 1) * 0.5f

    for (row in 0 until grid) {
        for (col in 0 until grid) {
            // Central cutout: 13 cols x 11 rows leaves 6 cols left/right & 7 rows top/bottom.
            if (col in 6..18 && row in 7..17) continue

            val cx = margin + col * step
            val cy = margin + row * step
            var radiusScale = 1f + highlightBoost * 0.18f
            var alpha = (0.88f + highlightBoost * 0.12f).coerceAtMost(1f)
            var crest = 0f

            if (waveActive) {
                val nx = col / (grid - 1f)
                val ny = row / (grid - 1f)
                val order = when (waveMode) {
                    0 -> {
                        // Clockwise perimeter flow with subtle spiral depth.
                        val angle = atan2((row - half).toDouble(), (col - half).toDouble()).toFloat()
                        val cw = ((angle + PI.toFloat() * 0.75f) / (2f * PI.toFloat()) + 1f) % 1f
                        val rNorm = hypot((col - half).toDouble(), (row - half).toDouble()).toFloat() / (half * 1.42f)
                        (cw * 0.86f + rNorm * 0.14f).coerceIn(0f, 1f)
                    }
                    1 -> {
                        // Diagonal top-left to bottom-right sweep.
                        (nx + ny) * 0.5f
                    }
                    2 -> {
                        // Radial outward pulse from the G5 / G6 core.
                        val rNorm = hypot((col - half).toDouble(), (row - half).toDouble()).toFloat() / (half * 1.42f)
                        ((rNorm - 0.34f) / 0.66f).coerceIn(0f, 1f)
                    }
                    else -> {
                        // Left-to-right flowing wave with gentle vertical curve.
                        (nx * 0.88f + abs(ny - 0.5f) * 0.24f).coerceIn(0f, 1f)
                    }
                }
                val delta = order - front
                crest = exp(-((delta / 0.135f) * (delta / 0.135f)).toDouble()).toFloat()
                val trough = exp(-(((delta + 0.17f) / 0.11f) * ((delta + 0.17f) / 0.11f)).toDouble()).toFloat() * 0.32f
                radiusScale = (1f + 0.80f * crest - trough).coerceIn(0.60f, 1.84f)
                alpha = (0.84f + 0.16f * crest - 0.22f * trough).coerceIn(0.52f, 1f)
            }

            val r = baseRadius * radiusScale
            if (crest > 0.22f) {
                drawCircle(
                    color = Color(0xFFD8E6FF).copy(alpha = 0.28f * crest),
                    radius = r * 1.95f,
                    center = Offset(cx, cy),
                )
            }
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = r,
                center = Offset(cx, cy),
            )
        }
    }
}

private fun DrawScope.drawSiliconBackFace(
    chipCode: String,
    corner: CornerRadius,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF1D222C), Color(0xFF0C0F14), Color(0xFF1A2029)),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        size = size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.18f),
        size = size,
        cornerRadius = corner,
        style = Stroke(width = 1.2.dp.toPx()),
    )

    // Subtle BGA contact pad grid on the reverse of the silicon die.
    val pads = 15
    val margin = size.minDimension * 0.14f
    val step = (size.minDimension - margin * 2f) / (pads - 1)
    val padR = step * 0.16f
    for (row in 0 until pads) {
        for (col in 0 until pads) {
            if (col in 4..10 && row in 5..9) continue
            drawCircle(
                color = Color(0xFF9EA8B8).copy(alpha = 0.24f),
                radius = padR,
                center = Offset(margin + col * step, margin + row * step),
            )
        }
    }

    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = (size.minDimension * 0.062f).toSp(),
        letterSpacing = 3.sp,
        color = Color.White.copy(alpha = 0.34f),
    )
    val layout = textMeasurer.measure("TENSOR $chipCode", style = style)
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            x = (size.width - layout.size.width) * 0.5f,
            y = (size.height - layout.size.height) * 0.5f,
        ),
    )
}
