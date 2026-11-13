package com.gpo.yoin.ui.tips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.player.ReplayGainMode
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.VolumeLevelingPageBody
import kotlinx.coroutines.delay

/**
 * Volume leveling (parity with iOS, 2026-11-13): a Subsonic account's Volume leveling page in the little phone,
 * the real page, and the dot picks By track, then By album. The demo keeps the choice to itself: it never
 * changes the real one.
 */
@Composable
internal fun VolumeLevelingDemo(playing: Boolean) {
    var mode by remember { mutableStateOf(ReplayGainMode.Off) }
    DemoPhone(playing = playing, script = {
        mode = ReplayGainMode.Off
        delay(700)
        moveTo(levelingRow(ReplayGainMode.Track))
        delay(250)
        tap { mode = ReplayGainMode.Track }
        delay(900)
        moveTo(levelingRow(ReplayGainMode.Album))
        delay(250)
        tap { mode = ReplayGainMode.Album }
        lift()
        delay(2_400)
    }) {
        SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsHeadline(title = stringResource(R.string.settings_volume_leveling_title))
                VolumeLevelingPageBody(
                    mode = mode,
                    onModeChange = { mode = it },
                    rowModifier = { Modifier.demoTarget(levelingRow(it)) },
                )
            }
        }
    }
}

/** The demo touch's name for [mode]'s row. */
private fun levelingRow(mode: ReplayGainMode): String = "leveling-${mode.name}"
