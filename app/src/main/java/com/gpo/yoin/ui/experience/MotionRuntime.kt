package com.gpo.yoin.ui.experience

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalAccessorScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.gpo.yoin.symbols.LocalSymbolMotion
import com.gpo.yoin.symbols.SymbolMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MotionProfile {
    Full,
    AdaptiveReduced,
}

/**
 * The app's motion tier. Dynamic, not static: it flips at runtime (Home's
 * loading pressure comes and goes on every cold start and account switch),
 * and a static local's flip recomposes the whole app under its provider —
 * the feed that just composed included — where this recomposes only what
 * reads it. Provide it with [motionProfileValues].
 */
val LocalMotionProfile = compositionLocalOf { MotionProfile.Full }

/**
 * [profile] as [LocalMotionProfile], with Yoin Symbols' motion following it
 * (AdaptiveReduced → [SymbolMotion.Reduced], else [SymbolMotion.Default];
 * design.md). The library's [LocalSymbolMotion] is a static local: provided
 * as a value it would recompose the whole tree on every flip, so it is
 * computed from the dynamic profile where it is read instead.
 */
fun motionProfileValues(profile: MotionProfile): Array<ProvidedValue<*>> = arrayOf(
    LocalMotionProfile provides profile,
    LocalSymbolMotion providesComputed SymbolMotionOfProfile
)

// One instance: the computed value's holder is reused while it is the same lambda.
private val SymbolMotionOfProfile: CompositionLocalAccessorScope.() -> SymbolMotion = {
    if (LocalMotionProfile.currentValue == MotionProfile.AdaptiveReduced) SymbolMotion.Reduced else SymbolMotion.Default
}

val LocalMotionCapabilityProvider = staticCompositionLocalOf {
    MotionCapabilityProvider(lowRamDevice = false)
}

class MotionCapabilityProvider(
    private val lowRamDevice: Boolean,
    private val powerSaveEnabled: () -> Boolean = { false },
) {
    constructor(context: Context) : this(
        lowRamDevice = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true,
        powerSaveEnabled = {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isPowerSaveMode == true
        },
    )

    private val highPressureTags = linkedSetOf<String>()
    private val _profile = MutableStateFlow(MotionProfile.Full)
    val profile: StateFlow<MotionProfile> = _profile.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _profile.value = resolveProfile(highPressureTags.isNotEmpty())
    }

    fun setHighPressure(
        tag: String,
        isHighPressure: Boolean,
    ) {
        synchronized(highPressureTags) {
            if (isHighPressure) {
                highPressureTags += tag
            } else {
                highPressureTags -= tag
            }
        }
        refresh()
    }

    private fun resolveProfile(hasHighPressure: Boolean): MotionProfile =
        if (lowRamDevice || powerSaveEnabled() || hasHighPressure) {
            MotionProfile.AdaptiveReduced
        } else {
            MotionProfile.Full
        }

    /**
     * [setHighPressure], with a high report lifting itself after [maxDurationMs]
     * (null = for as long as it stands). Suspends for that long; cancelling —
     * the reporter's pressure changed — leaves the next report in charge.
     */
    suspend fun reportPressure(tag: String, isHighPressure: Boolean, maxDurationMs: Long?) {
        setHighPressure(tag = tag, isHighPressure = isHighPressure)
        if (isHighPressure && maxDurationMs != null) {
            delay(maxDurationMs)
            setHighPressure(tag = tag, isHighPressure = false)
        }
    }
}

/**
 * Report [tag]'s pressure while composed. [maxDurationMs] caps one stretch of
 * high pressure, so a reporter stuck on it (a load that never lands) can't
 * hold the whole app at [MotionProfile.AdaptiveReduced].
 */
@Composable
fun ReportMotionPressure(tag: String, isHighPressure: Boolean, maxDurationMs: Long? = null) {
    val capabilityProvider = LocalMotionCapabilityProvider.current

    LaunchedEffect(capabilityProvider, tag, isHighPressure, maxDurationMs) {
        capabilityProvider.reportPressure(tag = tag, isHighPressure = isHighPressure, maxDurationMs = maxDurationMs)
    }

    DisposableEffect(capabilityProvider, tag) {
        onDispose {
            capabilityProvider.setHighPressure(tag = tag, isHighPressure = false)
        }
    }
}
