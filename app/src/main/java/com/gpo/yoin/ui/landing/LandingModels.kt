package com.gpo.yoin.ui.landing

import androidx.compose.runtime.Immutable
import com.gpo.yoin.ui.component.SeamTopStyle
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.settings.service.SetupService

/**
 * The first-run landing (docs/design.md › 首次引导): one shell-owned surface
 * with an in-page state machine — these steps. Back steps through them; the
 * first step leaves back to the system (first run) or closes the landing
 * (re-run from Settings).
 */
enum class LandingStep {
    Hello,
    Pick,
    About,
    Subsonic,
    Spotify,
    AppleMusic,
    Home,
    Edge,
    Ready,
    ;

    val service: SetupService?
        get() = when (this) {
            Subsonic -> SetupService.Subsonic
            Spotify -> SetupService.Spotify
            AppleMusic -> SetupService.AppleMusic
            else -> null
        }

    companion object {
        fun connectStep(service: SetupService): LandingStep = when (service) {
            SetupService.Subsonic -> Subsonic
            SetupService.Spotify -> Spotify
            SetupService.AppleMusic -> AppleMusic
        }
    }
}

/** First run (no account yet) or re-run from Settings › About (accounts may exist). */
enum class LandingMode { FirstRun, Rerun }

/**
 * The step list for a selection. One connect step per picked service, in the
 * catalog's order; Home only once an account exists to own the layout.
 */
internal fun landingSteps(picked: Set<SetupService>, hasAccount: Boolean): List<LandingStep> = buildList {
    add(LandingStep.Hello)
    add(LandingStep.Pick)
    add(LandingStep.About)
    SetupService.entries.filter { it in picked }.forEach { add(LandingStep.connectStep(it)) }
    if (hasAccount) add(LandingStep.Home)
    add(LandingStep.Edge)
    add(LandingStep.Ready)
}

@Immutable
internal data class LandingUiState(
    val mode: LandingMode,
    val steps: List<LandingStep>,
    val index: Int,
    val picked: Set<SetupService>,
    /** Services an account was added for during this landing. */
    val connected: Set<SetupService>,
    val hasAccount: Boolean,
    val layout: HomeLayout,
    val edge: SeamTopStyle,
    /** "Open Yoin" was pressed: the hand-off into the shell is running. */
    val finishing: Boolean = false,
) {
    val step: LandingStep get() = steps[index.coerceIn(0, steps.lastIndex)]
    val canGoBack: Boolean get() = index > 0
}
