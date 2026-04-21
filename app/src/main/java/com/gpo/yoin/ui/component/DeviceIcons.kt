package com.gpo.yoin.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeviceHub
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tablet
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.data.model.YoinDevice

fun iconForDevice(device: YoinDevice): ImageVector = when (device) {
    is YoinDevice.LocalPlayback -> Icons.Rounded.Headphones
    is YoinDevice.Chromecast -> Icons.Rounded.Cast
    is YoinDevice.SpotifyConnect -> when (device.spotifyType) {
        "Computer" -> Icons.Rounded.Computer
        "Smartphone" -> Icons.Rounded.Smartphone
        "Tablet" -> Icons.Rounded.Tablet
        "Speaker", "AVR" -> Icons.Rounded.Speaker
        "TV", "STB" -> Icons.Rounded.Tv
        "GameConsole" -> Icons.Rounded.SportsEsports
        "CastVideo", "CastAudio" -> Icons.Rounded.Cast
        "Automobile" -> Icons.Rounded.DirectionsCar
        else -> Icons.Rounded.DeviceHub
    }
}
