package com.gpo.yoin.lyra.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.lyra.data.LyraInboxItem
import com.gpo.yoin.lyra.data.LyraIncomingPull
import com.gpo.yoin.lyra.data.LyraJob
import com.gpo.yoin.lyra.data.LyraListenLaterItem
import com.gpo.yoin.lyra.data.LyraSessionState
import com.gpo.yoin.lyra.data.LyraShareView
import com.gpo.yoin.lyra.data.LyraSourceAlbumResponse
import com.gpo.yoin.lyra.data.LyraSourceItem
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.YoinSnackbarHost
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.theme.YoinContainerShapes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyraHubScreen(
    viewModel: LyraHubViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by viewModel.sessionState.collectAsState()
    val ui by viewModel.ui.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val pickAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadInboxAudioUris(uris)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val visibleTabs = remember(session.capabilities, session.isLyraServer) {
        buildList {
            if (!session.isLyraServer || session.capabilityEnabled("sources")) add(LyraHubTab.Sources)
            if (!session.isLyraServer || session.capabilityEnabled("inbox")) add(LyraHubTab.Inbox)
            if (!session.isLyraServer || session.capabilityEnabled("share_pass")) add(LyraHubTab.Passes)
            add(LyraHubTab.Server)
        }
    }
    val activeTab = if (ui.selectedTab in visibleTabs) ui.selectedTab else visibleTabs.first()

    ExpressivePageBackground(modifier = modifier) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.lyra_hub_title)) },
                    navigationIcon = { SettingsBackButton(onClick = onBack) },
                    actions = {
                        IconButton(onClick = viewModel::refreshSession) {
                            Icon(
                                imageVector = YoinSymbols.Refresh,
                                contentDescription = stringResource(R.string.lyra_server_refresh),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = { YoinSnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleTabs, key = { it.key }) { tab ->
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            label = { Text(stringResource(tab.labelRes())) },
                        )
                    }
                }

                if (!session.isConnected) {
                    DisconnectedCard(
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    when (activeTab) {
                        LyraHubTab.Sources -> SourcesTabContent(
                            session = session,
                            ui = ui,
                            onSelectSource = viewModel::selectSource,
                            onSelectType = viewModel::setSearchType,
                            onQueryChange = viewModel::updateSearchQuery,
                            onSearch = viewModel::searchSources,
                            onQueueItem = viewModel::queueSourceItem,
                            onOpenAlbum = viewModel::openSourceAlbum,
                            onCloseAlbum = viewModel::closeSourceAlbum,
                            onQueueAlbum = viewModel::queueSelectedAlbum,
                            bottomPadding = navBottom + 24.dp,
                        )
                        LyraHubTab.Inbox -> InboxTabContent(
                            ui = ui,
                            onUrlChange = viewModel::updateInboxUrl,
                            onSubmitUrl = viewModel::submitInboxUrl,
                            onPickAudio = { pickAudioLauncher.launch(arrayOf("audio/*")) },
                            onPromote = viewModel::promoteInboxItem,
                            onDiscard = viewModel::discardInboxItem,
                            onOpenServerTab = { viewModel.selectTab(LyraHubTab.Server) },
                            bottomPadding = navBottom + 24.dp,
                        )
                        LyraHubTab.Passes -> PassesTabContent(
                            ui = ui,
                            onPassUrlChange = viewModel::updatePassUrl,
                            onPullLibrary = { viewModel.pullPass("library") },
                            onPullInbox = { viewModel.pullPass("inbox") },
                            onCopyLink = { url ->
                                context.getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText("Lyra Pass", url))
                            },
                            onRevoke = viewModel::revokeShare,
                            onOpenServerTab = { viewModel.selectTab(LyraHubTab.Server) },
                            bottomPadding = navBottom + 24.dp,
                        )
                        LyraHubTab.Server -> ServerTabContent(
                            session = session,
                            ui = ui,
                            onOpenSync = onOpenSync,
                            onAdminTokenChange = viewModel::updateAdminTokenInput,
                            onSaveAdminToken = viewModel::saveAdminToken,
                            onClearAdminToken = viewModel::clearAdminToken,
                            bottomPadding = navBottom + 24.dp,
                        )
                    }
                }
            }
        }
    }
}

private fun LyraHubTab.labelRes(): Int = when (this) {
    LyraHubTab.Sources -> R.string.lyra_tab_sources
    LyraHubTab.Inbox -> R.string.lyra_tab_inbox
    LyraHubTab.Passes -> R.string.lyra_tab_passes
    LyraHubTab.Server -> R.string.lyra_tab_server
}

@Composable
private fun DisconnectedCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.lyra_server_not_connected),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.lyra_server_open_settings))
            }
        }
    }
}

@Composable
private fun SourcesTabContent(
    session: LyraSessionState,
    ui: LyraHubUiState,
    onSelectSource: (String) -> Unit,
    onSelectType: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onQueueItem: (LyraSourceItem) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onCloseAlbum: () -> Unit,
    onQueueAlbum: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    val activeSourceId = ui.selectedSourceId ?: session.sources.firstOrNull()?.sourceId
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (session.jobs.isNotEmpty()) {
            item(key = "jobs") {
                JobsQueueCard(jobs = session.jobs.take(5))
            }
        }
        if (session.sources.isEmpty()) {
            item(key = "no-sources") {
                InfoCard(text = stringResource(R.string.lyra_sources_none))
            }
            return@LazyColumn
        }
        item(key = "source-picker") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(session.sources, key = { it.sourceId }) { source ->
                    FilterChip(
                        selected = source.sourceId == activeSourceId,
                        onClick = { onSelectSource(source.sourceId) },
                        label = { Text(source.displayName) },
                    )
                }
            }
        }
        item(key = "search-bar") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ui.searchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.lyra_sources_search_hint)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    )
                    Button(onClick = onSearch, enabled = !ui.searching && ui.searchQuery.isNotBlank()) {
                        Text(stringResource(R.string.lyra_sources_search_action))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ui.searchType == "song",
                        onClick = { onSelectType("song") },
                        label = { Text(stringResource(R.string.lyra_sources_type_song)) },
                    )
                    FilterChip(
                        selected = ui.searchType == "album",
                        onClick = { onSelectType("album") },
                        label = { Text(stringResource(R.string.lyra_sources_type_album)) },
                    )
                }
            }
        }

        val albumDetail = ui.selectedAlbum
        if (ui.searching || ui.loadingAlbum) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    YoinLoadingIndicator()
                }
            }
        } else if (albumDetail != null) {
            item(key = "album-detail") {
                SourceAlbumCard(
                    detail = albumDetail,
                    queueingMid = ui.queueingMid,
                    onBack = onCloseAlbum,
                    onQueueAlbum = onQueueAlbum,
                    onQueueTrack = onQueueItem,
                )
            }
        } else if (ui.searchResults.isEmpty()) {
            item(key = "empty") {
                InfoCard(text = stringResource(R.string.lyra_sources_empty))
            }
        } else {
            items(ui.searchResults, key = { "${it.type}:${it.mid}:${it.title}" }) { item ->
                SourceResultRow(
                    item = item,
                    busy = ui.queueingMid == item.mid,
                    onAdd = { onQueueItem(item) },
                    onOpenAlbum = if (item.type == "album" && item.mid.isNotBlank()) {
                        { onOpenAlbum(item.mid) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun JobsQueueCard(jobs: List<LyraJob>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.lyra_jobs_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            jobs.forEach { job ->
                val statusText = when {
                    job.status == "running" -> stringResource(R.string.lyra_jobs_status_running)
                    job.isActive -> stringResource(R.string.lyra_jobs_status_queued)
                    job.isDone -> stringResource(R.string.lyra_jobs_status_done)
                    else -> stringResource(R.string.lyra_jobs_status_failed)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = job.displayTitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (job.isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceResultRow(
    item: LyraSourceItem,
    busy: Boolean,
    onAdd: () -> Unit,
    onOpenAlbum: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onOpenAlbum != null) {
                TextButton(onClick = onOpenAlbum) {
                    Text(stringResource(R.string.lyra_sources_view_tracks))
                }
            }
            FilledTonalButton(onClick = onAdd, enabled = !busy) {
                Text(
                    stringResource(
                        if (item.type == "album") R.string.lyra_sources_add_album else R.string.lyra_sources_add,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SourceAlbumCard(
    detail: LyraSourceAlbumResponse,
    queueingMid: String?,
    onBack: () -> Unit,
    onQueueAlbum: () -> Unit,
    onQueueTrack: (LyraSourceItem) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(detail.album.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = listOf(
                            detail.album.artist,
                            stringResource(R.string.lyra_sources_album_tracks_count, detail.tracks.size),
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onBack) {
                    Icon(YoinSymbols.Close, contentDescription = null)
                }
            }
            Button(
                onClick = onQueueAlbum,
                enabled = queueingMid != detail.album.mid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.lyra_sources_add_album))
            }
            detail.tracks.forEach { track ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onQueueTrack(track) },
                        enabled = queueingMid != track.mid,
                    ) {
                        Text(stringResource(R.string.lyra_sources_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxTabContent(
    ui: LyraHubUiState,
    onUrlChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onPickAudio: () -> Unit,
    onPromote: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onOpenServerTab: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.inboxNeedsAdminToken) {
            item(key = "admin-needed") {
                AdminTokenPromptCard(onOpenServerTab = onOpenServerTab)
            }
        }
        item(key = "inbox-input") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.inboxUrlInput,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.lyra_inbox_url_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSubmitUrl,
                        enabled = !ui.inboxBusy && ui.inboxUrlInput.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.lyra_inbox_add_url))
                    }
                    OutlinedButton(onClick = onPickAudio, enabled = !ui.inboxBusy) {
                        Text(stringResource(R.string.lyra_inbox_upload_audio))
                    }
                }
            }
        }
        if (ui.inboxItems.isEmpty()) {
            item(key = "inbox-empty") {
                InfoCard(text = stringResource(R.string.lyra_inbox_empty))
            }
        } else {
            items(ui.inboxItems, key = { it.id }) { item ->
                InboxItemRow(
                    item = item,
                    onPromote = { onPromote(item.id) },
                    onDiscard = { onDiscard(item.id) },
                )
            }
        }
        item(key = "later-header") {
            Text(
                text = stringResource(R.string.lyra_later_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (ui.laterItems.isEmpty()) {
            item(key = "later-empty") {
                InfoCard(text = stringResource(R.string.lyra_later_empty))
            }
        } else {
            items(ui.laterItems, key = { it.id }) { later ->
                LaterItemRow(item = later)
            }
        }
    }
}

@Composable
private fun InboxItemRow(
    item: LyraInboxItem,
    onPromote: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.filePath.ifBlank { item.originRef }.ifBlank { item.id },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(item.originKind, item.state, item.error).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.state == "ready") {
                    FilledTonalButton(onClick = onPromote) {
                        Text(stringResource(R.string.lyra_inbox_promote))
                    }
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.lyra_inbox_discard))
                }
            }
        }
    }
}

@Composable
private fun LaterItemRow(item: LyraListenLaterItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(item.title.ifBlank { item.ref }, style = MaterialTheme.typography.titleSmall)
            val sub = listOf(item.artist, item.note).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PassesTabContent(
    ui: LyraHubUiState,
    onPassUrlChange: (String) -> Unit,
    onPullLibrary: () -> Unit,
    onPullInbox: () -> Unit,
    onCopyLink: (String) -> Unit,
    onRevoke: (String) -> Unit,
    onOpenServerTab: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.passesNeedsAdminToken) {
            item(key = "passes-admin-needed") {
                AdminTokenPromptCard(onOpenServerTab = onOpenServerTab)
            }
        }
        item(key = "pass-pull-box") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ui.passUrlInput,
                    onValueChange = onPassUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.lyra_passes_pull_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPullLibrary,
                        enabled = !ui.passesBusy && ui.passUrlInput.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.lyra_passes_pull_library))
                    }
                    OutlinedButton(
                        onClick = onPullInbox,
                        enabled = !ui.passesBusy && ui.passUrlInput.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.lyra_passes_pull_inbox))
                    }
                }
            }
        }
        if (ui.incomingPulls.isNotEmpty()) {
            item(key = "incoming-header") {
                Text(
                    text = stringResource(R.string.lyra_passes_incoming_section),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(ui.incomingPulls, key = { it.id }) { pull ->
                IncomingPullRow(pull = pull)
            }
        }
        item(key = "outgoing-header") {
            Text(
                text = stringResource(R.string.lyra_passes_outgoing_section),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (ui.outgoingShares.isEmpty()) {
            item(key = "outgoing-empty") {
                InfoCard(text = stringResource(R.string.lyra_passes_empty))
            }
        } else {
            items(ui.outgoingShares, key = { it.token }) { share ->
                OutgoingShareRow(
                    share = share,
                    onCopy = { if (share.url.isNotBlank()) onCopyLink(share.url) },
                    onRevoke = { onRevoke(share.token) },
                )
            }
        }
    }
}

@Composable
private fun IncomingPullRow(pull: LyraIncomingPull) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = pull.manifestUrl,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(pull.target, pull.status, pull.error).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutgoingShareRow(
    share: LyraShareView,
    onCopy: () -> Unit,
    onRevoke: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(share.title.ifBlank { share.token }, style = MaterialTheme.typography.titleSmall)
            Text(
                text = listOf(share.state, share.url).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (share.url.isNotBlank()) {
                    FilledTonalButton(onClick = onCopy) {
                        Text(stringResource(R.string.lyra_passes_copy_link))
                    }
                }
                if (share.state == "live") {
                    TextButton(onClick = onRevoke) {
                        Text(stringResource(R.string.lyra_passes_revoke))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerTabContent(
    session: LyraSessionState,
    ui: LyraHubUiState,
    onOpenSync: () -> Unit,
    onAdminTokenChange: (String) -> Unit,
    onSaveAdminToken: () -> Unit,
    onClearAdminToken: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "server-status") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = YoinContainerShapes.Panel,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = session.serverUrl.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (session.isLyraServer) {
                        Text(
                            text = stringResource(
                                R.string.lyra_server_version,
                                session.lyraVersion.orEmpty(),
                                session.protocolVersion,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.capabilities.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.lyra_server_capabilities),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            session.capabilities.filterValues { it.enabled }.keys.sorted().forEach { cap ->
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        text = cap,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "sync-card") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = YoinContainerShapes.Panel,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lyra_server_sync_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.lyra_server_sync_card_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onOpenSync) {
                        Text(stringResource(R.string.lyra_server_sync_open))
                    }
                }
            }
        }
        item(key = "admin-token") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = YoinContainerShapes.Panel,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lyra_server_admin_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.lyra_server_admin_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = ui.adminTokenInput,
                        onValueChange = onAdminTokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text(stringResource(R.string.lyra_server_admin_hint)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onSaveAdminToken,
                            enabled = ui.adminTokenInput.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.lyra_server_admin_save))
                        }
                        if (session.hasAdminToken) {
                            OutlinedButton(onClick = onClearAdminToken) {
                                Text(stringResource(R.string.lyra_server_admin_clear))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTokenPromptCard(onOpenServerTab: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.lyra_admin_token_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            FilledTonalButton(onClick = onOpenServerTab) {
                Text(stringResource(R.string.lyra_tab_server))
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
