package com.gpo.yoin.ui.landing.guide

import android.app.RemoteAction
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The guide window's actions as the system receives them ([guideRemoteActions]): what the PiP menu draws, greys
 * and fires, rather than the slot table behind it.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectGuideRemoteActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The command the action broadcasts when tapped, read back from its PendingIntent. */
    private fun RemoteAction.command(): String? {
        val sent = shadowOf(actionIntent).savedIntent
        assertEquals(ACTION_GUIDE, sent.action)
        assertEquals(context.packageName, sent.`package`)
        return sent.getStringExtra(EXTRA_COMMAND)
    }

    @Test
    fun should_handTheSystemTheOwnersFixedRowWithItsGreyedPlaces_when_spotifyGuideStepsAreShown() {
        // "-" = isEnabled off (greyed, taps ignored). Same table as the owner's ruling, read off the RemoteActions.
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
        val actual = SpotifyGuideSteps.indices.map { step ->
            val actions = guideRemoteActions(context, GuideKind.Spotify, step, max = 3)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, actions.size)
            actions.joinToString(" ") { action -> action.command() + if (action.isEnabled) "" else "-" }
        }
        assertEquals(expected, actual)
    }

    @Test
    fun should_drawEachPlaceWithItsOwnIconAndLabel_when_spotifyGuideStepsAreShown() {
        SpotifyGuideSteps.indices.forEach { step ->
            val slots = guideActionSlots(GuideKind.Spotify, step)
            val actions = guideRemoteActions(context, GuideKind.Spotify, step, max = 3)
            slots.zip(actions).forEach { (slot, action) ->
                assertEquals(slot.command.icon, action.icon.resId)
                val label = context.getString(requireNotNull(slot.label))
                assertEquals(label, action.contentDescription.toString())
                assertEquals(label, action.title.toString())
            }
            assertEquals(R.drawable.ic_guide_next, actions[GUIDE_NEXT_SLOT].icon.resId)
        }
    }

    @Test
    fun should_handTheSystemThreeBlankSilentPlaces_when_appleMusicGuideIsShown() {
        // Never an empty list: the system would put Yoin's own playback buttons there (⏭ skips a track).
        AppleMusicGuideSteps.indices.forEach { step ->
            val actions = guideRemoteActions(context, GuideKind.AppleMusic, step, max = 3)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, actions.size)
            actions.forEach { action ->
                assertFalse(action.isEnabled)
                assertEquals(R.drawable.ic_guide_blank, action.icon.resId)
                // Nothing for TalkBack to read out on a place nobody can see.
                assertTrue(action.contentDescription.isEmpty())
                assertEquals(GuideCommand.None.name, action.command())
            }
        }
    }

    @Test
    fun should_neverHandTheSystemMoreThanItsLimit_when_itTakesFewerActions() {
        (1..GUIDE_ACTION_SLOT_COUNT).forEach { max ->
            SpotifyGuideSteps.indices.forEach { step ->
                val actions = guideRemoteActions(context, GuideKind.Spotify, step, max)
                assertEquals(max, actions.size)
                assertEquals(GuideCommand.Next.name, actions.last().command())
                assertEquals(step != SpotifyGuideSteps.lastIndex, actions.last().isEnabled)
            }
            assertEquals(max, guideRemoteActions(context, GuideKind.AppleMusic, 0, max).size)
        }
    }
}
