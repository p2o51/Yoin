package com.gpo.yoin.ui.settings

import com.gpo.yoin.ui.component.handPointer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gpo.yoin.R
import com.gpo.yoin.ui.detail.findActivityOrNull
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.ScreenCorners
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.currentScreenCornerRadius
import kotlinx.coroutines.delay

/**
 * Settings › Motion › Screen corners (owner 2026-11-08). The whole page is one flat rounded rectangle at
 * the chosen corner over black (a light primary page in both themes, [screenCornersPageScheme]), so
 * where it meets the glass is the preview itself: slide until no black shows in the corners. In the middle a phone plays, in turn, the places that take this corner (Now
 * Playing rising, Memories retreating, the back gesture); at the bottom, the corner bar.
 */
@Composable
internal fun ScreenCornersPage(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val radius = currentScreenCornerRadius()
    ScreenCornersSystemBarsEffect()
    ScreenCornersPageContent(
        value = radius.value,
        following = ScreenCorners.userRadius == null,
        reported = ScreenCorners.deviceRadius?.value,
        onValueChange = { ScreenCorners.select(context, it.dp, persist = false) },
        onInteractionChange = { active ->
            if (!active) ScreenCorners.select(context, ScreenCorners.radius, persist = true)
        },
        onFollow = { ScreenCorners.select(context, null) },
        onBackClick = onBackClick,
        modifier = modifier,
    )
}

/**
 * The page is light in both themes, so the window's bar icons go dark while it shows and back to what
 * they were when it leaves (else dark theme leaves white icons on the light page).
 */
@Composable
private fun ScreenCornersSystemBarsEffect() {
    val view = LocalView.current
    val window = view.context.findActivityOrNull()?.window ?: return
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
    }
}

/** [ScreenCornersPage] without its state: the Tips demo plays it with a value of its own. */
@Composable
internal fun ScreenCornersPageContent(
    value: Float,
    following: Boolean,
    reported: Float?,
    onValueChange: (Float) -> Unit,
    onInteractionChange: (Boolean) -> Unit,
    onFollow: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    examplesPlaying: Boolean = true,
    barModifier: Modifier = Modifier,
) {
    val scheme = screenCornersPageScheme(MaterialTheme.colorScheme)
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        MaterialTheme(colorScheme = scheme) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(value.dp))
                    .background(scheme.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsBackButton(onClick = onBackClick)
                        Text(
                            text = stringResource(R.string.settings_motion_screen_corners),
                            style = MaterialTheme.typography.titleLarge,
                            color = scheme.onSecondaryContainer,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    Text(
                        text = stringResource(R.string.screen_corners_page_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSecondaryContainer.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CornerExamples(radius = value, playing = examplesPlaying)
                    }
                    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                        ScreenCornerValueRow(value = value, following = following, onFollow = onFollow)
                        Spacer(Modifier.height(4.dp))
                        ScreenCornerBar(
                            value = value,
                            reported = reported,
                            onValueChange = onValueChange,
                            onInteractionChange = onInteractionChange,
                            modifier = barModifier,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The page's colours: one light, clearly coloured page in both themes (owner 2026-11-09: in dark theme
 * the old secondaryContainer page read as plain black on an OLED screen, so the black corners had
 * nothing to stand against). Light theme keeps primaryContainer; dark theme takes the same tone-90
 * primary (its onPrimaryContainer) and re-inks what sits on it, so the bar, its fill and "Match screen"
 * stay one colour family and readable.
 */
internal fun screenCornersPageScheme(base: ColorScheme): ColorScheme {
    if (base.surface.luminance() >= 0.5f) {
        return base.copy(onSecondaryContainer = base.onPrimaryContainer)
    }
    val page = base.onPrimaryContainer
    val ink = base.primaryContainer
    return base.copy(
        primaryContainer = page,
        onPrimaryContainer = ink,
        onSecondaryContainer = ink,
        onSurface = ink,
        onSurfaceVariant = ink.copy(alpha = 0.75f),
        surfaceVariant = ink.copy(alpha = 0.12f).compositeOver(page),
        surfaceContainerHighest = ink.copy(alpha = 0.10f).compositeOver(page),
        primary = base.inversePrimary,
        onPrimary = page,
    )
}

/** The places that take the screen corner, as the middle phone plays them. */
private enum class CornerExample(val label: Int) {
    NowPlaying(R.string.screen_corners_example_now_playing),
    Memories(R.string.screen_corners_example_memories),
    Back(R.string.screen_corners_example_back),
}

/**
 * A phone shaped like this window, its own corner the chosen one (scaled to its size), playing one
 * example at a time and moving on by itself; a tap moves on too. Each moving page wears the corner while
 * it moves, as the real one does. Reduced motion: each example holds mid-way.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun CornerExamples(radius: Float, playing: Boolean) {
    val haptics = rememberYoinHaptics()
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    var index by remember { mutableIntStateOf(0) }
    var turn by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    val moveSpec = YoinMotion.slowSpatialSpec<Float>()
    LaunchedEffect(index, turn, playing, reduced) {
        if (reduced || !playing) {
            progress.snapTo(if (reduced) 0.6f else 0f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        delay(400)
        progress.animateTo(1f, moveSpec)
        delay(900)
        progress.animateTo(0f, moveSpec)
        delay(300)
        index = (index + 1) % CornerExample.entries.size
    }
    val example = CornerExample.entries[index]
    val window = currentWindowDpSize()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
            val aspect = (window.width.value / window.height.value.coerceAtLeast(1f)).coerceIn(0.3f, 1.6f)
            val h = minOf(maxHeight, PhoneMaxHeight)
            val w = minOf(h * aspect, maxWidth)
            val scale = w.value / window.width.value.coerceAtLeast(1f)
            val corner = (radius * scale).dp
            val scheme = MaterialTheme.colorScheme
            Box(
                modifier = Modifier
                    .size(w, w / aspect)
                    .clip(RoundedCornerShape(corner))
                    .background(scheme.surface)
                    .border(2.dp, scheme.onSecondaryContainer.copy(alpha = 0.35f), RoundedCornerShape(corner))
                    .handPointer()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        haptics.performClick()
                        index = (index + 1) % CornerExample.entries.size
                        turn++
                    },
            ) {
                ExampleScene(example = example, progress = { progress.value }, corner = corner)
            }
        }
        AnimatedContent(
            targetState = example,
            transitionSpec = {
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
            },
            label = "cornerExample",
        ) { shown ->
            Text(
                text = stringResource(shown.label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CornerExample.entries.forEach { e ->
                Box(
                    modifier = Modifier
                        .size(width = if (e == example) 16.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (e == example) 0.9f else 0.3f),
                        ),
                )
            }
        }
    }
}

/** One example: a quiet page underneath, and the page that moves, wearing [corner]. */
@Composable
private fun ExampleScene(example: CornerExample, progress: () -> Float, corner: Dp) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(corner)
    // The page underneath: a few rows, as a list reads at a glance.
    Column(
        modifier = Modifier.fillMaxSize().background(scheme.surfaceContainerLow).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(56.dp).height(10.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.5f)))
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(scheme.surfaceContainerHighest))
                Box(Modifier.fillMaxWidth(0.7f).height(8.dp).clip(CircleShape).background(scheme.surfaceContainerHighest))
            }
        }
    }
    when (example) {
        // Now Playing rises from the bottom: a full page whose corners meet the glass.
        CornerExample.NowPlaying -> Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = (1f - progress()) * size.height
                    this.shape = shape
                    clip = true
                }
                .background(scheme.primary),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.62f)
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.onPrimary.copy(alpha = 0.85f)),
            )
        }
        // Memories retreats upward, shrinking, its corners the screen's.
        CornerExample.Memories -> Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress()
                    val s = 1f - 0.22f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = -0.06f * size.height * p
                    alpha = 1f - 0.35f * p
                    this.shape = shape
                    clip = true
                }
                .background(scheme.tertiary),
        ) { PagePlaceholder(scheme.onTertiary) }
        // The back gesture: the page shrinks toward the edge it came from, the one under it shows.
        CornerExample.Back -> Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = progress()
                    val s = 1f - 0.14f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(1f, 0.5f)
                    translationX = 0.04f * size.width * p
                    this.shape = shape
                    clip = true
                }
                .background(scheme.secondary),
        ) { PagePlaceholder(scheme.onSecondary) }
    }
}

/** A few rows, so a moving page reads as a page and not a block of colour. */
@Composable
private fun PagePlaceholder(ink: Color) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(48.dp).height(9.dp).clip(CircleShape).background(ink.copy(alpha = 0.55f)))
        repeat(5) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(ink.copy(alpha = 0.22f)))
                Box(Modifier.fillMaxWidth(0.6f).height(7.dp).clip(CircleShape).background(ink.copy(alpha = 0.22f)))
            }
        }
    }
}

private val PhoneMaxHeight = 320.dp
