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
import androidx.window.embedding.DividerAttributes
import androidx.window.embedding.EmbeddingAnimationBackground
import androidx.window.embedding.EmbeddingAnimationParams
import androidx.window.embedding.SplitAttributes
import androidx.window.embedding.SplitAttributesCalculatorParams
import androidx.window.embedding.SplitController
import com.gpo.yoin.ui.theme.YoinDarkColorScheme
import com.gpo.yoin.ui.theme.YoinLightColorScheme

/*
 * Activity Embedding geometry (断点交接 §7 / §2.3). The rules live in
 * res/xml/main_split_config.xml; this only decides HOW WIDE each side is.
 */

/**
 * Rule tags in main_split_config.xml share a prefix per family (tags must be
 * unique across the XML, or RuleController.parseRules throws at startup).
 */
internal const val SplitTagSettingsPrefix = "settings"

/** Shell → Settings: Settings takes the whole task in its own container. */
internal const val SplitTagShellSettings = "shell-settings"

/**
 * The shell's share of a split window. From 1080dp up the shell keeps at least
 * 600dp — so its own pane reads Medium and gets the centred bar instead of a
 * 576dp Compact column (1280 → ≈0.47) — while the detail keeps at least 480;
 * narrower windows keep the XML's 0.45. Pure, unit-tested.
 */
internal fun shellSplitRatio(windowWidthDp: Float, dividerWidthDp: Float = DividerWidthDp.toFloat()): Float {
    if (windowWidthDp < ShellRatioThresholdDp) return ShellDefaultRatio
    // The divider takes its width out of both panes: budget it on top of the
    // 600 so the shell pane still reads Medium, not a 597dp Compact column.
    val atLeastShell = (ShellMinWidthDp + dividerWidthDp) / windowWidthDp
    val atMostShell = 1f - (DetailMinWidthDp + dividerWidthDp) / windowWidthDp
    return maxOf(ShellDefaultRatio, atLeastShell).coerceAtMost(atMostShell)
}

/** Settings list-detail: the list takes ≈420dp (SettingsTablet 420 | 860), at least a third. */
internal fun settingsSplitRatio(windowWidthDp: Float): Float =
    (SettingsListWidthDp / windowWidthDp).coerceIn(SettingsMinRatio, SettingsMaxRatio)

private const val ShellRatioThresholdDp = 1080f
private const val ShellDefaultRatio = 0.45f
private const val ShellMinWidthDp = 600f
private const val DetailMinWidthDp = 480f
private const val SettingsListWidthDp = 420f
private const val SettingsMinRatio = 0.3f
private const val SettingsMaxRatio = 0.4f

/** The draggable split band (TabletSplit's 10dp band with its grip). */
private const val DividerWidthDp = 10

/**
 * Installs the width-aware ratios (WindowSdkExtensions ≥ 2; older devices keep
 * the XML ratios), on extension ≥ 5 the app background behind the system's
 * split animations, and on extension ≥ 6 the platform's draggable divider in
 * the app's own tone — never a hand-drawn one. The calculator depends on the
 * window size and night mode; size changes re-run it on their own, a light ⇄
 * dark switch does not (verified on the Pixel Tablet: the divider kept its
 * light tone), so that one invalidates the visible stacks itself.
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
        val divider: (() -> DividerAttributes)? =
            if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
                { draggableDivider(appContext, appContext.resources.configuration) }
            } else {
                null
            }
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
                divider = divider?.invoke(),
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
 * and shrinking. Whatever the panes don't cover reads as the app's own
 * background (light or dark, the dynamic scheme on API 31+) instead of the
 * platform's default black.
 */
@RequiresWindowSdkExtension(5)
private fun appBackgroundAnimationParams(context: Context, configuration: Configuration): EmbeddingAnimationParams =
    EmbeddingAnimationParams.Builder()
        .setAnimationBackground(
            EmbeddingAnimationBackground.createColorBackground(
                appColorScheme(context, configuration).background.toOpaqueArgb(),
            ),
        )
        .build()

/**
 * The platform divider, tinted like TabletSplit's band: one step deeper than
 * the page (surfaceContainerHigh), not the library's default black. The grip
 * on it is the system's own.
 */
@RequiresWindowSdkExtension(6)
private fun draggableDivider(context: Context, configuration: Configuration): DividerAttributes =
    DividerAttributes.DraggableDividerAttributes.Builder()
        .setWidthDp(DividerWidthDp)
        .setColor(appColorScheme(context, configuration).surfaceContainerHigh.toOpaqueArgb())
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

/** Both the animation background and the divider must be opaque. */
private fun Color.toOpaqueArgb(): Int = toArgb() or OpaqueAlpha

private const val OpaqueAlpha = 0xFF shl 24

/**
 * Only ever handed a non-null [divider] on extension ≥ 6 and a non-null
 * [animationParams] on extension ≥ 5 (see [installSplitAttributesCalculator]).
 */
@SuppressLint("RequiresWindowSdk")
private fun splitAttributesFor(
    params: SplitAttributesCalculatorParams,
    divider: DividerAttributes?,
    animationParams: EmbeddingAnimationParams?,
): SplitAttributes {
    // Below a rule's minimum dimensions (a landscape handset: 914 wide but 411
    // tall; a portrait tablet: 800 wide) the calculator must say EXPAND itself —
    // handing back the XML defaults would split anyway. Non-sticky
    // placeholders (Settings') then step out, as without a calculator.
    // Shell → Settings always expands: Settings gets its own full-task
    // container, so its list-detail pairs with a container that empties when
    // Settings leaves.
    if (!params.areDefaultConstraintsSatisfied || params.splitRuleTag == SplitTagShellSettings) {
        return SplitAttributes.Builder()
            .setSplitType(SplitAttributes.SplitType.SPLIT_TYPE_EXPAND)
            .apply { if (animationParams != null) setAnimationParams(animationParams) }
            .build()
    }
    val bounds = params.parentWindowMetrics.bounds
    val density = params.parentConfiguration.densityDpi / 160f
    val widthDp = bounds.width() / density
    val settings = params.splitRuleTag?.startsWith(SplitTagSettingsPrefix) == true
    val ratio = if (settings) settingsSplitRatio(widthDp) else shellSplitRatio(widthDp)
    return SplitAttributes.Builder()
        .setSplitType(SplitAttributes.SplitType.ratio(ratio))
        .setLayoutDirection(params.defaultSplitAttributes.layoutDirection)
        .apply {
            if (divider != null && !settings) setDividerAttributes(divider)
            if (animationParams != null) setAnimationParams(animationParams)
        }
        .build()
}
