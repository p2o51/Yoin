package com.gpo.yoin.ui.navigation.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellShortcutsTest {

    private fun press(
        keyCode: Int,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
    ) = shellShortcutFor(keyCode, ctrl = ctrl, alt = alt, shift = shift, meta = meta)

    @Test
    fun should_mapIosTwelveShortcuts_when_theirChordsArePressed() {
        assertEquals(ShellShortcut.PlayPause, press(KeyEvent.KEYCODE_SPACE))
        assertEquals(ShellShortcut.Next, press(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true))
        assertEquals(ShellShortcut.Previous, press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertEquals(ShellShortcut.Shuffle, press(KeyEvent.KEYCODE_S, ctrl = true, alt = true))
        assertEquals(ShellShortcut.Like, press(KeyEvent.KEYCODE_D, ctrl = true))
        assertEquals(ShellShortcut.NowPlaying, press(KeyEvent.KEYCODE_P, ctrl = true, shift = true))
        assertEquals(ShellShortcut.Search, press(KeyEvent.KEYCODE_K, ctrl = true))
        assertEquals(ShellShortcut.Back, press(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl = true))
        assertEquals(ShellShortcut.Settings, press(KeyEvent.KEYCODE_COMMA, ctrl = true))
        assertEquals(ShellShortcut.Home, press(KeyEvent.KEYCODE_1, ctrl = true))
        assertEquals(ShellShortcut.Library, press(KeyEvent.KEYCODE_2, ctrl = true))
        assertEquals(ShellShortcut.AllMemories, press(KeyEvent.KEYCODE_3, ctrl = true))
    }

    @Test
    fun should_giveEveryShortcutItsOwnChord_when_listed() {
        val chords = ShellShortcut.entries.map { it.chord }
        assertEquals(ShellShortcut.entries.size, chords.toSet().size)
        ShellShortcut.entries.forEach { shortcut ->
            val chord = shortcut.chord
            assertEquals(shortcut, press(chord.keyCode, chord.ctrl, chord.alt, chord.shift))
        }
    }

    @Test
    fun should_returnNull_when_modifiersDoNotMatchExactly() {
        assertNull(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(press(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = true, shift = true))
        assertNull(press(KeyEvent.KEYCODE_SPACE, ctrl = true))
        assertNull(press(KeyEvent.KEYCODE_S, ctrl = true))
        assertNull(press(KeyEvent.KEYCODE_P, ctrl = true))
        assertNull(press(KeyEvent.KEYCODE_K))
        assertNull(press(KeyEvent.KEYCODE_4, ctrl = true))
    }

    @Test
    fun should_leaveTheKeyToTheSystem_when_metaIsHeld() {
        assertNull(press(KeyEvent.KEYCODE_K, ctrl = true, meta = true))
        assertNull(press(KeyEvent.KEYCODE_SPACE, meta = true))
    }

    @Test
    fun should_carryCtrlAltShiftInMetaState_when_shownInTheSystemList() {
        assertEquals(0, ShellShortcut.PlayPause.chord.metaState)
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
            ShellShortcut.Shuffle.chord.metaState,
        )
        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
            ShellShortcut.NowPlaying.chord.metaState,
        )
    }

    @Test
    fun should_needASong_when_aPlayerShortcutIsPressed() {
        listOf(
            ShellShortcut.PlayPause,
            ShellShortcut.Next,
            ShellShortcut.Previous,
            ShellShortcut.Shuffle,
            ShellShortcut.Like,
            ShellShortcut.NowPlaying,
        ).forEach { shortcut ->
            assertFalse(
                shortcut.name,
                shortcut.isAvailable(hasTrack = false, nowPlayingOpen = false, canLike = true, canGoBack = true),
            )
            assertTrue(
                shortcut.name,
                shortcut.isAvailable(hasTrack = true, nowPlayingOpen = false, canLike = true, canGoBack = true),
            )
        }
    }

    @Test
    fun should_skipLike_when_theServiceKeepsNoFavorites() {
        assertFalse(
            ShellShortcut.Like.isAvailable(hasTrack = true, nowPlayingOpen = true, canLike = false, canGoBack = true),
        )
    }

    @Test
    fun should_closeNowPlaying_when_itIsOpenWithoutASong() {
        assertTrue(
            ShellShortcut.NowPlaying.isAvailable(hasTrack = false, nowPlayingOpen = true, canLike = false, canGoBack = true),
        )
    }

    @Test
    fun should_leaveBackAlone_when_nothingInTheShellIsOpen() {
        assertFalse(
            ShellShortcut.Back.isAvailable(hasTrack = true, nowPlayingOpen = false, canLike = true, canGoBack = false),
        )
        assertTrue(
            ShellShortcut.Back.isAvailable(hasTrack = false, nowPlayingOpen = false, canLike = false, canGoBack = true),
        )
    }

    @Test
    fun should_alwaysAnswer_when_aGoShortcutIsPressed() {
        listOf(
            ShellShortcut.Search,
            ShellShortcut.Settings,
            ShellShortcut.Home,
            ShellShortcut.Library,
            ShellShortcut.AllMemories,
        ).forEach { shortcut ->
            assertTrue(
                shortcut.name,
                shortcut.isAvailable(hasTrack = false, nowPlayingOpen = false, canLike = false, canGoBack = false),
            )
        }
    }

    private fun space(down: Boolean = true, repeatCount: Int = 0) =
        ShellKeyEvent(KeyEvent.KEYCODE_SPACE, down = down, repeatCount = repeatCount)

    @Test
    fun should_togglePlayback_when_spaceIsPressedAndNothingIsTyping() {
        assertEquals(
            ShellKeyRoute.Shortcut(ShellShortcut.PlayPause),
            shellKeyRoute(space(), heldKeyCode = null, typing = false),
        )
    }

    @Test
    fun should_leaveSpaceToTheWindow_when_theUserIsTyping() {
        assertEquals(ShellKeyRoute.Window, shellKeyRoute(space(), heldKeyCode = null, typing = true))
        assertEquals(
            ShellKeyRoute.Window,
            shellKeyRoute(space(repeatCount = 3), heldKeyCode = null, typing = true),
        )
        assertEquals(ShellKeyRoute.Window, shellKeyRoute(space(down = false), heldKeyCode = null, typing = true))
    }

    @Test
    fun should_toggleAndNotClick_when_aNonTextControlHoldsFocus() {
        // A focused card or button is not typing: the press is the shell's before the
        // window sees it, and its repeats and release are swallowed, so the control
        // never gets a press to turn into a click on key-up.
        assertEquals(
            ShellKeyRoute.Shortcut(ShellShortcut.PlayPause),
            shellKeyRoute(space(), heldKeyCode = null, typing = false),
        )
        val held = KeyEvent.KEYCODE_SPACE
        assertEquals(ShellKeyRoute.Swallow, shellKeyRoute(space(repeatCount = 1), held, typing = false))
        assertEquals(ShellKeyRoute.Swallow, shellKeyRoute(space(repeatCount = 2), held, typing = false))
        assertEquals(ShellKeyRoute.Swallow, shellKeyRoute(space(down = false), held, typing = false))
        assertTrue(ShellShortcut.PlayPause.keepsKeyWhenIdle)
    }

    @Test
    fun should_notToggleAgain_when_spaceRepeatsWithoutAHeldShortcut() {
        assertEquals(ShellKeyRoute.Window, shellKeyRoute(space(repeatCount = 1), heldKeyCode = null, typing = false))
    }

    @Test
    fun should_leaveEnterAndDpadToTheFocusedControl_when_pressed() {
        listOf(
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB,
        ).forEach { keyCode ->
            assertEquals(
                ShellKeyRoute.Window,
                shellKeyRoute(ShellKeyEvent(keyCode, down = true), heldKeyCode = null, typing = false),
            )
        }
        assertEquals(
            ShellKeyRoute.Window,
            shellKeyRoute(
                ShellKeyEvent(KeyEvent.KEYCODE_ENTER, down = true),
                heldKeyCode = KeyEvent.KEYCODE_SPACE,
                typing = false,
            ),
        )
    }

    @Test
    fun should_giveCtrlChordsBackToTheWindow_when_theyDoNothingNow() {
        ShellShortcut.entries.filter { it != ShellShortcut.PlayPause }.forEach { shortcut ->
            assertFalse(shortcut.name, shortcut.keepsKeyWhenIdle)
        }
    }
}
