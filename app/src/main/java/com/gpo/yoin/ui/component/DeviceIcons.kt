package com.gpo.yoin.ui.component

import androidx.compose.ui.graphics.vector.ImageVector
import com.gpo.yoin.data.model.YoinDevice
import com.gpo.yoin.symbols.YoinSymbols

fun iconForDevice(device: YoinDevice): ImageVector = when (device) {
    is YoinDevice.LocalPlayback -> YoinSymbols.Headphones
    is YoinDevice.Chromecast -> YoinSymbols.Cast
    is YoinDevice.SpotifyConnect -> when (device.spotifyType) {
        "Computer" -> YoinSymbols.Computer
        "Smartphone" -> YoinSymbols.Smartphone
        "Tablet" -> YoinSymbols.Tablet
        "Speaker", "AVR" -> YoinSymbols.Speaker
        "TV", "STB" -> YoinSymbols.Tv
        "GameConsole" -> YoinSymbols.Gamepad
        "CastVideo", "CastAudio" -> YoinSymbols.Cast
        "Automobile" -> YoinSymbols.Car
        else -> YoinSymbols.DeviceOther
    }
}
