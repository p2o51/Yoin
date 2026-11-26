package com.gpo.yoin.data.model

/**
 * Whether a song or a release is marked explicit, as its service says: Explicit, Clean, or null = unknown
 * (docs/design.md, the "E" badge). The type and its readers (Apple Music `contentRating`, OpenSubsonic
 * `explicitStatus`, Spotify `explicit` and a release's rating from its tracks, the stored name) live in :shared
 * (`com.gpo.yoin.shared.release`), so iOS and HarmonyOS read the marks the same way
 * (shared/src/commonTest/resources/golden/release-marks.json, pinned by ReleaseMarksPinTest).
 */
typealias ContentRating = com.gpo.yoin.shared.release.ContentRating
