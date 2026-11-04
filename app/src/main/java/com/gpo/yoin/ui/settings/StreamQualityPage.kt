package com.gpo.yoin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.AudioOutputKind
import com.gpo.yoin.player.StreamQuality
import com.gpo.yoin.player.StreamQualityPreference
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * Settings › Playback › Streaming quality (owner task 2026-11-03), a PushPage
 * like Scroll edge: SettingsFeatureActivity, native cross-Activity back to
 * Settings, the page's own back button; no gesture of its own, no back code.
 * Two groups, one choice each; it applies from the next song that opens.
 * Shown only while the active account can transcode (Capability.STREAM_QUALITY).
 */
@Composable
internal fun StreamQualityPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val settings by StreamQualityPreference.observe(context).collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        QualityGroup(
            title = stringResource(R.string.settings_stream_quality_wifi),
            selected = settings.wifi,
            onSelect = { quality ->
                haptics.performContextClick()
                StreamQualityPreference.setWifi(context, quality)
            },
        )
        QualityGroup(
            title = stringResource(R.string.settings_stream_quality_mobile),
            selected = settings.mobile,
            onSelect = { quality ->
                haptics.performContextClick()
                StreamQualityPreference.setMobile(context, quality)
            },
        )
    }
}

@Composable
private fun QualityGroup(
    title: String,
    selected: StreamQuality,
    onSelect: (StreamQuality) -> Unit,
) {
    SettingsGroup(title = title) {
        StreamQuality.entries.forEach { quality ->
            item(key = quality) {
                QualityRow(
                    label = streamQualityLabel(quality),
                    detail = if (quality == StreamQuality.Auto) autoQualityDetail() else null,
                    selected = quality == selected,
                    onClick = { if (quality != selected) onSelect(quality) },
                )
            }
        }
    }
}

@Composable
internal fun QualityRow(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(SettingsRowIconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What Auto asks for on each output: "Wired Original   Bluetooth 320 kbps   Speaker 192 kbps". */
@Composable
private fun autoQualityDetail(): String = stringResource(
    R.string.settings_stream_quality_auto_detail,
    streamQualityLabel(AudioOutputKind.Wired.autoQuality),
    streamQualityLabel(AudioOutputKind.Bluetooth.autoQuality),
    streamQualityLabel(AudioOutputKind.Speaker.autoQuality),
)

@Composable
internal fun streamQualityLabel(quality: StreamQuality): String = when {
    quality == StreamQuality.Auto -> stringResource(R.string.settings_stream_quality_auto)
    quality.maxBitRateKbps != null -> stringResource(R.string.settings_stream_quality_kbps, quality.maxBitRateKbps)
    else -> stringResource(R.string.settings_stream_quality_original)
}

@Preview(showBackground = true)
@Composable
private fun StreamQualityPagePreview() {
    YoinTheme {
        StreamQualityPageContent(modifier = Modifier.padding(16.dp))
    }
}
