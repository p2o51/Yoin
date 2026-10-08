package com.gpo.yoin.ui.nowplaying

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinContainerShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.common.asString
import kotlinx.coroutines.launch

/**
 * Lyrics search, on the M3 Expressive Search API. Driven by [LyricsSearchState];
 * [state] `isOpen` programmatically expands the full-screen search surface (this
 * has no persistent collapsed bar — it's opened by the lyrics "search" action),
 * and collapsing it (back gesture / the leading arrow) reports [onDismiss]. The
 * new [SearchBarDefaults.InputField] is driven by a `TextFieldState`, bridged to
 * the existing provider search via [onQueryChange]/[onSearch].
 *
 * Always composed by the caller (no `if (isOpen)` gate): when collapsed the search
 * bar renders nothing, so the morph in/out is the official animation. The caller
 * binds [SearchBarState.collapsedCoords] to the actual search button, just as
 * Material SearchBar does, so opening/closing uses that button as its anchor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSearchSheet(
    state: LyricsSearchState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (LyricsSearchResultUi) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    searchBarState: SearchBarState = rememberSearchBarState(),
) {
    val scope = rememberCoroutineScope()
    val textFieldState = rememberTextFieldState(state.query)

    // Field text → existing provider search (the VM debounces / fans out), and
    // outside resets of the query → field.
    BindSearchFieldToQuery(textFieldState, state.query, onQueryChange)
    // The lyrics "search" action toggles isOpen → expand/collapse the surface.
    LaunchedEffect(state.isOpen) {
        if (state.isOpen) {
            searchBarState.animateToExpanded()
        } else {
            searchBarState.animateToCollapsed()
        }
    }
    // Collapsing (back / leading arrow) reports the dismissal upward.
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed && state.isOpen) {
            onDismiss()
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            // FullScreenSearchBar also uses the anchor height to constrain its
            // input slot. Our 52 dp icon is shorter than a standard search
            // field; retain the official field height so text is not clipped.
            modifier = Modifier.requiredHeight(SearchBarDefaults.InputFieldHeight),
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = { onSearch(it) },
            placeholder = { Text(stringResource(R.string.np_lyrics_search_placeholder)) },
            leadingIcon = {
                IconButton(onClick = { scope.launch { searchBarState.animateToCollapsed() } }) {
                    Icon(
                        imageVector = YoinSymbols.Back,
                        contentDescription = stringResource(R.string.np_cd_close_lyrics_search),
                    )
                }
            },
            trailingIcon = {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (textFieldState.text.isNotEmpty()) {
                    IconButton(onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") }) {
                        Icon(
                            imageVector = YoinSymbols.Close,
                            contentDescription = stringResource(R.string.np_cd_clear),
                        )
                    }
                }
            },
        )
    }

    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = inputField,
        modifier = modifier,
    ) {
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 16.dp + navBottom,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val searchError = state.errorMessage
            if (searchError != null) {
                item(key = "error") {
                    Text(
                        text = searchError.asString(),
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
                            text = stringResource(R.string.np_lyrics_searching_providers),
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
                            text = stringResource(R.string.np_lyrics_none_found),
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
                                        text = stringResource(R.string.np_lyrics_searching),
                                        loading = true,
                                    )
                                }
                            }
                            provider.results.isEmpty() -> {
                                item(key = "empty:${provider.providerName}") {
                                    LyricsProviderStatusRow(text = stringResource(R.string.np_lyrics_no_results))
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

/**
 * Binds the search field to the model's [query], the FIELD being the source of
 * truth for what is typed: every edit goes up through [onQueryChange], and
 * [query] is written back only when it changed from outside (opening the
 * search seeds it, a song change resets it). The model's query trails the
 * field by a few frames — writing that echo back while typing fast dropped
 * the letters typed since ("Viva La Vida Coldplay" → "Viv L Vi Codpl").
 */
@Composable
internal fun BindSearchFieldToQuery(
    textFieldState: TextFieldState,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val echoes = remember { SearchFieldEchoes() }
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { text ->
                echoes.sent(text)
                latestOnQueryChange(text)
            }
    }
    LaunchedEffect(query) {
        if (echoes.isOutsideChange(query, textFieldState.text.toString())) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
}

/**
 * The field texts sent to the model that it hasn't echoed back yet, oldest
 * first. A query the model reports that is one of them is the field's own
 * (possibly stale) echo; anything else came from outside.
 */
internal class SearchFieldEchoes {
    private val inFlight = ArrayDeque<String>()

    fun sent(text: String) {
        inFlight.addLast(text)
        while (inFlight.size > MAX_IN_FLIGHT) inFlight.removeFirst()
    }

    /** True when [query] is an outside change the field (showing [fieldText]) must adopt. */
    fun isOutsideChange(query: String, fieldText: String): Boolean {
        val echo = inFlight.indexOf(query)
        if (echo >= 0) {
            // The model has caught up to this send; older ones are moot.
            repeat(echo + 1) { inFlight.removeFirst() }
            return false
        }
        inFlight.clear()
        return query != fieldText
    }

    private companion object {
        // Far beyond any typing burst between two frames; only bounds growth.
        const val MAX_IN_FLIGHT = 64
    }
}

@Composable
private fun LyricsProviderHeader(
    providerName: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = providerName.toLyricsProviderLabel(LocalContext.current.resources),
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
            .clip(YoinContainerShapes.ListRow)
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
                imageVector = YoinSymbols.ChevronRight,
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
        title = { Text(stringResource(R.string.np_lyrics_apply_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                placeholder = { Text(stringResource(R.string.np_lyrics_field_placeholder)) },
                minLines = 8,
            )
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { onApply(draft) },
            ) {
                Text(stringResource(R.string.np_lyrics_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.np_lyrics_cancel))
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

/** 歌词源 id → 面向用户的名字（搜索分区标题、snackbar）；不认识的原样返回。 */
internal fun String.toLyricsProviderLabel(resources: Resources): String = when (this) {
    "qq" -> resources.getString(R.string.np_lyrics_provider_qq)
    "netease" -> resources.getString(R.string.np_lyrics_provider_netease)
    "huawei" -> resources.getString(R.string.np_lyrics_provider_huawei)
    "lrclib" -> resources.getString(R.string.np_lyrics_provider_lrclib)
    else -> this
}
