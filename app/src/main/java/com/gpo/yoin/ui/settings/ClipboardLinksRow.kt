package com.gpo.yoin.ui.settings

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.gpo.yoin.R
import com.gpo.yoin.data.openin.OpenInSettingsStore
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/** Settings › Features › Detect music links in clipboard (docs/open-in-yoin.md); on by default. */
@Composable
internal fun ClipboardLinksRow(store: OpenInSettingsStore) {
    val enabled by store.clipboardDetectionEnabled.collectAsState()
    ClipboardLinksRow(enabled = enabled, onEnabledChange = store::setClipboardDetectionEnabled)
}

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
        trailing = { Switch(checked = enabled, onCheckedChange = null, modifier = Modifier.handPointer()) },
    )
}

@Preview
@Composable
private fun ClipboardLinksRowPreview() {
    YoinTheme {
        SettingsSegments { item { ClipboardLinksRow(enabled = true, onEnabledChange = {}) } }
    }
}
