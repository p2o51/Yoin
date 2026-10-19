package com.gpo.yoin.ui.tips

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinArmTransform
import com.gpo.yoin.ui.component.YoinMark
import com.gpo.yoin.ui.home.HomeBubbleTip
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/**
 * How a tip is done, as a small live phone built from the app's own
 * components (owner 2026-10-19: native UI the user can press too, with
 * nothing behind it; every scene out of the real components). A touch dot
 * acts the gesture out on a loop — tap (a ripple), hold (its ring fills),
 * drag — and the phone answers the way the app does. Once the user touches
 * the phone the demo steps aside and the phone is theirs; left alone, the
 * demo starts over (DemoPhone). It runs only while [playing] (its page is the
 * settled one); reduced motion keeps the phone still but still pressable.
 * Decorative for accessibility: the text says it.
 */
@Composable
internal fun TipIllustration(
    tip: HomeBubbleTip,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp))
            .padding(vertical = 16.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (tip) {
            HomeBubbleTip.EditHome -> EditHomeDemo(playing)
            HomeBubbleTip.PullForMemories -> PullDemo(playing)
            HomeBubbleTip.CoverMenu -> CoverMenuDemo(playing, keepScreenOn = false)
            HomeBubbleTip.SongMenu -> SongMenuDemo(playing)
            HomeBubbleTip.PlayModes -> PlayModesDemo(playing)
            HomeBubbleTip.NoteMoments -> NoteMomentsDemo(playing)
            HomeBubbleTip.LibrarySearch -> LibrarySearchDemo(playing)
            HomeBubbleTip.KeepScreenOn -> CoverMenuDemo(playing, keepScreenOn = true)
            HomeBubbleTip.ScrollEdge -> ScrollEdgeDemo(playing)
            HomeBubbleTip.Name -> NameDemo(playing)
        }
    }
}

/** 余韻: the Yoin mark; each tap (the demo's, or the user's) sounds a note whose rings keep spreading. */
@Composable
private fun NameDemo(playing: Boolean) {
    val notes = remember { mutableStateListOf<Long>() }
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            withFrameNanos { t ->
                now.longValue = t
                notes.removeAll { t - it > NoteLifeNs + 2 * NoteEchoNs }
            }
        }
    }
    DemoPhone(playing = playing, script = {
        moveTo("mark")
        tap { notes += now.longValue }
        delay(1_200)
        tap { notes += now.longValue }
        lift()
        delay(2_000)
    }) {
        val scheme = MaterialTheme.colorScheme
        ExpressivePageBackground(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        notes += now.longValue
                    },
            ) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val max = size.height * 0.6f
                val t = now.longValue
                notes.forEach { start ->
                    repeat(3) { k ->
                        val p = ((t - start - k * NoteEchoNs) / NoteLifeNs.toFloat()).coerceIn(0f, 1f)
                        if (p > 0f && p < 1f) {
                            drawCircle(
                                scheme.primary.copy(alpha = (1f - p) * 0.6f),
                                max * p,
                                c,
                                style = Stroke(width = (1f - p) * 6.dp.toPx() + 1.5.dp.toPx()),
                            )
                        }
                    }
                }
            }
            // The launcher mark's arm roles: tertiary, primary, secondary.
            YoinMark(
                transforms = List(3) { YoinArmTransform() },
                colors = listOf(scheme.tertiary, scheme.primary, scheme.secondary),
                lineColor = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(MarkSize)
                    .demoTarget("mark"),
            )
        }
    }
}

private val MarkSize = 96.dp
private const val NoteLifeNs = 2_400_000_000L
private const val NoteEchoNs = 220_000_000L

@Preview(showBackground = true)
@Composable
private fun TipIllustrationPreview() {
    YoinTheme {
        TipIllustration(tip = HomeBubbleTip.CoverMenu, playing = false, modifier = Modifier.padding(16.dp))
    }
}
