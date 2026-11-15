package com.gpo.yoin.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.source.spotify.MadeForYouKind
import com.gpo.yoin.data.source.spotify.MadeForYouPlaylist
import com.gpo.yoin.data.source.spotify.SpotifyContentBridge
import com.gpo.yoin.data.source.spotify.forHomeShelf
import com.gpo.yoin.data.source.spotify.madeForYou
import com.gpo.yoin.data.source.spotify.madeForYouKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Home's "Made For You" (owner 2026-11-15,「为你推荐」): the playlists Spotify
 * makes for this account — daylist, Discover Weekly, Release Radar, On Repeat,
 * the Daily Mixes — read from the Spotify app over App Remote
 * (data/source/spotify/SpotifyMadeForYou.kt), since the Web API won't serve
 * them. Spotify accounts only; the last list is kept per account so the shelf
 * shows at once, and read again each time Home comes up after a while
 * (daylist changes several times a day). A tap opens the playlist's page.
 */
class MadeForYouViewModel internal constructor(
    private val profiles: ProfileManager,
    private val store: MadeForYouStore,
) : ViewModel() {

    private val _items = MutableStateFlow<List<MadeForYouPlaylist>>(emptyList())
    val items: StateFlow<List<MadeForYouPlaylist>> = _items.asStateFlow()

    private var refresh: Job? = null
    private var readAt = 0L
    private var readFor: String? = null

    init {
        viewModelScope.launch {
            combine(profiles.activeProfileId, profiles.activeSource) { id, source ->
                id?.takeIf { source?.id == MediaId.PROVIDER_SPOTIFY }
            }.distinctUntilChanged().collect { spotifyProfile ->
                refresh?.cancel()
                readAt = 0L
                readFor = spotifyProfile
                _items.value = spotifyProfile?.let { withContext(Dispatchers.IO) { store.load(it) } }.orEmpty()
                if (spotifyProfile != null) onHomeShown()
            }
        }
    }

    /** Home came up: read the list again when the last read is old enough. */
    fun onHomeShown() {
        val profileId = readFor ?: return
        if (refresh?.isActive == true) return
        if (readAt != 0L && SystemClock.elapsedRealtime() - readAt < REFRESH_AFTER_MS) return
        val browser = SpotifyContentBridge.browser ?: return
        refresh = viewModelScope.launch {
            val shelf = browser.madeForYou().getOrNull()?.forHomeShelf() ?: return@launch
            if (readFor != profileId) return@launch
            readAt = SystemClock.elapsedRealtime()
            _items.value = shelf
            withContext(Dispatchers.IO) { store.save(profileId, shelf) }
        }
    }

    class Factory(private val container: AppContainer, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MadeForYouViewModel(
            profiles = container.profileManager,
            store = MadeForYouStore(context),
        ) as T
    }

    private companion object {
        const val REFRESH_AFTER_MS = 10L * 60 * 1_000
    }
}

/** The last Made For You shelf read, per account, so Home has it before App Remote is up. */
internal class MadeForYouStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(profileId: String): List<MadeForYouPlaylist> = runCatching {
        val array = JSONArray(prefs.getString(profileId, null) ?: return emptyList())
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val rawId = item.getString("id")
            MadeForYouPlaylist(
                rawId = rawId,
                title = item.getString("title"),
                subtitle = item.optString("subtitle"),
                imageUrl = item.optString("image").takeIf { it.isNotBlank() },
                kind = madeForYouKind(rawId),
            )
        }
    }.getOrDefault(emptyList())

    fun save(profileId: String, playlists: List<MadeForYouPlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.rawId)
                    .put("title", playlist.title)
                    .put("subtitle", playlist.subtitle)
                    .put("image", playlist.imageUrl.orEmpty())
            )
        }
        prefs.edit().putString(profileId, array.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "home_made_for_you"
    }
}

/**
 * The card's second line: who a Daily Mix or a Blend is made of (Spotify's own subtitle);
 * for the rest, whose it is — their long descriptions don't fit a card.
 */
internal fun MadeForYouPlaylist.cardSubtitle(): String = when (kind) {
    MadeForYouKind.Daylist -> "daylist"
    MadeForYouKind.DailyMix, MadeForYouKind.Blend, MadeForYouKind.Mix -> subtitle
    else -> "Spotify"
}
