package com.gpo.yoin.ui.settings.service

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols

/**
 * What a service is, told the way a person would pitch it: a tagline, a few
 * highlights, and the prerequisites. Deliberately NOT a support matrix — the
 * exhaustive per-feature status lives in `ServiceFeatureCatalog` for code, and
 * limits the UI can't act on are hidden rather than explained (design.md).
 * The service's glyph and colour come from its `ServiceIdentity`.
 */
internal data class ServiceIntro(
    @param:StringRes @get:StringRes val nameRes: Int,
    @param:StringRes @get:StringRes val taglineRes: Int,
    val highlights: List<Highlight>,
    val requirements: List<Int>,
    /** Short badge next to the name — e.g. "Preview" for connection-test-only services. */
    @param:StringRes @get:StringRes val badgeRes: Int? = null,
) {
    data class Highlight(
        val icon: ImageVector,
        @param:StringRes @get:StringRes val titleRes: Int,
        @param:StringRes @get:StringRes val bodyRes: Int,
    )
}

internal val SetupService.intro: ServiceIntro
    get() = when (this) {
        SetupService.Subsonic -> SubsonicIntro
        SetupService.Spotify -> SpotifyIntro
        SetupService.AppleMusic -> AppleMusicIntro
    }

private val SubsonicIntro = ServiceIntro(
    nameRes = R.string.settings_intro_subsonic_name,
    taglineRes = R.string.settings_intro_subsonic_tagline,
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Equalizer,
            R.string.settings_intro_subsonic_play_title,
            R.string.settings_intro_subsonic_play_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Favorite,
            R.string.settings_intro_subsonic_favorites_title,
            R.string.settings_intro_subsonic_favorites_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Playlist,
            R.string.settings_intro_subsonic_playlists_title,
            R.string.settings_intro_subsonic_playlists_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Cast,
            R.string.settings_intro_subsonic_cast_title,
            R.string.settings_intro_subsonic_cast_body,
        ),
    ),
    requirements = listOf(R.string.settings_intro_subsonic_need),
)

private val SpotifyIntro = ServiceIntro(
    nameRes = R.string.settings_intro_spotify_name,
    taglineRes = R.string.settings_intro_spotify_tagline,
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Library,
            R.string.settings_intro_spotify_saved_title,
            R.string.settings_intro_spotify_saved_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Favorite,
            R.string.settings_intro_spotify_hearts_title,
            R.string.settings_intro_spotify_hearts_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.Speaker,
            R.string.settings_intro_spotify_connect_title,
            R.string.settings_intro_spotify_connect_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.EditNote,
            R.string.settings_intro_spotify_extras_title,
            R.string.settings_intro_spotify_extras_body,
        ),
    ),
    requirements = listOf(
        R.string.settings_intro_spotify_need_premium,
        R.string.settings_intro_spotify_need_app,
    ),
)

private val AppleMusicIntro = ServiceIntro(
    nameRes = R.string.settings_intro_apple_name,
    taglineRes = R.string.settings_intro_apple_tagline,
    badgeRes = R.string.settings_intro_apple_badge,
    highlights = listOf(
        ServiceIntro.Highlight(
            YoinSymbols.Search,
            R.string.settings_intro_apple_search_title,
            R.string.settings_intro_apple_search_body,
        ),
        ServiceIntro.Highlight(
            YoinSymbols.PlayCircle,
            R.string.settings_intro_apple_play_title,
            R.string.settings_intro_apple_play_body,
        ),
    ),
    requirements = listOf(
        R.string.settings_intro_apple_need_sub,
        R.string.settings_intro_apple_need_token,
    ),
)
