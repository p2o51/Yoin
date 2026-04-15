package com.gpo.yoin.ui.navigation

internal fun albumCoverSharedKey(
    albumId: String,
    sourceKey: String? = null,
): String = if (sourceKey.isNullOrBlank()) {
    "album-cover:$albumId"
} else {
    "album-cover:$albumId:$sourceKey"
}

internal fun artistCoverSharedKey(
    artistId: String,
    sourceKey: String? = null,
): String = if (sourceKey.isNullOrBlank()) {
    "artist-cover:$artistId"
} else {
    "artist-cover:$artistId:$sourceKey"
}

internal fun playlistCoverSharedKey(
    playlistId: String,
    sourceKey: String? = null,
): String = if (sourceKey.isNullOrBlank()) {
    "playlist-cover:$playlistId"
} else {
    "playlist-cover:$playlistId:$sourceKey"
}
