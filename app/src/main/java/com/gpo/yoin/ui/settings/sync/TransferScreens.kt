package com.gpo.yoin.ui.settings.sync

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.sync.backup.BackupFormat
import com.gpo.yoin.data.transfer.RestoreReview
import com.gpo.yoin.data.transfer.ReviewRow
import com.gpo.yoin.data.transfer.ReviewStatus
import com.gpo.yoin.data.transfer.memories.MatchStatus
import com.gpo.yoin.data.transfer.memories.TransferPlan
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaFlow
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.formatTrackDuration
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.SettingsRowMinHeight
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.settings.backup.CandidateRow
import com.gpo.yoin.ui.settings.backup.CountTile
import com.gpo.yoin.ui.settings.backup.ReviewMark
import com.gpo.yoin.ui.settings.backup.SongLines
import com.gpo.yoin.ui.settings.backup.StatusPill
import com.gpo.yoin.ui.settings.backup.SummaryBar
import com.gpo.yoin.ui.settings.settingsSegmentShape
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/** What the flow's pages call back into ([TransferViewModel]). */
data class TransferActions(
    val pickSource: (String) -> Unit = {},
    val pickTarget: (String) -> Unit = {},
    val next: () -> Unit = {},
    val changeSource: () -> Unit = {},
    val start: () -> Unit = {},
    val cancel: () -> Unit = {},
    val setChecked: (Int, Boolean) -> Unit = { _, _ -> },
    val choose: (Int, Int) -> Unit = { _, _ -> },
    val copy: () -> Unit = {},
    val setRemoveCard: (Boolean) -> Unit = {},
    val clean: () -> Unit = {},
    val finish: () -> Unit = {},
    val addAccount: () -> Unit = {},
)

private enum class TransferFilter { All, ToCheck, NotFound }

@Composable
internal fun TransferFlow(
    state: TransferUiState,
    onBack: () -> Unit,
    actions: TransferActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    var confirmClean by rememberSaveable { mutableStateOf(false) }
    val sourceName = state.source?.account?.displayName.orEmpty()
    val targetName = state.target?.account?.displayName.orEmpty()
    SyncPage(
        barTitle = stringResource(if (state.step == TransferStep.Done) R.string.settings_xfer_done_bar else R.string.settings_xfer_title),
        onBack = onBack,
        close = state.step == state.firstStep || state.step == TransferStep.Done || state.step == TransferStep.Failed ||
            state.step == TransferStep.Loading,
        snackbarHostState = snackbarHostState,
        footer = footerFor(state, actions, onCancel = { confirmCancel = true }),
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                (
                    YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard) { if (forward) it / 8 else -it / 8 } +
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard)
                    ) togetherWith (
                    YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard) { if (forward) -it / 8 else it / 8 } +
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    )
            },
            label = "transferStep",
        ) { step ->
            Column(modifier = Modifier.fillMaxWidth()) {
                if (step != TransferStep.Done && step != TransferStep.Failed && step != TransferStep.Loading) StepBar(state, step)
                when (step) {
                    TransferStep.Loading -> ProgressHero(progress = null)
                    TransferStep.Source -> SourceStep(state, actions)
                    TransferStep.Target -> TargetStep(state, actions)
                    TransferStep.Summary -> SummaryStep(state)
                    TransferStep.Matching -> ProgressHero(
                        progress = if (state.progressTotal > 0) state.progressDone.toFloat() / state.progressTotal else 0f,
                        count = stringResource(R.string.settings_xfer_progress, state.progressDone, state.progressTotal),
                        caption = {
                            ServiceLine(
                                provider = state.target?.account?.provider.orEmpty(),
                                text = stringResource(R.string.settings_xfer_matching_on, serviceNameOf(state.target?.account?.provider)),
                            )
                        },
                    )
                    TransferStep.Review -> ReviewStep(state, actions)
                    TransferStep.Copying -> ProgressHero(
                        progress = null,
                        caption = { ServiceLine(state.target?.account?.provider.orEmpty(), stringResource(R.string.settings_xfer_copying, targetName)) },
                    )
                    TransferStep.Done -> DoneStep(state, actions, onClean = { confirmClean = true })
                    TransferStep.Failed -> OutcomeHero(
                        icon = YoinSymbols.Close,
                        title = when (state.failure) {
                            TransferFailure.ReadFailed -> stringResource(R.string.settings_xfer_read_failed, sourceName)
                            TransferFailure.TargetUnreachable -> stringResource(R.string.settings_xfer_target_unreachable, targetName)
                            else -> stringResource(R.string.settings_xfer_run_failed)
                        },
                        body = null,
                        failed = true,
                    )
                }
            }
        }
    }
    if (confirmCancel) {
        val haptics = rememberYoinHaptics()
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.settings_xfer_cancel_title)) },
            text = { Text(stringResource(R.string.settings_xfer_cancel_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performReject()
                        confirmCancel = false
                        actions.cancel()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.handPointer(),
                ) { Text(stringResource(R.string.settings_xfer_cancel_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }, modifier = Modifier.handPointer()) {
                    Text(stringResource(R.string.settings_xfer_cancel_keep))
                }
            },
        )
    }
    if (confirmClean) {
        val haptics = rememberYoinHaptics()
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text(stringResource(R.string.settings_xfer_clean_title, sourceName)) },
            text = { Text(stringResource(R.string.settings_xfer_clean_body, sourceName, countsSentence(state.source?.counts), targetName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.performReject()
                        confirmClean = false
                        actions.clean()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.handPointer(),
                ) { Text(stringResource(R.string.settings_xfer_clean_now)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClean = false }, modifier = Modifier.handPointer()) {
                    Text(stringResource(R.string.settings_xfer_clean_later))
                }
            },
        )
    }
}

@Composable
private fun footerFor(
    state: TransferUiState,
    actions: TransferActions,
    onCancel: () -> Unit,
): (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = when (state.step) {
    TransferStep.Source -> {
        { FlowButtons(primary = stringResource(R.string.settings_xfer_next), onPrimary = actions.next, primaryEnabled = state.sourceKey != null) }
    }
    TransferStep.Target -> {
        { FlowButtons(primary = stringResource(R.string.settings_xfer_next), onPrimary = actions.next, primaryEnabled = state.targetKey != null) }
    }
    TransferStep.Summary -> {
        {
            val ready = state.draft != null && !state.draftLoading
            if (ready && state.remainingItems == 0) {
                FlowButtons(primary = stringResource(R.string.settings_xfer_nothing_new), onPrimary = {}, primaryEnabled = false)
            } else {
                FlowButtons(
                    primary = stringResource(if (state.needsMatching) R.string.settings_xfer_start_match else R.string.settings_xfer_start_copy),
                    onPrimary = actions.start,
                    primaryEnabled = ready,
                    badge = state.remainingItems.takeIf { ready },
                )
            }
        }
    }
    TransferStep.Matching -> {
        {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, modifier = Modifier.height(56.dp).handPointer()) { Text(stringResource(R.string.settings_xfer_cancel)) }
        }
    }
    TransferStep.Review -> {
        {
            FlowButtons(
                primary = stringResource(R.string.settings_xfer_start_copy),
                onPrimary = actions.copy,
                primaryEnabled = state.songsToCopy > 0 || state.remainingItems > 0,
                badge = state.songsToCopy,
                secondary = stringResource(R.string.settings_xfer_cancel),
                onSecondary = onCancel,
            )
        }
    }
    TransferStep.Done -> {
        { FlowButtons(primary = stringResource(R.string.settings_sync_done), onPrimary = actions.finish) }
    }
    TransferStep.Failed -> {
        { FlowButtons(primary = stringResource(R.string.settings_sync_done), onPrimary = actions.cancel) }
    }
    else -> null
}

/** Progress segments and "Step n of m: name". */
@Composable
private fun StepBar(state: TransferUiState, step: TransferStep) {
    val index = state.copy(step = step).stepIndex()
    val count = state.stepCount
    val names = buildList {
        add(R.string.settings_xfer_step_source)
        add(R.string.settings_xfer_step_target)
        add(R.string.settings_xfer_step_summary)
        if (count == 5) add(R.string.settings_xfer_step_review)
        add(R.string.settings_xfer_step_copy)
    }
    Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(count) { i ->
                val color by animateColorAsState(
                    if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    YoinMotion.effectsSpring(),
                    label = "stepSegment$i",
                )
                Box(modifier = Modifier.weight(1f).height(4.dp).background(color, CircleShape))
            }
        }
        Text(
            stringResource(R.string.settings_xfer_step, index + 1, count, stringResource(names[index.coerceIn(0, names.lastIndex)])),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlowHead() {
    SyncHeadline(
        crumb = null,
        headline = stringResource(R.string.settings_xfer_title),
        lead = stringResource(R.string.settings_xfer_summary),
        small = true,
    )
}

@Composable
private fun SourceStep(state: TransferUiState, actions: TransferActions) {
    FlowHead()
    SettingsGroup(title = stringResource(R.string.settings_xfer_source_group)) {
        state.sources.forEach { account ->
            item(key = account.key, paintsOwnSegment = true) {
                AccountPick(account = account, selected = state.sourceKey == account.key, onClick = { actions.pickSource(account.key) })
            }
        }
    }
}

@Composable
private fun TargetStep(state: TransferUiState, actions: TransferActions) {
    val source = state.source ?: return
    FlowHead()
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        SettingsSegments {
            item {
                SettingsItem(
                    icon = identityOf(source.account.provider)?.glyph ?: YoinSymbols.Person,
                    iconTone = identityOf(source.account.provider)?.hue?.tone(),
                    title = stringResource(R.string.settings_xfer_from, source.account.displayName),
                    summaryContent = {
                        source.counts?.let { counts ->
                            val stats = scopeStatGroups(counts)
                            if (stats.isNotEmpty()) MetaFlow(groups = stats, style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    onClick = actions.changeSource,
                    trailing = {
                        Text(
                            stringResource(R.string.settings_xfer_change),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(title = stringResource(R.string.settings_xfer_target_group)) {
                state.targets.forEach { account ->
                    item(key = account.key, paintsOwnSegment = true) {
                        AccountPick(
                            account = account,
                            selected = state.targetKey == account.key,
                            onClick = { actions.pickTarget(account.key) },
                            badge = transferKindOf(source.account, account.account),
                        )
                    }
                }
                item(key = "add") {
                    SettingsItem(
                        icon = YoinSymbols.PersonAdd,
                        title = stringResource(R.string.settings_xfer_add_account),
                        summary = stringResource(R.string.settings_xfer_add_account_summary),
                        onClick = actions.addAccount,
                    )
                }
            }
            if (state.targets.isEmpty()) SyncNote(YoinSymbols.Info, stringResource(R.string.settings_xfer_no_targets))
            if (source.cloudOnly) SyncNote(YoinSymbols.Info, stringResource(R.string.settings_xfer_cloud_note, source.account.displayName))
        }
    }
}

/** A radio row for an account: glyph, name (仅在云端), service line, counts, and the pair's badge. */
@Composable
private fun AccountPick(account: TransferAccountUi, selected: Boolean, onClick: () -> Unit, badge: TransferKind? = null) {
    val haptics = rememberYoinHaptics()
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else com.gpo.yoin.ui.settings.settingsSurfaces().row,
        YoinMotion.effectsSpring(),
        label = "accountPick",
    )
    Surface(
        onClick = {
            haptics.performSegmentTick()
            onClick()
        },
        shape = com.gpo.yoin.ui.settings.LocalSettingsRowShape.current,
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.RadioButton }
            .handPointer(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SettingsRowMinHeight)
                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountGlyph(account.account.provider)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TitleWithPill(
                    title = account.account.displayName,
                    pill = when {
                        account.cloudOnly -> {
                            { SyncPill(stringResource(R.string.settings_sync_cloud_only)) }
                        }
                        badge != null -> {
                            {
                                SyncPill(
                                    stringResource(
                                        when (badge) {
                                            TransferKind.Direct -> R.string.settings_xfer_badge_direct
                                            TransferKind.Partial -> R.string.settings_xfer_badge_partial
                                            TransferKind.Match -> R.string.settings_xfer_badge_match
                                        },
                                    ),
                                    when (badge) {
                                        TransferKind.Direct -> SyncPillTone.Primary
                                        TransferKind.Partial -> SyncPillTone.Neutral
                                        TransferKind.Match -> SyncPillTone.Muted
                                    },
                                )
                            }
                        }
                        else -> null
                    },
                )
                val line = accountLineGroups(account.account.provider, account.account.displayName, account.hint, account.fromDevice)
                if (line.isNotEmpty()) MetaLine(groups = line, style = MaterialTheme.typography.bodyMedium)
                account.counts?.let { counts ->
                    val stats = scopeStatGroups(counts)
                    if (stats.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_sync_no_data),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    } else {
                        MetaFlow(groups = stats, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            RadioDot(selected)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val ring by animateColorAsState(if (selected) colors.primary else colors.outline, YoinMotion.effectsSpring(), label = "radioRing")
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(ring, CircleShape)
            .padding(2.dp)
            .background(if (selected) colors.secondaryContainer else colors.surfaceBright, CircleShape)
            .padding(4.dp)
            .background(if (selected) colors.primary else Color.Transparent, CircleShape),
    )
}

@Composable
private fun serviceNameOf(provider: String?): String =
    provider?.let(::identityOf)?.let { stringResource(it.nameRes) } ?: provider.orEmpty()

@Composable
private fun ServiceLine(provider: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AccountGlyph(provider)
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SummaryStep(state: TransferUiState) {
    val source = state.source ?: return
    val target = state.target ?: return
    SyncHeadline(crumb = null, headline = stringResource(R.string.settings_xfer_summary_title), lead = null, small = true)
    val draft = state.draft
    if (draft == null) {
        ProgressHero(progress = null)
        return
    }
    val items = draft.items
    val kind = when {
        !state.needsMatching -> TransferKind.Direct
        state.directSongs > 0 -> TransferKind.Partial
        else -> TransferKind.Match
    }
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        PairCard(source, target)
        if (draft.previouslyCopied > 0) {
            SkipBanner(previous = draft.previouslyCopied, remaining = state.remainingItems)
        }
        SettingsGroup(
            title = stringResource(R.string.settings_xfer_what),
            trailingLabel = stringResource(R.string.settings_xfer_total, formatCount(state.totalItems)),
        ) {
            item { CountRow(YoinSymbols.Note, stringResource(R.string.settings_xfer_row_notes), items.songNotes.size + items.albumNotes.size, R.plurals.settings_sync_unit_count) }
            item { CountRow(YoinSymbols.Star, stringResource(R.string.settings_xfer_row_song_ratings), items.trackRatings.size, R.plurals.settings_sync_unit_count) }
            item {
                CountRow(
                    YoinSymbols.Reviews,
                    stringResource(R.string.settings_xfer_row_albums),
                    com.gpo.yoin.data.sync.CloudDataViews.countsOf(items).albums,
                    R.plurals.settings_sync_unit_count,
                )
            }
            item { CountRow(YoinSymbols.Edit, stringResource(R.string.settings_xfer_row_custom_titles), items.albumTitles.size, R.plurals.settings_sync_unit_title_count) }
            item {
                CountRow(
                    YoinSymbols.Sparkle,
                    stringResource(R.string.settings_xfer_row_ai_titles),
                    maxOf(items.memoryTitles.size, items.memoryCopies.size),
                    R.plurals.settings_sync_unit_title_count,
                )
            }
            item {
                CountRow(
                    YoinSymbols.Album,
                    stringResource(R.string.settings_xfer_row_links),
                    items.tracks.size,
                    R.plurals.settings_sync_unit_songs,
                    summary = stringResource(R.string.settings_xfer_row_links_summary),
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_xfer_matching)) {
            item {
                when (kind) {
                    TransferKind.Direct -> SettingsItem(
                        icon = YoinSymbols.CheckCircle,
                        title = stringResource(R.string.settings_xfer_method_direct),
                        summary = stringResource(R.string.settings_xfer_method_direct_summary, serviceNameOf(source.account.provider)),
                    )
                    TransferKind.Partial -> SettingsItem(
                        icon = YoinSymbols.CheckCircle,
                        title = stringResource(R.string.settings_xfer_method_partial),
                        summaryContent = {
                            MetaLine(
                                groups = listOf(
                                    MetaGroup.Stat(formatCount(state.directSongs), stringResource(R.string.settings_xfer_direct_songs)),
                                    MetaGroup.Stat(formatCount(draft.tracks.size), stringResource(R.string.settings_xfer_match_songs)),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.settings_xfer_method_partial_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    TransferKind.Match -> SettingsItem(
                        icon = YoinSymbols.Search,
                        title = stringResource(R.string.settings_xfer_method_match),
                        summary = stringResource(R.string.settings_xfer_method_match_summary, source.account.displayName),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(title = stringResource(R.string.settings_xfer_duplicates)) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Note,
                        title = stringResource(R.string.settings_xfer_dup_notes),
                        summary = stringResource(R.string.settings_xfer_dup_notes_summary),
                    )
                }
                item { SettingsItem(icon = YoinSymbols.Star, title = stringResource(R.string.settings_xfer_dup_ratings)) }
                item {
                    SettingsItem(
                        icon = YoinSymbols.Reviews,
                        title = stringResource(R.string.settings_xfer_dup_reviews),
                        summary = stringResource(R.string.settings_xfer_dup_reviews_summary, source.account.displayName),
                    )
                }
            }
            SyncNote(YoinSymbols.Info, stringResource(R.string.settings_xfer_copy_only, source.account.displayName))
        }
    }
}

@Composable
private fun CountRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: Int, unit: Int, summary: String? = null) {
    SettingsItem(
        icon = icon,
        title = title,
        summary = summary,
        trailing = { MetaLine(groups = listOf(stat(count, unit)), style = MaterialTheme.typography.bodyLarge) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairCard(source: TransferAccountUi, target: TransferAccountUi) {
    SettingsSegments {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PairSide(source, Modifier.weight(1f))
                Icon(YoinSymbols.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                PairSide(target, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PairSide(account: TransferAccountUi, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AccountGlyph(account.account.provider, size = 52.dp)
        Text(
            account.account.displayName,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (account.cloudOnly) stringResource(R.string.settings_sync_cloud_only) else account.hint ?: serviceNameOf(account.account.provider),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SkipBanner(previous: Int, remaining: Int) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(YoinSymbols.History, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.settings_xfer_skip_title, formatCount(previous)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (remaining > 0) R.string.settings_xfer_skip_some else R.string.settings_xfer_skip_all),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReviewStep(state: TransferUiState, actions: TransferActions) {
    val plan = state.plan ?: return
    val target = state.target ?: return
    val source = state.source ?: return
    val haptics = rememberYoinHaptics()
    var filter by rememberSaveable { mutableStateOf(TransferFilter.All) }
    val rows = state.rows
    val summary = RestoreReview.summary(rows)
    val shown = when (filter) {
        TransferFilter.All -> rows
        TransferFilter.ToCheck -> rows.filter { it.status == ReviewStatus.Uncertain }
        TransferFilter.NotFound -> rows.filter { it.status == ReviewStatus.NotFound }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountGlyph(target.account.provider)
            Text(target.account.displayName, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (state.directSongs > 0 && source.account.provider == target.account.provider) {
                stringResource(R.string.settings_xfer_review_partial_lead, state.directSongs, rows.size)
            } else {
                stringResource(R.string.settings_xfer_review_lead, source.account.displayName, rows.size, serviceNameOf(target.account.provider))
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        SummaryBar(summary, modifier = Modifier.padding(horizontal = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            val options = listOf(
                TransferFilter.All to (stringResource(R.string.playlist_restore_all) to rows.size),
                TransferFilter.ToCheck to (stringResource(R.string.playlist_restore_to_check) to summary.toCheck),
                TransferFilter.NotFound to (stringResource(R.string.playlist_restore_not_found) to summary.notFound),
            )
            options.forEachIndexed { index, (option, label) ->
                ToggleButton(
                    checked = filter == option,
                    onCheckedChange = {
                        haptics.performSegmentTick()
                        filter = option
                    },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.Tab }
                        .handPointer(),
                ) { Text("${label.first} ${label.second}", maxLines = 1, overflow = TextOverflow.Ellipsis) } // i18n-allow: sample data or a symbol around data
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEachIndexed { index, row ->
                androidx.compose.runtime.key(row.position) {
                    TransferReviewRow(
                        row = row,
                        plan = plan,
                        shapeIndex = index,
                        shapeCount = shown.size,
                        onCheck = { checked ->
                            haptics.performClick()
                            actions.setChecked(row.position, checked)
                        },
                        onChoose = { candidate ->
                            haptics.performClick()
                            actions.choose(row.position, candidate)
                        },
                    )
                }
            }
        }
        if (plan.albums.isNotEmpty()) {
            val matched = plan.albums.count { it.status == MatchStatus.Matched || it.status == MatchStatus.Picked }
            SyncNote(
                YoinSymbols.Album,
                stringResource(R.string.settings_xfer_albums_note, plan.albums.size, matched, plan.albums.size - matched, source.account.displayName),
            )
        }
        SyncNote(YoinSymbols.Info, stringResource(R.string.settings_xfer_left_note, source.account.displayName))
    }
}

/** One song of the transfer review, as playlist restore draws its rows (mark, song, what the target offers). */
@Composable
private fun TransferReviewRow(
    row: ReviewRow,
    plan: TransferPlan,
    shapeIndex: Int,
    shapeCount: Int,
    onCheck: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
) {
    val match = plan.tracks.getOrNull(row.position) ?: return
    var open by rememberSaveable(row.position) { mutableStateOf(false) }
    val uncertain = row.status == ReviewStatus.Uncertain
    val notFound = row.status == ReviewStatus.NotFound
    val title = match.sourceTrack?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.settings_sync_unknown_song)
    val artist = match.sourceTrack?.artist.orEmpty()
    Surface(
        shape = settingsSegmentShape(shapeIndex, shapeCount),
        color = com.gpo.yoin.ui.settings.settingsSurfaces().row,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .handPointer(enabled = !notFound)
                    .clickable(enabled = !notFound) { if (uncertain) open = !open else onCheck(!row.checked) }
                    .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ReviewMark(status = row.status, checked = row.checked, enabled = row.checkable, onToggle = { onCheck(!row.checked) })
                Column(modifier = Modifier.weight(1f)) {
                    SongLines(title = title, artist = artist, dimmed = notFound)
                    if (uncertain) {
                        Text(
                            text = if (row.checked) "→ ${row.choice?.title.orEmpty()}" else stringResource(R.string.settings_xfer_choose_match), // i18n-allow: sample data or a symbol around data
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (row.status) {
                    ReviewStatus.Matched, ReviewStatus.Picked -> (row.choice?.durationSec ?: match.sourceTrack?.durationSec)?.let { seconds ->
                        Text(formatTrackDuration(seconds), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ReviewStatus.Uncertain -> StatusPill(
                        text = stringResource(R.string.playlist_restore_to_check),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    ReviewStatus.NotFound -> StatusPill(
                        text = stringResource(R.string.settings_xfer_stays),
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(
                visible = uncertain && open,
                enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) + YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) + YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    row.candidates.forEachIndexed { index, candidate ->
                        CandidateRow(
                            candidate = candidate,
                            selected = row.checked && row.chosen == index,
                            enabled = true,
                            shape = settingsSegmentShape(index, row.candidates.size),
                            onClick = { onChoose(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneStep(state: TransferUiState, actions: TransferActions, onClean: () -> Unit) {
    val result = state.result ?: return
    val source = state.source ?: return
    val target = state.target ?: return
    val colors = MaterialTheme.colorScheme
    val copied = result.copied.total + result.updated.total
    val skipped = result.skippedPreviously + result.kept.total
    val left = result.unmatched.total
    Column(verticalArrangement = Arrangement.spacedBy(SyncGroupGap)) {
        OutcomeHero(
            icon = YoinSymbols.Check,
            title = stringResource(R.string.settings_xfer_done_title, target.account.displayName),
            body = stringResource(R.string.settings_xfer_done_kept, source.account.displayName),
            extra = {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    CountTile(copied, stringResource(R.string.settings_xfer_tile_copied), colors.primaryContainer, colors.onPrimaryContainer)
                    CountTile(skipped, stringResource(R.string.settings_xfer_tile_skipped), colors.surfaceContainerHigh, colors.onSurface)
                    CountTile(left, stringResource(R.string.settings_xfer_tile_left), colors.surfaceContainerHigh, colors.onSurface)
                }
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val local = source.account.localProfileId != null
            val cleanable = state.cloudSyncOn && !state.cleaned && BackupFormat.isSyncScope(source.account.scope)
            if (local || cleanable) {
                SettingsSegments {
                    if (local) {
                        item(key = "card") {
                            SettingsItem(
                                icon = YoinSymbols.Person,
                                title = stringResource(R.string.settings_xfer_remove_card),
                                summary = stringResource(R.string.settings_xfer_remove_card_summary),
                                onClick = { actions.setRemoveCard(!state.removeCard) },
                                trailing = {
                                    Switch(checked = state.removeCard, onCheckedChange = { actions.setRemoveCard(it) }, modifier = Modifier.handPointer())
                                },
                            )
                        }
                    }
                    if (cleanable) {
                        item(key = "clean") {
                            SettingsItem(
                                icon = YoinSymbols.DeleteSweep,
                                title = stringResource(R.string.settings_xfer_clean),
                                summary = stringResource(R.string.settings_xfer_clean_summary, target.account.displayName, source.account.displayName),
                                onClick = onClean,
                                trailing = { Chevron() },
                            )
                        }
                    }
                }
            }
            SyncNote(YoinSymbols.Info, stringResource(R.string.settings_xfer_neodb_note))
        }
    }
}
