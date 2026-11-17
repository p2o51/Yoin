package com.gpo.yoin.ui.tips

import com.gpo.yoin.ui.component.handPointer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.ui.component.DeviceRow
import com.gpo.yoin.ui.component.MoreDevicesRow
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/**
 * Spotify speakers from the Devices sheet (owner 2026-11-08): More devices opens Android's
 * output panel over Yoin, with the Sonos speakers Spotify knows; picking one moves playback
 * there, and the sheet shows it as the device in use. Built from the sheet's own rows.
 */
@Composable
internal fun SpotifySpeakersDemo(playing: Boolean) {
    var panelOpen by remember { mutableStateOf(false) }
    var onSonos by remember { mutableStateOf(false) }
    val title = stringResource(R.string.cmp_devices_title)
    val subtitle = stringResource(R.string.cmp_devices_subtitle_spotify)
    val switchThroughSpotify = stringResource(R.string.np_device_spotify_only)
    DemoPhone(playing = playing, script = {
        panelOpen = false
        onSonos = false
        delay(500)
        moveTo("more")
        tap { panelOpen = true }
        delay(700)
        moveTo("living-room")
        tap {
            onSonos = true
            panelOpen = false
        }
        lift()
        delay(2_400)
    }) {
        val laptop = YoinDevice.SpotifyConnect(
            id = "laptop",
            name = "MacBook",
            isActive = !onSonos,
            spotifyType = "Computer",
        )
        val sonos = YoinDevice.SpotifyConnect(
            id = "sonos",
            name = DemoSonosName,
            isActive = onSonos,
            spotifyType = "Speaker",
            statusText = if (onSonos) null else switchThroughSpotify,
            spotifyOnly = true,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceDim),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                val rows = if (onSonos) listOf(sonos, laptop) else listOf(laptop, sonos)
                rows.forEach { device ->
                    DeviceRow(device = device, busy = false, onClick = {
                        if (device.spotifyOnly && !device.isActive) panelOpen = true
                    })
                }
                MoreDevicesRow(
                    onClick = { panelOpen = true },
                    modifier = Modifier.demoTarget("more"),
                )
            }
            // Android's output panel for Spotify's session, over Yoin.
            AnimatedVisibility(
                visible = panelOpen,
                enter = YoinMotion.fadeIn(YoinMotionRole.Standard) +
                    YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.94f),
                exit = YoinMotion.fadeOut(YoinMotionRole.Standard) +
                    YoinMotion.scaleOut(YoinMotionRole.Standard, targetScale = 0.94f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(28.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DemoSpeakers.forEach { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (name == DemoSonosName) Modifier.demoTarget("living-room") else Modifier)
                                .handPointer()
                                .clickable {
                                    if (name == DemoSonosName) onSonos = true
                                    panelOpen = false
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val DemoSonosName = "Living Room"
private val DemoSpeakers = listOf(DemoSonosName, "Kitchen", "PS5")
