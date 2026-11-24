package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.openin.OpenInSettingsStore
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * Settings › Features and appearance › Music links (Carbon + Rebecca, owner-approved 2026-11-24;
 * docs/open-in-yoin.md): a SettingsFeatureActivity page like Appearance, in the right pane on wide windows.
 * The clipboard toggle on top, then two read-only rows on how links reach Yoin.
 */

/** The app's clipboard-detection setting, or null in previews/tests that have no app container. */
@Composable
internal fun rememberOpenInSettingsStore(): OpenInSettingsStore? {
    val app = LocalContext.current.applicationContext as? YoinApplication
    return remember(app) { app?.container?.openInSettingsStore }
}

@Composable
internal fun MusicLinksPageContent(modifier: Modifier = Modifier) {
    val store = rememberOpenInSettingsStore()
    if (store == null) {
        var enabled by remember { mutableStateOf(true) }
        MusicLinksPageBody(enabled = enabled, onEnabledChange = { enabled = it }, modifier = modifier)
        return
    }
    val enabled by store.clipboardDetectionEnabled.collectAsState()
    MusicLinksPageBody(enabled = enabled, onEnabledChange = store::setClipboardDetectionEnabled, modifier = modifier)
}

/** The page itself, stateless. */
@Composable
internal fun MusicLinksPageBody(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        SettingsSegments {
            item(key = "clipboard-links") { ClipboardLinksRow(enabled = enabled, onEnabledChange = onEnabledChange) }
        }
        SettingsSegments {
            item(key = "share") {
                SettingsItem(
                    icon = YoinSymbols.Share,
                    title = stringResource(R.string.settings_music_links_share_title),
                    summary = stringResource(R.string.settings_music_links_share_body),
                )
            }
            item(key = "cross-service") {
                SettingsItem(
                    icon = YoinSymbols.Search,
                    title = stringResource(R.string.settings_music_links_cross_title),
                    summary = stringResource(R.string.settings_music_links_cross_body),
                )
            }
        }
    }
}

/** Music links › Detect music links in clipboard; on by default. A switch to TalkBack, state included. */
@Composable
internal fun ClipboardLinksRow(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val haptics = rememberYoinHaptics()
    SettingsItem(
        icon = YoinSymbols.Link,
        title = stringResource(R.string.settings_clipboard_links_title),
        summary = stringResource(R.string.settings_clipboard_links_summary),
        onClick = {
            haptics.performContextClick()
            onEnabledChange(!enabled)
        },
        checked = enabled,
        trailing = { Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.handPointer()) },
    )
}

@Preview(showBackground = true)
@Composable
private fun MusicLinksPagePreview() {
    YoinTheme {
        MusicLinksPageBody(enabled = true, onEnabledChange = {}, modifier = Modifier.padding(16.dp))
    }
}
