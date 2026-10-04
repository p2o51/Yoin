package com.gpo.yoin.ui.home.edit

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Every Home edit mode constant, ported as-is from the prototype the owner
 * signed off (port sheet §7). The prototype was drawn at 1 CSS px = 1 dp, so
 * its px are dp here; times are ms, angles degrees.
 */
internal object HomeEditTokens {
    // Press, charge and lift (port sheet §2.2–2.4).
    /** Side of the press square R_press, centred on the finger. */
    val PressBox = 96.dp

    /** R_press grows by this × charge. */
    val PressOutset = 4.dp

    /** The pressed block shrinks by this × charge, about the press point. */
    const val ChargeScale = .012f

    /** Plate pre-show alpha at full charge. */
    const val ChargePlateAlpha = .4f

    /** The lifted block grows by this × lift; a carried strip too. */
    const val LiftScale = .02f
    val LiftShadow = 6.dp

    /** Once P passes this the entry plate drops its charge latch. */
    const val LatchClearP = .6f

    // Entry ripple and plate (§2.5).
    const val RippleStep = .06f
    const val RippleMaxSteps = 4

    /** A block's entry kick fires when its ripple progress reaches this. */
    const val EntryKickAt = .85f
    val PlateOutsetH = 8.dp
    val PlateOutsetVMax = 6.dp

    /** Room the vertical outset leaves between neighbouring plates. */
    val PlateMinGap = 4.dp
    val PlateRadius = 20.dp

    /**
     * How far a block's layer reaches past its content. Below full alpha a
     * layer composites within its own bounds, so it must cover all the block
     * draws outside them: the plate and badges, the lift (scale and shadow)
     * and a shelf's bleed to the screen edge (the widest page margin is 76dp).
     */
    val BlockLayerOutsetH = 96.dp
    val BlockLayerOutsetV = 32.dp

    // Badges (§2.6).
    /** Ripple progress where the hide and handle badges start to appear. */
    const val BadgeStart = .25f
    const val BadgeMinScale = .6f

    // Wiggle (§3).
    const val SwayHz = 2.4f

    /** Each section's frequency is detuned by up to ± this, from its id hash. */
    const val SwayDetune = .07f

    /** Block-mode sway is this × Θ (the kick gets the full Θ). */
    const val BlockSwayFactor = .6f

    /** Sway envelope while a block is carried. */
    const val DragEnvelope = .6f

    /** IdleSettle: this long without a touch and the sway settles. */
    const val IdleMs = 6000L

    /** Sway fades out over this distance from the status and bar bands. */
    val BandFade = 48.dp

    /** Gap between the safe area and the status tide or the bar. */
    val SafeGap = 8.dp

    /** Block-mode amplitude Θ: this corner displacement over the plate's half-diagonal. */
    val ThetaCorner = 2.dp
    const val ThetaMinDeg = .15f
    const val ThetaMaxDeg = .8f

    /** Card amplitude A_c = [CardAmpDeg] × [CardAmpWidth] / card width: about 1dp at the card's edge. */
    const val CardAmpDeg = 1.1f
    val CardAmpWidth = 100.dp
    const val CardAmpMinDeg = .35f
    const val CardAmpMaxDeg = 1.1f

    /** Rotations smaller than this are not drawn. */
    const val MinAngleDeg = .0005f

    /** Envelope at or below this: no sway (it can dip below 0 on the fall). */
    const val SwayFloor = .0005f

    // Kicks (§3.7). Normalised: a kick of amplitude a first peaks at a.
    const val KickGain = 31.6f
    const val KickEntry = 1f

    /** An edit-mode tap, and a block dropped where it was lifted. */
    const val KickTap = .5f

    /** The carried block after a strip drop. */
    const val KickDrop = .7f

    /** Neighbours whose index changed, and a shown block in the viewport. */
    const val KickNeighbour = .35f

    /** Handle pulse start velocity: one bounce 1 → 1.2 → 1. */
    const val PulseVelocity = 11.3f

    // Strips (§4).
    val StripGap = 8.dp
    val StripMinH = 48.dp
    val StripMaxH = 64.dp
    val StripMaxW = 560.dp

    /** Cover edge = strip height − this, clamped to [StripCoverMin]..[StripCoverMax]. */
    val StripCoverInset = 24.dp
    val StripCoverMin = 24.dp
    val StripCoverMax = 36.dp

    /** Each cover after the first overlaps the previous by this × its edge. */
    const val StripCoverOverlap = .3f

    /** A strip shows at most this many of its section's covers. */
    const val StripCoverCount = 3
    val StripPadding = 16.dp
    val StripContentGap = 12.dp
    val StripHandleSize = 24.dp

    /** Fold where strip plates finish fading in (and the feed out). */
    const val FoldPlateEnd = .45f

    /** Fold where strip labels start fading in. */
    const val FoldLabelStart = .55f

    /** Fold where the hole and the cover tint start. */
    const val FoldHoleStart = .3f

    /** Fold where the carried strip's shadow starts. */
    const val FoldShadowStart = .6f

    /** How far a strip's colour leans toward its first cover. */
    const val StripTint = .3f

    /** Below this cover weight a strip keeps its plate colour: the prototype's 0.05% (its tint runs 0–30 in percent). */
    const val StripTintVisible = .0005f

    /** The hole's secondaryContainer fill alpha. */
    const val HoleAlpha = .35f

    /** Off-screen sections start as an invisible strip at this scale. */
    const val OffscreenStripScale = .96f

    /** Rubber band past the first and last slot: the displayed overscroll tends to this. */
    val RubberD = 56.dp
    const val RubberK = .55f

    /** unrubber never inverts past this fraction of [RubberD]. */
    const val UnrubberMaxRatio = .98f

    /** Overscroll past which the threshold haptic plays. */
    val OverHaptic = 2.dp

    /** Release speed (dp/s) that projects the drop one more slot. */
    val FlingV = 1600.dp

    /** Release speeds clamp to this (dp/s). */
    val MaxV = 8000.dp
    const val VelocityWindowMs = 100L

    /** A finger resting this long before lifting releases at 0. */
    const val VelocityStaleMs = 60L
    const val VelocityMinDtMs = 4L

    /** Re-grab hit zone around a settling strip. */
    val RegrabSlop = 8.dp

    /** The anchored layout normally lands on the next frame; the unfold never waits longer than this many. */
    const val AnchorFrameLimit = 3

    // Session.
    const val UndoMax = 20
    const val HideScale = .96f

    /** Bar "Add" scrolls the tray top to this far below the viewport top. */
    val ScrollToTrayOffset = 80.dp

    /** A blank tap this soon after a scroll only stops the fling. */
    const val FlingStopMs = 120L

    /** Edit-mode body hold before a lift: max(this, long-press timeout / 2). */
    const val BodyHoldMinMs = 150L
    val PlaceholderHeight = 112.dp

    // Spring thresholds (§1.3). They must sit on the spring itself: an
    // Animatable's own threshold never reaches a spring passed into it.
    const val ProgressThreshold = .0005f

    /** Charge, lift, lift tint, the handle pulse and every alpha. */
    const val UnitThreshold = .002f
    const val FoldThreshold = .001f
    const val EnvelopeThreshold = .001f
    const val HideScaleThreshold = .001f
    const val KickThreshold = .01f
    const val HolePxThreshold = .3f
    const val SettlePxThreshold = .4f
    const val NeighbourPxThreshold = .3f
}

/** The spring with its own visibility threshold; anything else passes through. */
internal fun FiniteAnimationSpec<Float>.withThreshold(threshold: Float): FiniteAnimationSpec<Float> =
    (this as? SpringSpec<Float>)?.let { SpringSpec(it.dampingRatio, it.stiffness, threshold) } ?: this

private val StageSpec = YoinMotion.stageSettleSpring<Float>().withThreshold(HomeEditTokens.ProgressThreshold)

// Effects are the same in both roles, so this stays role-free like stageSettle.
private val ReducedStageSpec = YoinMotion.fastEffectsSpec<Float>(
    role = YoinMotionRole.Expressive,
    expressiveScheme = MotionScheme.expressive(),
).withThreshold(HomeEditTokens.ProgressThreshold)

/** P's spring. Role-free, so the shell (Standard role) and Home agree. */
internal fun homeEditStageSpec(reduced: Boolean): FiniteAnimationSpec<Float> =
    if (reduced) ReducedStageSpec else StageSpec

/**
 * Every spring Home edit mode runs, each with its threshold, resolved once
 * for the Expressive role. Under reduced motion every spatial spring becomes
 * fastEffects (same thresholds).
 */
@Immutable
internal class HomeEditSpecs(
    val reduced: Boolean,
    /** P (stageSettle). */
    val stage: FiniteAnimationSpec<Float>,
    val fold: FiniteAnimationSpec<Float>,
    val settlePx: FiniteAnimationSpec<Float>,
    val neighbourPx: FiniteAnimationSpec<Float>,
    val holePx: FiniteAnimationSpec<Float>,
    val chargeUp: FiniteAnimationSpec<Float>,
    val chargeDown: FiniteAnimationSpec<Float>,
    val liftUp: FiniteAnimationSpec<Float>,
    val liftDown: FiniteAnimationSpec<Float>,
    /** Both ways. */
    val liftTint: FiniteAnimationSpec<Float>,
    /** Sway envelope E. */
    val envUp: FiniteAnimationSpec<Float>,
    val envDown: FiniteAnimationSpec<Float>,
    val kick: FiniteAnimationSpec<Float>,
    /** Handle pulse. */
    val pulse: FiniteAnimationSpec<Float>,
    val hideScaleOut: FiniteAnimationSpec<Float>,
    val hideAlphaOut: FiniteAnimationSpec<Float>,
    val showScaleIn: FiniteAnimationSpec<Float>,
    val showAlphaIn: FiniteAnimationSpec<Float>,
    val trayIn: FiniteAnimationSpec<Float>,
    val trayOut: FiniteAnimationSpec<Float>,
    /** The bar's left-slot cross-fade. */
    val slot: FiniteAnimationSpec<Float>,
    /** Feed item placement (`animateItem`). */
    val placement: FiniteAnimationSpec<IntOffset>,
    /** Feed item fades. Both keep the effects spring the feed already fades with (defaultEffects). */
    val effectsIn: FiniteAnimationSpec<Float>,
    val effectsOut: FiniteAnimationSpec<Float>,
) {
    companion object {
        /** Non-composable, for tests and anything outside composition. Always the Expressive role. */
        fun create(scheme: MotionScheme, reduced: Boolean): HomeEditSpecs {
            val role = YoinMotionRole.Expressive
            val fastEffects = YoinMotion.fastEffectsSpec<Float>(role, scheme)
            val defaultEffects = YoinMotion.defaultEffectsSpec<Float>(role, scheme)
            val defaultSpatial: FiniteAnimationSpec<Float> =
                if (reduced) fastEffects else YoinMotion.defaultSpatialSpec(role, scheme)
            val fastSpatial: FiniteAnimationSpec<Float> =
                if (reduced) fastEffects else YoinMotion.fastSpatialSpec(role, scheme)
            val slowSpatial: FiniteAnimationSpec<Float> =
                if (reduced) fastEffects else YoinMotion.slowSpatialSpec(role, scheme)
            val kick = if (reduced) {
                fastEffects.withThreshold(HomeEditTokens.KickThreshold)
            } else {
                YoinMotion.homeEditKickSpring()
            }
            val placement: FiniteAnimationSpec<IntOffset> = if (reduced) {
                YoinMotion.fastEffectsSpec(role, scheme)
            } else {
                YoinMotion.defaultSpatialSpec(role, scheme)
            }
            return with(HomeEditTokens) {
                HomeEditSpecs(
                    reduced = reduced,
                    stage = homeEditStageSpec(reduced),
                    fold = defaultSpatial.withThreshold(FoldThreshold),
                    settlePx = defaultSpatial.withThreshold(SettlePxThreshold),
                    neighbourPx = defaultSpatial.withThreshold(NeighbourPxThreshold),
                    holePx = fastSpatial.withThreshold(HolePxThreshold),
                    chargeUp = slowSpatial.withThreshold(UnitThreshold),
                    chargeDown = fastSpatial.withThreshold(UnitThreshold),
                    liftUp = fastSpatial.withThreshold(UnitThreshold),
                    liftDown = defaultSpatial.withThreshold(UnitThreshold),
                    liftTint = fastEffects.withThreshold(UnitThreshold),
                    envUp = fastSpatial.withThreshold(EnvelopeThreshold),
                    envDown = defaultSpatial.withThreshold(EnvelopeThreshold),
                    kick = kick,
                    pulse = fastSpatial.withThreshold(UnitThreshold),
                    hideScaleOut = fastSpatial.withThreshold(HideScaleThreshold),
                    hideAlphaOut = fastEffects.withThreshold(UnitThreshold),
                    showScaleIn = defaultSpatial.withThreshold(HideScaleThreshold),
                    showAlphaIn = defaultEffects.withThreshold(UnitThreshold),
                    trayIn = defaultEffects.withThreshold(UnitThreshold),
                    trayOut = fastEffects.withThreshold(UnitThreshold),
                    slot = fastEffects.withThreshold(UnitThreshold),
                    placement = placement,
                    effectsIn = defaultEffects.withThreshold(UnitThreshold),
                    effectsOut = defaultEffects.withThreshold(UnitThreshold),
                )
            }
        }
    }
}

/**
 * The feed's item placement while editing (port sheet §4.5): [spec], except
 * that a move starting while [snap] holds (strips folding, a carry) lands at
 * once, as the prototype's commit without FLIP does. Pass one remembered
 * instance to `animateItem`: [snap] is read when the list starts a move,
 * never in composition, so a fold leaves every section's modifiers as they are.
 */
@Stable
internal class GatedPlacementSpec(
    private val spec: FiniteAnimationSpec<IntOffset>,
    private val snap: () -> Boolean,
) : FiniteAnimationSpec<IntOffset> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<IntOffset, V>,
    ): VectorizedFiniteAnimationSpec<V> = if (snap()) Snap.vectorize(converter) else spec.vectorize(converter)

    private companion object {
        val Snap = SnapSpec<IntOffset>()
    }
}

/** Resolve the specs once, in Home's composition. The role is Expressive whatever the caller provides. */
@Composable
internal fun rememberHomeEditSpecs(reduced: Boolean): HomeEditSpecs {
    val scheme = MaterialTheme.motionScheme
    return remember(scheme, reduced) { HomeEditSpecs.create(scheme, reduced) }
}

/** Battery saver or low RAM (folded into AdaptiveReduced), or "remove animations": the same test as the seams. */
@Composable
internal fun rememberHomeEditReducedMotion(): Boolean {
    val profile = LocalMotionProfile.current
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return profile == MotionProfile.AdaptiveReduced || durationScale?.scaleFactor == 0f
}

/** How the edit-mode wiggle moves. The product ships [IdleSettle]; the others are debug trials. */
internal enum class HomeWiggleMode {
    /** Only kicks, no sway. */
    Kick,

    /** Sway that settles after [HomeEditTokens.IdleMs] without a touch. */
    IdleSettle,

    /** Sway until edit mode ends. */
    Continuous,
}

/** What rotates: whole blocks, or each card on its own. */
internal enum class HomeWiggleTarget { Block, Card }

@Immutable
internal data class HomeWiggleStyle(
    val mode: HomeWiggleMode = HomeWiggleMode.IdleSettle,
    val target: HomeWiggleTarget = HomeWiggleTarget.Card,
)

// Production never provides this; only the debug QA harness overrides it
// (the HomeHintVariant pattern), so the app always wiggles IdleSettle + Card.
internal val LocalHomeWiggleStyle = staticCompositionLocalOf { HomeWiggleStyle() }
