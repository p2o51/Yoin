package com.gpo.yoin.ui.home

import com.gpo.yoin.ui.component.handPointer
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEventDao
import com.gpo.yoin.data.local.OtherProfilePlayAggregate
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.profile.ProfileCredentials
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.remote.ServerCredentials
import com.gpo.yoin.data.remote.SubsonicApiFactory
import com.gpo.yoin.data.repository.ActivityContext
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.data.source.spotify.SpotifyElsewhere
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.settings.ProfileCard
import com.gpo.yoin.ui.settings.profileCardsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Home's "From your other profiles" (owner 2026-11-04,「在你的其他资料里」):
 * the albums and playlists the user's other accounts play most, from Yoin's
 * own play log, so it needs no network and shows at once. A tap switches to
 * that account (Home's switcher, so the header follows) and plays it; the
 * first tap ever says so first, since switching stops what is playing.
 */

/** One album or playlist another account plays often. */
@Immutable
data class OtherProfileItem(
    val profileId: String,
    val profileName: String,
    /** The account's avatar shape and service, for the mark before its name. */
    val profileShape: Int = 0,
    val profileProvider: ProviderKind = ProviderKind.SUBSONIC,
    val kind: Kind,
    val provider: String,
    val rawId: String,
    val title: String,
    val subtitle: String,
    /** The storage key the play log keeps (a URL, or a Subsonic cover id). */
    val coverKey: String?,
    val coverUrl: String?,
) {
    enum class Kind { Album, Playlist }

    val stableId: String get() = "other-profile:$profileId:${kind.name}:$rawId"

    val mediaId: MediaId get() = MediaId(provider, rawId)
}

/**
 * The shelf from the play log's [rows] (most played first): only accounts
 * that can be switched to ([cards] without an unavailable reason), at most
 * [perProfile] from each, the accounts taking turns (the one with the most
 * played item first) up to [total].
 */
internal fun buildOtherProfileItems(
    rows: List<OtherProfilePlayAggregate>,
    cards: List<ProfileCard>,
    perProfile: Int = OtherProfilesPerProfile,
    total: Int = OtherProfilesTotal,
    coverUrl: (profileId: String, coverKey: String) -> String?,
): List<OtherProfileItem> {
    val usable = cards
        .filter { !it.isActive && it.unavailableReason == null && !it.requiresReconnect }
        .associateBy { it.id }
    val byProfile = LinkedHashMap<String, MutableList<OtherProfileItem>>()
    val seen = HashSet<String>()
    for (row in rows) {
        val card = usable[row.profileId] ?: continue
        val kind = when (row.entityType) {
            ActivityEntityType.ALBUM.name -> OtherProfileItem.Kind.Album
            ActivityEntityType.PLAYLIST.name -> OtherProfileItem.Kind.Playlist
            else -> continue
        }
        val rawId = MediaId.storedRawId(row.provider, row.entityId)
        if (rawId.isBlank() || row.title.isBlank()) continue
        val list = byProfile.getOrPut(row.profileId) { mutableListOf() }
        if (list.size >= perProfile) continue
        val item = OtherProfileItem(
            profileId = row.profileId,
            profileName = card.title,
            profileShape = card.avatarShape,
            profileProvider = card.provider,
            kind = kind,
            provider = row.provider,
            rawId = rawId,
            title = row.title,
            subtitle = row.subtitle,
            coverKey = row.coverArtId?.takeIf { it.isNotBlank() },
            coverUrl = row.coverArtId?.takeIf { it.isNotBlank() }?.let { coverUrl(row.profileId, it) },
        )
        if (seen.add(item.stableId)) list += item
    }
    val lists = byProfile.values.filter { it.isNotEmpty() }
    return buildList {
        var index = 0
        while (size < total && lists.any { index < it.size }) {
            for (list in lists) {
                if (size >= total) break
                list.getOrNull(index)?.let(::add)
            }
            index++
        }
    }
}

internal const val OtherProfilesPerProfile = 6
internal const val OtherProfilesTotal = 12
private const val OtherProfilesWindowMs = 90L * 24 * 60 * 60 * 1_000
private const val OtherProfilesCoverSize = 320

/** The shelf's items, its one-off notice, and playing an item once its account is in use. */
class OtherProfilesViewModel internal constructor(
    private val activityEvents: ActivityEventDao,
    private val profiles: ProfileManager,
    profileCards: Flow<List<ProfileCard>>,
    private val playback: PlaybackManager,
    private val repository: YoinRepository,
    private val noticeStore: OtherProfilesNoticeStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<OtherProfileItem>> = profiles.activeProfileId
        .flatMapLatest { activeId ->
            if (activeId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    activityEvents.observeOtherProfilesMostPlayed(
                        activeProfileId = activeId,
                        since = clock() - OtherProfilesWindowMs,
                    ),
                    profileCards,
                ) { rows, cards ->
                    // Each Subsonic account's server, read once per pass, for its cover ids.
                    val servers = rows
                        .filter { it.provider == MediaId.PROVIDER_SUBSONIC }
                        .map { it.profileId }
                        .distinct()
                        .associateWith { subsonicCredentials(it) }
                    buildOtherProfileItems(rows, cards) { profileId, key -> resolveCover(key, servers[profileId]) }
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _spotifyElsewhere = MutableStateFlow<SpotifyElsewhere?>(null)

    /** Another signed-in Spotify account's playback, for Home's header (null = none, or shown already). */
    val spotifyElsewhere: StateFlow<SpotifyElsewhere?> = _spotifyElsewhere.asStateFlow()
    private var spotifyPeek: Job? = null

    init {
        // An answer for the account that asked: a switch drops it.
        viewModelScope.launch {
            profiles.activeProfileId.drop(1).collect {
                spotifyPeek?.cancel()
                _spotifyElsewhere.value = null
            }
        }
    }

    /** One look (the app opened or came back): Web API only, so Spotify is never woken for it. */
    fun peekSpotifyElsewhere() {
        if (spotifyPeek?.isActive == true) return
        _spotifyElsewhere.value = null
        val asked = profiles.activeProfileId.value
        spotifyPeek = viewModelScope.launch {
            val answer = profiles.peekSpotifyElsewhere()
            if (profiles.activeProfileId.value == asked) _spotifyElsewhere.value = answer
        }
    }

    /** Shown its 5 s (or Yoin plays): gone until the next look. */
    fun dismissSpotifyElsewhere() {
        _spotifyElsewhere.value = null
    }

    private val _noticeSeen = MutableStateFlow(noticeStore.seen)
    val noticeSeen: StateFlow<Boolean> = _noticeSeen.asStateFlow()

    fun markNoticeSeen() {
        if (_noticeSeen.value) return
        _noticeSeen.value = true
        noticeStore.seen = true
    }

    // A URL key is the cover itself; anything else is a Subsonic cover id,
    // resolved with that account's own server.
    private fun resolveCover(key: String, server: ServerCredentials?): String? =
        when (val ref = CoverRef.fromStorageKey(key)) {
            is CoverRef.Url -> ref.url
            is CoverRef.SourceRelative ->
                server?.let { SubsonicApiFactory.buildCoverArtUrl(it, ref.coverArtId, OtherProfilesCoverSize) }
            null -> null
        }

    private suspend fun subsonicCredentials(profileId: String): ServerCredentials? {
        val profile = profiles.profiles.first().firstOrNull { it.id == profileId } ?: return null
        val credentials = profiles.decodeCredentials(profile) as? ProfileCredentials.Subsonic ?: return null
        return ServerCredentials(credentials.serverUrl, credentials.username, credentials.password)
    }

    /**
     * Play [item], its account now in use (Home's switcher landed on it). The
     * read runs in [PlaybackManager], so leaving Home meanwhile doesn't drop it.
     */
    fun play(item: OtherProfileItem) {
        viewModelScope.launch {
            if (profiles.activeProfileId.value != item.profileId) return@launch
            val source = profiles.activeSource.value
                ?: withTimeoutOrNull(SourceWaitMs) { profiles.activeSource.first { it != null } }
                ?: return@launch
            if (source.id != item.provider) return@launch
            val id = item.mediaId
            when (item.kind) {
                OtherProfileItem.Kind.Album -> playback.playWhenLoaded(
                    source = source,
                    activityContext = ActivityContext.Album(
                        albumId = item.rawId,
                        albumName = item.title,
                        artistName = item.subtitle.takeIf { it.isNotBlank() },
                        coverArtId = item.coverKey,
                    ),
                    explicitStart = false,
                ) { repository.getAlbum(id)?.tracks.orEmpty() }
                OtherProfileItem.Kind.Playlist -> playback.playWhenLoaded(
                    source = source,
                    activityContext = ActivityContext.Playlist(
                        playlistId = item.rawId,
                        playlistName = item.title,
                        coverArtId = item.coverKey,
                    ),
                    explicitStart = false,
                ) { repository.getPlaylist(id)?.tracks.orEmpty() }
            }
        }
    }

    class Factory(private val container: AppContainer, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OtherProfilesViewModel(
            activityEvents = container.database.activityEventDao(),
            profiles = container.profileManager,
            profileCards = profileCardsFlow(container),
            playback = container.playbackManager,
            repository = container.repository,
            noticeStore = OtherProfilesNoticeStore(context),
        ) as T
    }

    private companion object {
        const val SourceWaitMs = 10_000L
    }
}

/** Whether the "this switches accounts" notice has been shown; once per install. */
internal class OtherProfilesNoticeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var seen: Boolean
        get() = prefs.getBoolean(KEY_SEEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SEEN, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "home_other_profiles"
        const val KEY_SEEN = "switch_notice_seen"
    }
}

/**
 * The one-off notice on the shelf's first tap: playing it switches to
 * [OtherProfileItem.profileName], which stops what is playing now.
 */
@Composable
internal fun OtherProfileSwitchSheet(
    item: OtherProfileItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AccountSwitchNoticeSheet(
        title = stringResource(R.string.home_other_profiles_switch_title, item.profileName),
        body = stringResource(R.string.home_other_profiles_switch_body, item.title),
        confirm = stringResource(R.string.home_other_profiles_switch_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * The one-off "this switches accounts" notice, shared by Home's other-accounts shelf and Memories (one
 * [OtherProfilesNoticeStore] flag for both): switching stops what is playing now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSwitchNoticeSheet(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    YoinModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.handPointer()) {
                    Text(stringResource(R.string.home_other_profiles_switch_cancel))
                }
                Button(onClick = onConfirm, modifier = Modifier.handPointer()) {
                    Text(confirm)
                }
            }
        }
    }
}
