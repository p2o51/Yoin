package com.gpo.yoin.ui.landing.guide

import com.gpo.yoin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectGuideTest {

    private val spotifySteps = spotifyGuideSteps(listOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33"))

    @Test
    fun should_formatFingerprintAsSpotifyTakesIt_when_digestIsGiven() {
        val digest = byteArrayOf(0xE7.toByte(), 0x47, 0x0B, 0x00, 0xFF.toByte())
        assertEquals("E7:47:0B:00:FF", formatFingerprint(digest))
    }

    @Test
    fun should_copyThePackageThenItsFingerprint_when_spotifyGuideReachesAndroidPackages() {
        val copies = spotifySteps.mapNotNull { it.copies }
        assertEquals(
            listOf(GuideCopy.RedirectUri, GuideCopy.AppRemoteRedirectUri, GuideCopy.PackageName, GuideCopy.Sha1),
            copies,
        )
        // Android must be ticked before its package rows show up on the dashboard.
        val apis = spotifySteps.indexOfFirst { "Android" in it.labels }
        val packageStep = spotifySteps.indexOfFirst { it.copies == GuideCopy.PackageName }
        assertTrue(apis in 0 until packageStep)
    }

    @Test
    fun should_endOnTheClientId_when_spotifyGuideIsWalked() {
        assertTrue("Client ID" in spotifySteps.last().labels)
        assertEquals(null, spotifySteps.last().then)
    }

    @Test
    fun should_offerNoCopyActions_when_guidingAppleMusicSignIn() {
        assertTrue(AppleMusicGuideSteps.all { it.copies == null })
        assertTrue(AppleMusicRetryStep in AppleMusicGuideSteps.indices)
    }

    @Test
    fun should_showTheOwnersFixedRow_when_eachSpotifyStepIsShown() {
        // "-" = greyed. ‹ | middle | › on every step; the middle copies where there is a value, and is back to
        // Yoin on the last step, where › is greyed (owner 2026-10-11).
        val expected = listOf(
            "Prev- Copy- Next",
            "Prev Copy- Next",
            "Prev Copy Next",
            "Prev Copy Next",
            "Prev Copy- Next",
            "Prev Copy Next",
            "Prev Copy Next",
            "Prev Copy- Next",
            "Prev Return Next-"
        )
        val actual = spotifySteps.indices.map { step ->
            guideActionSlots(GuideKind.Spotify, step, spotifySteps).joinToString(" ") { slot ->
                slot.command.name + if (slot.enabled) "" else "-"
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun should_keepEveryButtonInItsPlace_when_spotifyGuideMovesBetweenSteps() {
        spotifySteps.indices.forEach { step ->
            val slots = guideActionSlots(GuideKind.Spotify, step, spotifySteps)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, slots.size)
            assertEquals(GuideCommand.Prev, slots[0].command)
            assertEquals(GuideCommand.Next, slots[GUIDE_NEXT_SLOT].command)
            assertEquals(R.drawable.ic_guide_next, slots[GUIDE_NEXT_SLOT].command.icon)
            assertEquals(step > 0, slots[0].enabled)
        }
    }

    @Test
    fun should_copyOnlyWhereTheStepHasAValue_when_spotifyGuideIsWalked() {
        spotifySteps.dropLast(1).forEachIndexed { step, guideStep ->
            val middle = guideActionSlots(GuideKind.Spotify, step, spotifySteps)[1]
            assertEquals(GuideCommand.Copy, middle.command)
            assertEquals(guideStep.copies != null, middle.enabled)
            assertEquals(guideStep.copies?.actionLabel ?: R.string.guide_action_copy, middle.label)
        }
    }

    @Test
    fun should_putReturnInTheMiddleAndGreyNext_when_spotifyGuideIsOnItsLastStep() {
        val slots = guideActionSlots(GuideKind.Spotify, spotifySteps.lastIndex, spotifySteps)
        val returnSlot = slots.indexOfFirst { it.command == GuideCommand.Return }
        assertEquals(1, returnSlot)
        assertNotEquals(GUIDE_NEXT_SLOT, returnSlot)
        assertTrue(slots[returnSlot].enabled)
        assertEquals(R.string.guide_action_back, slots[returnSlot].label)
        assertEquals(GuideCommand.Next, slots[GUIDE_NEXT_SLOT].command)
        assertFalse(slots[GUIDE_NEXT_SLOT].enabled)
        // Tapping on past the end stays there: the step before has no Return where › is.
        val before = guideActionSlots(GuideKind.Spotify, spotifySteps.lastIndex - 1, spotifySteps)
        assertEquals(GuideCommand.Next, before[GUIDE_NEXT_SLOT].command)
        assertTrue(before[GUIDE_NEXT_SLOT].enabled)
    }

    @Test
    fun should_showTheFixedRowWithNothingToCopy_when_eachSpotoolfyStepIsShown() {
        // The user copies the Client ID inside Spotoolfy: the middle stays greyed until it is back to Yoin.
        val expected = listOf(
            "Prev- Copy- Next",
            "Prev Copy- Next",
            "Prev Copy- Next",
            "Prev Return Next-",
        )
        val actual = SpotoolfyGuideSteps.indices.map { step ->
            guideActionSlots(GuideKind.Spotoolfy, step, SpotoolfyGuideSteps).joinToString(" ") { slot ->
                slot.command.name + if (slot.enabled) "" else "-"
            }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun should_askForTheClientIdBeforeTheExport_when_spotoolfyGuideIsWalked() {
        // Back in Yoin the Spotify step then finds the ID on the clipboard; the file arrives through the share sheet.
        val clientId = SpotoolfyGuideSteps.indexOfFirst { R.string.guide_spotoolfy_label_client_id in it.labelRes }
        val export = SpotoolfyGuideSteps.indexOfFirst { R.string.guide_spotoolfy_label_export in it.labelRes }
        assertTrue(clientId in 0 until export)
        assertEquals(listOf(R.string.spotoolfy_share_label), SpotoolfyGuideSteps.last().labelRes)
        assertTrue(SpotoolfyGuideSteps.all { it.copies == null })
    }

    @Test
    fun should_neverHandTheSystemAnEmptyRow_when_guidingAppleMusicSignIn() {
        // An empty row lets the system fall back to Yoin's own playback buttons, whose ⏭ skips a track.
        AppleMusicGuideSteps.indices.forEach { step ->
            val slots = guideActionSlots(GuideKind.AppleMusic, step, AppleMusicGuideSteps)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, slots.size)
            assertTrue(slots.all { it.command == GuideCommand.None && !it.enabled })
            assertTrue(slots.all { it.command.icon == R.drawable.ic_guide_blank })
            // Blank to TalkBack too: nothing for it to read out.
            assertTrue(slots.all { it.label == null })
            assertTrue(fitGuideActionSlots(slots, max = 3).isNotEmpty())
        }
    }

    @Test
    fun should_keepTheWholeRow_when_systemTakesThreeActions() {
        spotifySteps.indices.forEach { step ->
            val slots = guideActionSlots(GuideKind.Spotify, step, spotifySteps)
            assertEquals(slots, fitGuideActionSlots(slots, max = 3))
        }
    }

    @Test
    fun should_dropPreviousFirstAndKeepNextLast_when_systemTakesFewerActions() {
        (1..2).forEach { max ->
            spotifySteps.indices.forEach { step ->
                val slots = guideActionSlots(GuideKind.Spotify, step, spotifySteps)
                val fitted = fitGuideActionSlots(slots, max)
                // Places are only cut from the left, never swapped: › stays the last place, as it is with three.
                assertEquals(slots.takeLast(max), fitted)
                assertEquals(GuideCommand.Next, fitted.last().command)
            }
        }
        val last = guideActionSlots(GuideKind.Spotify, spotifySteps.lastIndex, spotifySteps)
        val lastInTwo = fitGuideActionSlots(last, max = 2).map { it.command }
        assertEquals(listOf(GuideCommand.Return, GuideCommand.Next), lastInTwo)
    }

    @Test
    fun should_keepTheGreyedNextRatherThanReturn_when_systemTakesOneActionOnTheLastStep() {
        // Owner 2026-10-11: tapping on where › was stops at the last step and never closes the guide. With room
        // for one place that place is ›, greyed; the system's expand button is still the way back to Yoin.
        val fitted = fitGuideActionSlots(guideActionSlots(GuideKind.Spotify, spotifySteps.lastIndex, spotifySteps), max = 1)
        assertEquals(1, fitted.size)
        assertEquals(GuideCommand.Next, fitted.single().command)
        assertFalse(fitted.single().enabled)
        assertTrue(fitted.none { it.command == GuideCommand.Return })
    }
}
