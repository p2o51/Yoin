package com.gpo.yoin.ui.settings.sync

import com.gpo.yoin.shared.portability.BackupReadException
import com.gpo.yoin.shared.portability.ImportTarget
import com.gpo.yoin.testutil.MainDispatcherRule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val ops = FakeMemoriesOps()
    private val viewModel = BackupViewModel(ops, cloudSyncOn = { true })

    @Test
    fun should_showTheSavedFile_when_exportFinishes() = runTest {
        viewModel.export("yoin-backup-2026-11-23.zip", open = { ByteArrayOutputStream() }, size = { 486 * 1024L })

        val state = viewModel.state.value as BackupUiState.Exported
        assertEquals("yoin-backup-2026-11-23.zip", state.fileName)
        assertEquals(486 * 1024L, state.bytes)
        assertEquals(3, state.summary.manifest.accounts.size)
        assertFalse(viewModel.busy)
    }

    @Test
    fun should_fail_when_theFileCantBeOpenedOrWritten() = runTest {
        ops.exportResult = Result.failure(IOException("disk full"))
        viewModel.export("x.zip", open = { ByteArrayOutputStream() })
        assertTrue(viewModel.state.value is BackupUiState.ExportFailed)

        val second = BackupViewModel(ops)
        second.export("x.zip", open = { null })
        assertTrue(second.state.value is BackupUiState.ExportFailed)
    }

    @Test
    fun should_reviewWithEachAccountsDefault_when_aBackupIsRead() = runTest {
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }

        val review = viewModel.state.value as BackupUiState.Review
        assertTrue(review.intoCloud)
        assertEquals(ImportTarget.IntoProfile("nav"), review.choices["s-nav"])
        assertEquals(ImportTarget.CloudOnly, review.choices["sp-old"])
        assertEquals(ImportTarget.Skip, review.choices["am-gone"])
        assertEquals(listOf("sp-old", "am-gone"), review.plan.needsChoice.map { it.account.scope })
    }

    @Test
    fun should_notOfferCloudOnly_when_theAccountWasRemovedFromTheCloud() = runTest {
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }
        val purged = (viewModel.state.value as BackupUiState.Review).plan.accounts.first { it.account.scope == "am-gone" }

        assertEquals(listOf(ImportTarget.IntoProfile("am"), ImportTarget.Skip), purged.targetOptions())
        viewModel.choose("am-gone", ImportTarget.CloudOnly)
        assertEquals(ImportTarget.Skip, (viewModel.state.value as BackupUiState.Review).choices["am-gone"])
        viewModel.choose("am-gone", ImportTarget.IntoProfile("am"))
        assertEquals(ImportTarget.IntoProfile("am"), (viewModel.state.value as BackupUiState.Review).choices["am-gone"])
    }

    @Test
    fun should_importWithTheChoices_when_confirmed() = runTest {
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }
        viewModel.choose("sp-old", ImportTarget.IntoProfile("sp"))

        viewModel.confirmImport()

        assertTrue(viewModel.state.value is BackupUiState.Imported)
        assertEquals(ImportTarget.IntoProfile("sp"), ops.importedWith?.get("sp-old"))
        assertEquals(ImportTarget.IntoProfile("nav"), ops.importedWith?.get("s-nav"))
    }

    @Test
    fun should_explainWhy_when_theFileIsNotABackupOrDamaged() = runTest {
        val cases = mapOf(
            BackupReadException.NotABackup("no manifest") to BackupReadFailure.NotABackup,
            BackupReadException.TooNew(9) to BackupReadFailure.TooNew,
            BackupReadException.ChecksumMismatch() to BackupReadFailure.Checksum,
            IOException("gone") to BackupReadFailure.Unreadable,
        )
        cases.forEach { (error, expected) ->
            ops.readResult = Result.failure(error)
            val vm = BackupViewModel(ops)
            vm.read("bad.zip") { ByteArrayInputStream(ByteArray(0)) }
            assertEquals(expected, (vm.state.value as BackupUiState.ReadFailed).reason)
        }
    }

    @Test
    fun should_keepTheReview_when_theImportFails() = runTest {
        ops.importResult = Result.failure(IOException("db"))
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }

        viewModel.confirmImport()

        assertTrue(viewModel.state.value is BackupUiState.ImportFailed)
    }

    @Test
    fun should_letTheSameAccountBeSkipped_butNotSentElsewhere() = runTest {
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }
        val same = (viewModel.state.value as BackupUiState.Review).plan.accounts.first { it.sameAccount }

        assertEquals(listOf(ImportTarget.IntoProfile("nav"), ImportTarget.Skip), same.targetOptions())
        viewModel.choose("s-nav", ImportTarget.CloudOnly)
        assertEquals(ImportTarget.IntoProfile("nav"), (viewModel.state.value as BackupUiState.Review).choices["s-nav"])
        viewModel.choose("s-nav", ImportTarget.Skip)
        viewModel.confirmImport()
        assertEquals(ImportTarget.Skip, ops.importedWith?.get("s-nav"))
    }

    @Test
    fun should_haveNothingToImport_when_everyAccountIsSkippedAndThereIsNoGlobalData() = runTest {
        ops.readResult = Result.success(TransferSamples.importPlan().copy(globalCounts = emptyMap()))
        viewModel.read("yoin-backup.zip") { ByteArrayInputStream(ByteArray(0)) }
        listOf("s-nav", "sp-old", "am-gone").forEach { viewModel.choose(it, ImportTarget.Skip) }

        val review = viewModel.state.value as BackupUiState.Review
        assertFalse(review.canImport)
        viewModel.confirmImport()
        assertTrue(viewModel.state.value is BackupUiState.Review)
        assertFalse("import" in ops.calls)
    }
}
