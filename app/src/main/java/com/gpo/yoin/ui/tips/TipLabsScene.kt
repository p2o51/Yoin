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
import com.gpo.yoin.data.highlight.NanoStatus
import com.gpo.yoin.ui.settings.LabsPageBody
import com.gpo.yoin.ui.settings.LabsState
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import kotlinx.coroutines.delay

/**
 * Yoin Labs (owner 2026-11-09): Settings › Yoin Labs in the little phone, the real page on a phone with Gemini
 * Nano, and the dot turns the lyric cards on. The demo keeps the switch to itself: it never changes the real one.
 */
@Composable
internal fun LabsDemo(playing: Boolean) {
    var on by remember { mutableStateOf(false) }
    DemoPhone(playing = playing, script = {
        on = false
        delay(700)
        moveTo("labs-switch")
        delay(250)
        tap { on = true }
        lift()
        delay(2_400)
    }) {
        SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsHeadline(title = stringResource(R.string.settings_labs_title))
                LabsPageBody(
                    state = LabsState(nano = NanoStatus.AVAILABLE, lyricsOn = on),
                    onDownload = {},
                    onCheckAgain = {},
                    onLyricsChange = { on = it },
                    onOpenDiagnostics = {},
                    switchModifier = Modifier.demoTarget("labs-switch"),
                )
            }
        }
    }
}
