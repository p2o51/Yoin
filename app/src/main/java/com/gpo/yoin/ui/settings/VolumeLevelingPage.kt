package com.gpo.yoin.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.ReplayGainMode
import com.gpo.yoin.player.ReplayGainPreference
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * Settings › Playback › Volume leveling (owner task 2026-11-04), a PushPage
 * like Streaming quality: SettingsFeatureActivity, native cross-Activity back
 * to Settings, the page's own back button; no gesture of its own, no back
 * code. One group, one choice; it applies at once, to the song playing too.
 * Shown only while the active account reports ReplayGain (Capability.REPLAY_GAIN).
 */
@Composable
internal fun VolumeLevelingPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberYoinHaptics()
    val settings by ReplayGainPreference.observe(context).collectAsState()
    SettingsSegments(modifier = modifier) {
        ReplayGainMode.entries.forEach { mode ->
            item(key = mode) {
                QualityRow(
                    label = replayGainModeLabel(mode),
                    detail = null,
                    selected = mode == settings.mode,
                    onClick = {
                        if (mode != settings.mode) {
                            haptics.performContextClick()
                            ReplayGainPreference.setMode(context, mode)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun replayGainModeLabel(mode: ReplayGainMode): String = stringResource(
    when (mode) {
        ReplayGainMode.Off -> R.string.settings_volume_leveling_off
        ReplayGainMode.Track -> R.string.settings_volume_leveling_track
        ReplayGainMode.Album -> R.string.settings_volume_leveling_album
    },
)

@Preview(showBackground = true)
@Composable
private fun VolumeLevelingPagePreview() {
    YoinTheme {
        VolumeLevelingPageContent(modifier = Modifier.padding(16.dp))
    }
}
