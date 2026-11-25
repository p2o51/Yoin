package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.player.sing.SingAccelerator
import com.gpo.yoin.player.sing.SingBackendRun
import com.gpo.yoin.player.sing.SingInstalledBuild
import com.gpo.yoin.player.sing.SingModelDownloadStatus
import com.gpo.yoin.player.sing.SingModelOverview
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SingLabsRowTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_showTensorTpuEasterEgg_onlyOnTensorAndCycleQuotesOnTap() {
        val tensorOverview = SingModelOverview(
            status = SingModelDownloadStatus.Ready,
            expectedBuildName = "Tensor G6 AOT",
            expectedPackGroup = SingModelOverview.GROUP_TENSOR_G6,
            expectedDownloadBytes = SingModelOverview.TENSOR_G6_PACK_BYTES,
            expectedSha256Short = "0534f3d7",
            installedOrigin = "Play AI Pack sing_model_pack, group Google_Tensor_G6",
            installedGroup = "Google_Tensor_G6",
            installedBuilds = listOf(
                SingInstalledBuild("Tensor G6 AOT", 8_287_936L, "0534f3d7", listOf(SingAccelerator.NPU)),
                SingInstalledBuild("fp16", 4_465_088L, "176b9dd4", listOf(SingAccelerator.GPU, SingAccelerator.CPU)),
            ),
            totalInstalledBytes = SingModelOverview.TENSOR_G6_PACK_BYTES,
            soc = "Google Tensor G6",
            lastRun = SingBackendRun(SingAccelerator.NPU, model = "Tensor G6 AOT", soc = "Google Tensor G6"),
        )

        var removed = 0
        rule.setContent {
            YoinTheme {
                SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingsGroup(title = "Labs") {
                            item {
                                LyraSingLabsRow(
                                    overview = tensorOverview,
                                    onDownload = {},
                                    onRemove = { removed++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        // Badge is visible on Tensor TPU target; Easter egg card starts collapsed.
        rule.onNodeWithText("Tensor TPU", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(context.getString(R.string.labs_sing_tpu_egg_1), useUnmergedTree = true).assertDoesNotExist()

        // Tap 1 opens the Easter egg card with quote 1.
        rule.onNodeWithText("Tensor TPU", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.labs_sing_tpu_egg_1), useUnmergedTree = true).assertExists()

        // Tap 2 cycles to quote 2 and displays tap counter ×2.
        rule.onNodeWithText("Tensor TPU", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.labs_sing_tpu_egg_2), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("×2", useUnmergedTree = true).assertExists()

        // Remove button triggers removal callback.
        rule.onNodeWithText(context.getString(R.string.labs_sing_remove), useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_hideTensorBadge_and_triggerDownload_onNonTensorDevice() {
        val genericOverview = SingModelOverview(
            status = SingModelDownloadStatus.NotDownloaded,
            expectedBuildName = "fp16",
            expectedPackGroup = SingModelOverview.GROUP_OTHER,
            expectedDownloadBytes = SingModelOverview.GENERIC_PACK_BYTES,
            soc = "Qualcomm SM8750",
        )
        var downloads = 0
        rule.setContent {
            YoinTheme {
                SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingsGroup(title = "Labs") {
                            item {
                                LyraSingLabsRow(
                                    overview = genericOverview,
                                    onDownload = { downloads++ },
                                    onRemove = {},
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Tensor TPU", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.labs_sing_download), useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, downloads) }
    }
}
