package com.gpo.yoin.ui.navigation.keyboard

import android.view.KeyEvent

/**
 * The shell's keyboard shortcuts for a tablet keyboard or a desktop window:
 * iOS's set (YoinCommands.swift: Controls and Go), with Ctrl where iOS uses
 * ⌘ and Alt where it uses ⌥. Android has no menu bar — the system's shortcut
 * list (Meta + /) shows them. Esc is the platform's own back key already.
 */
enum class ShellShortcut {
    PlayPause,
    Next,
    Previous,
    Shuffle,
    Like,
    NowPlaying,
    Search,
    Back,
    Settings,
    Home,
    Library,
    AllMemories,
}

/** A key with exactly these modifiers held. */
data class ShortcutChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    /** The chord as [android.view.KeyboardShortcutInfo] takes it. */
    val metaState: Int
        get() = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON else 0)
}

val ShellShortcut.chord: ShortcutChord
    get() = when (this) {
        ShellShortcut.PlayPause -> ShortcutChord(KeyEvent.KEYCODE_SPACE)
        ShellShortcut.Next -> ShortcutChord(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true)
        ShellShortcut.Previous -> ShortcutChord(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true)
        ShellShortcut.Shuffle -> ShortcutChord(KeyEvent.KEYCODE_S, ctrl = true, alt = true)
        ShellShortcut.Like -> ShortcutChord(KeyEvent.KEYCODE_D, ctrl = true)
        ShellShortcut.NowPlaying -> ShortcutChord(KeyEvent.KEYCODE_P, ctrl = true, shift = true)
        ShellShortcut.Search -> ShortcutChord(KeyEvent.KEYCODE_K, ctrl = true)
        ShellShortcut.Back -> ShortcutChord(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl = true)
        ShellShortcut.Settings -> ShortcutChord(KeyEvent.KEYCODE_COMMA, ctrl = true)
        ShellShortcut.Home -> ShortcutChord(KeyEvent.KEYCODE_1, ctrl = true)
        ShellShortcut.Library -> ShortcutChord(KeyEvent.KEYCODE_2, ctrl = true)
        ShellShortcut.AllMemories -> ShortcutChord(KeyEvent.KEYCODE_3, ctrl = true)
    }

/**
 * The shortcut a key press means, or null. Modifiers must match exactly
 * (Ctrl+Shift+→ is not Next), and any press with Meta held belongs to the
 * system's own shortcuts.
 */
fun shellShortcutFor(
    keyCode: Int,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    meta: Boolean,
): ShellShortcut? {
    if (meta) return null
    val pressed = ShortcutChord(keyCode, ctrl = ctrl, alt = alt, shift = shift)
    return ShellShortcut.entries.firstOrNull { it.chord == pressed }
}

/**
 * Whether [this] does anything now; one that doesn't leaves the key to the
 * window (as iOS greys the menu item out). The player's keys need a song;
 * Like needs a service that keeps favorites; Back needs something open in
 * the shell to close (at the root it never leaves the app).
 */
fun ShellShortcut.isAvailable(
    hasTrack: Boolean,
    nowPlayingOpen: Boolean,
    canLike: Boolean,
    canGoBack: Boolean,
): Boolean = when (this) {
    ShellShortcut.PlayPause,
    ShellShortcut.Next,
    ShellShortcut.Previous,
    ShellShortcut.Shuffle,
    -> hasTrack
    ShellShortcut.Like -> hasTrack && canLike
    ShellShortcut.NowPlaying -> hasTrack || nowPlayingOpen
    ShellShortcut.Back -> canGoBack
    ShellShortcut.Search,
    ShellShortcut.Settings,
    ShellShortcut.Home,
    ShellShortcut.Library,
    ShellShortcut.AllMemories,
    -> true
}

/** What the shell does with a shortcut: true when it acted (the key is then consumed). */
fun interface ShellShortcutSink {
    fun perform(shortcut: ShellShortcut): Boolean
}

/** The shell window: the shell composition hands it its [ShellShortcutSink] while composed. */
interface ShellShortcutHost {
    var shellShortcutSink: ShellShortcutSink?
}
