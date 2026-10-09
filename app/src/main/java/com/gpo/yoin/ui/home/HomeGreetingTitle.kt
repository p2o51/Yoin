package com.gpo.yoin.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.YoinArmTransform
import com.gpo.yoin.ui.component.YoinMark
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.home.edit.HomeEditHeaderTitle
import com.gpo.yoin.ui.home.edit.homeEditHeaderIcon
import com.gpo.yoin.ui.theme.GoogleSansFlexRounded
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/*
 * Home's header title (owner 2026-10-09, prototype artifact GWEBwdavGiwS1ngqmcqTyF):
 * two lines — a small greeting for the time of day over a "Home" one size
 * smaller than before, both in the rounded cut of Google Sans Flex. On a cold
 * start the Yoin mark blooms where the title sits, then spins away and the
 * two lines rise in behind it. Returning to Home never replays it.
 */

/** The four greetings, by the hour. */
internal enum class HomeDayPart { Morning, Noon, Afternoon, Evening }

/** 5–11 morning, 11–13 noon, 13–18 afternoon, the rest evening. */
internal fun homeDayPartAt(hour: Int): HomeDayPart = when (hour) {
    in 5 until 11 -> HomeDayPart.Morning
    in 11 until 13 -> HomeDayPart.Noon
    in 13 until 18 -> HomeDayPart.Afternoon
    else -> HomeDayPart.Evening
}

/** The day part now, moving on by itself at the top of each hour. */
@Composable
private fun rememberHomeDayPart(): HomeDayPart {
    var part by remember { mutableStateOf(homeDayPartAt(LocalTime.now().hour)) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            val nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val wait = ChronoUnit.MILLIS.between(now, nextHour).let { if (it <= 0) it + 86_400_000 else it }
            delay(wait + 500)
            part = homeDayPartAt(LocalTime.now().hour)
        }
    }
    return part
}

/** Plays once per process: a cold start, not a return to Home or a rotation. */
private object HomeIntroLatch {
    @Volatile
    var played: Boolean = false
}

/** True while the cold-start bloom plays: the Memories bubble's mark waits for it. */
internal val HomeIntroRunning = mutableStateOf(false)

/** The intro's values, all 0 → 1. At rest (played, or reduced motion) everything is 1. */
@Stable
private class HomeIntro(atRest: Boolean) {
    val arms = List(3) { Animatable(if (atRest) 1f else 0f) }
    val exit = Animatable(if (atRest) 1f else 0f)
    val fade = Animatable(if (atRest) 1f else 0f)
    val greeting = Animatable(if (atRest) 1f else 0f)
    val title = Animatable(if (atRest) 1f else 0f)
    var markShown by mutableStateOf(!atRest)
}

// The bloom: each arm springs open a beat after the last, with a little overshoot.
private const val ArmStaggerMs = 70L
private const val ExitAtMs = 950L
private const val GreetingAfterExitMs = 100L
private const val TitleAfterGreetingMs = 80L
private val BloomSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 260f)

// Home reports loading as motion pressure (AdaptiveReduced) and clears it a frame
// after the feed arrives, so the header usually composes under it: wait this long
// for it to clear before treating the profile as a real reduce-motion device.
private const val PressureClearTimeoutMs = 400L

@Composable
private fun rememberHomeIntro(): HomeIntro {
    val preview = LocalInspectionMode.current
    val intro = remember { HomeIntro(atRest = HomeIntroLatch.played || preview) }
    val profile by rememberUpdatedState(LocalMotionProfile.current)
    val spatial = YoinMotion.defaultSpatialSpec<Float>()
    val effects = YoinMotion.defaultEffectsSpec<Float>()
    LaunchedEffect(intro) {
        if (!intro.markShown) return@LaunchedEffect
        HomeIntroLatch.played = true
        HomeIntroRunning.value = true
        try {
            playHomeIntro(intro, { profile }, spatial, effects)
        } finally {
            HomeIntroRunning.value = false
        }
    }
    return intro
}

private suspend fun playHomeIntro(
    intro: HomeIntro,
    profile: () -> MotionProfile,
    spatial: FiniteAnimationSpec<Float>,
    effects: FiniteAnimationSpec<Float>,
) {
    run {
        val full = withTimeoutOrNull(PressureClearTimeoutMs) {
            snapshotFlow { profile() }.first { it == MotionProfile.Full }
        }
        if (full == null) {
            // Reduce motion (low RAM, power saver): the two lines are simply there.
            (intro.arms + listOf(intro.exit, intro.fade, intro.greeting, intro.title)).forEach { it.snapTo(1f) }
            intro.markShown = false
            return
        }
        coroutineScope {
            intro.arms.forEachIndexed { i, arm ->
                launch {
                    delay(i * ArmStaggerMs)
                    arm.animateTo(1f, BloomSpring)
                }
            }
            delay(ExitAtMs)
            launch { intro.exit.animateTo(1f, spatial) }
            launch { intro.fade.animateTo(1f, effects) }
            delay(GreetingAfterExitMs)
            launch { intro.greeting.animateTo(1f, spatial) }
            delay(TitleAfterGreetingMs)
            intro.title.animateTo(1f, spatial)
        }
        intro.markShown = false
    }
}

/**
 * The greeting over "Home" (and "Edit Home" over it through edit mode's P, via
 * [HomeEditHeaderTitle]); the greeting fades out with the header icons.
 */
@Composable
internal fun HomeGreetingTitle(
    editProgress: () -> Float,
    onEnterEdit: () -> Unit,
    modifier: Modifier = Modifier,
    // Landscape handset: one step smaller again.
    compact: Boolean = false,
) {
    val intro = rememberHomeIntro()
    val greeting = when (rememberHomeDayPart()) {
        HomeDayPart.Morning -> stringResource(R.string.home_greeting_morning)
        HomeDayPart.Noon -> stringResource(R.string.home_greeting_noon)
        HomeDayPart.Afternoon -> stringResource(R.string.home_greeting_afternoon)
        HomeDayPart.Evening -> stringResource(R.string.home_greeting_evening)
    }
    val typography = MaterialTheme.typography
    val titleStyle = (if (compact) typography.headlineSmall else typography.headlineMedium).rounded()
    val greetingStyle = typography.titleMedium.rounded()
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Column {
            Text(
                text = greeting,
                style = greetingStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .homeEditHeaderIcon(editProgress)
                    .graphicsLayer {
                        val p = intro.greeting.value
                        alpha = p
                        translationY = (1f - p) * 12.dp.toPx()
                    },
            )
            HomeEditHeaderTitle(
                progress = editProgress,
                style = titleStyle,
                onEnterEdit = onEnterEdit,
                modifier = Modifier.graphicsLayer {
                    val p = intro.title.value
                    alpha = p
                    translationY = (1f - p) * 16.dp.toPx()
                },
            )
        }
        if (intro.markShown) {
            val scheme = MaterialTheme.colorScheme
            YoinMark(
                transforms = intro.arms.map { arm ->
                    val p = arm.value
                    YoinArmTransform(scale = p.coerceAtLeast(0f), rotationDeg = (1f - p) * -80f)
                },
                // Arm roles as in the launcher mark: tertiary, primary, secondary.
                colors = listOf(scheme.tertiary, scheme.primary, scheme.secondary),
                lineColor = Color.White,
                modifier = Modifier
                    .offset(x = (-2).dp)
                    .requiredSize(HomeIntroMarkSize)
                    .graphicsLayer {
                        val e = intro.exit.value
                        translationX = -6.dp.toPx() * e
                        rotationZ = 70f * e
                        scaleX = 1f - 0.6f * e
                        scaleY = 1f - 0.6f * e
                        alpha = 1f - intro.fade.value
                    },
            )
        }
    }
}

private val HomeIntroMarkSize = 56.dp

private fun TextStyle.rounded() = copy(fontFamily = GoogleSansFlexRounded, fontWeight = FontWeight.Medium)

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun HomeGreetingTitlePreview() {
    YoinTheme {
        HomeGreetingTitle(editProgress = { 0f }, onEnterEdit = {})
    }
}
