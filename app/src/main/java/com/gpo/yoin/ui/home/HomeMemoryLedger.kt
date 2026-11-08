package com.gpo.yoin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.ui.component.elasticPress
import com.gpo.yoin.ui.component.noRippleClickable
import com.gpo.yoin.ui.home.edit.homeEditInteractive
import com.gpo.yoin.ui.memories.ledger.MemoryGridPlacement
import com.gpo.yoin.ui.memories.ledger.MemoryGridView
import com.gpo.yoin.ui.memories.ledger.MemoryLedgerState
import com.gpo.yoin.ui.memories.ledger.scaleLabel
import com.gpo.yoin.ui.theme.GoogleSansFlex

/**
 * Home's Memories grid (owner 2026-11-08, "B3"): how much you've written, and the calendar wall of it, a
 * cell a month, week or day as you write more. A tap opens All Memories. Hidden while there's nothing.
 */
@Composable
internal fun MemoryLedgerSection(
    state: MemoryLedgerState,
    extractBackdropColors: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val openLabel = stringResource(R.string.memory_ledger_open)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HomeSectionTitle(text = stringResource(HomeSection.MemoryGrid.titleRes))
        Column(
            modifier = Modifier
                // A tablet's wide feed would blow a month's quads up past a cover; the card stays phone-wide.
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .elasticPress(interactionSource)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .noRippleClickable(
                    interactionSource = interactionSource,
                    enabled = homeEditInteractive(),
                    onClickLabel = openLabel,
                    onClick = onOpen,
                )
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                // What you wrote leads; with only scores so far, the scores.
                val wrote = state.stats.writings > 0
                val count = if (wrote) state.stats.writings else state.entries.size
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = GoogleSansFlex,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = pluralStringResource(
                        if (wrote) R.plurals.memory_ledger_writings_label else R.plurals.memory_ledger_scores_label,
                        count,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp).weight(1f),
                )
                Text(
                    text = scaleLabel(state.grid.scale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            MemoryGridView(
                grid = state.grid,
                coverUrls = state.coverUrls,
                placement = MemoryGridPlacement.Home,
                extractColors = extractBackdropColors,
            )
        }
    }
}
