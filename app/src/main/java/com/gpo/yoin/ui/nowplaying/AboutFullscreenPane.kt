package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.component.edgeFade
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/**
 * Fullscreen About page content. Intentionally does NOT render the Ask
 * Gemini bar — the bar is a sibling overlay in `NowPlayingFullscreenPane`
 * so it can sit at the very bottom of the screen (below title/artist)
 * and grow upward to cover the hero when focused.
 */
@Composable
fun AboutFullscreenPane(
    aboutUiState: AboutUiState,
    onRetryCanonical: () -> Unit,
    modifier: Modifier = Modifier,
    contentBottomPadding: androidx.compose.ui.unit.Dp = 96.dp,
) {
    AnimatedContent(
        targetState = aboutUiState,
        transitionSpec = {
            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
        },
        contentKey = { it::class },
        modifier = modifier.edgeFade(top = 32.dp, bottom = 64.dp),
        label = "aboutContent",
    ) { state ->
        when (state) {
            AboutUiState.Idle -> EmptyAboutHint(
                text = "Tap About to start — we'll fetch song details on first open.",
            )
            AboutUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    YoinLoadingIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Searching for song info…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AboutUiState.ApiKeyMissing -> EmptyAboutHint(
                text = "Configure your Gemini API key in Settings to see AI-generated song info.",
            )
            is AboutUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetryCanonical) { Text("Retry") }
            }
            is AboutUiState.Ready -> ReadyContent(
                entries = state.entries,
                bottomPadding = contentBottomPadding,
            )
        }
    }
}

@Composable
private fun EmptyAboutHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ReadyContent(
    entries: List<SongAboutEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        val byKey = entries.filter { it.kind == SongAboutEntry.KIND_CANONICAL }
            .associateBy { it.entryKey }

        SongAboutEntry.CANONICAL_ORDER
            .mapNotNull { key -> byKey[key]?.let { key to it } }
            .forEach { (_, row) ->
                InfoItem(label = labelFor(row.entryKey), value = row.answerText)
            }

        val asks = entries.filter { it.kind == SongAboutEntry.KIND_ASK }
        if (asks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            asks.forEach { row ->
                // Heading is Gemini's short title (v16+). Rows written
                // before the titleText column existed fall back to the
                // user's original question so history stays readable.
                val heading = row.titleText?.takeIf { it.isNotBlank() }
                    ?: row.promptText.orEmpty()
                InfoItem(label = heading, value = row.answerText)
            }
        }
        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

private fun labelFor(entryKey: String): String = when (entryKey) {
    SongAboutEntry.CANON_CREATION_TIME -> "Creation Time"
    SongAboutEntry.CANON_CREATION_LOCATION -> "Creation Location"
    SongAboutEntry.CANON_LYRICIST -> "Lyricist"
    SongAboutEntry.CANON_COMPOSER -> "Composer"
    SongAboutEntry.CANON_PRODUCER -> "Producer"
    SongAboutEntry.CANON_REVIEW -> "About"
    else -> entryKey
}

@Composable
private fun InfoItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Bottom bar state machine. Lives as an overlay in
 * `NowPlayingFullscreenPane` so that when it expands it visually covers
 * the song title + artist sitting directly above.
 *
 * Shape is a **fixed** 40.dp rounded corner radius in every state — Idle
 * (height 80dp, radius = height/2 reads as a pill) → Focused (taller,
 * still 40dp corners, so it's a rounded rectangle) → Loading (back to
 * 80dp pill while the answer upserts).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskGeminiBar(
    askState: AskBarState,
    onSubmit: (String) -> Unit,
    onFocus: () -> Unit,
    onCollapseRequest: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var draft by remember { mutableStateOf("") }

    // Idle after a Loading → answer landed, reset the draft so the bar is
    // empty on next tap.
    LaunchedEffect(askState) {
        if (askState is AskBarState.Idle) {
            draft = ""
        }
    }

    // Collapse Focused → Idle when the user dismisses the IME without
    // submitting (back button, swipe-down). We can't hook onFocusChanged
    // because BasicTextField fires a false on first compose, which flips
    // us back before the keyboard ever shows. Instead, watch the IME
    // inset directly and debounce: only fire on a visible → hidden
    // transition, not on the initial hidden state when Focused is entered.
    val imeVisible = WindowInsets.isImeVisible
    var imeHasOpened by remember(askState) { mutableStateOf(false) }
    LaunchedEffect(imeVisible, askState) {
        if (askState is AskBarState.Focused) {
            if (imeVisible) {
                imeHasOpened = true
            } else if (imeHasOpened) {
                onCollapseRequest()
            }
        }
    }

    val targetHeight = when (askState) {
        // 30% taller than idle still easily covers the hero above, and
        // both values land on a 4dp grid so vertical spacing agrees with
        // the rest of the screen. Corner radius stays pinned at 28dp —
        // Idle (56dp) is a pill (radius = height/2); Focused (252dp)
        // is a rounded rectangle with the same 28dp radius.
        AskBarState.Focused -> 252.dp
        else -> 56.dp
    }
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "askBarHeight",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .animateContentSize(),
        shape = RoundedCornerShape(AskBarCornerRadius),
        // Primary family, softened — sits in the same hero palette as
        // the rest of Now Playing but reads as calmer than a selected
        // tab would. onPrimaryContainer gives enough contrast for the
        // "Ask Gemini" label without fighting the hero title above.
        color = MaterialTheme.colorScheme.primaryContainer,
        // No shadow: the bar is a solid colour block over a gradient;
        // an elevation shadow would fake depth that isn't earned.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        when (askState) {
            is AskBarState.Idle,
            is AskBarState.Error -> IdleOrErrorLayout(
                message = (askState as? AskBarState.Error)?.message,
                onTap = {
                    if (askState is AskBarState.Error) {
                        onDismissError()
                    } else {
                        // Fire the state transition directly — don't try to
                        // requestFocus on a FocusRequester that isn't
                        // attached yet (it's scoped to the Focused layout
                        // which hasn't composed). Once the state flips,
                        // FocusedLayout's LaunchedEffect handles focusing.
                        onFocus()
                    }
                },
            )
            AskBarState.Focused -> FocusedLayout(
                draft = draft,
                onDraftChange = { draft = it },
                focusRequester = focusRequester,
                onSubmit = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        focusManager.clearFocus()
                        onSubmit(text)
                    }
                },
            )
            AskBarState.Loading -> LoadingLayout()
        }
    }
}

private val AskBarCornerRadius = 28.dp

@Composable
private fun IdleOrErrorLayout(
    message: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onTap() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        StaticIndicator()
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = message ?: "Ask Gemini",
            style = MaterialTheme.typography.titleMedium,
            color = if (message != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FocusedLayout(
    draft: String,
    onDraftChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Request focus once when this layout first composes — the tap
    // handler on Idle flipped state to Focused; the TextField is now in
    // the tree so the requester is attached and can legally focus.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        StaticIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(focusRequester),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
            ),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(
                            text = "What is this song aiming for?",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
        Text(
            text = "Enter to ask Gemini",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LoadingLayout(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        YoinLoadingIndicator(size = 28.dp)
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = "Ask Gemini",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * v1 placeholder for the pentagon shape in the mockups. A filled circle
 * in `primary` reads as a dot/seal. Swap for `MaterialShapes.Pentagon` as
 * soon as the M3 Expressive 1.4+ shape morph API stabilizes.
 */
@Composable
private fun StaticIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}
