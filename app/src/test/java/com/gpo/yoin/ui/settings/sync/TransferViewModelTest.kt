package com.gpo.yoin.ui.settings.sync

import app.cash.turbine.test
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.data.transfer.memories.MatchStatus
import com.gpo.yoin.data.transfer.memories.MemorySource
import com.gpo.yoin.testutil.MainDispatcherRule
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TransferViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ops = FakeMemoriesOps()
    private val controller = FakeCloudSyncController(initial = CloudSyncSamples.proto(0L), clock = { 0L })

    private fun viewModel(args: TransferArgs = TransferArgs()) = TransferViewModel(ops, controller, args)

    @Test
    fun should_startAtTheSource_when_nothingIsPreset() {
        val vm = viewModel()
        val state = vm.state.value
        assertEquals(TransferStep.Source, state.step)
        assertEquals(TransferStep.Source, state.firstStep)
        assertEquals(4, state.sources.size)
        assertFalse(vm.back())
    }

    @Test
    fun should_startAtTheTarget_when_theSourceIsPreset_andListOnlyOtherLocalProfiles() {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old"))
        val state = vm.state.value
        assertEquals(TransferStep.Target, state.step)
        assertEquals("cloud:sp-old", state.sourceKey)
        assertTrue(state.source!!.cloudOnly)
        assertEquals(listOf("local:nav", "local:am", "local:sp"), state.targets.map { it.key })
        // Counts come from the cloud summary.
        assertEquals(212, state.source!!.counts?.notes)
    }

    @Test
    fun should_copyStraightAway_when_theIdsCarryOver() = runTest {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old"))
        vm.pickTarget("local:sp")
        vm.next()

        var state = vm.state.value
        assertEquals(TransferStep.Summary, state.step)
        assertFalse(state.needsMatching)
        assertEquals(4, state.stepCount)
        assertEquals(2, state.remainingItems)
        assertEquals(TransferKind.Direct, transferKindOf(state.source!!.account, state.target!!.account))

        vm.start()

        state = vm.state.value
        assertEquals(TransferStep.Done, state.step)
        assertEquals(listOf("draft", "run"), ops.calls)
        assertEquals(2, state.result!!.copied.total)
    }

    @Test
    fun should_offerNothing_when_everythingWasCopiedBefore() = runTest {
        ops.draft = { source, target -> TransferSamples.directPlan(source, target, previouslyCopied = 2) }
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp"))

        val state = vm.state.value
        assertEquals(TransferStep.Summary, state.step)
        assertEquals(TransferStep.Summary, state.firstStep)
        assertEquals(0, state.remainingItems)
        vm.start()
        assertEquals(TransferStep.Summary, vm.state.value.step)
        assertFalse("run" in ops.calls)
    }

    @Test
    fun should_matchThenReview_withUnsureSongsPreselectedButUnchecked() = runTest {
        ops.draft = TransferSamples::unmatchedDraft
        val vm = viewModel(TransferArgs(sourceProfileId = "nav", targetProfileId = "am"))
        assertTrue(vm.state.value.needsMatching)
        assertEquals(5, vm.state.value.stepCount)

        vm.start()

        val state = vm.state.value
        assertEquals(TransferStep.Review, state.step)
        assertEquals(2, state.progressDone)
        val rows = state.rows
        assertEquals(ReviewStatus.Matched, rows[0].status)
        assertTrue(rows[0].checked)
        assertEquals(ReviewStatus.Uncertain, rows[1].status)
        assertFalse(rows[1].checked)
        assertEquals("Everyday", rows[1].choice?.title)
        assertEquals(ReviewStatus.NotFound, rows[2].status)
        assertFalse(rows[2].checkable)
        assertEquals(1, state.songsToCopy)
    }

    @Test
    fun should_copyThePicks_andLeaveTheRestInTheSource() = runTest {
        ops.draft = TransferSamples::unmatchedDraft
        val vm = viewModel(TransferArgs(sourceProfileId = "nav", targetProfileId = "am"))
        vm.start()

        vm.choose(position = 1, index = 1)
        vm.setChecked(position = 0, checked = false)
        vm.setChecked(position = 2, checked = true)
        assertEquals(1, vm.state.value.songsToCopy)

        vm.copyReviewed()

        val plan = ops.ranWith!!
        assertNull(plan.tracks[0].chosen)
        assertEquals(MatchStatus.Picked, plan.tracks[1].status)
        assertEquals("Everyday (Live)", plan.tracks[1].chosen?.title)
        assertNull(plan.tracks[2].chosen)
        assertEquals(TransferStep.Done, vm.state.value.step)
    }

    @Test
    fun should_keepAMatchAsMatched_when_itStaysChecked() {
        val plan = TransferSamples.matchedPlan(MemorySource.LocalProfile("nav"), "am")
        val applied = applyReview(plan, reviewRowsOf(plan))
        assertEquals(MatchStatus.Matched, applied.tracks[0].status)
        assertEquals(MatchStatus.Uncertain, applied.tracks[1].status)
    }

    @Test
    fun should_fail_when_theTargetServiceCantBeReached() = runTest {
        ops.draft = TransferSamples::unmatchedDraft
        ops.catalogAvailable = false
        val vm = viewModel(TransferArgs(sourceProfileId = "nav", targetProfileId = "am"))

        vm.start()

        assertEquals(TransferStep.Failed, vm.state.value.step)
        assertEquals(TransferFailure.TargetUnreachable, vm.state.value.failure)
        assertFalse("run" in ops.calls)
    }

    @Test
    fun should_goBackAStep_andClose_fromTheFirstStep() {
        val vm = viewModel()
        vm.pickSource("local:nav")
        vm.next()
        assertEquals(TransferStep.Target, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(TransferStep.Source, vm.state.value.step)
        assertEquals("local:nav", vm.state.value.sourceKey)
        assertFalse(vm.back())
    }

    @Test
    fun should_dropATargetEqualToTheNewSource() {
        val vm = viewModel(TransferArgs(sourceProfileId = "nav"))
        vm.pickTarget("local:sp")
        vm.changeSource()
        vm.pickSource("local:sp")
        assertNull(vm.state.value.targetKey)
    }

    @Test
    fun should_removeTheSourceCard_when_askedOnTheDonePage() = runTest {
        val vm = viewModel(TransferArgs(sourceProfileId = "sp", targetProfileId = "nav"))
        vm.start()
        assertEquals(TransferStep.Done, vm.state.value.step)

        vm.events.test {
            vm.setRemoveCard(true)
            vm.finish()
            assertEquals(TransferEvent.Finished(TransferFinishMessage.CardRemoved("Mika")), awaitItem())
        }
        assertEquals(listOf("sp"), ops.removedProfiles)
    }

    @Test
    fun should_notOfferRemovingTheCard_when_theSourceIsOnlyInTheCloud() = runTest {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp"))
        vm.start()
        vm.setRemoveCard(true)
        assertFalse(vm.state.value.removeCard)
        vm.events.test {
            vm.finish()
            assertEquals(TransferEvent.Finished(TransferFinishMessage.Copied("mika.old")), awaitItem())
        }
        assertTrue(ops.removedProfiles.isEmpty())
    }

    @Test
    fun should_removeTheSourceFromTheCloud_when_cleanupIsConfirmed() = runTest {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp"))
        vm.start()

        vm.events.test {
            vm.cleanSource()
            assertEquals(TransferEvent.Message(TransferMessage.Cleaned("mika.old")), awaitItem())
        }
        assertTrue("removeAccountFromCloud(sp-old)" in controller.calls)
        assertTrue(vm.state.value.cleaned)
    }

    @Test
    fun should_reportFailure_when_theCopyThrows() = runTest {
        ops.runResult = Result.failure(IOException("db"))
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp"))
        vm.start()
        assertEquals(TransferStep.Failed, vm.state.value.step)
        assertEquals(TransferFailure.RunFailed, vm.state.value.failure)
    }

    @Test
    fun should_refuseBack_whileCopying() = runTest {
        ops.draft = TransferSamples::unmatchedDraft
        val vm = viewModel(TransferArgs(sourceProfileId = "nav", targetProfileId = "am"))
        vm.start()
        assertEquals(TransferStep.Review, vm.state.value.step)
        assertTrue(vm.back())
        assertEquals(TransferStep.Summary, vm.state.value.step)
    }

    @Test
    fun should_readAPresetCloudSource_evenWhenTheListDoesntNameIt() {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-previous", targetProfileId = "sp"))
        val state = vm.state.value
        assertEquals("cloud:sp-previous", state.sourceKey)
        assertEquals(TransferStep.Summary, state.step)
    }

    @Test
    fun should_countOnlySongsLeftToCopy_when_rerun() {
        val source = MemorySource.CloudAccount("sp-old")
        val fresh = TransferUiState(draft = TransferSamples.directPlan(source, "sp"))
        assertEquals(2, fresh.directSongs)

        // n0 (song t1) went over in an earlier run: only t2 is left.
        val rerun = TransferUiState(
            draft = TransferSamples.directPlan(source, "sp", previouslyCopied = 1).copy(ledger = mapOf("note:n0" to "copied|x")),
        )
        assertEquals(1, rerun.directSongs)
        assertEquals(1, rerun.songsToCopy)
    }

    @Test
    fun should_removeTheCardOnce_when_doneIsPressedTwice() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowOps = object : MemoriesOps by ops {
            override suspend fun removeProfile(profileId: String) {
                gate.await()
                ops.removeProfile(profileId)
            }
        }
        val vm = TransferViewModel(slowOps, controller, TransferArgs(sourceProfileId = "sp", targetProfileId = "nav"))
        vm.start()
        vm.setRemoveCard(true)

        vm.finish()
        vm.finish()
        assertTrue(vm.back())
        assertTrue(vm.state.value.finishing)
        gate.complete(Unit)

        assertEquals(listOf("sp"), ops.removedProfiles)
    }

    @Test
    fun should_removeFromTheCloudOnce_when_cleanupIsConfirmedTwice() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var removals = 0
        val slowController = object : com.gpo.yoin.data.sync.CloudSyncController by controller {
            override suspend fun removeAccountFromCloud(syncProfileId: String): Result<Unit> {
                removals++
                gate.await()
                return Result.success(Unit)
            }
        }
        val vm = TransferViewModel(ops, slowController, TransferArgs(sourceCloudScope = "sp-old", targetProfileId = "sp"))
        vm.start()

        vm.cleanSource()
        assertTrue(vm.state.value.cleaning)
        vm.cleanSource()
        gate.complete(Unit)

        assertEquals(1, removals)
        assertTrue(vm.state.value.cleaned)
        assertFalse(vm.state.value.cleaning)
    }

    @Test
    fun should_keepTheServiceOfAPresetCloudSource_whenTheListDoesntNameIt() {
        val vm = viewModel(TransferArgs(sourceCloudScope = "sp-previous", targetProfileId = "sp", sourceProvider = "spotify"))
        val state = vm.state.value
        assertEquals("spotify", state.source?.account?.provider)
        assertEquals(TransferKind.Direct, transferKindOf(state.source!!.account, state.target!!.account))
    }

    @Test
    fun should_notOfferCopying_when_theReviewLeavesNothingToCopy() = runTest {
        ops.draft = TransferSamples::unmatchedDraft
        val vm = viewModel(TransferArgs(sourceProfileId = "nav", targetProfileId = "am"))
        vm.start()
        assertTrue(vm.state.value.canCopyReviewed)

        vm.setChecked(position = 0, checked = false)

        assertEquals(0, vm.state.value.songsToCopy)
        assertFalse(vm.state.value.canCopyReviewed)
    }
}
