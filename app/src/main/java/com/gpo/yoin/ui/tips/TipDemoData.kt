package com.gpo.yoin.ui.tips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.R
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.Artist
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.home.HomeWidgetCard
import com.gpo.yoin.ui.home.HomeWidgetTarget

/*
 * The demo phone's library: made-up albums with made-up covers (the
 * tip_demo_cover_* drawables), so the real components have something to show.
 */

/** One made-up album: its name, artist and cover drawable. */
internal data class DemoAlbum(val name: String, val artist: String, val cover: Int)

internal val DemoAlbums = listOf(
    DemoAlbum("Harbour Lights", "Mira Sol", R.drawable.tip_demo_cover_1),
    DemoAlbum("Paper Moons", "The Lanterns", R.drawable.tip_demo_cover_2),
    DemoAlbum("Low Tide", "Ines Varga", R.drawable.tip_demo_cover_3),
    DemoAlbum("Night Garden", "Okto", R.drawable.tip_demo_cover_4),
    DemoAlbum("Golden Hour", "Mira Sol", R.drawable.tip_demo_cover_5),
    DemoAlbum("Blue Rooms", "Halden", R.drawable.tip_demo_cover_6),
)

internal val DemoTrackTitles = listOf(
    "Slow Light",
    "Northbound",
    "Salt and Glass",
    "Weathervane",
    "Quiet Hours",
    "Lanterns",
    "Undertow",
    "Afterglow",
)

/** The cover of [album] as an artwork url (an android.resource uri). */
@Composable
internal fun demoCoverUrl(album: DemoAlbum): String {
    val packageName = LocalContext.current.packageName
    return remember(packageName, album) { "android.resource://$packageName/${album.cover}" }
}

/** Every album's cover url, in [DemoAlbums] order. */
@Composable
internal fun demoCoverUrls(): List<String> {
    val packageName = LocalContext.current.packageName
    return remember(packageName) { DemoAlbums.map { "android.resource://$packageName/${it.cover}" } }
}

internal fun demoTrack(index: Int, album: DemoAlbum = DemoAlbums[0], coverUrl: String? = null): Track = Track(
    id = MediaId.subsonic("tip-track-$index"),
    title = DemoTrackTitles[index % DemoTrackTitles.size],
    artist = album.artist,
    artistId = MediaId.subsonic("tip-artist-${album.artist}"),
    album = album.name,
    albumId = MediaId.subsonic("tip-album-${album.name}"),
    coverArt = coverUrl?.let { CoverRef.Url(it) },
    durationSec = 180 + index * 23,
    trackNumber = index + 1,
    year = 2024,
    genre = null,
    userRating = null,
)

/** Jump Back In's cards: every album, a 1×1 cover each. */
@Composable
internal fun demoWidgetCards(): List<HomeWidgetCard> {
    val covers = demoCoverUrls()
    return remember(covers) {
        (DemoAlbums + DemoAlbums.reversed()).mapIndexed { i, album ->
            HomeWidgetCard(
                stableId = "tip-card-$i",
                entityType = MemoryEntityType.ALBUM,
                title = album.name,
                subtitle = album.artist,
                coverArtUrl = covers[DemoAlbums.indexOf(album)],
                target = HomeWidgetTarget.AlbumDetail("tip-album-$i"),
            )
        }
    }
}

@Composable
internal fun demoLibraryAlbums(): List<Album> {
    val covers = demoCoverUrls()
    return remember(covers) {
        (DemoAlbums + DemoAlbums.reversed()).mapIndexed { i, album ->
            Album(
                id = MediaId.subsonic("tip-album-$i"),
                name = album.name,
                artist = album.artist,
                artistId = null,
                coverArt = CoverRef.Url(covers[DemoAlbums.indexOf(album)]),
                songCount = 8,
                durationSec = 2_400,
                year = 2024,
                genre = null,
            )
        }
    }
}

internal val DemoLibraryArtists: List<Artist> = DemoAlbums.map { it.artist }.distinct().map { name ->
    Artist(id = MediaId.subsonic("tip-artist-$name"), name = name, albumCount = 2, coverArt = null)
}
