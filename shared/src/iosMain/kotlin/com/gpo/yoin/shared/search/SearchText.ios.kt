package com.gpo.yoin.shared.search

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeNfkd(text: String): String =
    (text as NSString).decomposedStringWithCompatibilityMapping
