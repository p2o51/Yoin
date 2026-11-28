package com.gpo.yoin.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.home.edit.HomeEditController
import com.gpo.yoin.ui.home.edit.rememberStandaloneHomeEditController
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Review renders of Home (not a check: skipped unless YOIN_RENDER_OUT names a folder): the page at rest,
 * the edit header, and a frame sequence of the pinch list folding in and back out. Files are
 * `<YOIN_RENDER_OUT>/<YOIN_RENDER_TAG>-<size>-<theme>-<lang>-<state>.png` (tag defaults to "now").
 *
 * `YOIN_RENDER_OUT=/path YOIN_RENDER_TAG=after ./gradlew :app:testDebugUnitTest --tests '*HomeTwoColumnRenders'`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTwoColumnRenders {

    @get:Rule
    val rule = createComposeRule()

    private val out: File? = System.getenv("YOIN_RENDER_OUT")?.let(::File)
    private val tag: String = System.getenv("YOIN_RENDER_TAG") ?: "now"

    private lateinit var controller: HomeEditController

    // ── Two columns (Jump Back In in a half) ──────────────────────────────

    @Config(qualifiers = "w880dp-h690dp-notnight-xhdpi")
    @Test fun foldInnerLightEn() = render("fold-880x690", 880, 690, "light", "en", frames = true)

    @Config(qualifiers = "zh-rCN-w880dp-h690dp-night-xhdpi")
    @Test fun foldInnerDarkZh() = render("fold-880x690", 880, 690, "dark", "zh")

    @Config(qualifiers = "w840dp-h700dp-notnight-xhdpi")
    @Test fun boundaryLightEn() = render("boundary-840x700", 840, 700, "light", "en")

    @Config(qualifiers = "w1280dp-h800dp-notnight-xhdpi")
    @Test fun landscapeLightEn() = render("tablet-1280x800", 1280, 800, "light", "en", frames = true)

    @Config(qualifiers = "w1280dp-h800dp-night-xhdpi")
    @Test fun landscapeDarkEn() = render("tablet-1280x800", 1280, 800, "dark", "en")

    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-notnight-xhdpi")
    @Test fun landscapeLightZh() = render("tablet-1280x800", 1280, 800, "light", "zh")

    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-night-xhdpi")
    @Test fun landscapeDarkZh() = render("tablet-1280x800", 1280, 800, "dark", "zh")

    // ── One column ────────────────────────────────────────────────────────

    @Config(qualifiers = "w393dp-h852dp-notnight-xhdpi")
    @Test fun phoneLightEn() = render("phone-393x852", 393, 852, "light", "en", frames = true)

    @Config(qualifiers = "w393dp-h852dp-night-xhdpi")
    @Test fun phoneDarkEn() = render("phone-393x852", 393, 852, "dark", "en")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-notnight-xhdpi")
    @Test fun phoneLightZh() = render("phone-393x852", 393, 852, "light", "zh")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night-xhdpi")
    @Test fun phoneDarkZh() = render("phone-393x852", 393, 852, "dark", "zh")

    @Config(qualifiers = "w800dp-h1280dp-notnight-xhdpi")
    @Test fun portraitLightEn() = render("tablet-800x1280", 800, 1280, "light", "en")

    @Config(qualifiers = "w800dp-h1280dp-night-xhdpi")
    @Test fun portraitDarkEn() = render("tablet-800x1280", 800, 1280, "dark", "en")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-notnight-xhdpi")
    @Test fun portraitLightZh() = render("tablet-800x1280", 800, 1280, "light", "zh")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-night-xhdpi")
    @Test fun portraitDarkZh() = render("tablet-800x1280", 800, 1280, "dark", "zh")

    private fun render(
        size: String,
        widthDp: Int,
        heightDp: Int,
        theme: String,
        lang: String,
        frames: Boolean = false,
    ) {
        val dir = out
        assumeTrue("YOIN_RENDER_OUT not set", dir != null)
        dir!!.mkdirs()
        val zh = lang == "zh"
        val name = "$tag-$size-$theme-$lang"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(
                LocalMotionProfile provides MotionProfile.Full,
                LocalHomeGreetingOverride provides if (zh) "aespa《LEMONADE》最近常在播" else "aespa LEMONADE playing often",
            ) {
                YoinTheme {
                    ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
                        controller = rememberStandaloneHomeEditController(HomeLayout.Default)
                        // The shell paints the page behind Home.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        HomeEditorialContent(
                            activities = activities(zh),
                            widgetGrid = jumpBackIn(zh),
                            editController = controller,
                            onNavigateToSettings = {},
                            onNavigateToMemories = {},
                            onAlbumClick = { _, _ -> },
                            onArtistClick = {},
                            onPlaylistClick = {},
                            onSongClick = {},
                            buildCoverArtUrl = { "" },
                        )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(4_000L)
        save(dir, "$name-rest")

        // The edit header (P = 1).
        rule.runOnIdle { controller.enter(null, lifted = false) }
        rule.mainClock.advanceTimeBy(3_000L)
        save(dir, "$name-edit")
        if (!frames) return

        // Feed → list through the header's one-hand switch, then back: the same spring a pinch lands on.
        val listLabel = if (zh) "紧凑列表" else "Compact list"
        val fullLabel = if (zh) "完整版面" else "Full layout"
        rule.onAllNodesWithContentDescription(listLabel, useUnmergedTree = true).onFirst().performClick()
        captureFrames(dir, "$name-fold")
        rule.onAllNodesWithContentDescription(fullLabel, useUnmergedTree = true).onLast().performClick()
        captureFrames(dir, "$name-unfold")
    }

    private fun captureFrames(dir: File, name: String) {
        var t = 0L
        var i = 0
        while (t <= FrameSpanMs) {
            save(dir, "$name-${i.toString().padStart(2, '0')}-${t}ms")
            rule.mainClock.advanceTimeBy(FrameStepMs)
            t += FrameStepMs
            i++
        }
        rule.mainClock.advanceTimeBy(1_000L)
    }

    private fun save(dir: File, name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun activities(zh: Boolean): List<ActivityEvent> {
        val now = System.currentTimeMillis()
        val rows = if (zh) {
            listOf(
                Triple(ActivityEntityType.ALBUM, "春·日光（苏打绿版）", "苏打绿"),
                Triple(ActivityEntityType.ARTIST, "苏打绿", ""),
                Triple(ActivityEntityType.ALBUM, "TURN OFF THE LIGHTS", "Kim Petras"),
                Triple(ActivityEntityType.ARTIST, "Kim Petras", ""),
                Triple(ActivityEntityType.ALBUM, "Limbo", "Amine"),
                Triple(ActivityEntityType.ARTIST, "Namasenda", ""),
                Triple(ActivityEntityType.ALBUM, "我們不要傷心了", "萬芳"),
            )
        } else {
            listOf(
                Triple(ActivityEntityType.ALBUM, "Spring Daylight", "sodagreen"),
                Triple(ActivityEntityType.ARTIST, "sodagreen", ""),
                Triple(ActivityEntityType.ALBUM, "TURN OFF THE LIGHTS", "Kim Petras"),
                Triple(ActivityEntityType.ARTIST, "Kim Petras", ""),
                Triple(ActivityEntityType.ALBUM, "Limbo", "Amine"),
                Triple(ActivityEntityType.ARTIST, "Namasenda", ""),
                Triple(ActivityEntityType.ALBUM, "Let's Not Be Sad", "Wan Fang"),
            )
        }
        return rows.mapIndexed { i, (type, title, subtitle) ->
            ActivityEvent(
                id = i + 1L,
                entityType = type.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "e$i",
                title = title,
                subtitle = subtitle,
                coverArtId = null,
                albumId = if (type == ActivityEntityType.ALBUM) "a$i" else null,
                timestamp = now - (i + 1) * 17 * 60_000L,
            )
        }
    }

    /** Four memory / note 1×2s (as the VM ranks them: wide first), then twenty covers. */
    private fun jumpBackIn(zh: Boolean): List<HomeWidgetCard> {
        val notes = if (zh) {
            listOf(
                Triple("bomb", "ILLIT", "魔法少女是艾利特的谎言"),
                Triple("5:20AM", "刀酱", "比 New York 更真诚，比 Dade County Dreaming 更安静"),
                Triple("TUNE & PLAY", "aespa", "阴郁女孩的破壳日"),
                Triple("Whiplash", "aespa", "第二遍才听懂副歌"),
            )
        } else {
            listOf(
                Triple("bomb", "ILLIT", "The magical-girl lie of ILLIT"),
                Triple("5:20AM", "Daojiang", "More honest than New York, quieter than Dade County Dreaming"),
                Triple("TUNE & PLAY", "aespa", "A gloomy girl's hatching day"),
                Triple("Whiplash", "aespa", "The chorus clicked on the second play"),
            )
        }
        val scores = listOf("7.9", "8.9", "5.0", "8.2")
        val signals = notes.mapIndexed { i, (title, artist, note) ->
            HomeWidgetCard(
                stableId = "s$i",
                entityType = MemoryEntityType.ALBUM,
                title = title,
                subtitle = artist,
                coverArtUrl = null,
                ratingText = scores[i],
                ratingBasis = if (i == 0) null else "Nov ${24 - i * 6}",
                comment = note,
                commentIsHeadline = true,
                expanded = true,
                target = HomeWidgetTarget.MemoryFocus(i.toLong()),
            )
        }
        val covers = (1..20).map { i ->
            HomeWidgetCard(
                stableId = "c$i",
                entityType = listOf(MemoryEntityType.ALBUM, MemoryEntityType.SONG, MemoryEntityType.PLAYLIST)[i % 3],
                title = (if (zh) "专辑 " else "Album ") + i,
                subtitle = if (zh) "艺人 $i" else "Artist $i",
                coverArtUrl = null,
                target = HomeWidgetTarget.AlbumDetail("subsonic:c$i"),
            )
        }
        return signals + covers
    }

    private companion object {
        const val FrameStepMs = 48L
        const val FrameSpanMs = 720L
    }
}
