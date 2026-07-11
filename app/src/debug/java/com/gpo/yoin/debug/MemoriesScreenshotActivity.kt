package com.gpo.yoin.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.home.HomeEditorialContent
import com.gpo.yoin.ui.home.HomeWidgetCard
import com.gpo.yoin.ui.home.HomeWidgetTarget
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File

/**
 * Debug-only launcher for visual QA of the whole redesigned home feed —
 * Activities bento, the Jump Back In widget grid, and the compact Recently
 * Added shelf — with fixed fake data. Not exported in release. Stand-in covers
 * are solid-colour bitmaps written to cacheDir on first launch so the backdrop
 * palette tints each shape (and card) the way real album art would. Launch:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesScreenshotActivity
 */
class MemoriesScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeEditorialContent(
                        activities = fakeActivities(),
                        widgetGrid = fakeWidgetGrid(),
                        activityHeroFootnote = "2024 · 12 songs · 44 min",
                        recentlyAdded = fakeRecentlyAdded(),
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        // Storage keys in the fakes are file:// URIs; identity
                        // pass-through lets Coil load them directly.
                        buildCoverArtUrl = { it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    private fun swatchCover(name: String, color: Int): String {
        val file = File(cacheDir, "mem_$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }

    private fun fakeActivities(): List<ActivityEvent> {
        val now = System.currentTimeMillis()
        return listOf(
            ActivityEvent(
                id = 1,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a1",
                title = "This Infinite",
                subtitle = "Vitesse X",
                coverArtId = swatchCover("mint", 0xFF3DAE77.toInt()),
                albumId = "a1",
                timestamp = now - 5L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 2,
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "p1",
                // Long on purpose: the small bento card must wrap this onto
                // two lines instead of truncating at one.
                title = "Clock — late night drives",
                subtitle = "51",
                coverArtId = swatchCover("pink", 0xFFD4537E.toInt()),
                timestamp = now - 26L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 3,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a2",
                title = "Para que salgamos bien en la foto",
                subtitle = "Rakky Ripper",
                coverArtId = swatchCover("blue", 0xFF378ADD.toInt()),
                albumId = "a2",
                timestamp = now - 60L * 60 * 1000,
            ),
            ActivityEvent(
                id = 4,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a3",
                title = "天国の部屋",
                subtitle = "坂口諒之介",
                coverArtId = swatchCover("salmon", 0xFFE0705A.toInt()),
                albumId = "a3",
                timestamp = now - 25L * 60 * 60 * 1000,
            ),
        )
    }

    private fun fakeWidgetGrid(): List<HomeWidgetCard> {
        // The design composition: 2+2+3+3+2 = 12 cells.
        val notedSong = fakeTrack("t-note", "跳不完的舞", "秦凡淇", swatchCover("olive", 0xFF7C9A1E.toInt()))
        return listOf(
            HomeWidgetCard(
                stableId = "grid-memory:demo",
                entityType = MemoryEntityType.ALBUM,
                title = "Describe",
                subtitle = "Album · Hannah Jadagu",
                coverArtUrl = swatchCover("green", 0xFF639922.toInt()),
                ratingText = "8.4",
                ratingBasis = "Jun 26",
                comment = "Still opens the same door, six months on.",
                expanded = true,
                target = HomeWidgetTarget.MemoryFocus(1L),
            ),
            HomeWidgetCard(
                stableId = "grid-note:demo",
                entityType = MemoryEntityType.SONG,
                title = notedSong.title.orEmpty(),
                subtitle = "Single · ${notedSong.artist}",
                coverArtUrl = swatchCover("olive", 0xFF7C9A1E.toInt()),
                ratingText = "9.5",
                ratingBasis = "Jun 26",
                comment = "在她的身体里，跳舞也可以变成带着哲思的苦行。",
                expanded = true,
                target = HomeWidgetTarget.PlaySong(notedSong),
            ),
            fakeAlbumCard("Little House", "Rachel C.", swatchCover("pink", 0xFFD4537E.toInt())),
            fakeSongCard("Gimme Time", "Hannah Jadagu", swatchCover("coral", 0xFFD85A30.toInt())),
            fakePlaylistCard("Endless Natsu", "51", swatchCover("teal", 0xFF1D9E75.toInt())),
            fakeAlbumCard("AIと刹那", "Mom", swatchCover("blue", 0xFF378ADD.toInt())),
            fakeSongCard("sense (is)", "hemlocke springs", swatchCover("violet", 0xFF7F77DD.toInt())),
            fakePlaylistCard("My Angelist #101", "HESSBEN", swatchCover("mint", 0xFF3DAE77.toInt())),
            fakeAlbumCard("Freakout/Release", "Hot Chip", swatchCover("salmon", 0xFFE0705A.toInt())),
            fakePlaylistCard("305tilidie", "Camila Cabello", swatchCover("navy", 0xFF185FA5.toInt())),
        )
    }

    private fun fakeRecentlyAdded(): List<Track> = listOf(
        fakeTrack("r1", "Perfect", "Hannah Jadagu", swatchCover("green", 0xFF639922.toInt())),
        fakeTrack("r2", "D.I.A.A", "Hannah Jadagu", swatchCover("coral", 0xFFD85A30.toInt())),
        fakeTrack("r3", "Couldn't Call", "Hannah Jadagu", swatchCover("teal", 0xFF1D9E75.toInt())),
        fakeTrack("r4", "My Love", "Hannah Jadagu", swatchCover("pink", 0xFFD4537E.toInt())),
        fakeTrack("r5", "Tell Me", "Hannah Jadagu", swatchCover("blue", 0xFF378ADD.toInt())),
    )

    private fun fakeAlbumCard(title: String, artist: String, cover: String): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-album:$title",
            entityType = MemoryEntityType.ALBUM,
            title = title,
            subtitle = "Album · $artist",
            coverArtUrl = cover,
            target = HomeWidgetTarget.AlbumDetail("subsonic:$title"),
        )

    private fun fakeSongCard(title: String, artist: String, cover: String): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-song:$title",
            entityType = MemoryEntityType.SONG,
            title = title,
            subtitle = "Single · $artist",
            coverArtUrl = cover,
            target = HomeWidgetTarget.PlaySong(fakeTrack("s-$title", title, artist, cover)),
        )

    private fun fakePlaylistCard(title: String, owner: String, cover: String): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-playlist:$title",
            entityType = MemoryEntityType.PLAYLIST,
            title = title,
            subtitle = "Playlist · $owner",
            coverArtUrl = cover,
            target = HomeWidgetTarget.PlaylistDetail("subsonic:$title"),
        )

    private fun fakeTrack(rawId: String, title: String, artist: String, cover: String): Track =
        Track(
            id = MediaId.subsonic(rawId),
            title = title,
            artist = artist,
            artistId = null,
            album = null,
            albumId = null,
            coverArt = CoverRef.Url(cover),
            durationSec = 200,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        )
}
