package com.gpo.yoin.ui.detail

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
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
 * Review renders of the album page's top: header, page dots, cover and the meta line with the length
 * (not a check: skipped unless YOIN_RENDER_OUT names a folder). Each size × theme × language draws the
 * page at rest into `<YOIN_RENDER_OUT>/<size>-<theme>-<lang>.png`.
 *
 * `YOIN_RENDER_OUT=/path ./gradlew :app:testDebugUnitTest --tests '*AlbumHeaderRenders'`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumHeaderRenders {

    @get:Rule
    val rule = createComposeRule()

    private val out: File? = System.getenv("YOIN_RENDER_OUT")?.let(::File)

    @Config(qualifiers = "w393dp-h852dp-notnight")
    @Test fun phoneLightEn() = render("phone-393", 393, 852, "light", "en")

    @Config(qualifiers = "w393dp-h852dp-night")
    @Test fun phoneDarkEn() = render("phone-393", 393, 852, "dark", "en")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-notnight")
    @Test fun phoneLightZh() = render("phone-393", 393, 852, "light", "zh")

    @Config(qualifiers = "zh-rCN-w393dp-h852dp-night")
    @Test fun phoneDarkZh() = render("phone-393", 393, 852, "dark", "zh")

    @Config(qualifiers = "w800dp-h1280dp-notnight")
    @Test fun tabletLightEn() = render("tablet-800", 800, 1280, "light", "en")

    @Config(qualifiers = "w800dp-h1280dp-night")
    @Test fun tabletDarkEn() = render("tablet-800", 800, 1280, "dark", "en")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-notnight")
    @Test fun tabletLightZh() = render("tablet-800", 800, 1280, "light", "zh")

    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-night")
    @Test fun tabletDarkZh() = render("tablet-800", 800, 1280, "dark", "zh")

    @Config(qualifiers = "w1280dp-h800dp-notnight")
    @Test fun twoColumnLightEn() = render("two-column-1280", 1280, 800, "light", "en")

    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-night")
    @Test fun twoColumnDarkZh() = render("two-column-1280", 1280, 800, "dark", "zh")

    private fun render(size: String, widthDp: Int, heightDp: Int, theme: String, lang: String) {
        val dir = out
        assumeTrue("YOIN_RENDER_OUT not set", dir != null)
        dir!!.mkdirs()
        val zh = lang == "zh"
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
                    // The status bar a device keeps over the page (Robolectric draws none).
                    Box(modifier = Modifier.fillMaxSize().padding(top = if (widthDp >= 600) 24.dp else 32.dp)) {
                        AlbumDetailScreen(
                            uiState = content(zh),
                            onBackClick = {},
                            onSongClick = {},
                            onToggleStar = {},
                            onRetry = {},
                            // A scrapbook page: the header shows its two dots.
                            scrapbook = AlbumScrapbookUiState.Loading,
                        )
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(4_000L)
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$size-$theme-$lang.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(zh: Boolean) = AlbumDetailUiState.Content(
        albumId = "spotify:album-1",
        albumName = "The College Dropout",
        artistName = "Kanye West",
        artistId = "spotify:artist-1",
        coverArtId = null,
        coverArtUrl = null,
        year = 2004,
        songCount = 21,
        // 1 h 16 min
        totalDuration = 76 * 60 + 12,
        songs = listOf(
            AlbumSong("1", "Intro", "Kanye West", 1, 19, false),
            AlbumSong("2", "We Don't Care", "Kanye West", 2, 239, true),
            AlbumSong("3", "Graduation Day", "Kanye West", 3, 82, false),
            AlbumSong("4", "All Falls Down", "Kanye West feat. Syleena Johnson", 4, 223, false, featArtist = "Syleena Johnson"),
            AlbumSong("5", "I'll Fly Away", "Kanye West", 5, 69, false),
            AlbumSong("6", "Spaceship", "Kanye West", 6, 324, false),
            AlbumSong("7", "Jesus Walks", "Kanye West", 7, 193, false),
        ),
        averageTrackRating = 10f,
        ratedTrackCount = 1,
        lastPlayedAt = System.currentTimeMillis() - 3_600_000L,
        userReview = if (zh) "我爱它" else "Still the one.",
    )
}
