package com.gpo.yoin.ui.detail

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.gpo.yoin.data.album.AlbumAboutAsk
import com.gpo.yoin.data.album.AlbumAboutContent
import com.gpo.yoin.data.album.AlbumAiCredits
import com.gpo.yoin.data.album.AlbumScrapbookData
import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.ui.experience.ProvidePreviewWindow
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * Previews of the album About (credits line + card on the scrapbook, and its sheet), in both languages,
 * light and dark, phone and tablet widths. The AI's words are sample text in the language of the preview.
 */

private const val AboutPreviewNow = 1_791_000_000_000L

private fun aboutPreviewAlbum(zh: Boolean): AlbumDetailUiState.Content {
    val titles = listOf("Dawn Chorus", "Glass House", "Undertow", "Paper Moon", "Low Tide", "Coda")
    return AlbumDetailUiState.Content(
        albumId = "subsonic:al-tide",
        albumName = if (zh) "潮汐表（十周年版）" else "Tide Tables (10th Anniversary)",
        albumVersion = null,
        artistName = if (zh) "森川" else "Morikawa",
        artistId = null,
        coverArtId = null,
        coverArtUrl = null,
        year = 2024,
        releaseDate = ReleaseDate(2024, 3, 8),
        // The service names the label (no AI) and dates the first release apart (a reissue).
        recordLabels = listOf("Northern Lights Records"),
        originalReleaseDate = ReleaseDate(2014, 5, 20),
        songCount = titles.size,
        totalDuration = 2_460,
        songs = titles.mapIndexed { i, title -> AlbumSong("subsonic:t${i + 1}", title, "Morikawa", i + 1, 240 + i * 11, isStarred = false) },
        userReview = if (zh) "海边录的那几首，听得到窗外的风。" else "You can hear the wind outside on the seaside takes.",
        averageTrackRating = 8.6f,
        ratedTrackCount = 3,
        ratedSongIds = setOf("subsonic:t1", "subsonic:t3", "subsonic:t5"),
    )
}

private fun aboutPreviewData(zh: Boolean): AlbumScrapbookData = AlbumScrapbookData(
    albumAbout = AlbumAboutContent(
        credits = AlbumAiCredits(
            producers = listOf("Kenji Arai", "Mei Lin"),
            mastering = listOf("Bob Ludwig"),
            recordedTime = if (zh) "2013年9月—12月" else "Sep–Dec 2013",
            recordedStudios = listOf("Studio Mare, Kamakura"),
        ),
        blurb = if (zh) {
            "《潮汐表》是森川的第二张专辑，也是他从卧室制作转向乐队录音的转折点。整张唱片在镰仓海边的一间旧仓库里完成，" +
                "乐手围着一支立体声话筒同时演奏，窗外的海浪声被有意保留在混音里，成为贯穿全碟的底色。" +
                "编曲以原声吉他和管风琴为主，节奏随潮汐的涨落时缓时急。十周年版重新做了母带，并补收了三首当年未完成的小样。" +
                "最常被提起的一段幕后：标题曲最后的人声是凌晨涨潮时一次录成的，后来再也没能重现那种回声。"
        } else {
            "Tide Tables was Morikawa's second album and the point where he moved from bedroom production to a band in " +
                "a room. It was cut in an old warehouse by the sea in Kamakura, the players gathered around one stereo pair, " +
                "and the surf outside was left in the mix on purpose, a ground tone under every track. Acoustic guitar and " +
                "pump organ carry the arrangements, the tempos easing and pressing like the tide. The anniversary edition " +
                "is newly mastered and adds three demos left unfinished at the time. The story most often told: the title " +
                "track's last vocal was sung once, at high tide before dawn, and the echo was never caught again."
        },
        asks = listOf(
            AlbumAboutAsk(
                question = if (zh) "那三首小样是什么时候录的？" else "When were the three demos recorded?",
                title = if (zh) "小样的来历" else "Where the demos came from",
                answer = if (zh) {
                    "三首小样录于 2013 年夏天正式进棚之前，是森川在家用四轨机录下的草稿。"
                } else {
                    "The demos date from summer 2013, before the band sessions: sketches Morikawa made at home on a four-track."
                },
            ),
        ),
        generated = true,
    ),
)

@Composable
private fun AboutScrapbookPreview(zh: Boolean, widthDp: Int, heightDp: Int) {
    YoinTheme {
        ProvidePreviewWindow(widthDp = widthDp, heightDp = heightDp) {
            val content = aboutPreviewAlbum(zh)
            Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                AlbumScrapbookPage(
                    state = AlbumScrapbookUiState.Ready(
                        buildAlbumScrapbook(
                            content,
                            aboutPreviewData(zh),
                            nowMillis = AboutPreviewNow,
                            // zh with a Memory title (the line under it), en without (the line beside the cover).
                            title = if (zh) ScrapTitle("海边录下的那一年", edited = true, canRestore = false) else null,
                        ),
                    ),
                    content = content,
                    colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                    currentTrackId = null,
                    pageOffset = { 0f },
                    onSongClick = {},
                    onNoteMomentClick = { _, _ -> },
                    onEditReview = {},
                )
            }
        }
    }
}

@Composable
private fun AboutSheetPreview(zh: Boolean) {
    YoinTheme {
        val content = aboutPreviewAlbum(zh)
        val book = buildAlbumScrapbook(content, aboutPreviewData(zh), nowMillis = AboutPreviewNow)
        val about = book.pieces.filterIsInstance<ScrapPiece.AlbumAbout>().first()
        val credits = book.pieces.filterIsInstance<ScrapPiece.Opening>().first().credits
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow)) {
            AlbumAboutSheetBody(
                albumName = content.albumName,
                about = about,
                credits = credits,
                colors = rememberScrapbookColors(MaterialTheme.colorScheme),
                gemini = ScrapGemini(),
                onEdit = {},
            )
        }
    }
}

@Preview(name = "Album About · phone · en · light", widthDp = 390, heightDp = 900, locale = "en")
@Preview(
    name = "Album About · phone · en · dark",
    widthDp = 390,
    heightDp = 900,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlbumAboutPhoneEnPreview() = AboutScrapbookPreview(zh = false, widthDp = 390, heightDp = 900)

@Preview(name = "Album About · phone · zh · light", widthDp = 390, heightDp = 900, locale = "zh-rCN")
@Preview(
    name = "Album About · phone · zh · dark",
    widthDp = 390,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlbumAboutPhoneZhPreview() = AboutScrapbookPreview(zh = true, widthDp = 390, heightDp = 900)

@Preview(name = "Album About · tablet portrait · zh · light", widthDp = 800, heightDp = 900, locale = "zh-rCN")
@Preview(
    name = "Album About · tablet portrait · zh · dark",
    widthDp = 800,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlbumAboutTabletZhPreview() = AboutScrapbookPreview(zh = true, widthDp = 800, heightDp = 900)

@Preview(name = "Album About · tablet landscape · en · light", widthDp = 1280, heightDp = 800, locale = "en")
@Preview(
    name = "Album About · tablet landscape · en · dark",
    widthDp = 1280,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlbumAboutTabletEnPreview() = AboutScrapbookPreview(zh = false, widthDp = 1280, heightDp = 800)

@Preview(name = "Album About sheet · zh · light", widthDp = 390, heightDp = 1100, locale = "zh-rCN")
@Preview(
    name = "Album About sheet · zh · dark",
    widthDp = 390,
    heightDp = 1100,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlbumAboutSheetZhPreview() = AboutSheetPreview(zh = true)

@Preview(name = "Album About sheet · en · light", widthDp = 390, heightDp = 1100, locale = "en")
@Preview(
    name = "Album About sheet · en · dark",
    widthDp = 390,
    heightDp = 1100,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Album About sheet · en · tablet", widthDp = 800, heightDp = 1100, locale = "en")
@Composable
private fun AlbumAboutSheetEnPreview() = AboutSheetPreview(zh = false)
