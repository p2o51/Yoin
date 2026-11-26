@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.gpo.yoin.ui.nowplaying

import com.gpo.yoin.ui.component.aiString
import com.gpo.yoin.ui.component.pointerPressPreview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.highlight.AboutFollowUpPrompt
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.QuickPrompts
import com.gpo.yoin.ui.component.quickPromptsFor
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import androidx.graphics.shapes.RoundedPolygon

@Composable
internal fun AskGeminiBar(
    askState: AskBarState,
    onSubmit: (String) -> Unit,
    onFocus: () -> Unit,
    onCollapseRequest: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    // What was already asked about this song: those quick prompts aren't offered again.
    asked: List<String> = emptyList(),
    // Visible About rows for on-device follow-up question suggestions (empty / unavailable falls back to static).
    aboutEntries: List<SongAboutEntry> = emptyList(),
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var draft by remember { mutableStateOf(TextFieldValue()) }

    val preview = LocalInspectionMode.current
    val context = LocalContext.current
    val followUpGenerator = remember(context, preview) {
        if (preview) null else (context.applicationContext as? YoinApplication)?.container?.aboutFollowUpGenerator
    }
    var nanoFollowUps by remember(aboutEntries, asked, followUpGenerator) {
        mutableStateOf(
            followUpGenerator?.peekCached(aboutEntries, asked).orEmpty(),
        )
    }
    LaunchedEffect(aboutEntries, asked, followUpGenerator) {
        if (followUpGenerator == null || aboutEntries.isEmpty()) {
            nanoFollowUps = emptyList()
            return@LaunchedEffect
        }
        val suggested = followUpGenerator.suggest(aboutEntries, asked)
        nanoFollowUps = suggested
    }

    // Idle after a Loading → answer landed, reset the draft so the bar is
    // empty on next tap.
    LaunchedEffect(askState) {
        if (askState is AskBarState.Idle) {
            draft = TextFieldValue()
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

    // The AI's colour role is tertiary (docs/design.md: About and Ask are the AI's, the listener's own
    // words primary). While the AI thinks, the bar lightens toward the page so it sits in the pastel wash
    // above instead of reading as a dark slab under it (gradient lab, owner 2026-11-03). Effects spring: a
    // colour change.
    val scheme = MaterialTheme.colorScheme
    val barColor by animateColorAsState(
        targetValue = if (askState is AskBarState.Loading) {
            lerp(scheme.tertiaryContainer, scheme.background, 0.45f)
        } else {
            scheme.tertiaryContainer
        },
        animationSpec = YoinMotion.defaultEffectsSpec(role = YoinMotionRole.Expressive),
        label = "askBarColor",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(animatedHeight)
            .animateContentSize(),
        shape = RoundedCornerShape(AskBarCornerRadius),
        // Tertiary, softened: the AI's own role. onTertiaryContainer gives
        // the "Ask <AI>" label enough contrast without fighting the title.
        color = barColor,
        // No shadow: the bar is a solid colour block over a gradient;
        // an elevation shadow would fake depth that isn't earned.
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        when (askState) {
            // One row for idle, asking and a failed ask: the leading circle stays put and turns into the
            // loading indicator while the AI answers (and back).
            is AskBarState.Idle,
            is AskBarState.Loading,
            is AskBarState.Error -> RestLayout(
                message = (askState as? AskBarState.Error)?.message?.asString(),
                loadingTitle = (askState as? AskBarState.Loading)?.title,
                onTap = when (askState) {
                    is AskBarState.Error -> onDismissError
                    // Fire the state transition directly — don't try to
                    // requestFocus on a FocusRequester that isn't
                    // attached yet (it's scoped to the Focused layout
                    // which hasn't composed). Once the state flips,
                    // FocusedLayout's LaunchedEffect handles focusing.
                    is AskBarState.Idle -> onFocus
                    else -> null
                },
            )
            AskBarState.Focused -> FocusedLayout(
                draft = draft,
                onDraftChange = { draft = it },
                prompts = AboutFollowUpPrompt.mergeWithFallback(
                    suggested = nanoFollowUps,
                    fallback = quickPromptsFor(asked),
                    asked = asked,
                ),
                focusRequester = focusRequester,
                onSubmit = {
                    val text = draft.text.trim()
                    if (text.isNotEmpty()) {
                        focusManager.clearFocus()
                        onSubmit(text)
                    }
                },
            )
        }
    }
}

private val AskBarCornerRadius = 28.dp

@Composable
private fun RestLayout(
    message: String?,
    // Non-null while asking: the question (or, blank, the AI's name) beside the indicator.
    loadingTitle: String?,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (onTap != null) {
                    Modifier
                        .pointerPressPreview(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onTap,
                        )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        AskMark(loading = loadingTitle != null)
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = message ?: loadingTitle?.takeIf { it.isNotBlank() } ?: aiString(R.string.ai_ask_provider),
            style = MaterialTheme.typography.titleMedium,
            color = if (message != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FocusedLayout(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    prompts: List<String>,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Request focus once when this layout first composes — the tap
    // handler on Idle flipped state to Focused; the TextField is now in
    // the tree so the requester is attached and can legally focus.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The column pads only top and bottom so the quick prompts can scroll edge to edge.
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
    ) {
        AskMark(loading = false, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(modifier = Modifier.height(12.dp))
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 20.dp)
                .focusRequester(focusRequester),
            // Inherit the themed style (Google Sans Flex + its tracking) —
            // a bare TextStyle here fell back to the system font.
            textStyle = MaterialTheme.typography.titleMedium.copy(
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { inner ->
                Box {
                    if (draft.text.isEmpty()) {
                        Text(
                            text = aiString(R.string.ai_ask_provider),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                        )
                    }
                    inner()
                }
            },
        )
        // Quick questions while the field is empty (owner 2026-11-02); a tap fills the field, the
        // keyboard's Send asks it. Ones already asked about this song aren't offered.
        AnimatedVisibility(
            visible = draft.text.isEmpty() && prompts.isNotEmpty(),
            enter = YoinMotion.expandVertically(YoinMotionRole.Standard) + YoinMotion.fadeIn(YoinMotionRole.Standard),
            exit = YoinMotion.shrinkVertically(YoinMotionRole.Standard) + YoinMotion.fadeOut(YoinMotionRole.Standard),
        ) {
            QuickPrompts(
                prompts = prompts,
                container = MaterialTheme.colorScheme.surface,
                content = MaterialTheme.colorScheme.onSurface,
                onPick = { prompt -> onDraftChange(TextFieldValue(prompt, TextRange(prompt.length))) },
                modifier = Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            )
        }
    }
}

/** What was already asked about the song on show, for the quick prompts to leave out. */
internal fun AboutUiState.askedQuestions(): List<String> =
    (this as? AboutUiState.Ready)?.entries.orEmpty()
        .filter { it.kind == SongAboutEntry.KIND_ASK || it.kind == SongAboutEntry.KIND_OWN }
        .mapNotNull { row -> (row.promptText ?: row.titleText)?.takeIf(String::isNotBlank) }

/**
 * The bar's leading mark: a plain filled circle that becomes the M3 Expressive loading indicator while the
 * AI answers (owner 2026-11-26: the circle back, no wand). The indicator's shape sequence starts on that very
 * circle, drawn at the circle's size, so the first frame of asking IS the resting circle and it morphs on
 * from there (Circle → Soft burst → Cookie → …, the official LoadingIndicator); when the answer lands the
 * indicator fades back into the circle on the effects spring.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AskMark(loading: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier.size(AskMarkSize), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = loading,
            transitionSpec = {
                if (targetState) {
                    // The indicator's first frame is the circle itself: no fade in.
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                        YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                } using null
            },
            contentAlignment = Alignment.Center,
            label = "askMark",
        ) { busy ->
            if (busy) {
                LoadingIndicator(
                    color = color,
                    polygons = AskMarkPolygons,
                    // Unbounded: the indicator's box is larger than the circle it starts as.
                    modifier = Modifier
                        .wrapContentSize(unbounded = true)
                        .size(AskIndicatorBox),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(AskMarkSize)
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}

/** The resting circle. */
private val AskMarkSize = 28.dp

/** The indicator morphs from the circle through the official indeterminate shapes. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val AskMarkPolygons = listOf(MaterialShapes.Circle.normalized()) + LoadingIndicatorDefaults.IndeterminateIndicatorPolygons

/**
 * The indicator's box, so that the circle it draws first is exactly [AskMarkSize]: the indicator draws its
 * shapes at [loadingIndicatorShapeFraction] of its box.
 */
private val AskIndicatorBox = AskMarkSize / loadingIndicatorShapeFraction(AskMarkPolygons)

/**
 * The fraction of a LoadingIndicator's box its shapes are drawn at — the component's own sizing: the active
 * indicator ([LoadingIndicatorDefaults.IndicatorSize] of the [LoadingIndicatorDefaults.ContainerWidth]
 * container) times the scale that makes every shape in the sequence look the same size (each shape's
 * bounds over the bounds it sweeps when rotated; the smallest wins).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun loadingIndicatorShapeFraction(polygons: List<RoundedPolygon>): Float {
    val active = LoadingIndicatorDefaults.IndicatorSize.value /
        minOf(LoadingIndicatorDefaults.ContainerWidth.value, LoadingIndicatorDefaults.ContainerHeight.value)
    var scale = 1f
    val bounds = FloatArray(4)
    val maxBounds = FloatArray(4)
    polygons.forEach { polygon ->
        val normalized = polygon.normalized()
        normalized.calculateBounds(bounds)
        normalized.calculateMaxBounds(maxBounds)
        val sx = (bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0])
        val sy = (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1])
        scale = minOf(scale, maxOf(sx, sy))
    }
    return active * scale
}
