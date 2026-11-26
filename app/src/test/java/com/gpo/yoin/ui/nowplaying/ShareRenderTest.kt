package com.gpo.yoin.ui.nowplaying

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders of the share images and the share sheet, for design review (not an assertion): light / dark ×
 * English / Chinese, with the long AI credits that once truncated (owner 2026-11-26). Skipped unless
 * `YOIN_SHARE_RENDER_DIR` names a directory to write the PNGs to:
 *
 *     YOIN_SHARE_RENDER_DIR=/tmp/share ./gradlew :app:testDebugUnitTest --tests '*ShareRenderTest*'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h720dp-xhdpi")
class ShareRenderTest(
    private val case: String,
    private val lang: String,
    private val theme: String,
) {

    @get:Rule
    val rule = createComposeRule()

    private val outDir: File? = System.getenv("YOIN_SHARE_RENDER_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test
    fun render() {
        val dir = outDir
        assumeTrue("set YOIN_SHARE_RENDER_DIR to render", dir != null)
        dir!!.mkdirs()
        RuntimeEnvironment.setQualifiers("+${if (lang == "zh") "zh-rCN" else "en-rUS"}")
        RuntimeEnvironment.setQualifiers("+${if (theme == "dark") "night" else "notnight"}")
        val content = longCreditsContent(lang)
        val image = when {
            case.startsWith("sheet-") -> sheet(content)
            else -> {
                val (layoutName, formatName) = case.split('-')
                export(content, ShareLayout.valueOf(layoutName), LyricsShareFormat.valueOf(formatName))
            }
        }
        File(dir, "$case-$lang-$theme.png").outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun export(content: ShareContent, layout: ShareLayout, format: LyricsShareFormat): ImageBitmap {
        var layer: GraphicsLayer? = null
        rule.setContent {
            YoinTheme(darkTheme = theme == "dark") {
                val recording = rememberGraphicsLayer()
                layer = recording
                ShareImage(content = content, layout = layout, format = format, tone = ShareTone.Soft, exportLayer = recording)
            }
        }
        rule.onRoot().captureToImage()
        return runBlocking { checkNotNull(layer).toImageBitmap() }
    }

    /**
     * The sheet's body in the sheet's frame — M3's rounded top, its drag handle, the sheet colour — in one of
     * the owner's states (a ModalBottomSheet is a dialog window of its own, which a capture here misses).
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun sheet(content: ShareContent): ImageBitmap {
        val (tab, layout) = when (case) {
            "sheet-lyrics" -> ShareTab.Lyrics to ShareLayout.LyricsPlayer
            "sheet-about-scrolled", "sheet-about" -> ShareTab.About to ShareLayout.AboutQuote
            else -> ShareTab.Note to ShareLayout.NoteBig
        }
        rule.setContent {
            YoinTheme(darkTheme = theme == "dark") {
                CompositionLocalProvider(LocalShareLayoutStore provides ShareLayoutStore.InMemory()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                            .padding(top = 24.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(BottomSheetDefaults.ExpandedShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            BottomSheetDefaults.DragHandle()
                            ShareSheetBody(
                                content = content,
                                tabs = content.tabs,
                                tab = tab,
                                layout = layout,
                                format = LyricsShareFormat.Card,
                                tone = ShareTone.Soft,
                                layers = remember { mutableMapOf() },
                                wide = false,
                                // A tall phone's preview in a shorter frame: the body scrolls, as on the owner's phone.
                                windowHeight = 860.dp,
                                navBottom = 0.dp,
                                title = "",
                                canSave = true,
                                saveStatus = SaveStatus.Idle,
                                sharing = false,
                                onTab = {},
                                onLayout = {},
                                onFormat = {},
                                onTone = {},
                                onSave = {},
                                onShare = {},
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        if (case == "sheet-about-scrolled") {
            // Part-way down: the tabs and thumbnails slide under the sheet's top.
            rule.onNodeWithTag(SharePreviewBoxTag).performTouchInput { swipeUp(startY = centerY, endY = centerY - 70.dp.toPx()) }
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
        }
        return rule.onRoot().captureToImage()
    }

    companion object {
        private val cases = listOf(
            "AboutReceipt-Card", "AboutQuote-Card", "AboutLiner-Card",
            "AboutReceipt-Story", "AboutQuote-Story", "AboutLiner-Story",
            "sheet-lyrics", "sheet-about", "sheet-about-scrolled", "sheet-note",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1} {2}")
        fun params(): List<Array<Any>> = cases.flatMap { case ->
            listOf("en", "zh").flatMap { lang -> listOf("light", "dark").map { arrayOf<Any>(case, lang, it) } }
        }

        /** The owner's 2026-11-26 screenshots: AI credits far longer than a name. */
        internal fun longCreditsContent(lang: String): ShareContent {
            val zh = lang == "zh"
            val credits = if (zh) {
                listOf(
                    ShareCredit("作词", "沐一（注：部分来源标注为闪电）", ai = true), // i18n-allow: test data
                    ShareCredit("作曲", "沐一（注：部分来源标注为闪电）", ai = true), // i18n-allow: test data
                    ShareCredit("制作人", "田碧川", ai = true), // i18n-allow: test data
                    ShareCredit("创作时间", "2024年4月（单曲于2024年4月19日左右发行）", ai = true), // i18n-allow: test data
                )
            } else {
                listOf(
                    ShareCredit("Lyricist", "Remi Wolf / Jared Solomon (some sources also list Solomonophonic)", ai = true),
                    ShareCredit("Composer", "Remi Wolf / Jared Solomon", ai = true),
                    ShareCredit("Producer", "Remi Wolf / Solomonophonic", ai = true),
                    ShareCredit("Creation Time", "Early 2023, recorded during the 2022–2023 tour", ai = true),
                )
            }
            return PreviewShareContent.copy(
                song = PreviewShareContent.song.copy(
                    title = if (zh) "5:20AM" else "Cinderella",
                    artist = if (zh) "刀酱" else "Remi Wolf", // i18n-allow: test data
                ),
                about = AboutSharePick(
                    credits = credits,
                    heading = if (zh) "关于" else "About", // i18n-allow: test data
                    paragraph = if (zh) {
                        "《5:20AM》是一首在社交媒体上广为流传的流行音乐作品，以其极具仪式感的歌词为核心记忆点，勾勒出当代年轻人对浪漫爱情的渴望。" // i18n-allow: test data
                    } else {
                        "\"Cinderella\" opens Remi Wolf's second album Big Ideas: 70s funk, a stadium whistle and bright horns, " +
                            "a dance song that is also a monologue about saving yourself."
                    },
                ),
            )
        }
    }
}
