package com.gpo.yoin.ui.nowplaying

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.album.aboutRef
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.ui.component.LyricCreditsCard
import com.gpo.yoin.ui.component.ThinkingDots
import com.gpo.yoin.ui.component.coveredAboutKeys
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import com.gpo.yoin.ui.component.verticalEdgeFadeOnScroll
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SettingsActivity
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Fullscreen About page content. Intentionally does NOT render the Ask
 * Gemini bar — the bar is a sibling accessory in `NowPlayingScreen`
 * so it can slide with the About page and grow upward when focused.
 */
@Composable
fun AboutFullscreenPane(
    aboutUiState: AboutUiState,
    onRetryCanonical: () -> Unit,
    modifier: Modifier = Modifier,
    /** Credits the lyrics source wrote into the lyrics; shown first, with or without Gemini. */
    credits: LyricCredits? = null,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
) {
    val context = LocalContext.current
    AnimatedContent(
        targetState = aboutUiState,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        modifier = modifier,
        label = "aboutContent",
    ) { state ->
        when (state) {
            AboutUiState.Idle -> Box(modifier = Modifier.fillMaxSize())
            AboutUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YoinLoadingIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.np_about_searching),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AboutUiState.ApiKeyMissing -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                CreditsHeader(credits)
                Text(
                    text = stringResource(R.string.np_about_api_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { context.startActivity(SettingsActivity.intent(context)) },
                ) { Text(stringResource(R.string.np_about_open_settings)) }
            }
            is AboutUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                CreditsHeader(credits)
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetryCanonical) { Text(stringResource(R.string.np_about_retry)) }
            }
            is AboutUiState.Ready -> ReadyContent(
                entries = state.entries,
                credits = credits,
                bottomPadding = contentBottomPadding,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    entries: List<SongAboutEntry>,
    credits: LyricCredits?,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    // Scroll-aware fade on the content itself (not the AnimatedContent
    // wrapper): releases at the end of the scroll so the last entry reads
    // crisp above the Ask Gemini bar.
    val scrollState = rememberScrollState()
    // A long-press on a row opens its sheet (manage in the scrapbook, regenerate); none in previews.
    val actions = LocalAboutEntryActions.current
    val regenerating = actions?.regenerating?.collectAsState()?.value.orEmpty()
    val haptics = rememberYoinHaptics()
    var menuFor by remember { mutableStateOf<Pair<SongAboutEntry, String>?>(null) }
    val longPressFor: (SongAboutEntry, String) -> (() -> Unit)? = { row, label ->
        actions?.let {
            {
                haptics.performLongPress()
                menuFor = row to label
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalEdgeFadeOnScroll(scrollState, bottom = 64.dp)
            .verticalScroll(scrollState),
    ) {
        val resources = LocalContext.current.resources
        val byKey = entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL }
            .associateBy { it.entryKey }
        // The lyrics source's credits stand in for Gemini's lyricist / composer / producer.
        val covered = credits.coveredAboutKeys()
        CreditsHeader(credits)

        SongAboutEntry.CANONICAL_ORDER
            .filterNot(covered::contains)
            .mapNotNull { key -> byKey[key]?.let { key to it } }
            .filter { (_, row) -> row.answerText.isNotBlank() }
            .forEach { (_, row) ->
                val label = labelFor(row.entryKey, resources)
                InfoItem(
                    label = label,
                    value = row.answerText,
                    thinking = row.aboutRef() in regenerating,
                    onLongPress = longPressFor(row, label),
                )
            }

        // Gemini's answers and the listener's own entries (written in the scrapbook).
        val asks = entries.filter { it.kind == SongAboutEntry.KIND_ASK || it.kind == SongAboutEntry.KIND_OWN }
        if (asks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            asks.forEach { row ->
                // Heading is Gemini's short title (v16+). Rows written
                // before the titleText column existed fall back to the
                // user's original question so history stays readable.
                val heading = row.titleText?.takeIf { it.isNotBlank() }
                    ?: row.promptText.orEmpty()
                InfoItem(
                    label = heading,
                    value = row.answerText,
                    thinking = row.aboutRef() in regenerating,
                    onLongPress = longPressFor(row, heading),
                )
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
    // The song can move on while a row's sheet is open: the sheet goes with its row, so Manage never
    // pairs one song's row with the next song's album.
    val open = menuFor?.takeIf { (row, _) -> entries.any { it.aboutRef() == row.aboutRef() } }
    LaunchedEffect(open == null) { if (open == null) menuFor = null }
    if (actions != null && open != null) {
        AboutEntrySheet(
            entry = open.first,
            label = open.second,
            actions = actions,
            onDismissRequest = { menuFor = null },
        )
    }
}

@Composable
private fun CreditsHeader(credits: LyricCredits?) {
    if (credits == null) return
    LyricCreditsCard(credits = credits, modifier = Modifier.padding(bottom = 12.dp))
}

internal fun labelFor(entryKey: String, resources: Resources): String = when (entryKey) {
    SongAboutEntry.CANON_CREATION_TIME -> resources.getString(R.string.np_about_creation_time)
    SongAboutEntry.CANON_CREATION_LOCATION -> resources.getString(R.string.np_about_creation_location)
    SongAboutEntry.CANON_LYRICIST -> resources.getString(R.string.np_about_lyricist)
    SongAboutEntry.CANON_COMPOSER -> resources.getString(R.string.np_about_composer)
    SongAboutEntry.CANON_PRODUCER -> resources.getString(R.string.np_about_producer)
    SongAboutEntry.CANON_REVIEW -> resources.getString(R.string.np_about_review)
    else -> entryKey
}

@Composable
private fun InfoItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    // Gemini is writing this row again: dots in place of the old text.
    thinking: Boolean = false,
    onLongPress: (() -> Unit)? = null,
) {
    val menuLabel = stringResource(R.string.np_about_cd_entry_menu)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val press = if (onLongPress == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { currentOnLongPress?.invoke() }) }
            .semantics {
                onLongClick(label = menuLabel) {
                    currentOnLongPress?.invoke()
                    true
                }
            }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(press)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (thinking) {
            ThinkingDots(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
        } else {
            Text(
                text = markdownBoldAnnotatedString(value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
