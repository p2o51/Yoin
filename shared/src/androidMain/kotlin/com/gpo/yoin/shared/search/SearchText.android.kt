package com.gpo.yoin.shared.search

import java.text.Normalizer

internal actual fun normalizeNfkd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
