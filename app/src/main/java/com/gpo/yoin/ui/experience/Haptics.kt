package com.gpo.yoin.ui.experience

import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * A centralized haptic feedback helper for Yoin, built on top of the
 * standard Android View.performHapticFeedback API to access richer
 * constants (CONFIRM, REJECT, CLOCK_TICK, etc.) that Compose's standard
 * HapticFeedback interface doesn't fully expose across all API levels.
 */
class YoinHaptics internal constructor(private val view: View) {

    private val vibrator: Vibrator? by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            null
        }
    }

    /**
     * A plucked string: one soft attack, then a ring that dies away over
     * about a quarter second. Paired with motion that travels (the lyric
     * translation opening line after line down the page), so the ring lasts
     * roughly as long as the wave is still moving near the eye. [opening]
     * plucks a touch brighter than closing.
     *
     * Composed primitives where the vibrator has them (API 31+); otherwise
     * the lightest View tick, which every device maps to something gentle.
     */
    fun performPluck(opening: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && composePluck(opening)) return
        performLightTick()
    }

    /** Crisp mechanical 3D flip impulse for the Tensor silicon easter egg: launch click, mid-turn tick, landing settle. */
    fun performTensorFlip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && composeTensorFlip()) return
        performConfirm()
    }

    /** Flowing dot-matrix ripple cascade after the Tensor die settles: five soft travelling micro-ticks. */
    fun performTensorWave() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && composeTensorWave()) return
        performLightTick()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun composeTensorFlip(): Boolean {
        if (!view.isHapticFeedbackEnabled) return true
        val v = vibrator ?: return false
        return try {
            if (!v.hasVibrator()) return false
            val click = VibrationEffect.Composition.PRIMITIVE_CLICK
            val tick = VibrationEffect.Composition.PRIMITIVE_TICK
            if (!v.areAllPrimitivesSupported(click, tick)) return false
            if (!isLegacyHapticsEnabled()) return true
            val hasThud = v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
            val land = if (hasThud) VibrationEffect.Composition.PRIMITIVE_THUD else click
            val effect = VibrationEffect.startComposition()
                .addPrimitive(click, 0.70f)
                .addPrimitive(tick, 0.36f, 150)
                .addPrimitive(land, 0.58f, 260)
                .compose()
            playComposed(v, effect)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun composeTensorWave(): Boolean {
        if (!view.isHapticFeedbackEnabled) return true
        val v = vibrator ?: return false
        return try {
            if (!v.hasVibrator()) return false
            val lowTick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            if (!v.areAllPrimitivesSupported(lowTick)) return false
            if (!isLegacyHapticsEnabled()) return true
            val effect = VibrationEffect.startComposition()
                .addPrimitive(lowTick, 0.22f)
                .addPrimitive(lowTick, 0.34f, 68)
                .addPrimitive(lowTick, 0.44f, 68)
                .addPrimitive(lowTick, 0.32f, 68)
                .addPrimitive(lowTick, 0.18f, 76)
                .compose()
            playComposed(v, effect)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isLegacyHapticsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return Settings.System.getInt(
            view.context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0
    }

    private fun playComposed(v: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun composePluck(opening: Boolean): Boolean {
        if (!view.isHapticFeedbackEnabled) return true
        val v = vibrator ?: return false
        return try {
            if (!v.hasVibrator()) return false
            val attack = VibrationEffect.Composition.PRIMITIVE_CLICK
            val ring = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            if (!v.areAllPrimitivesSupported(attack, ring)) return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                // USAGE_TOUCH only exists from 33; before that honour the switch by hand.
                val enabled = Settings.System.getInt(
                    view.context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1,
                )
                if (enabled == 0) return true
            }
            val strength = if (opening) 1f else 0.8f
            val effect = VibrationEffect.startComposition()
                .addPrimitive(attack, 0.42f * strength)
                .addPrimitive(ring, 0.30f * strength, PluckRingGapMs)
                .addPrimitive(ring, 0.18f * strength, PluckRingGapMs)
                .addPrimitive(ring, 0.09f * strength, PluckRingGapMs + 12)
                .compose()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun performClick() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    fun performTick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun performConfirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun performReject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    fun performLongPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    fun performContextClick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun performLightTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** Picking something up to drag it. */
    fun performDragStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.DRAG_START)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /** A dragged item crossing into the next slot. */
    fun performSegmentTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** One of many small things landing in quick succession (the album bar's colour bands). */
    fun performFrequentTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        } else {
            performLightTick()
        }
    }

    /** A drag passing a limit (the rubber band starts). */
    fun performThreshold() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        } else {
            performLightTick()
        }
    }

    fun performToggle(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
}

/** Silence between the pluck's ring beats (counted from the end of the previous one). */
private const val PluckRingGapMs = 34

@Composable
fun rememberYoinHaptics(): YoinHaptics {
    val view = LocalView.current
    return remember(view) { YoinHaptics(view) }
}
