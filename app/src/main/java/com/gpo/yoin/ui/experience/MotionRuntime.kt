package com.gpo.yoin.ui.experience

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
}

@Composable
fun ReportMotionPressure(
    tag: String,
    isHighPressure: Boolean,
) {
    val capabilityProvider = LocalMotionCapabilityProvider.current

    LaunchedEffect(capabilityProvider, tag, isHighPressure) {
        capabilityProvider.setHighPressure(tag = tag, isHighPressure = isHighPressure)
    }

    DisposableEffect(capabilityProvider, tag) {
        onDispose {
            capabilityProvider.setHighPressure(tag = tag, isHighPressure = false)
        }
    }
}
