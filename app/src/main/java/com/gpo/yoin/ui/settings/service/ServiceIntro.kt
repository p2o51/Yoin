package com.gpo.yoin.ui.settings.service

import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.symbols.YoinSymbols

/**
 * What a service is, told the way a person would pitch it: a tagline, a few
 * highlights, and the prerequisites. Deliberately NOT a support matrix — the
 * exhaustive per-feature status lives in `ServiceFeatureCatalog` for code, and
 * limits the UI can't act on are hidden rather than explained (design.md).
 * The service's glyph and colour come from its `ServiceIdentity`.
 */
internal data class ServiceIntro(
    val name: String,
    val tagline: String,
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
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Equalizer,
            "Plays right in Yoin",
            "Streams from your server, with lock-screen and background controls.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Favorite,
            "Favorites stay in sync",
            "A heart in Yoin stars the song on your server.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Playlist,
            "Your playlists",
            "Browse them all and edit the ones you own.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Cast,
            "Cast and cache",
            "Send music to a speaker; recent plays are cached for you.",
        ),
    ),
    requirements = listOf("A server address, username and password"),
)

private val SpotifyIntro = ServiceIntro(
    name = "Spotify",
    tagline = "Your Spotify library in Yoin, played through the Spotify app.",
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Library,
            "Everything you've saved",
            "Playlists, liked songs, albums and artists.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Favorite,
            "Hearts go to Liked Songs",
            "Save a song in Yoin and it shows up in Spotify.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Speaker,
            "Spotify Connect",
            "Play on any speaker or device Spotify can reach.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.EditNote,
            "Yoin's extras",
            "Lyrics, ratings and notes, kept in Yoin.",
        ),
    ),
    requirements = listOf("Spotify Premium", "The Spotify app on this device"),
)

private val AppleMusicIntro = ServiceIntro(
    name = "Apple Music",
    tagline = "Your Apple Music library and catalog, played in Yoin.",
    badge = "Preview",
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Search,
            "Search the catalog",
            "Find any song on Apple Music.",
        ),
        ServiceIntro.Highlight(
            YoinSymbols.PlayCircle,
            "Plays in Yoin",
            "Listen with MusicKit, background playback and system controls.",
        ),
    ),
    requirements = listOf("An Apple Music subscription", "A developer token service"),
)
