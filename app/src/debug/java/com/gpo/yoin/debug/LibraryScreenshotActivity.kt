package com.gpo.yoin.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Playlist
import com.gpo.yoin.data.model.Starred
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.library.LibraryContent
import com.gpo.yoin.ui.library.LibraryTab
import com.gpo.yoin.ui.library.LibraryUiState
import androidx.compose.runtime.CompositionLocalProvider
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File

/**
 * Debug-only launcher for visual QA of the Library page with fixed fake data.
 * Not exported in release. Pick the tab via the "tab" string extra:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.LibraryScreenshotActivity --es tab Albums
 */
class LibraryScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val tab = intent.getStringExtra("tab")
            ?.let { raw -> LibraryTab.entries.firstOrNull { it.name == raw } }
            ?: LibraryTab.Albums
        setContent {
            // 对齐生产环境：QA 台也提供窗口信息，Medium/Wide 分支才可验。
            val windowInfo = rememberYoinWindowInfo()
            CompositionLocalProvider(LocalYoinWindowInfo provides windowInfo) {
            YoinTheme {
                LibraryContent(
                    uiState = fakeState(tab),
                    onTabSelected = {},
                    onSearchQueryChanged = {},
                    onClearSearch = {},
                    onNavigateToSettings = {},
                    onArtistClick = {},
                    onAlbumClick = {},
                    onPlaylistClick = {},
                    onSongClick = {},
                    onRetry = {},
                    coverArtUrlBuilder = null,
                )
            }
            }
        }
    }

    private fun swatchCover(name: String, color: Int): CoverRef {
        val file = File(cacheDir, "lib_$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return CoverRef.Url(Uri.fromFile(file).toString())
    }

    private fun fakeState(tab: LibraryTab): LibraryUiState.Content {
        val palette = listOf(
            "green" to 0xFF639922.toInt(),
            "coral" to 0xFFD85A30.toInt(),
            "pink" to 0xFFD4537E.toInt(),
            "teal" to 0xFF1D9E75.toInt(),
            "blue" to 0xFF378ADD.toInt(),
            "violet" to 0xFF7F77DD.toInt(),
            "olive" to 0xFF7C9A1E.toInt(),
            "navy" to 0xFF185FA5.toInt(),
            "mint" to 0xFF3DAE77.toInt(),
        )
        fun cover(index: Int) = palette[index % palette.size].let { (n, c) -> swatchCover(n, c) }

        val albumNames = listOf(
            "Describe" to "Hannah Jadagu",
            "This Infinite" to "Vitesse X",
            "Freakout/Release" to "Hot Chip",
            "Para que salgamos" to "Rakky Ripper",
            "天国の部屋" to "坂口諒之介",
            "sense (is)" to "hemlocke springs",
            "Little House" to "Rachel Chinouriri",
            "AIと刹那のポリティクス" to "Mom",
            "Endless Summer" to "The Jungle Giants",
            // Wide 桌面网格(1280 一类窗 7 列)要铺满 3 行,补到 21 张;
            // 前 9 张保持原样,songs 的 albumNames[i % size] 映射不受影响
            // (i 最大 5,增列表长度不改前段取值)。
            "Salt & Citrus" to "Mira Fontaine",
            "夜光虫" to "青い雨",
            "Weekday Ghost" to "Palm Reader Club",
            "微熱都市" to "林曖曖",
            "Held Open" to "Marigold Tapes",
            "うたかたの夏" to "水星クラブ",
            "Bright Bikes" to "The Umbrella Steps",
            "半透明" to "konoha",
            "Fig Season" to "Josie Halpert",
            "Sea of Rooms" to "Cloud Bench",
            "灰と雪" to "月かげ",
            "Field Notes" to "Prairie FM",
        )
        val albums = albumNames.mapIndexed { i, (name, artist) ->
            Album(
                id = MediaId.subsonic("al$i"),
                name = name,
                artist = artist,
                artistId = null,
                coverArt = cover(i),
                songCount = 8 + i,
                durationSec = 2000 + i * 60,
                year = 2020 + (i % 6),
                genre = null,
            )
        }
        val artists = listOf("Hannah Jadagu", "Vitesse X", "秦凡淇", "hemlocke springs").mapIndexed { i, name ->
            Artist(
                id = MediaId.subsonic("ar$i"),
                name = name,
                coverArt = cover(i + 2),
                albumCount = 2 + i,
            )
        }
        val songs = listOf(
            "Warning Sign" to "Hannah Jadagu",
            "跳不完的舞" to "秦凡淇",
            "Gimme Time" to "Hannah Jadagu",
            "Eraser" to "Vitesse X",
            "sense (is)" to "hemlocke springs",
            "Down Bad" to "Dounia",
        ).mapIndexed { i, (title, artist) ->
            Track(
                id = MediaId.subsonic("t$i"),
                title = title,
                artist = artist,
                artistId = null,
                album = albumNames[i % albumNames.size].first,
                albumId = null,
                coverArt = cover(i + 4),
                durationSec = 180 + i * 17,
                trackNumber = null,
                year = null,
                genre = null,
                userRating = null,
            )
        }
        val playlists = listOf(
            "Clock — late night drives" to "51",
            "My Angelist #101" to "HESSBEN",
            "305tilidie" to "Camila Cabello",
        ).mapIndexed { i, (name, owner) ->
            Playlist(
                id = MediaId.subsonic("pl$i"),
                name = name,
                owner = owner,
                coverArt = cover(i + 6),
                songCount = 12 + i * 5,
                durationSec = 3000,
            )
        }
        return LibraryUiState.Content(
            selectedTab = tab,
            artists = artists,
            albums = albums,
            songs = songs,
            playlists = playlists,
            favorites = Starred(
                tracks = songs.take(3),
                albums = albums.take(2),
                artists = artists.take(2),
            ),
            searchQuery = "",
            searchResults = null,
            isSearching = false,
        )
    }
}
