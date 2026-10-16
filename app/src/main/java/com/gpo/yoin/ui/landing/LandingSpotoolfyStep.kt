package com.gpo.yoin.ui.landing

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter.CommitResult
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter.ReceiveResult
import com.gpo.yoin.data.migration.spotoolfy.SpotoolfyImporter.StageResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.landing.guide.SpotoolfyMark
import com.gpo.yoin.ui.landing.guide.spotoolfyLaunchIntent
import com.gpo.yoin.ui.settings.service.SetupService
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// The landing's "Using Spotoolfy?" step (docs/spotoolfy-migration.md §5): before Spotify's connect step, so the
// user comes back from Spotoolfy with the file and the Client ID together. The file is only staged here; it goes
// into the Spotify account the moment that account connects (LandingScreen calls commitTo).

internal enum class SpotoolfyChoice { Using, NotUsing }

internal enum class SpotoolfyFailure(@param:StringRes val message: Int) {
    NotSpotoolfy(R.string.landing_spotoolfy_failed_not_spotoolfy),
    Corrupt(R.string.landing_spotoolfy_failed_corrupt),
    TooLarge(R.string.landing_spotoolfy_failed_too_large),
    Empty(R.string.landing_spotoolfy_failed_empty),
    AlreadyImported(R.string.landing_spotoolfy_failed_already),
    Unreadable(R.string.landing_spotoolfy_failed_unreadable),
}

/** Where the step's file is. */
internal sealed interface SpotoolfyImport {
    data object None : SpotoolfyImport

    data object Reading : SpotoolfyImport

    /** Staged: read, recap ready, waiting for the Spotify account. */
    data class Received(val batchId: String, val noteCount: Int, val ratingCount: Int) : SpotoolfyImport

    data class Failed(val failure: SpotoolfyFailure) : SpotoolfyImport

    /** In [accountName]'s account. */
    data class Written(val batchId: String, val noteCount: Int, val ratingCount: Int, val accountName: String) :
        SpotoolfyImport

    /** The account already held the user's own data, so nothing was written (§4.1). */
    data object NotWritten : SpotoolfyImport
}

@Immutable
internal data class SpotoolfyStepState(
    val choice: SpotoolfyChoice? = null,
    val import: SpotoolfyImport = SpotoolfyImport.None,
    /** Spotoolfy is on this phone: the step opens it; otherwise it asks for the exported file. */
    val installed: Boolean = false,
) {
    /** A file read but not in any account yet. */
    val unwritten: Boolean get() = choice == SpotoolfyChoice.Using && import is SpotoolfyImport.Received

    val batchId: String?
        get() = when (val current = import) {
            is SpotoolfyImport.Received -> current.batchId
            is SpotoolfyImport.Written -> current.batchId
            else -> null
        }
}

internal sealed interface SpotoolfyStepEvent {
    /** A file was just read: show its recap. */
    data class OpenRecap(val batchId: String) : SpotoolfyStepEvent

    /** The file couldn't be used. */
    data object Refused : SpotoolfyStepEvent
}

/**
 * The step's state machine over [SpotoolfyImporter]. Files reach it two ways: the share sheet ("Import to Yoin",
 * which lands in the importer's pending file) and the file picker. It only reads a waiting file while the step is
 * on screen ([setShown]): a file shared before the landing opened waits for this step (§5.4).
 */
internal class SpotoolfyStepViewModel(
    private val importer: SpotoolfyImporter,
    private val openStream: (Uri) -> InputStream?,
    installed: () -> Boolean,
    private val unknownTitle: String,
    /** The file went into an account: its tracks' albums can be looked up now. */
    private val onWritten: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SpotoolfyStepState(installed = installed()))
    val state: StateFlow<SpotoolfyStepState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SpotoolfyStepEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SpotoolfyStepEvent> = _events.asSharedFlow()

    private val staging = Mutex()
    private var shown = false

    init {
        viewModelScope.launch {
            // A batch staged earlier in this landing (the process went meanwhile) is still this step's file.
            importer.staged()?.let { batch ->
                _state.update { state ->
                    if (state.import != SpotoolfyImport.None) {
                        state
                    } else {
                        state.copy(
                            choice = SpotoolfyChoice.Using,
                            import = SpotoolfyImport.Received(batch.batchId, batch.noteCount, batch.ratingCount),
                        )
                    }
                }
            }
            importer.arrivals.collect { if (shown) readPending() }
        }
    }

    fun setShown(isShown: Boolean) {
        shown = isShown
        if (isShown) viewModelScope.launch { readPending() }
    }

    fun refreshInstalled(installed: Boolean) {
        _state.update { it.copy(installed = installed) }
    }

    fun choose(choice: SpotoolfyChoice) {
        val batchId = _state.value.batchId
        _state.update { it.copy(choice = choice) }
        // "No" after a file was read: it must not reach the account, or comes back out of it. A commit still
        // running finishes first (the importer takes turns), so either way nothing of the file stays.
        if (choice == SpotoolfyChoice.NotUsing && batchId != null) {
            viewModelScope.launch {
                importer.discard(batchId)
                _state.update { it.copy(import = SpotoolfyImport.None) }
            }
        }
    }

    /** A file picked with the system picker: copied in, then read like a shared one. */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(choice = SpotoolfyChoice.Using, import = SpotoolfyImport.Reading) }
            val received = withContext(io) {
                runCatching { openStream(uri)?.let(importer::receive) }.getOrNull() ?: ReceiveResult.Unreadable
            }
            when (received) {
                ReceiveResult.Received -> readPending()
                ReceiveResult.TooLarge -> fail(SpotoolfyFailure.TooLarge)
                ReceiveResult.Unreadable -> fail(SpotoolfyFailure.Unreadable)
            }
        }
    }

    /**
     * "Use another file": the current file goes, from the account too if it was already written (§4.7), including
     * by a commit still running (it finishes first, then this takes it back).
     */
    fun replace() {
        val batchId = _state.value.batchId
        _state.update { it.copy(choice = SpotoolfyChoice.Using, import = SpotoolfyImport.None) }
        if (batchId != null) viewModelScope.launch { importer.discard(batchId) }
    }

    fun reopenRecap() {
        _state.value.batchId?.let { _events.tryEmit(SpotoolfyStepEvent.OpenRecap(it)) }
    }

    /** Spotify connected as [profileId]: the staged file goes into that account, if it holds nothing yet. */
    fun commitTo(profileId: String, accountName: String) {
        val received = _state.value.takeIf { it.unwritten }?.import as? SpotoolfyImport.Received ?: return
        viewModelScope.launch {
            val next = when (val result = importer.commit(received.batchId, profileId, unknownTitle)) {
                is CommitResult.Committed -> {
                    onWritten()
                    SpotoolfyImport.Written(received.batchId, result.noteCount, result.ratingCount, accountName)
                }
                CommitResult.NotEligible -> SpotoolfyImport.NotWritten
                CommitResult.AlreadyInYoin -> SpotoolfyImport.Failed(SpotoolfyFailure.AlreadyImported)
                CommitResult.Missing -> SpotoolfyImport.Failed(SpotoolfyFailure.Unreadable)
            }
            _state.update { state -> if (state.import == received) state.copy(import = next) else state }
        }
    }

    /** The landing ends without an account for the file: it, and anything still waiting, goes. */
    suspend fun discardUnwritten() {
        importer.discardStaged()
        importer.clearPending()
        _state.update { state ->
            if (state.import is SpotoolfyImport.Received) state.copy(import = SpotoolfyImport.None) else state
        }
    }

    private suspend fun readPending(): Unit = staging.withLock {
        if (!importer.hasPending()) return@withLock
        val previous = _state.value.import
        _state.update { it.copy(choice = SpotoolfyChoice.Using, import = SpotoolfyImport.Reading) }
        when (val result = importer.stagePending()) {
            is StageResult.Staged -> {
                // A new file after one was already written: the new one replaces it (§4.7).
                if (previous is SpotoolfyImport.Written && previous.batchId != result.batchId) {
                    importer.discard(previous.batchId)
                }
                val recap = result.recap
                _state.update {
                    it.copy(import = SpotoolfyImport.Received(result.batchId, recap.noteCount, recap.ratedTrackCount))
                }
                _events.tryEmit(SpotoolfyStepEvent.OpenRecap(result.batchId))
            }
            // The very file already written in this landing: nothing changes.
            StageResult.AlreadyImported ->
                if (previous is SpotoolfyImport.Written) {
                    _state.update { it.copy(import = previous) }
                } else {
                    fail(SpotoolfyFailure.AlreadyImported)
                }
            StageResult.NotSpotoolfy -> fail(SpotoolfyFailure.NotSpotoolfy)
            StageResult.Corrupt -> fail(SpotoolfyFailure.Corrupt)
            StageResult.TooLarge -> fail(SpotoolfyFailure.TooLarge)
            StageResult.Empty -> fail(SpotoolfyFailure.Empty)
        }
        Unit
    }

    private fun fail(failure: SpotoolfyFailure) {
        _state.update { it.copy(import = SpotoolfyImport.Failed(failure)) }
        _events.tryEmit(SpotoolfyStepEvent.Refused)
    }

    class Factory(private val context: Context, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val app = context.applicationContext
            return SpotoolfyStepViewModel(
                importer = container.spotoolfyImporter,
                openStream = { uri -> app.contentResolver.openInputStream(uri) },
                installed = { spotoolfyLaunchIntent(app) != null },
                unknownTitle = app.getString(R.string.landing_spotoolfy_unknown_title),
                onWritten = { container.trackAlbumLinkEnricher.kick() },
            ) as T
        }
    }
}

/** The pill's main button on this step. */
internal fun spotoolfyPrimary(state: SpotoolfyStepState): LandingPrimary = when {
    state.choice == null -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Unavailable)
    state.choice == SpotoolfyChoice.NotUsing -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled)
    state.import == SpotoolfyImport.Reading -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Unavailable)
    state.import is SpotoolfyImport.Received ||
        state.import is SpotoolfyImport.Written ||
        state.import == SpotoolfyImport.NotWritten -> LandingPrimary(R.string.landing_next, LandingPrimary.Style.Filled)
    state.installed -> LandingPrimary(R.string.landing_spotoolfy_open, LandingPrimary.Style.Filled)
    else -> LandingPrimary(R.string.landing_spotoolfy_pick, LandingPrimary.Style.Filled)
}

@Composable
internal fun SpotoolfyScene(
    container: AppContainer,
    vm: SpotoolfyStepViewModel,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val setup = rememberSetupViewModel(container, SetupService.Spotify)
    val setupState by setup.uiState.collectAsState()
    // Back from Spotoolfy: the window regains focus and may read the clipboard. A 32-hex string is the Client ID
    // the guide asked for; it goes where the Spotify step reads it, unless an ID of the user's own is set already.
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        vm.refreshInstalled(spotoolfyLaunchIntent(context) != null)
        if (state.choice != SpotoolfyChoice.Using) return@LaunchedEffect
        val own = setupState.spotify.clientId.isNotBlank() && !setupState.spotify.usesBuildFallback
        if (!own) readClientIdFromClipboard(context)?.let(setup::saveSpotifyClientId)
    }
    LandingSceneColumn {
        SpotoolfyHeader()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TokenOption(
                title = R.string.landing_spotoolfy_yes_title,
                body = R.string.landing_spotoolfy_yes_body,
                selected = state.choice == SpotoolfyChoice.Using,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                onClick = { vm.choose(SpotoolfyChoice.Using) },
            )
            TokenOption(
                title = R.string.landing_spotoolfy_no_title,
                body = R.string.landing_spotoolfy_no_body,
                selected = state.choice == SpotoolfyChoice.NotUsing,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
                onClick = { vm.choose(SpotoolfyChoice.NotUsing) },
            )
        }
        AnimatedVisibility(
            visible = state.choice == SpotoolfyChoice.Using,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard) +
                expandVertically(YoinMotion.defaultSpatialSpec()),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) +
                shrinkVertically(YoinMotion.defaultSpatialSpec()),
        ) {
            AnimatedContent(
                targetState = state.import,
                contentKey = { it::class },
                transitionSpec = {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard)
                        .togetherWith(YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast))
                },
                label = "spotoolfyImport",
                modifier = Modifier.padding(top = 12.dp),
            ) { import ->
                SpotoolfyImportStatus(
                    import = import,
                    installed = state.installed,
                    onPickFile = onPickFile,
                    onRecap = vm::reopenRecap,
                    onReplace = vm::replace,
                )
            }
        }
    }
}

@Composable
private fun SpotoolfyHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(bottom = 16.dp),
    ) {
        SpotoolfyMark(size = 48.dp)
        Column {
            Text(
                text = stringResource(R.string.guide_spotoolfy_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.landing_spotoolfy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpotoolfyImportStatus(
    import: SpotoolfyImport,
    installed: Boolean,
    onPickFile: () -> Unit,
    onRecap: () -> Unit,
    onReplace: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when (import) {
        SpotoolfyImport.None -> if (installed) {
            TextButton(onClick = onPickFile) { Text(stringResource(R.string.landing_spotoolfy_pick_instead)) }
        }
        SpotoolfyImport.Reading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            YoinLoadingIndicator(size = 24.dp)
            Text(stringResource(R.string.landing_spotoolfy_reading), style = MaterialTheme.typography.bodyLarge)
        }
        is SpotoolfyImport.Received -> ImportCard(
            title = if (import.noteCount > 0) {
                pluralStringResource(R.plurals.landing_spotoolfy_received_notes, import.noteCount, import.noteCount)
            } else {
                val count = import.ratingCount
                pluralStringResource(R.plurals.landing_spotoolfy_received_ratings, count, count)
            },
            body = stringResource(R.string.landing_spotoolfy_received_body),
            onRecap = onRecap,
            onReplace = onReplace,
        )
        is SpotoolfyImport.Written -> ImportCard(
            title = if (import.noteCount > 0) {
                pluralStringResource(
                    R.plurals.landing_spotoolfy_written_notes,
                    import.noteCount,
                    import.noteCount,
                    import.accountName,
                )
            } else {
                pluralStringResource(
                    R.plurals.landing_spotoolfy_written_ratings,
                    import.ratingCount,
                    import.ratingCount,
                    import.accountName,
                )
            },
            body = null,
            onRecap = onRecap,
            onReplace = onReplace,
        )
        SpotoolfyImport.NotWritten -> Text(
            text = stringResource(R.string.landing_spotoolfy_not_written),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        is SpotoolfyImport.Failed -> Column {
            ErrorLine(stringResource(import.failure.message))
            // Without Spotoolfy the pill already chooses a file; with it, the pill opens Spotoolfy.
            if (installed) {
                TextButton(onClick = onPickFile, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.landing_spotoolfy_pick))
                }
            }
        }
    }
}

@Composable
private fun ImportCard(title: String, body: String?, onRecap: () -> Unit, onReplace: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceBright)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(YoinSymbols.Check, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurface,
            )
        }
        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        }
        Row(modifier = Modifier.padding(start = 22.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onRecap) { Text(stringResource(R.string.landing_spotoolfy_recap_again)) }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onReplace) { Text(stringResource(R.string.landing_spotoolfy_replace)) }
        }
    }
}
