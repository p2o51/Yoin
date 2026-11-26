@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.settings

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.player.sing.TensorGeneration
import com.gpo.yoin.ui.component.SystemBarIconsOverride
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.detail.DetailBackButton
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.experience.topBarsPadding
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.yoinFont
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
    SystemBarIconsOverride(lightStatusBars = false, lightNavigationBars = false)
}

/**
 * Heavy geometric Google Sans Flex variable cut matching the bold machined `G5` / `G6`
 * wordmark etched into the Google Tensor silicon die.
 */
private val TensorDieFont = FontFamily(
    yoinFont(
        resId = R.font.google_sans_flex_variable,
        weight = FontWeight.ExtraBold,
        axes = mapOf(
            "wght" to 820f,
            "ROND" to 72f,
            "wdth" to 104f,
            "opsz" to 72f,
        ),
    ),
)

private val StudioBackdropTop = Color(0xFF151923)
private val StudioBackdropBottom = Color(0xFF07080B)

/**
 * Reads smoothed gravity tilt in degrees (`x` = rotationY, `y` = rotationX), compensating for
 * current display rotation and capturing the user's comfortable handheld pitch as neutral rest.
 */
@Composable
private fun rememberGravityTiltDegrees(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context, enabled) {
        if (!enabled) {
            tilt.value = Offset.Zero
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@DisposableEffect onDispose {}
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return@DisposableEffect onDispose {}

        var initialized = false
        var baseGx = 0f
        var baseGy = 4.5f
        var smoothY = 0f
        var smoothX = 0f

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rawX = event.values.getOrNull(0) ?: return
                val rawY = event.values.getOrNull(1) ?: return
                val rotation = currentDisplayRotation(context)
                val (gx, gy) = when (rotation) {
                    Surface.ROTATION_90 -> -rawY to rawX
                    Surface.ROTATION_180 -> -rawX to -rawY
                    Surface.ROTATION_270 -> rawY to -rawX
                    else -> rawX to rawY
                }
                if (!initialized) {
                    initialized = true
                    baseGx = gx.coerceIn(-2.2f, 2.2f)
                    baseGy = gy.coerceIn(0f, 8.2f)
                }
                val targetRotY = (-((gx - baseGx) / 5.2f).coerceIn(-1f, 1f)) * 14f
                val targetRotX = (((gy - baseGy) / 5.2f).coerceIn(-1f, 1f)) * 12f
                smoothY += (targetRotY - smoothY) * 0.11f
                smoothX += (targetRotX - smoothX) * 0.11f
                tilt.value = Offset(smoothY, smoothX)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager.unregisterListener(listener)
        }
    }
    return tilt
}

private fun currentDisplayRotation(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.display.rotation }.getOrNull()?.let { return it }
    }
    @Suppress("DEPRECATION")
    return (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        ?.defaultDisplay
        ?.rotation
        ?: Surface.ROTATION_0
}

@Composable
internal fun TensorEasterEggContent(
    generation: TensorGeneration,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val scope = rememberCoroutineScope()

    val gravityTilt by rememberGravityTiltDegrees(enabled = !reduced)

    val spinY = remember { Animatable(0f) }
    val liftScale = remember { Animatable(1f) }
    val waveProgress = remember { Animatable(0f) }
    val waveGain = remember { Animatable(0f) }
    val reducedPulse = remember { Animatable(0f) }

    var targetTurns by remember { mutableIntStateOf(0) }
    var waveMode by remember { mutableIntStateOf(1) }
    var isSpinning by remember { mutableStateOf(false) }
    var waveJob by remember { mutableStateOf<Job?>(null) }
    var reducedJob by remember { mutableStateOf<Job?>(null) }

    val spinSpec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Standard)
    val liftSpec = YoinMotion.fastSpatialSpec<Float>(role = YoinMotionRole.Standard)

    // Smooth lift response while spinning; never snaps on rapid taps.
    LaunchedEffect(isSpinning, reduced) {
        if (reduced) {
            liftScale.snapTo(1f)
        } else {
            liftScale.animateTo(if (isSpinning) 1.055f else 1f, animationSpec = liftSpec)
        }
    }

    // Play a gentle introductory dot-matrix sweep shortly after opening.
    LaunchedEffect(reduced) {
        if (!reduced) {
            delay(260L)
            if (targetTurns == 0) {
                waveMode = 1
                waveJob = launch {
                    waveProgress.snapTo(0f)
                    waveGain.snapTo(1f)
                    waveProgress.animateTo(1f, tween(durationMillis = 1350, easing = LinearEasing))
                    waveGain.snapTo(0f)
                    waveProgress.snapTo(0f)
                }
            }
        }
    }

    // Velocity-continuous spin accumulator: rapid taps add turns cleanly without ever restarting
    // or snapping; when the die finally settles, fire landing haptic + flowing dot-matrix wave.
    LaunchedEffect(targetTurns) {
        if (targetTurns <= 0 || reduced) return@LaunchedEffect
        isSpinning = true
        // Smoothly relax any in-flight wave without snapping dot radii.
        waveJob?.cancel()
        if (waveGain.value > 0.01f) {
            launch {
                waveGain.animateTo(0f, tween(durationMillis = 140, easing = FastOutSlowInEasing))
            }
        }
        val targetDeg = targetTurns * 360f
        spinY.animateTo(
            targetValue = targetDeg,
            animationSpec = spinSpec,
        )
        isSpinning = false
        haptics.performTensorLand()
        delay(36L)
        waveMode = (targetTurns - 1) % 4
        waveJob = launch {
            waveProgress.snapTo(0f)
            waveGain.snapTo(1f)
            haptics.performTensorWave()
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 1380, easing = LinearEasing),
            )
            waveGain.snapTo(0f)
            waveProgress.snapTo(0f)
        }
    }

    val onTapChip: () -> Unit = {
        if (reduced) {
            haptics.performConfirm()
            reducedJob?.cancel()
            reducedJob = scope.launch {
                reducedPulse.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
                reducedPulse.animateTo(0f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
            }
        } else {
            haptics.performTensorTap()
            targetTurns += 1
        }
    }

    // Unified 3D pose combining spin momentum + gravity tilt so graphicsLayer, back-face handoff,
    // and specular lighting are mathematically identical with zero seam at 90 deg / 270 deg.
    val spinRad = (spinY.value * PI / 180.0).toFloat()
    val spinPitch = if (reduced) 0f else sin(spinRad) * 9f
    val totalRotY = if (reduced) 0f else spinY.value + gravityTilt.x
    val totalRotX = if (reduced) 0f else spinPitch + gravityTilt.y

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
                .topBarsPadding()
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
                    // Ambient studio reflection following both 3D spin face visibility and gravity tilt.
                    val cosTurn = abs(cos(totalRotY * PI / 180.0)).toFloat().coerceIn(0.18f, 1f)
                    Canvas(
                        modifier = Modifier
                            .size(width = dieSize * 1.16f, height = dieSize * 1.16f),
                    ) {
                        val glowRadius = size.minDimension * 0.56f
                        val shift = Offset(
                            x = -gravityTilt.x * 1.3.dp.toPx(),
                            y = gravityTilt.y * 1.3.dp.toPx(),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF9FB4D4).copy(alpha = 0.15f * cosTurn + 0.08f * reducedPulse.value),
                                    Color.Transparent,
                                ),
                                center = center + shift,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = center + shift,
                        )
                    }

                    TensorSiliconDie(
                        chipCode = generation.chipCode,
                        rotationYDeg = totalRotY,
                        rotationXDeg = totalRotX,
                        waveProgress = waveProgress.value,
                        waveGain = waveGain.value,
                        waveMode = waveMode,
                        highlightBoost = reducedPulse.value,
                        modifier = Modifier
                            .size(dieSize)
                            .graphicsLayer {
                                rotationY = totalRotY
                                rotationX = totalRotX
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
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp,
                    ),
                    color = Color.White.copy(alpha = 0.86f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.labs_sing_model_name),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = GoogleSansFlex),
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
    rotationXDeg: Float,
    waveProgress: Float,
    waveGain: Float,
    waveMode: Int,
    highlightBoost: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val normDeg = ((rotationYDeg % 360f) + 360f) % 360f
        val showingBack = normDeg in 90f..270f
        val angleYRad = (normDeg * PI / 180.0).toFloat()
        val angleXRad = (rotationXDeg * PI / 180.0).toFloat()
        val corner = CornerRadius(size.minDimension * 0.028f)

        if (showingBack) {
            scale(scaleX = -1f, scaleY = 1f, pivot = center) {
                drawSiliconBackFace(
                    chipCode = chipCode,
                    angleYRad = angleYRad,
                    angleXRad = angleXRad,
                    corner = corner,
                    textMeasurer = textMeasurer,
                )
            }
            return@Canvas
        }

        // Brushed obsidian & titanium diagonal metallic surface reacting smoothly to both tilt & spin.
        val glintX = sin(angleYRad) * size.width * 0.42f
        val glintY = -sin(angleXRad) * size.height * 0.36f
        drawRoundRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to Color(0xFFC8D0DC),
                    0.22f to Color(0xFF838E9E),
                    0.44f to Color(0xFF0E1219),
                    0.56f to Color(0xFF07090D),
                    0.74f to Color(0xFF232A36),
                    0.90f to Color(0xFF8E98A8),
                    1.00f to Color(0xFFC0C9D6),
                ),
                start = Offset(size.width * 0.08f + glintX, glintY),
                end = Offset(size.width * 0.92f + glintX, size.height + glintY),
            ),
            size = size,
            cornerRadius = corner,
        )

        // Subtle sweeping specular sheen driven by unified 3D tilt + spin angle.
        val tiltEnergy = hypot(sin(angleYRad).toDouble(), sin(angleXRad).toDouble()).toFloat()
        val sheenAlpha = (tiltEnergy * 0.24f + highlightBoost * 0.14f).coerceIn(0f, 0.28f)
        if (sheenAlpha > 0.01f) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = sheenAlpha),
                        Color.Transparent,
                    ),
                    start = Offset(size.width * 0.12f + glintX, glintY),
                    end = Offset(size.width * 0.72f + glintX, size.height + glintY),
                ),
                size = size,
                cornerRadius = corner,
            )
        }

        // Precision machined chamfer bevel contained strictly within the die bounds (zero corner seam).
        val rimBiasX = (sin(angleYRad) * 0.25f).coerceIn(-0.22f, 0.22f)
        val rimBiasY = (-sin(angleXRad) * 0.25f).coerceIn(-0.22f, 0.22f)
        val rimStroke = 1.6.dp.toPx()
        val halfStroke = rimStroke * 0.5f
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.56f + rimBiasX + rimBiasY).coerceIn(0.18f, 0.82f)),
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = (0.38f - rimBiasX - rimBiasY).coerceIn(0.14f, 0.72f)),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            topLeft = Offset(halfStroke, halfStroke),
            size = Size(size.width - rimStroke, size.height - rimStroke),
            cornerRadius = corner,
            style = Stroke(width = rimStroke),
        )

        // 25 x 25 luminous dot-matrix ring surrounding the central glossy obsidian G5 / G6 window.
        drawTensorDotMatrix(
            waveProgress = waveProgress,
            waveGain = waveGain,
            waveMode = waveMode,
            highlightBoost = highlightBoost,
        )

        // Bold Google Sans Flex G5 / G6 emblem centered inside the obsidian cutout.
        val labelFontSize = (size.minDimension * 0.215f).toSp()
        val style = TextStyle(
            fontFamily = TensorDieFont,
            fontWeight = FontWeight.ExtraBold,
            fontSize = labelFontSize,
            letterSpacing = (-1.2).sp,
            color = Color(0xFFF5F7FB).copy(alpha = (0.95f + highlightBoost * 0.05f).coerceAtMost(1f)),
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
    waveGain: Float,
    waveMode: Int,
    highlightBoost: Float,
) {
    val grid = 25
    val margin = size.minDimension * 0.11f
    val span = size.minDimension - margin * 2f
    val step = span / (grid - 1)
    val baseRadius = step * 0.27f
    val waveActive = waveGain > 0.005f && waveProgress > 0.001f && waveProgress < 0.999f
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
                        val angle = atan2((row - half).toDouble(), (col - half).toDouble()).toFloat()
                        val cw = ((angle + PI.toFloat() * 0.75f) / (2f * PI.toFloat()) + 1f) % 1f
                        val rNorm = hypot((col - half).toDouble(), (row - half).toDouble()).toFloat() / (half * 1.42f)
                        (cw * 0.86f + rNorm * 0.14f).coerceIn(0f, 1f)
                    }
                    1 -> (nx + ny) * 0.5f
                    2 -> {
                        val rNorm = hypot((col - half).toDouble(), (row - half).toDouble()).toFloat() / (half * 1.42f)
                        ((rNorm - 0.34f) / 0.66f).coerceIn(0f, 1f)
                    }
                    else -> (nx * 0.88f + abs(ny - 0.5f) * 0.24f).coerceIn(0f, 1f)
                }
                val delta = order - front
                crest = exp(-((delta / 0.135f) * (delta / 0.135f)).toDouble()).toFloat() * waveGain
                val trough = exp(-(((delta + 0.17f) / 0.11f) * ((delta + 0.17f) / 0.11f)).toDouble()).toFloat() *
                    0.32f * waveGain
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
    angleYRad: Float,
    angleXRad: Float,
    corner: CornerRadius,
    textMeasurer: TextMeasurer,
) {
    val shiftX = -sin(angleYRad) * size.width * 0.25f
    val shiftY = -sin(angleXRad) * size.height * 0.25f
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF232934), Color(0xFF0C0F14), Color(0xFF1E242F)),
            start = Offset(shiftX, shiftY),
            end = Offset(size.width + shiftX, size.height + shiftY),
        ),
        size = size,
        cornerRadius = corner,
    )
    val rimStroke = 1.4.dp.toPx()
    val halfStroke = rimStroke * 0.5f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.22f),
        topLeft = Offset(halfStroke, halfStroke),
        size = Size(size.width - rimStroke, size.height - rimStroke),
        cornerRadius = corner,
        style = Stroke(width = rimStroke),
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
        fontFamily = GoogleSansFlex,
        fontWeight = FontWeight.SemiBold,
        fontSize = (size.minDimension * 0.064f).toSp(),
        letterSpacing = 2.6.sp,
        color = Color.White.copy(alpha = 0.36f),
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
