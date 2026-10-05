package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class LyricsShareCardTest {

    @get:Rule
    val rule = createComposeRule()

    private val longPick = (0 until MaxSelectedLyricLines).map { i ->
        LyricLine(
            startMs = i * 4_000L,
            text = "A long enough lyric line number $i to wrap across the card",
            translation = "第 $i 行的译文，也够长",
        )
    }

    @Test
    fun should_exportCardsAtSpotoolfysPosterWidth_when_laidOutAtAnyDensity() {
        // 360dp laid out at 2.0 and at 2.625 density.
        assertEquals(IntSize(1440, 2000), lyricsShareExportSize(LyricsShareFormat.Card, 720f, 1000f))
        assertEquals(IntSize(1440, 1920), lyricsShareExportSize(LyricsShareFormat.Card, 945f, 1260f))
    }

    @Test
    fun should_exportStoriesAt1080By1920_when_laidOutAtAnyDensity() {
        assertEquals(IntSize(1080, 1920), lyricsShareExportSize(LyricsShareFormat.Story, 720f, 1280f))
        assertEquals(IntSize(1080, 1920), lyricsShareExportSize(LyricsShareFormat.Story, 945f, 1680f))
    }

    @Test
    fun should_neverEnlarge_when_theContentAlreadyFits() {
        assertEquals(1f, lyricsShareFitScale(300, 400, 600, Constraints.Infinity), 0f)
    }

    @Test
    fun should_scaleByTheTighterBound_when_theContentIsTooBig() {
        assertEquals(0.5f, lyricsShareFitScale(400, 400, 200, 1_000), 0.0001f)
        assertEquals(0.25f, lyricsShareFitScale(400, 800, 1_000, 200), 0.0001f)
    }

    @Test
    fun should_keepTheTitleButDropForbiddenCharacters_when_namingTheFile() {
        assertEquals(
            "Yoin lyrics - AC DC Back in Black - 42.png",
            lyricsShareFileName("AC/DC: Back in Black?", 42L),
        )
        assertEquals("Yoin lyrics - 晴天 - 7.png", lyricsShareFileName("  晴天 ", 7L))
    }

    @Test
    fun should_nameByTimeAlone_when_theTitleIsBlank() {
        assertEquals("Yoin lyrics 9.png", lyricsShareFileName(" / ", 9L))
        assertEquals("Yoin lyrics 9.png", lyricsShareFileName(null, 9L))
    }

    @Test
    fun should_readSpotoolfysFourPairings_when_resolvingTones() {
        val scheme = lightColorScheme(
            primary = Color(0xFF000001),
            primaryContainer = Color(0xFF000002),
            onPrimaryContainer = Color(0xFF000003),
            tertiary = Color(0xFF000004),
            onTertiary = Color(0xFF000005),
            tertiaryContainer = Color(0xFF000006),
            onTertiaryContainer = Color(0xFF000007),
        )

        val soft = LyricsCardTone.Soft.colors(scheme)
        assertEquals(scheme.primaryContainer, soft.card)
        assertEquals(scheme.onPrimaryContainer, soft.content)
        assertEquals(scheme.primary, soft.backdrop)
        val deep = LyricsCardTone.Deep.colors(scheme)
        assertEquals(scheme.onPrimaryContainer, deep.card)
        assertEquals(scheme.primaryContainer, deep.content)
        assertEquals(scheme.tertiary, LyricsCardTone.Vivid.colors(scheme).card)
        assertEquals(scheme.onTertiary, LyricsCardTone.Vivid.colors(scheme).content)
        assertEquals(scheme.tertiaryContainer, LyricsCardTone.Mist.colors(scheme).card)
        assertEquals(scheme.onTertiaryContainer, LyricsCardTone.Mist.colors(scheme).content)
    }

    @Test
    fun should_keepFullSizeType_when_thePickFitsTheStory() {
        assertEquals(1f, lyricsStoryZoom { true }, 0f)
    }

    @Test
    fun should_zoomOutJustEnough_when_thePickIsTooTall() {
        // Fits from 0.62 down (smaller zoom = wider, shorter layout).
        val zoom = lyricsStoryZoom { it <= 0.62f }

        assertTrue("zoom $zoom fits", zoom <= 0.62f)
        assertEquals(0.62f, zoom, 0.01f)
        assertEquals(LyricsStoryMinZoom, lyricsStoryZoom { false }, 0f)
    }

    @Test
    fun should_keepAFullPickInsideTheStory_when_itIsTallerThanTheFrame() {
        rule.setContent {
            YoinTheme {
                LyricsShareImage(
                    format = LyricsShareFormat.Story,
                    tone = LyricsCardTone.Soft,
                    lines = longPick,
                    showTranslation = true,
                    songTitle = "Song",
                    artist = "Artist",
                    coverArtUrl = null,
                    modifier = Modifier.testTag("story"),
                )
            }
        }

        val story = rule.onNodeWithTag("story").getBoundsInRoot()
        assertEquals(360f, (story.right - story.left).value, 0.5f)
        assertEquals(640f, (story.bottom - story.top).value, 0.5f)
        val first = rule.onNodeWithText(longPick.first().text).getBoundsInRoot()
        val last = rule.onNodeWithText(longPick.last().translation!!).getBoundsInRoot()
        assertTrue("first line starts inside the story: ${first.top}", first.top >= story.top)
        assertTrue("last translation ends inside the story: ${last.bottom}", last.bottom <= story.bottom)
    }

    @Test
    fun should_zoomOutAtFullWidth_when_aTypicalLongPickIsTallerThanTheStory() {
        val typical = (0 until 10).map { i ->
            LyricLine(startMs = i * 4_000L, text = "Placeholder lyric text, line $i", translation = "第 $i 行")
        }
        rule.setContent {
            YoinTheme {
                LyricsShareImage(
                    format = LyricsShareFormat.Story,
                    tone = LyricsCardTone.Soft,
                    lines = typical,
                    showTranslation = true,
                    songTitle = "Song",
                    artist = "Artist",
                    coverArtUrl = null,
                )
            }
        }

        // Zoomed out, not shrunk: the card still spans the story's width
        // (360dp minus its 28dp margins), only its type is smaller.
        val card = rule.onNodeWithTag(LyricsShareCardTag).getBoundsInRoot()
        assertEquals(304f, (card.right - card.left).value, 1f)
        assertTrue("card fits the frame: ${card.bottom}", card.bottom.value <= 640f - 72f + 0.5f)
        // It did have to zoom: the first lyric line is drawn smaller than its style.
        val line = rule.onNodeWithText(typical.first().text).getBoundsInRoot()
        assertTrue("line drawn at ${line.bottom - line.top}", (line.bottom - line.top).value < 28f)
    }

    @Test
    fun should_fitThePreviewToTheSheet_when_theCardIsWiderThanIt() {
        rule.setContent {
            YoinTheme {
                Box(Modifier.width(300.dp)) {
                    LyricsShareImage(
                        format = LyricsShareFormat.Card,
                        tone = LyricsCardTone.Soft,
                        lines = longPick.take(2),
                        showTranslation = false,
                        songTitle = "Song",
                        artist = "Artist",
                        coverArtUrl = null,
                        modifier = Modifier
                            .testTag("card")
                            .scaleDownToFit(),
                    )
                }
            }
        }

        val card = rule.onNodeWithTag("card").getBoundsInRoot()
        assertEquals(300f, (card.right - card.left).value, 0.5f)
    }
}
