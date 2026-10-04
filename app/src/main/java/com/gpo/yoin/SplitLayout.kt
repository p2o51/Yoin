package com.gpo.yoin

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.window.RequiresWindowSdkExtension
import androidx.window.WindowSdkExtensions
import androidx.window.embedding.ActivityEmbeddingController
import androidx.window.embedding.EmbeddingAnimationBackground
import androidx.window.embedding.EmbeddingAnimationParams
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitAttributesCalculatorParams
import androidx.window.embedding.SplitController
import com.gpo.yoin.ui.settings.settingsPageColor
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme

/*
 * Activity Embedding geometry — Settings list-detail ONLY (断点交接 §7). The
 * shell ↔ detail split is NOT Activity Embedding any more: on a Wide window
 * the detail page is a column of the shell's own window
 * (ui/navigation/pane, adaptive principle 3), so one bar can span both
 * columns and the divider is the app's own M3 drag handle. Settings keeps
 * the platform split because its pages are plain destinations with no
 * shared chrome. The rules live in res/xml/main_split_config.xml; this only
 * decides HOW WIDE each side is.
 */

/**
 * Rule tags in main_split_config.xml share a prefix per family (tags must be
 * unique across the XML, or RuleController.parseRules throws at startup).
 */
internal const val SplitTagSettingsPrefix = "settings"

/** Settings list-detail: the list takes ≈420dp (SettingsTablet 420 | 860), at least a third. */
internal fun settingsSplitRatio(windowWidthDp: Float): Float =
    (SettingsListWidthDp / windowWidthDp).coerceIn(SettingsMinRatio, SettingsMaxRatio)

private const val SettingsListWidthDp = 420f
private const val SettingsMinRatio = 0.3f
private const val SettingsMaxRatio = 0.4f

/**
 * Installs the width-aware ratio (WindowSdkExtensions ≥ 2; older devices keep
 * the XML ratio) and, on extension ≥ 5, the app background behind the
 * system's split animations. The calculator depends on the window size and
 * night mode; size changes re-run it on their own, a light ⇄ dark switch does
 * not (verified on the Pixel Tablet), so that one invalidates the visible
 * stacks itself.
 */
// Lint does not read WindowSdkExtensions checks as guards; each call below sits
// behind one.
@SuppressLint("RequiresWindowSdk")
internal fun installSplitAttributesCalculator(context: Context, splitController: SplitController) {
    if (WindowSdkExtensions.getInstance().extensionVersion >= 2) {
        val appContext = context.applicationContext
        // Light/dark comes from the app's own configuration: on a switch the
        // calculator runs (see refreshSplitsOnNightModeChange) before the
        // parent container's configuration catches up, so the params still
        // say the old mode.
        val animationBackground: (() -> EmbeddingAnimationParams)? =
            if (WindowSdkExtensions.getInstance().extensionVersion >= 5) {
                { appBackgroundAnimationParams(appContext, appContext.resources.configuration) }
            } else {
                null
            }
        if (WindowSdkExtensions.getInstance().extensionVersion >= 3) {
            refreshSplitsOnNightModeChange(appContext)
        }
        splitController.setSplitAttributesCalculator { params ->
            splitAttributesFor(
                params = params,
                animationParams = animationBackground?.invoke(),
            )
        }
    }
}

@RequiresWindowSdkExtension(3)
private fun refreshSplitsOnNightModeChange(appContext: Context) {
    var night = appContext.resources.configuration.isNight()
    appContext.registerComponentCallbacks(
        object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val nowNight = newConfig.isNight()
                if (nowNight == night) return
                night = nowNight
                ActivityEmbeddingController.getInstance(appContext).invalidateVisibleActivityStacks()
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        },
    )
}

private fun Configuration.isNight(): Boolean =
    uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/**
 * The split opens and closes on demand, so the system animates panes growing
 * and shrinking. Whatever the panes don't cover reads as the Settings page
 * colour (the only Activity-Embedding split left; light or dark, the dynamic
 * scheme on API 31+) instead of the platform's default black.
 */
@RequiresWindowSdkExtension(5)
private fun appBackgroundAnimationParams(context: Context, configuration: Configuration): EmbeddingAnimationParams =
    EmbeddingAnimationParams.Builder()
        .setAnimationBackground(
            EmbeddingAnimationBackground.createColorBackground(
                settingsPageColor(appColorScheme(context, configuration)).toOpaqueArgb(),
            ),
        )
        .build()

/** The YoinTheme scheme for the configuration's light/dark mode (dynamic on API 31+). */
private fun appColorScheme(context: Context, configuration: Configuration): ColorScheme {
    val night = configuration.isNight()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (night) YoinDarkColorScheme else YoinLightColorScheme
    }
}

/** The animation background must be opaque. */
private fun Color.toOpaqueArgb(): Int = toArgb() or OpaqueAlpha

private const val OpaqueAlpha = 0xFF shl 24

/**
 * Only ever handed a non-null [animationParams] on extension ≥ 5 (see
 * [installSplitAttributesCalculator]).
 */
@SuppressLint("RequiresWindowSdk")
private fun splitAttributesFor(
    params: SplitAttributesCalculatorParams,
    animationParams: EmbeddingAnimationParams?,
): SplitAttributes {
    // Below a rule's minimum dimensions (a landscape handset: 914 wide but 411
    // tall; a portrait tablet: 800 wide) the calculator must say EXPAND itself —
    // handing back the XML defaults would split anyway. Non-sticky
    // placeholders (Settings') then step out, as without a calculator.
    if (!params.areDefaultConstraintsSatisfied) {
        return SplitAttributes.Builder()
            .setSplitType(SplitAttributes.SplitType.SPLIT_TYPE_EXPAND)
            .apply { if (animationParams != null) setAnimationParams(animationParams) }
            .build()
    }
    val bounds = params.parentWindowMetrics.bounds
    val density = params.parentConfiguration.densityDpi / 160f
    val widthDp = bounds.width() / density
    val settings = params.splitRuleTag?.startsWith(SplitTagSettingsPrefix) == true
    // Only Settings rules exist; anything else keeps the rule's own default,
    // which already carries a dragged ratio when the platform hands one back.
    val splitType = if (settings) {
        SplitAttributes.SplitType.ratio(settingsSplitRatio(widthDp))
    } else {
        params.defaultSplitAttributes.splitType
    }
    return SplitAttributes.Builder()
        .setSplitType(splitType)
        .setLayoutDirection(params.defaultSplitAttributes.layoutDirection)
        .apply { if (animationParams != null) setAnimationParams(animationParams) }
        .build()
}
