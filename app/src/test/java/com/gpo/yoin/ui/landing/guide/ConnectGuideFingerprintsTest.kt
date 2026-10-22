package com.gpo.yoin.ui.landing.guide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

@RunWith(RobolectricTestRunner::class)
class ConnectGuideFingerprintsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<ConnectGuideActivity>>()
    private val fingerprints = listOf("11", "22", "33").map { byte ->
        List(20) { byte }.joinToString(":")
    }

    @After
    fun tearDown() {
        controllers.toList().asReversed().forEach(::destroyGuide)
        GuideKind.entries.forEach(ConnectGuide::closed)
    }

    @Test
    fun should_keepNineSteps_when_oneFingerprintIsAvailable() {
        val steps = spotifyGuideSteps(fingerprints.take(1))

        assertEquals(9, steps.size)
        assertEquals(
            listOf(GuideCopy.RedirectUri, GuideCopy.AppRemoteRedirectUri, GuideCopy.PackageName, GuideCopy.Sha1),
            steps.mapNotNull { it.copies },
        )
        assertEquals(fingerprints.first(), steps[6].fingerprint)
        assertEquals(fingerprints.first(), steps[6].copyValue(context))
        assertEquals(R.string.guide_then_save, steps[6].then)
        assertEquals(R.string.guide_client_id, steps.last().text)
    }

    @Test
    fun should_repeatPackageAndFingerprintStepsInOrder_when_distinctCertificatesAreAvailable() {
        val repeated = listOf(fingerprints[0], fingerprints[1], fingerprints[0], fingerprints[2], fingerprints[1])
        val steps = spotifyGuideSteps(repeated)

        assertEquals(13, steps.size)
        assertEquals(steps, guideSteps(GuideKind.Spotify, repeated))
        assertEquals(fingerprints, steps.mapNotNull { it.fingerprint })
        fingerprints.forEachIndexed { index, fingerprint ->
            val packageStep = steps[5 + index * 2]
            val fingerprintStep = steps[6 + index * 2]
            assertEquals(GuideCopy.PackageName, packageStep.copies)
            assertEquals(context.packageName, packageStep.copyValue(context))
            assertEquals(GuideCopy.Sha1, fingerprintStep.copies)
            assertEquals(fingerprint, fingerprintStep.copyValue(context))
            assertEquals(
                if (index == fingerprints.lastIndex) R.string.guide_then_save else R.string.guide_then_package,
                fingerprintStep.then,
            )
        }
        assertEquals(R.string.guide_save, steps[11].text)
        assertEquals(R.string.guide_client_id, steps[12].text)
    }

    @Test
    fun should_disableOnlyTheUnavailableFingerprintCopy_when_noCertificatesCanBeRead() {
        val steps = spotifyGuideSteps(emptyList())

        assertEquals(9, steps.size)
        assertEquals(GuideCopy.PackageName, steps[5].copies)
        assertEquals(R.string.guide_sha1_unavailable, steps[6].text)
        assertNull(steps[6].fingerprint)
        assertNull(steps[6].copyValue(context))
        val actions = guideActionSlots(GuideKind.Spotify, 6, steps)
        assertEquals(GuideCommand.Copy, actions[1].command)
        assertFalse(actions[1].enabled)
        assertTrue(actions[2].enabled)
        assertEquals(R.string.guide_save, steps[7].text)
        assertEquals(R.string.guide_client_id, steps[8].text)
    }

    @Test
    fun should_keepNextInItsFixedSlotAndReturnOnlyOnTheLastStep_when_theGuideHasMultipleCertificates() {
        val steps = spotifyGuideSteps(fingerprints)

        steps.indices.forEach { index ->
            val slots = guideActionSlots(GuideKind.Spotify, index, steps)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, slots.size)
            assertEquals(GuideCommand.Prev, slots[0].command)
            assertEquals(index > 0, slots[0].enabled)
            assertEquals(GuideCommand.Next, slots[GUIDE_NEXT_SLOT].command)
            assertEquals(index < steps.lastIndex, slots[GUIDE_NEXT_SLOT].enabled)
            assertEquals(if (index == steps.lastIndex) GuideCommand.Return else GuideCommand.Copy, slots[1].command)
            assertEquals(index == steps.lastIndex || steps[index].copyValue(context) != null, slots[1].enabled)
            (1..GUIDE_ACTION_SLOT_COUNT).forEach { max ->
                assertEquals(slots.takeLast(max), fitGuideActionSlots(slots, max))
                val remote = guideRemoteActions(context, GuideKind.Spotify, index, max, steps)
                assertEquals(max, remote.size)
                assertEquals(R.drawable.ic_guide_next, remote.last().icon.resId)
                assertEquals(index < steps.lastIndex, remote.last().isEnabled)
            }
        }
        assertEquals(guideActionSlots(GuideKind.Spotify, 0, steps), guideActionSlots(GuideKind.Spotify, -1, steps))
        assertEquals(
            guideActionSlots(GuideKind.Spotify, steps.lastIndex, steps),
            guideActionSlots(GuideKind.Spotify, Int.MAX_VALUE, steps),
        )
    }

    @Test
    fun should_leaveAppleMusicStepsAndBlankActionsUnchanged_when_spotifyHasMultipleCertificates() {
        val steps = guideSteps(GuideKind.AppleMusic, fingerprints)

        assertEquals(AppleMusicGuideSteps, steps)
        steps.indices.forEach { index ->
            val slots = guideActionSlots(GuideKind.AppleMusic, index, steps)
            assertEquals(GUIDE_ACTION_SLOT_COUNT, slots.size)
            assertTrue(slots.all { it.command == GuideCommand.None && !it.enabled && it.label == null })
        }
    }

    @Test
    fun should_copyEachStepsExactValue_when_theSameCopyPendingIntentIsUsedAcrossCertificateSteps() {
        val steps = spotifyGuideSteps(fingerprints)
        createGuide(savedState(fingerprints))
        val copy = guideRemoteActions(context, GuideKind.Spotify, 6, 3, steps)[1].actionIntent
        val next = guideRemoteActions(context, GuideKind.Spotify, 0, 3, steps)[2].actionIntent
        val copied = mutableListOf<String?>()

        // PendingIntent identity stays command-based; the open activity selects the current step's value.
        for (index in 0 until steps.lastIndex) {
            if (steps[index].copies != null) {
                copy.send()
                idle()
                copied += clipboardText()
            }
            next.send()
            idle()
        }

        assertEquals(
            listOf(SpotifyAuthConfig.REDIRECT_URI, SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI) +
                fingerprints.flatMap { listOf(context.packageName, it) },
            copied,
        )
    }

    @Test
    fun should_restoreTheSameCertificateAndIndex_when_theActivityIsRecreatedFromItsSavedBundle() {
        val original = createGuide(savedState(fingerprints, step = 8))
        val saved = saveState(original)
        assertEquals(ArrayList(fingerprints), saved.getStringArrayList("fingerprints"))
        assertEquals(8, saved.getInt("step"))
        destroyGuide(original)

        val restored = createGuide(saved)
        val steps = spotifyGuideSteps(fingerprints)
        guideRemoteActions(context, GuideKind.Spotify, 8, 3, steps)[1].actionIntent.send()
        idle()

        assertEquals(fingerprints[1], clipboardText())
        assertEquals(saved.getStringArrayList("fingerprints"), saveState(restored).getStringArrayList("fingerprints"))
        assertEquals(8, saveState(restored).getInt("step"))
    }

    @Test
    fun should_preserveAnEmptyCertificateSnapshotAndNotCopy_when_theUnavailableStepIsRestored() {
        val guide = createGuide(savedState(emptyList(), step = 6))
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "unchanged"))
        val actions = guideRemoteActions(context, GuideKind.Spotify, 6, 3, spotifyGuideSteps(emptyList()))

        assertFalse(actions[1].isEnabled)
        actions[1].actionIntent.send()
        idle()

        assertEquals("unchanged", clipboardText())
        assertEquals(emptyList<String>(), saveState(guide).getStringArrayList("fingerprints"))
    }

    @Test
    fun should_clampToTheFirstStep_when_aNegativeStepIsRestored() {
        val guide = createGuide(savedState(fingerprints, step = -5))

        assertEquals(0, saveState(guide).getInt("step"))
    }

    @Test
    fun should_clampToTheLastStepAndIgnoreCopy_when_anOutOfRangeStepIsRestored() {
        val steps = spotifyGuideSteps(fingerprints)
        val guide = createGuide(savedState(fingerprints, step = Int.MAX_VALUE))
        clipboard().setPrimaryClip(ClipData.newPlainText("test", "unchanged"))

        guideRemoteActions(context, GuideKind.Spotify, 6, 3, steps)[1].actionIntent.send()
        idle()

        assertEquals(steps.lastIndex, saveState(guide).getInt("step"))
        assertEquals("unchanged", clipboardText())
    }

    @Test
    fun should_refreshTheSnapshotAndStartOver_when_theGuideIsOpenedAgainNormally() {
        val guide = createGuide(savedState(fingerprints, step = 8))

        guide.newIntent(ConnectGuide.intent(context, GuideKind.Spotify))

        val saved = saveState(guide)
        assertEquals(0, saved.getInt("step"))
        assertEquals(signingCertificateSha1s(context), saved.getStringArrayList("fingerprints"))
    }

    @Test
    fun should_keepTheSnapshotAndStep_when_theReturnActionLaunchesTheGuideAgain() {
        val guide = createGuide(savedState(fingerprints, step = 8))

        guide.newIntent(guideReturnIntent(context))

        val saved = saveState(guide)
        assertEquals(8, saved.getInt("step"))
        assertEquals(fingerprints, saved.getStringArrayList("fingerprints"))
    }

    private fun savedState(fingerprints: List<String>, step: Int = 0): Bundle = Bundle().apply {
        putStringArrayList("fingerprints", ArrayList(fingerprints))
        putInt("step", step)
    }

    private fun createGuide(savedState: Bundle): ActivityController<ConnectGuideActivity> =
        Robolectric.buildActivity(ConnectGuideActivity::class.java, ConnectGuide.intent(context, GuideKind.Spotify))
            .create(savedState)
            .start()
            .resume()
            .also(controllers::add)

    private fun saveState(controller: ActivityController<ConnectGuideActivity>): Bundle = Bundle().also {
        controller.saveInstanceState(it)
    }

    private fun destroyGuide(controller: ActivityController<ConnectGuideActivity>) {
        controller.pause().stop().destroy()
        controllers.remove(controller)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun clipboard(): ClipboardManager = requireNotNull(context.getSystemService(ClipboardManager::class.java))

    private fun clipboardText(): String? = clipboard().primaryClip?.getItemAt(0)?.text?.toString()
}
