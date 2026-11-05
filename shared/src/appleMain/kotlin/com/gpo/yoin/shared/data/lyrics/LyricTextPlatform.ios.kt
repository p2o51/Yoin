package com.gpo.yoin.shared.data.lyrics

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.precomposedStringWithCompatibilityMapping
import platform.Foundation.stringByApplyingTransform

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeNfkc(text: String): String =
    (text as NSString).precomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun normalizeNfd(text: String): String =
    (text as NSString).decomposedStringWithCanonicalMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun traditionalToSimplified(text: String): String {
    if (!containsHan(text)) return text
    // ICU transform id "Hant-Hans" == "Traditional-Simplified".
    return (text as NSString).stringByApplyingTransform("Hant-Hans", false) ?: text
}
