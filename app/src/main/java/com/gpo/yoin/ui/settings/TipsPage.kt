package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeBubbleTip
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.tips.InMemoryTipReadStore
import com.gpo.yoin.ui.tips.LocalTipReadStore
import com.gpo.yoin.ui.tips.SharedPrefsTipReadStore
import com.gpo.yoin.ui.tips.TipActivity
import com.gpo.yoin.ui.tips.TipReadStore
import androidx.compose.runtime.CompositionLocalProvider

@Composable
private fun rememberTipReadStore(): TipReadStore {
    val context = LocalContext.current
    return LocalTipReadStore.current ?: remember(context) { SharedPrefsTipReadStore.get(context) }
}

/** "3 of 10 read" / "All read" — the Tips row's summary. */
@Composable
internal fun tipsReadSummary(): String {
    val read by rememberTipReadStore().read.collectAsState()
    val total = HomeBubbleTip.entries.size
    return if (read.size >= total) {
        stringResource(R.string.settings_tips_all_read)
    } else {
        stringResource(R.string.settings_tips_read_count, read.size, total)
    }
}

/**
 * Settings › About › Tips as a page: every tip, read ones checked; a tap
 * opens it on its own page. Once something is read, one more row marks them
 * all unread, so the bubble brings them up again.
 */
@Composable
internal fun TipsPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val store = rememberTipReadStore()
    val read by store.read.collectAsState()
    Column(modifier = modifier) {
        SettingsSegments {
            HomeBubbleTip.entries.forEach { tip ->
                item(key = tip) {
                    SettingsItem(
                        icon = tip.icon,
                        title = stringResource(tip.title),
                        summary = stringResource(tip.text),
                        onClick = { context.startActivity(TipActivity.intent(context, tip)) },
                        trailing = if (tip in read) {
                            {
                                Icon(
                                    imageVector = YoinSymbols.Check,
                                    contentDescription = stringResource(R.string.tip_read),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        if (read.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsSegments {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Refresh,
                        title = stringResource(R.string.settings_tips_mark_all_unread),
                        onClick = {
                            haptics.performClick()
                            store.setAllUnread()
                        },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TipsPageContentPreview() {
    YoinTheme {
        CompositionLocalProvider(
            LocalTipReadStore provides InMemoryTipReadStore(setOf(HomeBubbleTip.EditHome, HomeBubbleTip.Name)),
        ) {
            TipsPageContent()
        }
    }
}
