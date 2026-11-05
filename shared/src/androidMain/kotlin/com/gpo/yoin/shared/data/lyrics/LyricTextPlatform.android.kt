package com.gpo.yoin.shared.data.lyrics

import android.icu.text.Transliterator
import android.os.Build
import java.text.Normalizer

internal actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun normalizeNfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

internal actual fun traditionalToSimplified(text: String): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !containsHan(text)) return text
    return IcuTraditionalToSimplified.apply(text)
}

private object IcuTraditionalToSimplified {

    private const val TRANSFORM_ID = "Traditional-Simplified"

    /** null = this system's ICU has no such transform; always return the text unchanged then. */
    private val transliterator: Transliterator? by lazy {
        try {
            Transliterator.getInstance(TRANSFORM_ID)
        } catch (e: RuntimeException) {
            null
        }
    }

    fun apply(value: String): String {
        val transform = transliterator ?: return value
        // ICU's Transliterator isn't guaranteed thread-safe; searches run concurrently.
        return synchronized(transform) { transform.transliterate(value) } ?: value
    }
}
