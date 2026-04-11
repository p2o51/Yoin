package com.gpo.yoin.ui.navigation

internal fun albumCoverSharedKey(
    albumId: String,
    sourceKey: String? = null,
): String = if (sourceKey.isNullOrBlank()) {
    "album-cover:$albumId"
} else {
    "album-cover:$albumId:$sourceKey"
}
