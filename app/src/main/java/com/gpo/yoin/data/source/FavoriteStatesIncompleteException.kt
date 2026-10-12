package com.gpo.yoin.data.source

import com.gpo.yoin.data.model.MediaId

/**
 * A [MusicWriteActions.favoriteStates] read asked in parts (Spotify: 40
 * tracks a request) that failed after some parts answered — a 429 on a later
 * batch. [answered] holds what the parts before [cause] learned, so the
 * requests that got through aren't wasted; the tracks missing from it were
 * not answered.
 */
class FavoriteStatesIncompleteException(
    val answered: Map<MediaId, Boolean>,
    cause: Throwable
) : Exception("Favorite lookup stopped after ${answered.size} answers: ${cause.message}", cause)
