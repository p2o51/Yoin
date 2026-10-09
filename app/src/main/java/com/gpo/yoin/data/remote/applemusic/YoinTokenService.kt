package com.gpo.yoin.data.remote.applemusic

/**
 * Yoin's own MusicKit developer-token service, offered to anyone without an
 * Apple Developer Program membership. Public on purpose (owner 2026-10-09):
 * it hands out short-lived developer tokens, nothing account-specific.
 */
object YoinTokenService {
    const val URL = "https://yoin-apple-music-token.p2o51willam.workers.dev/token"
}
