package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/**
 * How the top seam under fixed chrome looks ([SeamTop.Chrome]: Library's
 * chips, a detail page's docked band) — the user's choice in Settings ›
 * Motion. Only that seam: the bottom field, Home's status-bar tide and the
 * text fades are the same in every style.
 */
internal enum class SeamTopStyle(
    /** Stable persisted key; never rename. */
    val key: String,
    /** The option's name in Settings. */
    val label: String,
) {
    /** The tide line (SeamTide.kt): graphics sink under two waves. The default. */
    Tide("tide", "Tide line"),

    /** Curve C halftone (dissolve-final §1.1): graphics break into dots over the band. */
    Dots("dots", "Dots"),

    /** 曲奇浪口 (SeamCookie.kt): a wave lip that morphs Cookie → sine → SoftBurst over the exit. */
    Cookie("cookie", "Cookie wave"),
    ;

    /** Text inside a print is lifted out of it near this seam: only where the print itself breaks up. */
    val liftsText: Boolean get() = this != Tide

    companion object {
        val Default = Tide

        /** Unknown or missing keys fall back to [Default]. */
        fun fromKey(key: String?): SeamTopStyle = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * The process-wide top seam style. Snapshot state, read in draw and layer
 * blocks, so changing it in Settings repaints every open seam live (Settings
 * is another Activity in the same process).
 */
internal object SeamTopPreference {
    private const val PREFS_NAME = "yoin_ui_preferences"
    private const val KEY_TOP = "seam_top_style"

    private val state = mutableStateOf(SeamTopStyle.Default)

    @Volatile
    private var loaded = false

    /** The current style. A snapshot read — call it from composition, draw or layer blocks. */
    val style: SeamTopStyle get() = state.value

    /**
     * Reads the stored style once per process; later calls are free. The
     * write goes to the global snapshot, so a discarded composition that
     * called this cannot drop the value while keeping [loaded].
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val stored = SeamTopStyle.fromKey(prefs(context).getString(KEY_TOP, null))
        Snapshot.global { state.value = stored }
        loaded = true
    }

    /** The user's choice: shown at once everywhere, and stored. */
    fun select(context: Context, style: SeamTopStyle) {
        loaded = true
        state.value = style
        prefs(context).edit { putString(KEY_TOP, style.key) }
    }

    /**
     * QA harnesses: shows [style] without storing it. null forgets it, so the
     * stored choice is read again on the next attach.
     */
    fun preview(style: SeamTopStyle?) {
        loaded = style != null
        state.value = style ?: SeamTopStyle.Default
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** The current top seam style for Settings; loads the stored choice before the first read. */
@Composable
internal fun currentSeamTopStyle(): SeamTopStyle {
    val context = LocalContext.current
    val preference = remember(context) { SeamTopPreference.also { it.ensureLoaded(context) } }
    return preference.style
}
