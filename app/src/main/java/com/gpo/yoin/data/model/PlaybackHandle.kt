package com.gpo.yoin.data.model

/**
 * How the player should realise a [Track]. Subsonic returns [DirectStream]
 * (Media3 handles it directly); Spotify v2 will return [ExternalController]
 * for handoff to the App Remote SDK.
 */
sealed interface PlaybackHandle {

    data class DirectStream(
        val uri: String,
        val headers: Map<String, String> = emptyMap(),
    ) : PlaybackHandle

    data class ExternalController(
        val type: ControllerType,
        val payload: Any,
    ) : PlaybackHandle

    enum class ControllerType {
        SPOTIFY_APP_REMOTE,
    }
}
