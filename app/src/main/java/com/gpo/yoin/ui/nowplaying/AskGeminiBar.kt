@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gpo.yoin.ui.nowplaying

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

@Composable
internal fun AskGeminiBar(
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
            is AskBarState.Loading -> LoadingLayout(title = askState.title)
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
            // Inherit the themed style (Google Sans Flex + its tracking) —
            // a bare TextStyle here fell back to the system font.
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onPrimaryContainer,
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
private fun LoadingLayout(title: String, modifier: Modifier = Modifier) {
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
            text = title.takeIf { it.isNotBlank() } ?: "Ask Gemini",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
