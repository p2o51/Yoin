package com.gpo.yoin.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * About's credits card (owner 2026-11-05): what the lyrics source wrote into
 * the lyrics. The four headline roles up front, every other role behind
 * "more", copyright notices small at the bottom. Shown with or without Gemini.
 */
@Composable
fun LyricCreditsCard(
    credits: LyricCredits,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(credits) { mutableStateOf(false) }
    val headline = LyricCreditRole.entries.mapNotNull { role ->
        credits.namesFor(role)?.let { role to it }
    }
    val others = credits.others
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stringResource(R.string.cmp_lyric_credits_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.cmp_lyric_credits_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            headline.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEach { (role, names) ->
                        HeadlineCredit(label = labelFor(role), names = names, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (others.isNotEmpty()) {
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        others.forEach { OtherCredit(it) }
                    }
                }
                val noteLabel = stringResource(R.string.cmp_lyric_credits_note)
                Text(
                    text = if (expanded) {
                        stringResource(R.string.cmp_lyric_credits_less)
                    } else {
                        stringResource(
                            R.string.cmp_lyric_credits_more,
                            others.size,
                            others.take(3).joinToString("、") { it.role.ifEmpty { noteLabel } } + "…",
                        )
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .padding(vertical = 4.dp),
                )
            }
            if (credits.notices.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    credits.notices.forEach { notice ->
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadlineCredit(label: String, names: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = names,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OtherCredit(credit: LyricCredit) {
    if (credit.role.isEmpty()) {
        Text(
            text = credit.names,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }
    Row {
        Text(
            text = credit.role,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            text = credit.names,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun labelFor(role: LyricCreditRole): String = stringResource(
    when (role) {
        LyricCreditRole.Lyricist -> R.string.cmp_lyric_credits_lyricist
        LyricCreditRole.Composer -> R.string.cmp_lyric_credits_composer
        LyricCreditRole.Arranger -> R.string.cmp_lyric_credits_arranger
        LyricCreditRole.Producer -> R.string.cmp_lyric_credits_producer
    },
)

/**
 * Gemini's canonical About keys the lyrics source already answers (owner
 * 2026-11-05: the source's credits replace Gemini's guess for the same field).
 */
fun LyricCredits?.coveredAboutKeys(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        if (namesFor(LyricCreditRole.Lyricist) != null) add(SongAboutEntry.CANON_LYRICIST)
        if (namesFor(LyricCreditRole.Composer) != null) add(SongAboutEntry.CANON_COMPOSER)
        if (namesFor(LyricCreditRole.Producer) != null) add(SongAboutEntry.CANON_PRODUCER)
    }
}

@Preview(showBackground = true)
@Composable
private fun LyricCreditsCardPreview() {
    YoinTheme {
        LyricCreditsCard(
            credits = LyricCredits(
                roles = listOf(
                    LyricCredit("作词", "唐恬", LyricCreditRole.Lyricist),
                    LyricCredit("作曲", "钱雷", LyricCreditRole.Composer),
                    LyricCredit("编曲", "钱雷", LyricCreditRole.Arranger),
                    LyricCredit("制作人", "钱雷", LyricCreditRole.Producer),
                    LyricCredit("吉他", "高飞"),
                    LyricCredit("混音", "周天澈"),
                ),
                notices = listOf("录音作品及MV版权：EAS MUSIC LTD"),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
