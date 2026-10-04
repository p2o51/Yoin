package com.gpo.yoin.data.model

/**
 * An Apple Music library song Apple never matched to its catalog (imported or
 * uploaded music). MusicKit can only play catalog songs, so Yoin shows these
 * dimmed with an explanation instead of failing at play time.
 */
val Track.isUnplayableAppleImport: Boolean
    get() = id.provider == MediaId.PROVIDER_APPLE_MUSIC &&
        extras["appleMusicCatalogId"] == null &&
        !id.rawId.matches(Regex("[0-9]+"))

const val UNPLAYABLE_APPLE_IMPORT_REASON =
    "This song was imported into your Apple Music library and has no Apple Music catalog version. " +
        "MusicKit can only play catalog songs, so play it in the Apple Music app."
