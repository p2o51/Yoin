package com.gpo.yoin.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.theme.YoinShapeTokens

/*
 * What asking Gemini about a song looks like wherever it is asked — the scrapbook's track sheet and Now
 * Playing's Ask bar (owner-approved prototype v3, 2026-11-02): the quick questions offered while the field
 * is empty, and the dots shown while Gemini writes.
 */

/** The quick questions still worth offering for a song: those not asked about it yet. */
@Composable
internal fun quickPromptsFor(asked: Collection<String>): List<String> =
    remainingQuickPrompts(stringArrayResource(R.array.cmp_quick_prompts).toList(), asked)

/** The quick prompts left once [asked] are taken out (compared the way About rows are keyed). */
internal fun remainingQuickPrompts(prompts: List<String>, asked: Collection<String>): List<String> {
    val done = asked.mapTo(HashSet(), SongAboutEntry::normalize)
    return prompts.filter { SongAboutEntry.normalize(it) !in done }
}

/** The quick questions as a row of pills that scrolls sideways; a tap hands the question to [onPick]. */
@Composable
internal fun QuickPrompts(
    prompts: List<String>,
    container: Color,
    content: Color,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val label = stringResource(R.string.cmp_quick_prompts_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = label }
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        prompts.forEach { prompt ->
            Surface(
                onClick = { onPick(prompt) },
                shape = YoinShapeTokens.Full,
                color = container,
                contentColor = content,
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Three dots blinking in turn while Gemini writes (held still under reduced motion). */
@Composable
internal fun ThinkingDots(color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val description = stringResource(R.string.cmp_cd_gemini_thinking)
    val transition = rememberInfiniteTransition(label = "geminiThinking")
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val alpha = if (reduced) {
                0.6f
            } else {
                transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 500, delayMillis = i * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "geminiThinkingDot$i",
                ).value
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
