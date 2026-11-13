package com.gpo.yoin.ui.continueon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.player.continueon.ContinueOn
import com.gpo.yoin.player.continueon.ContinueOnCursor
import com.gpo.yoin.player.continueon.ContinueOnPrompt
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.MetaLine
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The small card a Continue On hand-off shows before anything plays here
 * (owner 2026-11-08): the song, where the other device is in it, and
 * "Continue here". Only that tap starts it and pauses the other device.
 */
@Composable
fun ContinueOnCard(continueOn: ContinueOn, modifier: Modifier = Modifier) {
    val prompt by continueOn.prompt.collectAsState()
    ContinueOnCard(
        prompt = prompt,
        onContinue = continueOn::confirm,
        onDismiss = continueOn::dismiss,
        // The window's card clears the navigation bar. The Tips demo's phone has none, and padding
        // the card there by this device's bar lifted it off the demo's bottom edge.
        modifier = modifier.navigationBarsPadding(),
    )
}

@Composable
internal fun ContinueOnCard(
    prompt: ContinueOnPrompt?,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** On "Continue here" (the Tips demo aims its finger at it). */
    continueModifier: Modifier = Modifier,
) {
    // The last card stays drawn while it leaves.
    var shown by remember { mutableStateOf(prompt) }
    if (prompt != null) shown = prompt
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = prompt != null,
            enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it / 2 } +
                YoinMotion.scaleIn(role = YoinMotionRole.Expressive, initialScale = 0.92f) +
                YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
            exit = YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it / 2 } +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            shown?.let { card ->
                CardBody(
                    prompt = card,
                    onContinue = onContinue,
                    onDismiss = onDismiss,
                    continueModifier = continueModifier,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CardBody(
    prompt: ContinueOnPrompt,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    continueModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val page = MaterialTheme.colorScheme
    val cardScheme = remember(page) { continueOnCardScheme(page) }
    MaterialTheme(
        colorScheme = cardScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        Surface(
            modifier = modifier,
            shape = YoinShapeTokens.ExtraLarge,
            color = cardScheme.surface,
            contentColor = cardScheme.onSurface,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(prompt.coverUrl)
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        val device = prompt.deviceName ?: stringResource(R.string.continue_on_other_device)
                        Text(
                            text = stringResource(R.string.continue_on_from, device),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = prompt.title ?: stringResource(R.string.continue_on_unknown_song),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // As a song row has them (SongListItem): the artist, then the album muted, no glyph between.
                        val artist = prompt.artist?.takeIf { it.isNotBlank() }
                        val album = prompt.album?.takeIf { it.isNotBlank() }
                        if (artist != null || album != null) {
                            MetaLine(
                                groups = listOfNotNull(
                                    artist?.let { MetaGroup.Plain(it) },
                                    album?.let { MetaGroup.Plain(it, muted = true) },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        MetaLine(
                            groups = listOf(
                                MetaGroup.Kind(
                                    stringResource(
                                        if (prompt.playing) R.string.continue_on_playing else R.string.continue_on_paused,
                                    ),
                                ),
                                MetaGroup.Stat(formatPosition(prompt.positionMs)),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                AnimatedContent(
                    targetState = prompt.stage to prompt.account,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    label = "continueOnActions",
                ) { (stage, account) ->
                    Actions(
                        stage = stage,
                        account = account,
                        continueModifier = continueModifier,
                        onContinue = {
                            haptics.performConfirm()
                            onContinue()
                        },
                        onDismiss = {
                            haptics.performClick()
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The card's colours: flat, no shadow (owner 2026-11-13), so it stands on the page's inverse
 * surface the way YoinSnackbar does, about 18:1 against the page in light and dark. The title
 * takes the page's own surface tone, the quieter lines inverseOnSurface, the accent
 * inversePrimary, and "Continue here" is an inversePrimary pill with the card's colour for text.
 */
internal fun continueOnCardScheme(page: ColorScheme): ColorScheme = page.copy(
    surface = page.inverseSurface,
    onSurface = page.surface,
    onSurfaceVariant = page.inverseOnSurface,
    surfaceContainerHigh = page.inverseSurface,
    surfaceContainerHighest = lerp(page.inverseSurface, page.inverseOnSurface, 0.14f),
    primary = page.inversePrimary,
    onPrimary = page.inverseSurface,
)

@Composable
private fun Actions(
    stage: ContinueOnPrompt.Stage,
    account: ContinueOnPrompt.Account,
    continueModifier: Modifier,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val note = when {
        stage == ContinueOnPrompt.Stage.OtherStillPlaying -> R.string.continue_on_other_still_playing
        stage == ContinueOnPrompt.Stage.Failed -> R.string.continue_on_failed
        account == ContinueOnPrompt.Account.Missing -> R.string.continue_on_no_account
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (note != null) {
            Text(
                text = stringResource(note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val asking = stage == ContinueOnPrompt.Stage.Asking && account !is ContinueOnPrompt.Account.Missing
            when {
                stage == ContinueOnPrompt.Stage.Starting -> YoinLoadingIndicator(size = 40.dp)
                asking -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.continue_on_not_now)) }
                    Button(
                        onClick = onContinue,
                        enabled = account is ContinueOnPrompt.Account.Found,
                        modifier = continueModifier,
                    ) {
                        Text(stringResource(R.string.continue_on_continue_here))
                    }
                }
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.continue_on_ok)) }
            }
        }
    }
}

@Composable
private fun Cover(url: String?) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(YoinArtworkShapes.Thumb)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YoinSymbols.Album,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun formatPosition(positionMs: Long): String {
    val seconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

/** A card for a hand-off that never happened: the preview's and the Tips demo's. */
internal fun sampleContinueOnPrompt(
    title: String,
    artist: String,
    album: String,
    coverUrl: String?,
    stage: ContinueOnPrompt.Stage = ContinueOnPrompt.Stage.Asking,
): ContinueOnPrompt = ContinueOnPrompt(
    cursor = ContinueOnCursor(
        provider = "subsonic",
        accountKey = null,
        trackId = "sample",
        title = title,
        artist = artist,
        album = album,
        cover = null,
        queueIds = listOf("sample"),
        index = 0,
        positionMs = 83_000L,
        capturedAtMs = 0L,
        playing = true,
        repeatMode = 0,
        shuffle = false,
        context = null,
        deviceName = "Pixel",
        pause = null,
    ),
    account = ContinueOnPrompt.Account.Found("sample", "Home server"),
    coverUrl = coverUrl,
    stage = stage,
)

@Preview
@Composable
private fun ContinueOnCardPreview() {
    YoinTheme {
        ContinueOnCard(
            prompt = sampleContinueOnPrompt("Weightless", "Marconi Union", "Weightless", coverUrl = null),
            onContinue = {},
            onDismiss = {},
        )
    }
}
