package com.gpo.yoin.ui.detail

import android.app.Activity
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.ui.experience.ExperienceSessionStore

/**
 * The shell hand-off this detail window belongs to
 * ([ExperienceSessionStore.beginDetailHandoff]); stamped by [launchDetailFromShell]
 * next to [DETAIL_EXTRA_FROM_SHELL].
 */
const val DETAIL_EXTRA_HANDOFF_GENERATION = "handoffGeneration"

private val Activity.sessionStore: ExperienceSessionStore
    get() = (application as YoinApplication).container.experienceSessionStore

private val Activity.launchedFromShell: Boolean
    get() = intent.getBooleanExtra(DETAIL_EXTRA_FROM_SHELL, false)

/**
 * A detail Activity's onStop. The outer detail launched from the shell
 * publishes the settled tick once its finishing window has left the screen —
 * the shell's reverse-morph backstop. Only that one: with translucent detail
 * windows MainActivity stays STARTED, and an inner detail's tick would clear
 * the shell pose while its outer detail is still on screen.
 */
internal fun Activity.publishShellHandoffSettled() {
    if (!isFinishing || !launchedFromShell || !intent.hasExtra(DETAIL_EXTRA_HANDOFF_GENERATION)) return
    sessionStore.noteDetailWindowSettled(intent.getLongExtra(DETAIL_EXTRA_HANDOFF_GENERATION, 0L))
}

/**
 * A detail Activity's onDestroy. The outer detail launched from the shell
 * retires its own hand-off when it goes for good. Back always clears it
 * earlier; this covers a window destroyed AFTER it had already stopped (the
 * task swiped from recents while music keeps the process alive, a CLEAR_TOP
 * launch from a widget), which never sends the settled tick — the hand-off
 * would outlive the window and the next shell would open shifted 96dp under
 * the detail bar. A recreation keeps it: the same window comes back.
 */
internal fun Activity.retireShellHandoff() {
    if (!isFinishing || isChangingConfigurations) return
    endShellHandoff()
}

/** Explicit page exits retire only this window's hand-off, just like lifecycle cleanup. */
internal fun Activity.endShellHandoff() {
    if (!launchedFromShell || !intent.hasExtra(DETAIL_EXTRA_HANDOFF_GENERATION)) return
    sessionStore.endDetailHandoff(intent.getLongExtra(DETAIL_EXTRA_HANDOFF_GENERATION, 0L))
}
