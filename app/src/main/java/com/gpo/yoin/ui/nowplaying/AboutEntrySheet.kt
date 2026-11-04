package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.album.AlbumScrapbookAboutRef
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.YoinModalBottomSheet
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.detail.TrackMenuGroup
import com.gpo.yoin.ui.detail.TrackMenuRowItem
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * Now Playing's About rows answer a long-press (owner-approved prototype v3, 2026-11-02): a sheet in the
 * song menu's style offers the row in the scrapbook, where it can be edited, and a fresh answer from Gemini
 * for a row that may have gone out of date (undoable from the snackbar).
 */

/** What a long-pressed About row can do; Now Playing's host provides it. */
@Stable
internal class AboutEntryActions(
    /** The rows Gemini is writing again right now. */
    val regenerating: StateFlow<Set<AlbumScrapbookAboutRef>>,
    /** Whether the song on show has an album page to open (its scrapbook is there). */
    val canManage: () -> Boolean,
    val onManage: (SongAboutEntry) -> Unit,
    val onRegenerate: (SongAboutEntry) -> Unit,
)

/** Null in previews and harnesses: the rows then have no long-press. */
internal val LocalAboutEntryActions = staticCompositionLocalOf<AboutEntryActions?> { null }

private data class AboutEntryAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/**
 * The long-pressed row's sheet: its heading and the start of its text, then Manage in Scrapbook (when the
 * song has an album page) and Regenerate with Gemini (not for the listener's own entry).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutEntrySheet(
    entry: SongAboutEntry,
    label: String,
    actions: AboutEntryActions,
    onDismissRequest: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismissRequest()
        }
    }
    val rows = listOfNotNull(
        AboutEntryAction(stringResource(R.string.np_about_manage), YoinSymbols.Note) {
            actions.onManage(entry)
        }.takeIf { actions.canManage() },
        AboutEntryAction(stringResource(R.string.np_about_regenerate), YoinSymbols.Sparkle) {
            actions.onRegenerate(entry)
        }.takeIf { entry.kind != SongAboutEntry.KIND_OWN },
    )
    YoinModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 4.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = markdownBoldAnnotatedString(entry.answerText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (rows.isNotEmpty()) {
                TrackMenuGroup(count = rows.size) { index, shape ->
                    val row = rows[index]
                    TrackMenuRowItem(
                        label = row.label,
                        icon = row.icon,
                        shape = shape,
                        onClick = {
                            haptics.performContextClick()
                            row.run()
                            close()
                        },
                    )
                }
            }
        }
    }
}
