package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.gpo.yoin.player.sing.TensorGeneration
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
    fun should_showCleanOptimizationLine_and_openTensorPageAfterTenTaps() {
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
        var openedEasterEgg = 0
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
                                    onOpenTensorEasterEgg = { openedEasterEgg++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        val optimizedText = context.getString(R.string.labs_sing_optimized_for, "Google Tensor G6")
        rule.onNodeWithText(optimizedText, useUnmergedTree = true).assertExists()

        // 9 taps should not open yet; 10th tap opens the Tensor page.
        repeat(9) {
            rule.onNodeWithText("Lyra Sing", useUnmergedTree = true).performClick()
        }
        rule.runOnIdle { assertEquals(0, openedEasterEgg) }

        rule.onNodeWithText("Lyra Sing", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, openedEasterEgg) }

        // Remove button still works cleanly without interfering.
        rule.onNodeWithText(context.getString(R.string.labs_sing_remove), useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_showGpuAcceleration_and_notOpenTensorPage_onNonTensorDevice() {
        val genericOverview = SingModelOverview(
            status = SingModelDownloadStatus.NotDownloaded,
            expectedBuildName = "fp16",
            expectedPackGroup = SingModelOverview.GROUP_OTHER,
            expectedDownloadBytes = SingModelOverview.GENERIC_PACK_BYTES,
            soc = "Qualcomm SM8750",
        )
        var downloads = 0
        var openedEasterEgg = 0
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
                                    onOpenTensorEasterEgg = { openedEasterEgg++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(context.getString(R.string.labs_sing_accelerated_gpu), useUnmergedTree = true)
            .assertExists()

        repeat(10) {
            rule.onNodeWithText("Lyra Sing", useUnmergedTree = true).performClick()
        }
        rule.runOnIdle { assertEquals(0, openedEasterEgg) }

        rule.onNodeWithText(context.getString(R.string.labs_sing_download), useUnmergedTree = true).performClick()
        rule.runOnIdle { assertEquals(1, downloads) }
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_showMediaTekOptimization_and_notOpenTensorEasterEgg() {
        val mediatekOverview = SingModelOverview(
            status = SingModelDownloadStatus.Ready,
            expectedBuildName = "Dimensity 9400 AOT",
            expectedPackGroup = "MediaTek_Dimensity_9400",
            installedGroup = "MediaTek_Dimensity_9400",
            installedBuilds = listOf(
                SingInstalledBuild("Dimensity 9400 AOT", 7_100_000L, "abcdef12", listOf(SingAccelerator.NPU)),
            ),
            totalInstalledBytes = 7_100_000L,
            soc = "Mediatek Dimensity 9400",
            lastRun = SingBackendRun(SingAccelerator.NPU, model = "Dimensity 9400 AOT", soc = "Mediatek Dimensity 9400"),
        )
        var openedEasterEgg = 0
        rule.setContent {
            YoinTheme {
                SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingsGroup(title = "Labs") {
                            item {
                                LyraSingLabsRow(
                                    overview = mediatekOverview,
                                    onDownload = {},
                                    onRemove = {},
                                    onOpenTensorEasterEgg = { openedEasterEgg++ },
                                )
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()

        val expected = context.getString(R.string.labs_sing_optimized_for, "MediaTek Dimensity 9400")
        rule.onNodeWithText(expected, useUnmergedTree = true).assertExists()

        repeat(10) {
            rule.onNodeWithText("Lyra Sing", useUnmergedTree = true).performClick()
        }
        rule.runOnIdle { assertEquals(0, openedEasterEgg) }
    }

    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun should_renderTensorEasterEggPage_and_acceptRapidChipTaps() {
        var backed = 0
        rule.setContent {
            YoinTheme {
                TensorEasterEggContent(
                    generation = TensorGeneration.G5,
                    onBackClick = { backed++ },
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Google Tensor G5").assertExists()
        val chipCd = context.getString(R.string.labs_sing_tensor_chip_cd, "Google Tensor G5")
        repeat(4) {
            rule.onNodeWithContentDescription(chipCd).performClick()
        }
        rule.waitForIdle()
        assertEquals(0, backed)
    }
}
