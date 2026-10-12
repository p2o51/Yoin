package com.gpo.yoin.ui.landing.guide

import android.app.ActivityOptions
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** The command the action sends when tapped, read back from its PendingIntent. */
    private fun RemoteAction.command(): String? {
        val pending = shadowOf(actionIntent)
        val sent = pending.savedIntent
        if (pending.isActivityIntent) {
            // Only "back to Yoin" starts an activity: the guide window itself, which the system then expands.
            assertTrue(sent.isGuideReturn())
            assertEquals(ConnectGuideActivity::class.java.name, sent.component?.className)
            return GuideCommand.Return.name
        }
        assertTrue(pending.isBroadcastIntent)
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

    @Suppress("DEPRECATION") // MODE_BACKGROUND_ACTIVITY_START_ALLOWED below 36
    @Test
    fun should_launchTheGuideWindowNotYoin_when_backToYoinIsTapped() {
        // P10-1: a broadcast left the start of Yoin to a window that only floats, which Android 17 blocks.
        val actions = guideRemoteActions(context, GuideKind.Spotify, SpotifyGuideSteps.lastIndex, max = 3)
        val back = actions[1]
        assertTrue(back.isEnabled)
        val pending = shadowOf(back.actionIntent)
        assertTrue(pending.isActivityIntent)
        assertTrue(pending.isImmutable)
        val sent = pending.savedIntent
        assertEquals(ConnectGuideActivity::class.java.name, sent.component?.className)
        assertNotEquals(MainActivity::class.java.name, sent.component?.className)
        assertTrue(sent.isGuideReturn())
        assertTrue(sent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // The creator opts in to lending its own right to start activities (Android 15 on asks for it).
        val sdk = Build.VERSION.SDK_INT
        val expectedMode = when {
            sdk >= Build.VERSION_CODES.BAKLAVA -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
            sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }
        assertEquals(expectedMode, pending.options?.getInt(CREATOR_MODE_KEY))
        // ‹ and › still go to the open window as broadcasts.
        assertTrue(shadowOf(actions[0].actionIntent).isBroadcastIntent)
        assertTrue(shadowOf(actions[2].actionIntent).isBroadcastIntent)
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

    private companion object {
        /** Where ActivityOptions.toBundle() keeps the creator's mode (framework source, ActivityOptions.java). */
        const val CREATOR_MODE_KEY = "android.activity.pendingIntentCreatorBackgroundActivityStartMode"
    }
}
