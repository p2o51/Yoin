package com.gpo.yoin.ui.landing

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
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
@Config(qualifiers = "w320dp-h900dp")
class SigningFingerprintRowsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun should_showEveryFingerprintInAnOriginalCopyRowWithoutNewTitles_when_multipleCertificatesExist() {
        show(FINGERPRINTS)

        FINGERPRINTS.forEachIndexed { index, fingerprint ->
            rule.onNodeWithText(fingerprint, useUnmergedTree = true).assertIsDisplayed()
            copyButton(index).assertIsDisplayed().assert(hasText(context.getString(R.string.landing_copy)))
        }
        rule.onAllNodes(hasClickAction()).assertCountEquals(FINGERPRINTS.size)
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).assertCountEquals(FINGERPRINTS.size * 2)
    }

    @Test
    fun should_copyOnlyTheChosenFingerprintAndKeepOtherRowsIndependent_when_eachButtonIsClicked() {
        show(FINGERPRINTS)
        rule.mainClock.autoAdvance = false

        FINGERPRINTS.forEachIndexed { index, fingerprint ->
            copyButton(index).performClick()
            // Publish the snapshot change, then apply its recomposition while the reset timer stays paused.
            rule.mainClock.advanceTimeBy(32)

            rule.runOnIdle {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                assertEquals(1, clip?.itemCount)
                assertEquals(fingerprint, clip?.getItemAt(0)?.coerceToText(context)?.toString())
            }
            FINGERPRINTS.indices.forEach { row ->
                val label = if (row <= index) R.string.landing_copied else R.string.landing_copy
                copyButton(row).assert(hasText(context.getString(label)))
            }
        }
    }

    @Test
    fun should_showNoRowsOrCopyButtons_when_noCertificatesCanBeRead() {
        show(emptyList())

        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    private fun copyButton(index: Int) = rule.onAllNodes(hasClickAction())[index]

    private fun show(fingerprints: List<String>) {
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(280.dp)) {
                    SigningFingerprintRows(fingerprints)
                }
            }
        }
        rule.waitForIdle()
    }

    private companion object {
        val FINGERPRINTS = listOf(
            "7E:EF:1F:55:23:64:EC:48:53:D9:FB:18:FF:C7:B1:80:E0:99:2E:DF",
            "3C:05:37:B6:D0:D2:D4:8F:8C:12:C7:B5:7F:08:B5:7D:97:69:9D:2A",
            "5E:6A:EC:11:F0:22:A9:DD:4F:DD:C4:D8:74:63:1B:43:B3:C1:74:B3",
        )
    }
}
