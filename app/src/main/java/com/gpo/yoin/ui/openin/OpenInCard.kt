package com.gpo.yoin.ui.openin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gpo.yoin.R
import com.gpo.yoin.data.openin.MusicLinkMetadata
import com.gpo.yoin.data.openin.OpenInPrompt
import com.gpo.yoin.data.openin.OpenInTarget
import com.gpo.yoin.shared.openin.YoinMusicLink
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.shared.openin.YoinMusicLinkService
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.continueon.continueOnCardScheme
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * The clipboard prompt (docs/open-in-yoin.md): a small card at the bottom of the shell, non-modal, on the
 * inverse surface the Continue On card and YoinSnackbar stand on. Two looks, kept visibly apart:
 *
 * - a **hit** (the user has an account on the link's service): cover, title, artist, the service's name,
 *   and Listen (a song) or Open (anything else) — it opens in that account;
 * - a **found** link (no such account): text only, "title · artist", and Search — it is searched on the
 *   account in use.
 *
 * Until the link's title is read the card says "<service> link in clipboard". It leaves on its own after a
 * while, as a snackbar does (longer under TalkBack).
 */
@Composable
fun OpenInCard(
    prompt: OpenInPrompt?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The account in use's provider: a found link's Search names it to TalkBack ("Search on Subsonic"). */
    currentProvider: String? = null,
) {
    // The last card stays drawn while it leaves.
    var shown by remember { mutableStateOf(prompt) }
    if (prompt != null) shown = prompt
    val accessibility = LocalAccessibilityManager.current
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(prompt?.serial) {
        if (prompt == null) return@LaunchedEffect
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            AUTO_DISMISS_MS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: AUTO_DISMISS_MS
        delay(timeout)
        dismiss()
    }
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
                    currentProvider = currentProvider,
                    onAccept = onAccept,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CardBody(
    prompt: OpenInPrompt,
    currentProvider: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberYoinHaptics()
    val page = MaterialTheme.colorScheme
    val cardScheme = remember(page) { continueOnCardScheme(page) }
    val hit = prompt.target is OpenInTarget.Hit
    val serviceName = stringResource(serviceNameRes(prompt.link.service))
    val searchLabel = currentProvider?.let { stringResource(R.string.open_in_search_on, serviceName(it)) }
    MaterialTheme(colorScheme = cardScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Surface(
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            shape = YoinShapeTokens.ExtraLarge,
            color = cardScheme.surface,
            contentColor = cardScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (hit) Cover(prompt.metadata?.artworkUrl, prompt.link.kind) else FoundMark()
                AnimatedContent(
                    targetState = prompt.metadata,
                    transitionSpec = {
                        YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                    },
                    contentKey = { it != null },
                    label = "openInLines",
                    modifier = Modifier.weight(1f),
                ) { metadata ->
                    Lines(hit = hit, metadata = metadata, serviceName = serviceName)
                }
                Button(
                    onClick = {
                        haptics.performConfirm()
                        onAccept()
                    },
                    modifier = Modifier
                        .handPointer()
                        .then(
                            if (!hit && searchLabel != null) {
                                Modifier.semantics { contentDescription = searchLabel }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        stringResource(
                            when {
                                !hit -> R.string.open_in_search
                                prompt.listens -> R.string.open_in_listen
                                else -> R.string.open_in_open
                            },
                        ),
                    )
                }
                IconButton(
                    onClick = {
                        haptics.performClick()
                        onDismiss()
                    },
                    modifier = Modifier.handPointer(),
                ) {
                    Icon(
                        imageVector = YoinSymbols.Close,
                        contentDescription = stringResource(R.string.cmp_snackbar_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Lines(hit: Boolean, metadata: MusicLinkMetadata?, serviceName: String) {
    val fallback = stringResource(R.string.open_in_link_in_clipboard, serviceName)
    val title = metadata?.title?.takeIf(String::isNotBlank)
    val artist = metadata?.artist?.takeIf(String::isNotBlank)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (hit) {
            Text(
                text = serviceName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title ?: fallback,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (title != null && artist != null) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            // Found: text only, "title · artist".
            Text(
                text = when {
                    title != null && artist != null -> "$title · $artist"
                    title != null -> title
                    else -> fallback
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Cover(url: String?, kind: YoinMusicLinkKind) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(if (kind == YoinMusicLinkKind.Artist) YoinShapeTokens.Full else YoinArtworkShapes.Thumb)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (kind) {
                YoinMusicLinkKind.Artist -> YoinSymbols.Artist
                YoinMusicLinkKind.Playlist -> YoinSymbols.Playlist
                YoinMusicLinkKind.Track -> YoinSymbols.MusicNote
                else -> YoinSymbols.Album
            },
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

/** A found link's lead: the link glyph, no cover (it isn't on the user's service). */
@Composable
private fun FoundMark() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(YoinShapeTokens.Full)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = YoinSymbols.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun serviceNameRes(service: YoinMusicLinkService): Int = when (service) {
    YoinMusicLinkService.Spotify -> R.string.settings_service_name_spotify
    YoinMusicLinkService.AppleMusic -> R.string.settings_service_name_apple
}

private const val AUTO_DISMISS_MS = 12_000L

private fun samplePrompt(target: OpenInTarget, metadata: MusicLinkMetadata?) = OpenInPrompt(
    serial = 1,
    link = YoinMusicLink(
        YoinMusicLinkService.Spotify,
        YoinMusicLinkKind.Track,
        "0VjIjW4GlUZAMYd2vXMi3b",
        storefront = null,
        url = "https://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b",
    ),
    target = target,
    metadata = metadata,
)

private val sampleMetadata = MusicLinkMetadata(YoinMusicLinkKind.Track, "Blinding Lights", "The Weeknd")

@Preview(name = "Hit")
@Composable
private fun OpenInCardHitPreview() {
    YoinTheme {
        OpenInCard(samplePrompt(OpenInTarget.Hit("p", "Spotify"), sampleMetadata), onAccept = {}, onDismiss = {})
    }
}

@Preview(name = "Found")
@Composable
private fun OpenInCardFoundPreview() {
    YoinTheme {
        OpenInCard(samplePrompt(OpenInTarget.Found, sampleMetadata), onAccept = {}, onDismiss = {})
    }
}

@Preview(name = "Hit, title not read yet")
@Composable
private fun OpenInCardPendingPreview() {
    YoinTheme {
        OpenInCard(samplePrompt(OpenInTarget.Hit("p", "Spotify"), null), onAccept = {}, onDismiss = {})
    }
}
