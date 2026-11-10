package com.gpo.yoin.ui.navigation

internal fun nowPlayingCoverSharedKey(songId: String?): String =
    if (songId.isNullOrBlank()) {
        "np_cover"
    } else {
        "np_cover:$songId"
    }
