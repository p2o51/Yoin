package com.gpo.yoin.shared.search

/*
 * The one text operation search folding needs that Kotlin common has no API for. Android uses
 * java.text.Normalizer, iOS NSString (Foundation): the same Unicode tables either way.
 */

/** Unicode NFKD: compatibility forms to their plain forms (Ｙ → Y, ｶ → カ, ﬁ → fi), then canonical decomposition. */
internal expect fun normalizeNfkd(text: String): String
