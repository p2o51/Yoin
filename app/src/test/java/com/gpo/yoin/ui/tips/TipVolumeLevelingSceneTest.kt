package com.gpo.yoin.ui.tips

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.player.ReplayGainMode
import com.gpo.yoin.player.ReplayGainPreference
import com.gpo.yoin.ui.settings.VolumeLevelingPageBody
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Volume leveling tip's demo is the real page over a choice of its own: the page reports a pick instead of
 * saving it, and pressing the little phone never reaches the real setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class TipVolumeLevelingSceneTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_reportOnlyAChangedChoice_when_rowsAreTapped() {
        val picked = mutableListOf<ReplayGainMode>()
        rule.setContent {
            YoinTheme { VolumeLevelingPageBody(mode = ReplayGainMode.Off, onModeChange = picked::add) }
        }

        rule.onNodeWithText("Off").performClick()
        rule.onNodeWithText("By album").performClick()

        rule.runOnIdle { assertEquals(listOf(ReplayGainMode.Album), picked) }
    }

    @Test
    fun should_leaveTheRealSettingAlone_when_theDemoPhoneIsPressed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val saved = ReplayGainPreference.current(context).mode
        rule.setContent { YoinTheme { VolumeLevelingDemo(playing = false) } }

        rule.onNodeWithText("Volume leveling").assertExists()
        rule.onNodeWithText(if (saved == ReplayGainMode.Album) "By track" else "By album").performClick()

        rule.runOnIdle { assertEquals(saved, ReplayGainPreference.current(context).mode) }
    }

    @Config(qualifiers = "zh-rCN-w412dp-h915dp")
    @Test
    fun should_showThePagesOwnChineseLabels_when_theAppUsesSimplifiedChinese() {
        rule.setContent { YoinTheme { VolumeLevelingDemo(playing = false) } }

        rule.onNodeWithText("音量平衡").assertExists()
        rule.onNodeWithText("按曲目").assertExists()
        rule.onNodeWithText("按专辑").assertExists()
    }
}
