package com.gpo.yoin

import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.ui.component.LocalAiProvider
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.notes.editor.LocalWindowTaps
import com.gpo.yoin.ui.notes.editor.WindowTaps
import com.gpo.yoin.ui.notes.editor.windowTapSource
import com.gpo.yoin.ui.nowplaying.LocalCoverDarkScheme
import com.gpo.yoin.ui.nowplaying.LocalCoverField
import com.gpo.yoin.ui.nowplaying.LocalCoverNeighbours
import com.gpo.yoin.ui.nowplaying.roomNeighbourIndices
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.CoverSizes
import com.gpo.yoin.player.PlaybackState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.gpo.yoin.data.notes.MentionAccount
import com.gpo.yoin.ui.notes.LocalNoteBacklinks
import com.gpo.yoin.ui.notes.LocalNoteMentions
import com.gpo.yoin.ui.notes.NoteMentionController
import com.gpo.yoin.ui.notes.editor.LocalMentionCandidates
import com.gpo.yoin.ui.notes.editor.MentionCandidateSource
import com.gpo.yoin.ui.notes.editor.RepositoryMentionCandidateSource
import com.gpo.yoin.ui.notes.openNoteDestination
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.media3.common.Player
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.gpo.yoin.ui.component.LocalPlaybackProgressSmoother
import com.gpo.yoin.ui.component.LocalPlaybackWaveState
import com.gpo.yoin.ui.component.ProvideSeamBarField
import com.gpo.yoin.ui.detail.findActivityOrNull
import com.gpo.yoin.ui.experience.CoveredWindowAnimationGate
import com.gpo.yoin.ui.experience.LocalMotionCapabilityProvider
import com.gpo.yoin.ui.experience.LocalShellChromeInsets
import com.gpo.yoin.ui.experience.LocalWindowCovered
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionCapabilityProvider
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.motionProfileValues
import com.gpo.yoin.ui.experience.rememberShellChromeInsets
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.CoverNeighbours
import com.gpo.yoin.ui.theme.CoverSeedExtractor
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Transparent edge-to-edge for every Yoin Activity. Call before `setContent`
 * so the window is configured before the first frame (avoids inset jumps on
 * cold start). `SystemBarStyle.auto` picks the icon colours for the theme the
 * window starts in: dark icons in light theme, light icons in dark theme.
 *
 * `auto` decides only once, here. An Activity that handles `uiMode` itself
 * (MainActivity: the system's light / dark switch reaches it as a
 * configuration change, not a new Activity) would keep the old theme's icons,
 * so the icons are set again whenever night mode flips.
 */
fun ComponentActivity.enableYoinEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    followNightModeWithBarIcons()
    requestPeakRefreshRate()
}

/**
 * Keeps the window's bar icons on the theme across a light / dark switch the
 * Activity handles without being recreated. Runs before Compose recomposes for
 * the new theme, so a page that overrides the icons (the album's spectrum bar)
 * sets its own again afterwards.
 */
private fun ComponentActivity.followNightModeWithBarIcons() {
    var night = resources.configuration.isNightMode()
    addOnConfigurationChangedListener { configuration ->
        val nowNight = configuration.isNightMode()
        if (nowNight != night) {
            night = nowNight
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !nowNight
                isAppearanceLightNavigationBars = !nowNight
            }
        }
    }
}

private fun Configuration.isNightMode(): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/**
 * Opt the window into the display's fastest refresh mode at the CURRENT
 * resolution. The app never asked before, and several OEMs (foldables
 * especially) hold un-opted apps at 60Hz via adaptive-refresh heuristics —
 * every spring in the app then paces at 60 even though nothing in code caps
 * it. preferredDisplayModeId (not preferredRefreshRate) because the soft
 * hint is exactly what those heuristics ignore.
 */
private fun ComponentActivity.requestPeakRefreshRate() {
    val display = if (Build.VERSION.SDK_INT >= 30) display else return
    val current = display?.mode ?: return
    val best = display.supportedModes
        .filter {
            it.physicalWidth == current.physicalWidth &&
                it.physicalHeight == current.physicalHeight
        }
        .maxByOrNull { it.refreshRate }
        ?: return
    if (best.modeId != current.modeId) {
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }
}

/**
 * Shared composition root for EVERY Yoin Activity (the main shell and each
 * detail Activity). Provides the cover-palette state, the theme keyed on the
 * current playback cover, the cover-color extraction, and the motion-profile
 * locals — so a detail Activity looks and animates exactly like the shell.
 *
 * Detail Activities read the same resolved palette (the same scheme instance)
 * from their first frame. Opening a window never resets playback colors.
 */
@Composable
fun YoinActivityRoot(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as? YoinApplication
    YoinTheme(playbackThemeState = app?.container?.playbackThemeState) {
        YoinAppEnvironment(content = content)
    }
}

@Composable
private fun YoinAppEnvironment(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as? YoinApplication
    val fallbackMotionCapabilityProvider = remember { MotionCapabilityProvider(lowRamDevice = false) }
    val motionCapabilityProvider = app?.container?.motionCapabilityProvider ?: fallbackMotionCapabilityProvider
    val motionProfile by motionCapabilityProvider.profile.collectAsState(initial = MotionProfile.Full)

    // The hero cover size, before any cover url is built; a fold opening raises it (CoverSizes).
    val windowSize = LocalWindowInfo.current.containerSize
    // Idempotent (returns at once after the first call); it must run before the content composes urls.
    if (app != null) CoverSizes.install(app)
    LaunchedEffect(windowSize) { CoverSizes.noteDisplay(windowSize.width, windowSize.height) }

    if (app != null) {
        val context = app.applicationContext
        val imageLoader = remember(context) { SingletonImageLoader.get(context) }
        // Only the fields the playback art reads: position ticks (every 250 ms) no longer
        // recompose this root, which every window composes. It starts from the playback
        // state as it is now, not an empty snapshot, so a window opening never sends
        // "no cover, empty queue" to the app-wide theme (that cleared the shared palette
        // and the neighbour edges for one frame, or for good if the next frame came late).
        val playbackFlow = app.container.playbackManager.playbackState
        val artwork by remember(app) {
            playbackFlow.map(::rootArtworkSnapshotOf).distinctUntilChanged()
        }.collectAsState(initial = remember(app) { rootArtworkSnapshotOf(playbackFlow.value) })

        val configurationRevision by app.container.musicConfigurationRevision.collectAsState()
        LaunchedEffect(artwork.coverArt, artwork.queueEmpty, configurationRevision) {
            val model = artwork.coverArt?.let { playbackCoverUrl(app.container.repository::resolveCoverUrl, it) }
            app.container.playbackThemeState.updateArtwork(
                model = model,
                clearWhenMissing = artwork.queueEmpty,
            ) { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(CoverSeedExtractor.BitmapSizePx, CoverSeedExtractor.BitmapSizePx))
                    .allowHardware(false)
                    .build()
                (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        }

        // Now Playing's room is framed by the songs before and after this one
        // (RoomAmbient, the iOS World recipe). The same URL as their hero when they
        // play (decoded at 200 px here), so this read also fetches the next song's
        // Now Playing cover, and a skip finds the cover that just played.
        LaunchedEffect(artwork.previousArt, artwork.nextArt, configurationRevision) {
            val resolve = app.container.repository::resolveCoverUrl
            app.container.playbackThemeState.updateNeighbours(
                previous = artwork.previousArt?.let { playbackCoverUrl(resolve, it) },
                next = artwork.nextArt?.let { playbackCoverUrl(resolve, it) },
            ) { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(Size(CoverSeedExtractor.BitmapSizePx, CoverSeedExtractor.BitmapSizePx))
                    .allowHardware(false)
                    .build()
                (imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
            }
        }
    }

    // Window size + fold posture, observed once per Activity. Drives the
    // Compact / Wide / Tabletop render dimension (orthogonal to stage mode)
    // and where the Button Group lives (ShellChromeForm).
    val windowInfo = rememberYoinWindowInfo()
    val shellChromeInsets = rememberShellChromeInsets(windowInfo)
    val activity = LocalContext.current.findActivityOrNull()
    val windowCovered = remember(activity) { CoveredWindowAnimationGate.coveredState(activity) }

    // Notes: mentions resolve for the account in use and open what they name; editors' @ rows.
    val noteScope = rememberCoroutineScope()
    val noteMentions = remember(app) {
        app?.container?.let { container ->
            NoteMentionController(
                resolver = container.mentionResolver,
                account = { container.profileManager.activeProfile.value?.let { MentionAccount(it.id, it.provider) } },
                scope = noteScope,
                trackAlbum = { container.repository.findTrackAlbum(it) },
                navigate = ::openNoteDestination,
                resolveCover = { key -> container.repository.resolveCoverUrl(CoverRef.fromStorageKey(key), 120) },
                artwork = { resolved ->
                    container.repository.mentionArtworkUrl(resolved.mention.kind, resolved.id, MentionArtworkSizePx)
                },
                accountChanges = container.profileManager.activeProfileId,
            )
        }
    }
    val windowTaps = remember { WindowTaps() }
    val mentionCandidates = remember(app) {
        app?.container?.let { container ->
            RepositoryMentionCandidateSource(
                repository = container.repository,
                nowPlaying = { container.playbackManager.playbackState.value.currentTrack },
            )
        } ?: MentionCandidateSource.None
    }

    // Copy names the AI service in use ("Ask DeepSeek"), and follows a switch in Settings.
    val aiPrefs = app?.container?.aiProviderStore?.prefs?.collectAsState()
    val aiProvider = aiPrefs?.value?.provider ?: AiProvider.Gemini

    CompositionLocalProvider(
        LocalAiProvider provides aiProvider,
        LocalNoteMentions provides noteMentions,
        LocalNoteBacklinks provides app?.container?.noteBacklinks,
        LocalMentionCandidates provides mentionCandidates,
        LocalWindowCovered provides windowCovered,
        LocalCoverField provides app?.container?.playbackThemeState?.coverField,
        LocalCoverDarkScheme provides app?.container?.playbackThemeState?.colorScheme(darkTheme = true),
        LocalCoverNeighbours provides (app?.container?.playbackThemeState?.neighbours ?: CoverNeighbours()),
        LocalPlaybackWaveState provides app?.container?.experienceSessionStore?.playbackWave,
        LocalPlaybackProgressSmoother provides app?.container?.playbackProgressSmoother,
        LocalMotionCapabilityProvider provides motionCapabilityProvider,
        // The motion tier, and Yoin Symbols' animated icons following it.
        *motionProfileValues(motionProfile),
        LocalYoinWindowInfo provides windowInfo,
        LocalShellChromeInsets provides shellChromeInsets,
        LocalWindowTaps provides windowTaps,
    ) {
        // Every tap in this window, heard (not taken) before the page: ends an editor's @ mode.
        Box(modifier = Modifier.windowTapSource(windowTaps), propagateMinConstraints = true) {
            // This window's floating bar, for the seams' bottom field (dissolve-final §1.2).
            ProvideSeamBarField(content = content)
        }
    }
}

/** The mention thumbnail and peek card's cover, requested once at the larger (peek) size. */
private const val MentionArtworkSizePx = 240

/** What the root's playback art reads from [PlaybackState]: the cover, whether the queue is empty, the neighbours. */
internal data class RootArtworkSnapshot(
    val coverArt: CoverRef? = null,
    val queueEmpty: Boolean = true,
    val previousArt: CoverRef? = null,
    val nextArt: CoverRef? = null,
)

internal fun rootArtworkSnapshotOf(state: PlaybackState): RootArtworkSnapshot {
    val (previousIndex, nextIndex) = roomNeighbourIndices(
        queueSize = state.queue.size,
        currentIndex = state.currentIndex,
        upcoming = state.upcoming,
        repeatAll = state.repeatMode == Player.REPEAT_MODE_ALL,
        repeatOne = state.repeatMode == Player.REPEAT_MODE_ONE,
        shuffle = state.shuffleEnabled,
    )
    return RootArtworkSnapshot(
        coverArt = state.currentTrack?.coverArt,
        queueEmpty = state.queue.isEmpty(),
        previousArt = previousIndex?.let { state.queue.getOrNull(it)?.coverArt },
        // The player's own next (shuffle + repeat-all wrap included) wins
        // over the non-wrapping upcoming list.
        nextArt = (state.nextTrack ?: nextIndex?.let { state.queue.getOrNull(it) })?.coverArt,
    )
}

/**
 * The URL the playback theme, its neighbour edges, Now Playing and the pill
 * load a playing song's cover from: one per cover ([CoverSizes.heroPx]).
 */
internal fun playbackCoverUrl(resolve: (CoverRef?, Int?) -> String?, cover: CoverRef): String? =
    resolve(cover, CoverSizes.heroPx)
