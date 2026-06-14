package com.gpo.yoin.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSearchSheet(
    state: LyricsSearchState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (LyricsSearchResultUi) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            )
        },
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // The sheet is its own window, so the host Activity's adjustResize can't
        // shrink it — imePadding lifts the search field + results above the
        // keyboard (matches the sibling WriteNoteSheet).
        Column(modifier = Modifier.imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 8.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Search lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close lyrics search",
                    )
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Song or artist") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { onSearch(state.query) }) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search lyrics",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(state.query) }),
            )

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 16.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.errorMessage != null) {
                    item(key = "error") {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                when {
                    state.providers.isEmpty() && state.loading -> {
                        item(key = "loading") {
                            LyricsProviderStatusRow(
                                text = "Searching providers...",
                                loading = true,
                            )
                        }
                    }
                    state.providers.isEmpty() &&
                        !state.loading &&
                        state.query.isNotBlank() &&
                        state.errorMessage == null -> {
                        item(key = "empty") {
                            Text(
                                text = "No lyrics found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                            )
                        }
                    }
                    else -> {
                        state.providers.forEachIndexed { providerIndex, provider ->
                            item(key = "header:${provider.providerName}") {
                                LyricsProviderHeader(providerName = provider.providerName)
                            }
                            when {
                                provider.errorMessage != null -> {
                                    item(key = "error:${provider.providerName}") {
                                        LyricsProviderStatusRow(
                                            text = provider.errorMessage,
                                            error = true,
                                        )
                                    }
                                }
                                state.loading && provider.results.isEmpty() -> {
                                    item(key = "loading:${provider.providerName}") {
                                        LyricsProviderStatusRow(
                                            text = "Searching...",
                                            loading = true,
                                        )
                                    }
                                }
                                provider.results.isEmpty() -> {
                                    item(key = "empty:${provider.providerName}") {
                                        LyricsProviderStatusRow(text = "No results")
                                    }
                                }
                                else -> {
                                    items(
                                        items = provider.results,
                                        key = LyricsSearchResultUi::stableKey,
                                    ) { result ->
                                        LyricsSearchResultRow(
                                            result = result,
                                            applying = state.applyingCandidateKey == result.stableKey,
                                            enabled = state.applyingCandidateKey == null,
                                            onClick = { onSelect(result) },
                                        )
                                    }
                                }
                            }
                            if (providerIndex != state.providers.lastIndex) {
                                item(key = "divider:${provider.providerName}") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsProviderHeader(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = providerName.toLyricsProviderLabel(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun LyricsProviderStatusRow(
    text: String,
    loading: Boolean = false,
    error: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun LyricsSearchResultRow(
    result: LyricsSearchResultUi,
    applying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (applying) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LyricsApplyDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var draft by remember(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply lyrics") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                placeholder = { Text("Lyrics") },
                minLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { onApply(draft) },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

internal fun List<LyricLine>.toEditableLyricsText(): String {
    if (isEmpty()) return ""
    return joinToString("\n") { line ->
        val start = line.startMs
        if (start == null) {
            line.text
        } else {
            "${start.toLrcTimestamp()}${line.text}"
        }
    }
}

private fun Long.toLrcTimestamp(): String {
    val totalSeconds = this.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val hundredths = (this.coerceAtLeast(0L) % 1_000L) / 10L
    return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
}

private fun String.toLyricsProviderLabel(): String = when (this) {
    "qq" -> "QQ Music"
    "netease" -> "NetEase"
    "lrclib" -> "LRCLIB"
    else -> this
}
