package com.gpo.yoin.data.model

/**
 * An Apple Music library song MusicKit can't play: one Apple never matched to
 * its catalog (imported or uploaded music), or one whose catalog song the
 * account's storefront doesn't have (`appleMusicCatalogUnavailable`; its old
 * catalog id still names it). MusicKit can only play catalog songs, and it
 * drops the second kind from a queue without a word, so Yoin shows these
 * dimmed with an explanation and never hands them over.
 */
val Track.isUnplayableAppleImport: Boolean
    get() = id.provider == MediaId.PROVIDER_APPLE_MUSIC && (
        extras["appleMusicCatalogUnavailable"] == "true" ||
            extras["appleMusicCatalogId"] == null && !id.rawId.matches(Regex("[0-9]+"))
        )

const val UNPLAYABLE_APPLE_IMPORT_REASON =
    "This song was imported into your Apple Music library and has no Apple Music catalog version. " +
        "MusicKit can only play catalog songs, so play it in the Apple Music app."
