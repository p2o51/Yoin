package com.gpo.yoin.ui.tips

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The export and transfer tip: Cloud sync's real 备份与迁移 rows in the demo phone; the dot downloads
 * everything (the real "saved" page), then opens Transfer Memories on the step that picks the account.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h900dp")
class TipCloudSyncSceneTest {

    @get:Rule
    val rule = createComposeRule()

    private val resources get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun should_showTheBackupRows_when_atRest() {
        rule.setContent { YoinTheme { CloudExportTransferDemo(playing = false) } }

        rule.onNodeWithText(resources.getString(R.string.settings_sync_export_title)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.settings_xfer_title)).assertExists()
    }

    @Test
    fun should_downloadThenOpenTheTransfer_when_theDemoPlays() {
        rule.mainClock.autoAdvance = false
        rule.setContent { YoinTheme { CloudExportTransferDemo(playing = true) } }
        rule.mainClock.advanceTimeByFrame()

        // Past the tap on "download all": the finished download's page.
        rule.mainClock.advanceTimeBy(2_400)
        rule.onNodeWithText(resources.getString(R.string.settings_sync_export_done_bar), useUnmergedTree = true)
            .assertExists()

        // Back on the rows, then past the tap on Transfer Memories: the step that asks for the account.
        rule.mainClock.advanceTimeBy(4_200)
        rule.onNodeWithText(resources.getString(R.string.settings_xfer_head_target), useUnmergedTree = true)
            .assertExists()
    }
}
