package com.gpo.yoin.shared.data.lyrics

/*
 * The three text operations the candidate matcher needs that Kotlin common
 * has no API for. Android uses java.text.Normalizer / android.icu; iOS uses
 * NSString (Foundation) — the same Unicode tables either way.
 */

/** Unicode NFKC (full-width → half-width, compatibility forms). */
internal expect fun normalizeNfkc(text: String): String

/** Unicode NFD (accented Latin letters → base letter + combining marks). */
internal expect fun normalizeNfd(text: String): String

/**
 * Traditional → Simplified Chinese (ICU `Traditional-Simplified`). Text without
 * Han is returned as is; when the platform has no such transform the text comes
 * back unchanged (then 繁簡-different names fall through to "can't tell").
 */
internal expect fun traditionalToSimplified(text: String): String
