package com.gpo.yoin.ui.home

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.data.highlight.LyricHighlightProcessor
import com.gpo.yoin.data.highlight.LyricHighlightSelector
import com.gpo.yoin.data.highlight.LyricHighlightSignals
import com.gpo.yoin.data.highlight.NanoStatus
import com.gpo.yoin.data.local.LyricHighlight
import com.gpo.yoin.data.local.LyricHighlightDao
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.player.PlaybackManager
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.seamDissolve
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinSerifTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Home's 「歌词一句」 (owner 2026-11-08): one lyric line Gemini Nano picked on this phone
 * (data/highlight/), on its song's cover wash. A tap plays the song from that line; the
 * small button draws another. Shown only where Nano is available, and only once there
 * is at least one highlight.
 */

/** The highlight on Home: the picked lines, its song, and why it was chosen. */
@Immutable
data class HomeLyricHighlight(
    val songId: MediaId,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: MediaId?,
    /** A [CoverRef] storage key. */
    val coverKey: String?,
    val durationMs: Long,
    /** The original lines, joined by "\n". */
    val text: String,
    val startMs: Long?,
    val kind: LyricHighlightSelector.Bucket?,
) {
    fun asTrack(): Track = Track(
        id = songId,
        title = title,
        artist = artist,
        artistId = null,
        album = album.ifBlank { null },
        albumId = albumId,
        coverArt = CoverRef.fromStorageKey(coverKey),
        durationSec = (durationMs / 1000L).toInt().takeIf { it > 0 },
        trackNumber = null,
        year = null,
        genre = null,
        userRating = null,
    )
}

/** The current highlight for Home, drawing another on request and playing one from its line. */
class LyricHighlightViewModel internal constructor(
    private val dao: LyricHighlightDao,
    private val processor: LyricHighlightProcessor,
    private val profiles: ProfileManager,
    private val playback: PlaybackManager,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Whether this device has Nano at all; Home leaves the section out entirely when it doesn't. */
    val available: StateFlow<Boolean> = processor.status
        .map { it == NanoStatus.AVAILABLE || BuildConfig.NANO_TEST }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            processor.status.value == NanoStatus.AVAILABLE || BuildConfig.NANO_TEST,
        )

    /** The Gemini Nano test copy's step log, newest first; always empty in a normal build. */
    val testLog: StateFlow<List<String>> = processor.events

    private val _card = MutableStateFlow<HomeLyricHighlight?>(null)
    val card: StateFlow<HomeLyricHighlight?> = _card.asStateFlow()

    init {
        // A new pick for each visit to Home (this ViewModel's life), a new account, or the first highlight
        // arriving; a later highlight doesn't swap a line out from under the reader.
        viewModelScope.launch {
            profiles.activeProfileId.collect { draw() }
        }
        viewModelScope.launch {
            processor.revision.collect { if (_card.value == null) draw() }
        }
    }

    /** Another line. */
    fun next() {
        viewModelScope.launch { draw() }
    }

    private var drawnAt = 0L

    /** Home came back into view: a fresh line when the last one has been up a while. */
    fun onHomeShown() {
        if (clock() - drawnAt >= ROTATE_AFTER_MS) next()
    }

    private suspend fun draw() {
        val next = withContext(Dispatchers.IO) { pick() }
        drawnAt = clock()
        _card.value = next
    }

    private suspend fun pick(): HomeLyricHighlight? {
        if (processor.status.value != NanoStatus.AVAILABLE) return null
        val profileId = profiles.activeProfileId.value ?: return null
        val source = profiles.activeSource.value ?: withTimeoutOrNull(SOURCE_WAIT_MS) {
            profiles.activeSource.first { it != null }
        } ?: return null
        val provider = source.id
        val rows = dao.usable(profileId, provider)
        if (rows.isEmpty()) return null
        val now = clock()
        val history = LyricHighlightSignals.load(dao, profileId, provider, now)
        val current = _card.value?.songId?.rawId
        val playing = playback.playbackState.value.currentTrack?.id?.rawId
        // Not the song playing now, and not the same line twice in a row while there are others.
        val pool = if (rows.size > 1 && current != null) rows.filter { it.songId != current } else rows
        val chosen = LyricHighlightSelector.nextToShow(
            shown = pool.map { LyricHighlightSelector.Shown(it.songId, it.lastShownAt) },
            bucketOf = { history.bucketOf(it, now) },
            now = now,
            exclude = playing.takeIf { pool.size > 1 },
        ) ?: return null
        val row = pool.first { it.songId == chosen.songId }
        dao.markShown(profileId, provider, row.songId, now)
        return row.toCard(history.bucketOf(row.songId, now))
    }

    /**
     * Plays the song alone, then moves to the picked line once it is playing. Without a synced line it just
     * plays from the top.
     */
    fun play(card: HomeLyricHighlight) {
        viewModelScope.launch {
            val source = profiles.activeSource.value ?: withTimeoutOrNull(SOURCE_WAIT_MS) {
                profiles.activeSource.first { it != null }
            } ?: return@launch
            if (source.id != card.songId.provider) return@launch
            playback.playSingle(track = card.asTrack(), source = source)
            val startMs = card.startMs?.takeIf { it > SEEK_LEAD_MS } ?: return@launch
            val started = withTimeoutOrNull(START_WAIT_MS) {
                playback.playbackState.first { state ->
                    state.currentTrack?.id == card.songId && state.isPlaying && state.duration > 0
                }
            } ?: return@launch
            if (startMs < started.duration) playback.seekTo(startMs - SEEK_LEAD_MS)
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LyricHighlightViewModel(
            dao = container.database.lyricHighlightDao(),
            processor = container.lyricHighlightProcessor,
            profiles = container.profileManager,
            playback = container.playbackManager,
        ) as T
    }

    private companion object {
        const val SOURCE_WAIT_MS = 4_000L
        const val START_WAIT_MS = 10_000L

        /** Coming back to Home after this long shows another line. */
        const val ROTATE_AFTER_MS = 20L * 60L * 1000L

        /** Land a breath before the line, so its first word isn't clipped. */
        const val SEEK_LEAD_MS = 600L
    }
}

private fun LyricHighlight.toCard(kind: LyricHighlightSelector.Bucket?): HomeLyricHighlight = HomeLyricHighlight(
    songId = MediaId(provider, songId),
    title = title,
    artist = artist,
    album = album,
    albumId = albumId.takeIf { it.isNotBlank() }?.let { MediaId(provider, it) },
    coverKey = coverArtId,
    durationMs = durationMs,
    text = text.orEmpty(),
    startMs = startMs,
    kind = kind,
)

private val LyricCardPadding = 16.dp
private val LyricCardArtSize = 76.dp

/**
 * The section: its title and one card. The card wears its cover's wash like Rediscover's song cards; the
 * lines are set in the serif so they read as a quote, under a small eyebrow for why this song came up.
 */
@Composable
internal fun LyricHighlightSection(
    card: HomeLyricHighlight,
    buildCoverArtUrl: (String) -> String,
    extractBackdropColors: Boolean,
    onPlay: (HomeLyricHighlight) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    testLog: List<String> = emptyList(),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeSectionTitle(text = stringResource(HomeSection.LyricHighlight.titleRes))
        LyricHighlightCard(
            card = card,
            coverArtUrl = remember(card.coverKey, buildCoverArtUrl) {
                when (val ref = CoverRef.fromStorageKey(card.coverKey)) {
                    null -> null
                    is CoverRef.Url -> ref.url
                    is CoverRef.SourceRelative -> buildCoverArtUrl(ref.coverArtId)
                }
            },
            extractBackdropColors = extractBackdropColors,
            onPlay = { onPlay(card) },
            onNext = onNext,
            modifier = Modifier.fillMaxWidth(),
        )
        if (testLog.isNotEmpty()) NanoTestLog(lines = testLog, withTitle = false, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The Gemini Nano test copy's log (BuildConfig.NANO_TEST): what Nano is doing, newest first, so a run on a real
 * phone can be read without adb. Never shown in a normal build (its log stays empty).
 */
@Composable
internal fun NanoTestLog(lines: List<String>, withTitle: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (withTitle) HomeSectionTitle(text = stringResource(HomeSection.LyricHighlight.titleRes))
        Surface(
            shape = YoinContainerShapes.Card,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(LyricCardPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Nano test",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricHighlightCard(
    card: HomeLyricHighlight,
    coverArtUrl: String?,
    extractBackdropColors: Boolean,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberActivityCardColors(coverArtUrl, extractBackdropColors)
    Surface(
        modifier = modifier
            .elasticPress(interactionSource)
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = colors.container,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClickLabel = stringResource(R.string.home_lyric_play_from_line),
                    onClick = onPlay,
                )
                .padding(LyricCardPadding),
            horizontalArrangement = Arrangement.spacedBy(LyricCardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(LyricCardArtSize)) {
                WidgetBackdropArtwork(
                    model = coverArtUrl,
                    kind = WidgetShapeKind.Song,
                    contentDescription = card.title,
                    extractBackdropColors = extractBackdropColors,
                    interactionSource = interactionSource,
                    modifier = Modifier.size(LyricCardArtSize),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .seamFade(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                card.kind?.let { kind ->
                    Text(
                        text = stringResource(kind.labelRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.ink,
                        maxLines = 1,
                    )
                }
                Text(
                    text = card.text,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = YoinSerifTitle,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                    ),
                    color = colors.content,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(card.title, card.artist).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.contentMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onNext,
                enabled = homeEditInteractive(),
                modifier = Modifier.align(Alignment.Top),
            ) {
                Icon(
                    imageVector = YoinSymbols.Refresh,
                    contentDescription = stringResource(R.string.home_lyric_next),
                    tint = colors.contentMuted,
                )
            }
        }
    }
}

private val LyricHighlightSelector.Bucket.labelRes: Int
    get() = when (this) {
        LyricHighlightSelector.Bucket.HEAVY -> R.string.home_lyric_kind_heavy
        LyricHighlightSelector.Bucket.REDISCOVER -> R.string.home_lyric_kind_rediscover
        LyricHighlightSelector.Bucket.FRESH -> R.string.home_lyric_kind_fresh
    }
