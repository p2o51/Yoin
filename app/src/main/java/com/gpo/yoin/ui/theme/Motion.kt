package com.gpo.yoin.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally as composeExpandHorizontally
import androidx.compose.animation.fadeIn as composeFadeIn
import androidx.compose.animation.fadeOut as composeFadeOut
import androidx.compose.animation.scaleIn as composeScaleIn
import androidx.compose.animation.scaleOut as composeScaleOut
import androidx.compose.animation.slideInHorizontally as composeSlideInHorizontally
import androidx.compose.animation.slideInVertically as composeSlideInVertically
import androidx.compose.animation.slideOutHorizontally as composeSlideOutHorizontally
import androidx.compose.animation.slideOutVertically as composeSlideOutVertically
import androidx.compose.animation.shrinkHorizontally as composeShrinkHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.TransformOrigin
import com.gpo.yoin.ui.navigation.back.BackMotionTokens
import kotlin.math.roundToInt

enum class YoinMotionRole {
    Expressive,
    Standard,
}

enum class YoinMotionSpeed {
    Fast,
    Default,
    Slow,
}

val LocalYoinMotionRole = staticCompositionLocalOf { YoinMotionRole.Standard }

@Composable
fun ProvideYoinMotionRole(
    role: YoinMotionRole,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalYoinMotionRole provides role, content = content)
}

/**
 * Material-backed motion wrapper.
 *
 * `MotionScheme` defines the semantic spring buckets; `MotionProfile` decides
 * when the app should use a lighter bucket. Business surfaces should consume
 * these helpers instead of creating raw animation specs inline.
 */
object YoinMotion {
    private val PredictiveBackTransformOrigin = TransformOrigin(0f, 0.5f)
    private const val BackFadeDurationMillis = 184
    private const val BackFadeHoldMillis = 36
    private const val PredictiveBackSpringSpeedMultiplier = 1.3f

    private val expressiveMotionScheme = MotionScheme.expressive()
    private val standardMotionScheme = MotionScheme.standard()

    private fun <T> predictiveBackSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow * PredictiveBackSpringSpeedMultiplier,
    )

    private fun <T> simplePushSpring() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    private fun resolveScheme(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme,
    ): MotionScheme = when (role) {
        YoinMotionRole.Expressive -> expressiveScheme
        YoinMotionRole.Standard -> standardScheme
    }

    private fun <T> spatialSpecForScheme(
        scheme: MotionScheme,
        speed: YoinMotionSpeed,
    ): FiniteAnimationSpec<T> = when (speed) {
        YoinMotionSpeed.Fast -> scheme.fastSpatialSpec()
        YoinMotionSpeed.Default -> scheme.defaultSpatialSpec()
        YoinMotionSpeed.Slow -> scheme.slowSpatialSpec()
    }

    private fun <T> effectsSpecForScheme(
        scheme: MotionScheme,
        speed: YoinMotionSpeed,
    ): FiniteAnimationSpec<T> = when (speed) {
        YoinMotionSpeed.Fast -> scheme.fastEffectsSpec()
        YoinMotionSpeed.Default -> scheme.defaultEffectsSpec()
        YoinMotionSpeed.Slow -> scheme.slowEffectsSpec()
    }

    fun <T> defaultSpatialSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = spatialSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Default,
    )

    fun <T> fastSpatialSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = spatialSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Fast,
    )

    fun <T> slowSpatialSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = spatialSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Slow,
    )

    fun <T> defaultEffectsSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = effectsSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Default,
    )

    fun <T> fastEffectsSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = effectsSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Fast,
    )

    fun <T> slowEffectsSpec(
        role: YoinMotionRole,
        expressiveScheme: MotionScheme,
        standardScheme: MotionScheme = standardMotionScheme,
    ): FiniteAnimationSpec<T> = effectsSpecForScheme(
        scheme = resolveScheme(role, expressiveScheme, standardScheme),
        speed = YoinMotionSpeed.Slow,
    )

    @Composable
    fun <T> defaultSpatialSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = defaultSpatialSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> fastSpatialSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = fastSpatialSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> slowSpatialSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = slowSpatialSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> defaultEffectsSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = defaultEffectsSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> fastEffectsSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = fastEffectsSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> slowEffectsSpec(
        role: YoinMotionRole = LocalYoinMotionRole.current,
    ): FiniteAnimationSpec<T> = slowEffectsSpec(
        role = role,
        expressiveScheme = MaterialTheme.motionScheme,
    )

    @Composable
    fun <T> expressiveSpatialSpring(): FiniteAnimationSpec<T> = slowSpatialSpec()

    @Composable
    fun <T> spatialSpring(): FiniteAnimationSpec<T> = defaultSpatialSpec()

    @Composable
    fun <T> effectsSpring(): FiniteAnimationSpec<T> = defaultEffectsSpec()

    @Composable
    fun <T> bouncySpatialSpring(): FiniteAnimationSpec<T> = slowSpatialSpec()

    @Composable
    fun <T> stiffSpatialSpring(): FiniteAnimationSpec<T> = fastSpatialSpec()

    fun <T> predictiveBackSettleSpring(): FiniteAnimationSpec<T> = predictiveBackSpring()

    private val delayedBackFadeIn = composeFadeIn(
        animationSpec = keyframes {
            durationMillis = BackFadeDurationMillis
            0f at 0
            0f at BackFadeHoldMillis
            1f at BackFadeDurationMillis
        },
    )

    private val delayedBackFadeOut = composeFadeOut(
        animationSpec = keyframes {
            durationMillis = BackFadeDurationMillis
            1f at 0
            1f at BackFadeHoldMillis
            0f at BackFadeDurationMillis
        },
    )

    fun fadeIn(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
    ): EnterTransition = composeFadeIn(
        animationSpec = effectsSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
    )

    fun fadeOut(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
    ): ExitTransition = composeFadeOut(
        animationSpec = effectsSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
    )

    fun slideInHorizontally(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        initialOffsetX: (Int) -> Int,
    ): EnterTransition = composeSlideInHorizontally(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        initialOffsetX = initialOffsetX,
    )

    fun slideOutHorizontally(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        targetOffsetX: (Int) -> Int,
    ): ExitTransition = composeSlideOutHorizontally(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        targetOffsetX = targetOffsetX,
    )

    fun slideInVertically(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        initialOffsetY: (Int) -> Int,
    ): EnterTransition = composeSlideInVertically(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        initialOffsetY = initialOffsetY,
    )

    fun slideOutVertically(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        targetOffsetY: (Int) -> Int,
    ): ExitTransition = composeSlideOutVertically(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        targetOffsetY = targetOffsetY,
    )

    fun scaleIn(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        initialScale: Float,
        transformOrigin: TransformOrigin = TransformOrigin.Center,
    ): EnterTransition = composeScaleIn(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        initialScale = initialScale,
        transformOrigin = transformOrigin,
    )

    fun scaleOut(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
        targetScale: Float,
        transformOrigin: TransformOrigin = TransformOrigin.Center,
    ): ExitTransition = composeScaleOut(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
        targetScale = targetScale,
        transformOrigin = transformOrigin,
    )

    fun expandHorizontally(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
    ): EnterTransition = composeExpandHorizontally(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
    )

    fun shrinkHorizontally(
        role: YoinMotionRole,
        speed: YoinMotionSpeed = YoinMotionSpeed.Default,
        expressiveScheme: MotionScheme = expressiveMotionScheme,
    ): ExitTransition = composeShrinkHorizontally(
        animationSpec = spatialSpecForScheme(
            scheme = resolveScheme(role, expressiveScheme, standardMotionScheme),
            speed = speed,
        ),
    )

    val navHostStableEnter: EnterTransition = EnterTransition.None
    val navHostStableExit: ExitTransition = ExitTransition.None

    val navEnterForward: EnterTransition =
        slideInHorizontally(role = YoinMotionRole.Expressive) {
            (it * 0.14f).roundToInt()
        } + fadeIn(role = YoinMotionRole.Standard)

    val navExitForward: ExitTransition =
        scaleOut(
            role = YoinMotionRole.Standard,
            speed = YoinMotionSpeed.Fast,
            targetScale = 0.96f,
        ) + fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast)

    val navEnterBack: EnterTransition =
        composeScaleIn(
            animationSpec = predictiveBackSpring(),
            initialScale = 0.94f,
            transformOrigin = PredictiveBackTransformOrigin,
        ) + delayedBackFadeIn

    val navExitBack: ExitTransition =
        composeScaleOut(
            animationSpec = predictiveBackSpring(),
            targetScale = 0.9f,
            transformOrigin = PredictiveBackTransformOrigin,
        ) + delayedBackFadeOut

    val albumDetailSharedEnter: EnterTransition =
        fadeIn(role = YoinMotionRole.Standard)

    val albumDetailSharedPopExit: ExitTransition =
        delayedBackFadeOut

    val navEnterOverlay: EnterTransition =
        slideInVertically(role = YoinMotionRole.Expressive) { it } +
            fadeIn(role = YoinMotionRole.Standard)

    val navExitOverlay: ExitTransition =
        slideOutVertically(
            role = YoinMotionRole.Standard,
            speed = YoinMotionSpeed.Fast,
        ) { it } + fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast)

    val simplePushEnter: EnterTransition =
        composeScaleIn(
            animationSpec = simplePushSpring(),
            initialScale = BackMotionTokens.PushPageScaleTarget,
            transformOrigin = PredictiveBackTransformOrigin,
        ) + composeFadeIn(animationSpec = simplePushSpring())

    val simplePushExit: ExitTransition = ExitTransition.None

    val simplePushPopEnter: EnterTransition = EnterTransition.None

    val simplePushPopExit: ExitTransition =
        composeScaleOut(
            animationSpec = simplePushSpring(),
            targetScale = BackMotionTokens.PushPageScaleTarget,
            transformOrigin = PredictiveBackTransformOrigin,
        ) + composeFadeOut(animationSpec = simplePushSpring())
}
