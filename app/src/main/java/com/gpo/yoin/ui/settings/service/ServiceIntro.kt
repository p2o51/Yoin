package com.gpo.yoin.ui.settings.service

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What a service is, told the way a person would pitch it: a tagline, a few
 * highlights, and the prerequisites. Deliberately NOT a support matrix — the
 * exhaustive per-feature status lives in `ServiceFeatureCatalog` for code, and
 * limits the UI can't act on are hidden rather than explained (design.md).
 */
internal data class ServiceIntro(
    val name: String,
    val tagline: String,
    val icon: ImageVector,
    val highlights: List<Highlight>,
    val requirements: List<String>,
    /** Short badge next to the name — e.g. "Preview" for connection-test-only services. */
    val badge: String? = null,
) {
    data class Highlight(val icon: ImageVector, val title: String, val body: String)
}

internal val SetupService.intro: ServiceIntro
    get() = when (this) {
        SetupService.Subsonic -> SubsonicIntro
        SetupService.Spotify -> SpotifyIntro
        SetupService.AppleMusic -> AppleMusicIntro
    }

private val SubsonicIntro = ServiceIntro(
    name = "Subsonic",
    tagline = "Your own music server — Navidrome, Airsonic, Gonic or anything OpenSubsonic.",
    icon = Icons.Rounded.CloudQueue,
    highlights = listOf(
        ServiceIntro.Highlight(
            Icons.Rounded.GraphicEq,
            "Plays right in Yoin",
            "Streams from your server, with lock-screen and background controls.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.Favorite,
            "Favorites stay in sync",
            "A heart in Yoin stars the song on your server.",
        ),
        ServiceIntro.Highlight(
            Icons.AutoMirrored.Rounded.QueueMusic,
            "Your playlists",
            "Browse them all and edit the ones you own.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.Cast,
            "Cast and cache",
            "Send music to a speaker; recent plays are cached for you.",
        ),
    ),
    requirements = listOf("A server address, username and password"),
)

private val SpotifyIntro = ServiceIntro(
    name = "Spotify",
    tagline = "Your Spotify library in Yoin, played through the Spotify app.",
    icon = Icons.Rounded.Headphones,
    highlights = listOf(
        ServiceIntro.Highlight(
            Icons.Rounded.LibraryMusic,
            "Everything you've saved",
            "Playlists, liked songs, albums and artists.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.Favorite,
            "Hearts go to Liked Songs",
            "Save a song in Yoin and it shows up in Spotify.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.Speaker,
            "Spotify Connect",
            "Play on any speaker or device Spotify can reach.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.EditNote,
            "Yoin's extras",
            "Lyrics, ratings and notes, kept in Yoin.",
        ),
    ),
    requirements = listOf("Spotify Premium", "The Spotify app on this device"),
)

private val AppleMusicIntro = ServiceIntro(
    name = "Apple Music",
    tagline = "Your Apple Music library and catalog, played in Yoin.",
    icon = Icons.Rounded.MusicNote,
    badge = "Preview",
    highlights = listOf(
        ServiceIntro.Highlight(
            Icons.Rounded.Search,
            "Search the catalog",
            "Find any song on Apple Music.",
        ),
        ServiceIntro.Highlight(
            Icons.Rounded.PlayCircle,
            "Plays in Yoin",
            "Listen with MusicKit, background playback and system controls.",
        ),
    ),
    requirements = listOf("An Apple Music subscription", "A developer token service"),
)
