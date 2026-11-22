package com.gpo.yoin.ui.nowplaying.compact

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.notes.BacklinkSource
import com.gpo.yoin.data.notes.NoteBacklink
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.formatNotePosition
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.rememberPressMorphShape
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.notes.LocalNoteMentions
import com.gpo.yoin.ui.nowplaying.NoteDoodle
import com.gpo.yoin.ui.nowplaying.NoteDoodleViewBoxHeight
import com.gpo.yoin.ui.nowplaying.NoteDoodleViewBoxWidth
import com.gpo.yoin.ui.nowplaying.NoteEmptyDoodle
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * The collapsed Note pane with no notes yet (prototype scenario 1): the Stroke doodle draws itself beside a
 * 写笔记 button that carries the live playhead (the moment a note would be filed at); pressing it morphs its
 * cookie into a circle, releasing opens the write bar ([onWrite]). Under it, when the song is mentioned
 * elsewhere, one quiet line says where (the newest place, and how many more); a tap goes there.
 */
@Composable
internal fun NoteCompactEmpty(
    positionMs: () -> Long,
    onWrite: () -> Unit,
    backlinks: List<NoteBacklink>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        val doodleWidth = minOf(maxWidth * 0.42f, 160.dp)
        val doodleHeight = doodleWidth * (NoteDoodleViewBoxHeight / NoteDoodleViewBoxWidth)
        val showDoodle = maxWidth >= 300.dp && maxHeight >= doodleHeight
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (showDoodle) {
                NoteEmptyDoodle(doodle = NoteDoodle.Stroke, modifier = Modifier.size(doodleWidth, doodleHeight))
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NoteWriteButton(positionMs = positionMs, onClick = onWrite)
                backlinks.firstOrNull()?.let { first -> MentionedHint(first, more = backlinks.size - 1) }
            }
        }
    }
}

/**
 * The empty pane's 写笔记: a pill with a cookie-shaped pen badge (morphs to a circle while pressed, turns a
 * little on arrival), the label and the live playhead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NoteWriteButton(positionMs: () -> Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    val interaction = remember { MutableInteractionSource() }
    val badgeShape = rememberPressMorphShape(
        base = MaterialShapes.Cookie9Sided,
        interactionSource = interaction,
        pressed = MaterialShapes.Circle,
    )
    val turn = remember { Animatable(-90f) }
    val arrive = remember { Animatable(0.86f) }
    val turnSpec = YoinMotion.slowSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    val popSpec = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(Unit) { arrive.animateTo(1f, popSpec) }
    LaunchedEffect(Unit) {
        delay(BadgeTurnDelayMs)
        turn.animateTo(0f, turnSpec)
    }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Press) haptics.performTick() }
    }
    val latest by rememberUpdatedState(positionMs)
    val seconds by remember { derivedStateOf { latest() / 1_000L } }
    val stamp = formatNotePosition(seconds * 1_000L)
    val label = stringResource(R.string.notes_empty_write_cd, stamp)
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = arrive.value
                scaleY = arrive.value
                alpha = ((arrive.value - 0.86f) / 0.14f).coerceIn(0f, 1f)
            }
            .background(MaterialTheme.colorScheme.primaryContainer, YoinShapeTokens.Full)
            .elasticPress(interaction)
            .noRippleClickable(interactionSource = interaction) {
                haptics.performConfirm()
                onClick()
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            }
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .graphicsLayer { rotationZ = turn.value }
                .background(MaterialTheme.colorScheme.primary, badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = -turn.value },
            )
        }
        Text(
            text = stringResource(R.string.notes_empty_write),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = stamp,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun MentionedHint(first: NoteBacklink, more: Int) {
    val controller = LocalNoteMentions.current
    val context = LocalContext.current
    val (name, what) = when (val source = first.source) {
        is BacklinkSource.SongNote -> source.trackTitle to stringResource(R.string.notes_source_note)
        is BacklinkSource.AlbumNote -> source.albumName to stringResource(R.string.notes_source_album_note)
        is BacklinkSource.Review -> (source.albumName ?: "") to stringResource(R.string.notes_source_review)
    }
    val text = if (more > 0) {
        pluralStringResource(R.plurals.notes_mentioned_in_more, more, name, what, more)
    } else {
        stringResource(R.string.notes_mentioned_in, name, what)
    }
    Row(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .noRippleClickable(interactionSource = remember { MutableInteractionSource() }) {
                controller?.openSource(context, first.source)
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExpressiveMediaArtwork(
            model = controller?.coverUrl(first.source.coverKey),
            contentDescription = null,
            shape = YoinArtworkShapes.Thumb,
            fallbackIcon = YoinSymbols.At,
            modifier = Modifier.size(20.dp),
            tonalElevation = 0.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val BadgeTurnDelayMs = 900L

@Preview(showBackground = true, widthDp = 380, heightDp = 160)
@Composable
private fun NoteCompactEmptyPreview() {
    YoinTheme {
        NoteCompactEmpty(positionMs = { 42_000L }, onWrite = {}, backlinks = emptyList(), modifier = Modifier.padding(24.dp))
    }
}
