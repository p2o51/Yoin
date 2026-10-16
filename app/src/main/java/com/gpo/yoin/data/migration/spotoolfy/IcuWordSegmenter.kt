package com.gpo.yoin.data.migration.spotoolfy

import android.icu.text.BreakIterator
import android.icu.util.ULocale

/**
 * Word segmentation on the platform ICU (API 24+): dictionary-based, so Chinese and Japanese split into words and
 * mixed Chinese, Japanese and English notes work in one pass. Spaces and punctuation are dropped.
 */
class IcuWordSegmenter : WordSegmenter {
    override fun words(text: String): List<String> {
        val iterator = BreakIterator.getWordInstance(ULocale.ROOT)
        iterator.setText(text)
        val words = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (iterator.ruleStatus >= BreakIterator.WORD_NONE_LIMIT) words += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return words
    }
}
