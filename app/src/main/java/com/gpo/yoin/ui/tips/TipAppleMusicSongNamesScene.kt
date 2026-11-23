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
import com.gpo.yoin.data.source.applemusic.AppleMusicNameRegion
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.applemusic.AppleMusicNameRegionContent
import kotlinx.coroutines.delay

/**
 * Apple Music song names (2026-11-23): the real Apple Music Song names group inside the little phone;
 * the dot picks Original language, then a storefront region. The demo keeps its selection local.
 */
@Composable
internal fun AppleMusicSongNamesDemo(playing: Boolean) {
    var region by remember { mutableStateOf<AppleMusicNameRegion>(AppleMusicNameRegion.Off) }
    DemoPhone(playing = playing, script = {
        region = AppleMusicNameRegion.Off
        delay(700)
        moveTo(appleMusicNameRow(AppleMusicNameRegion.Original.key))
        delay(250)
        tap { region = AppleMusicNameRegion.Original }
        delay(950)
        moveTo(appleMusicNameRow("region"))
        delay(250)
        tap { region = AppleMusicNameRegion.Japan }
        lift()
        delay(2_400)
    }) {
        SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SettingsHeadline(title = stringResource(R.string.settings_apple_names_title))
                AppleMusicNameRegionContent(
                    selected = region,
                    onSelect = { region = it },
                    rowModifier = { Modifier.demoTarget(appleMusicNameRow(it)) },
                )
            }
        }
    }
}

private fun appleMusicNameRow(key: String): String = "am-names-$key"
