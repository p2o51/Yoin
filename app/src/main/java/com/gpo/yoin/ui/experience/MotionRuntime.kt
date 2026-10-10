package com.gpo.yoin.ui.experience

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MotionProfile {
    Full,
    AdaptiveReduced,
}

val LocalMotionProfile = staticCompositionLocalOf { MotionProfile.Full }

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
