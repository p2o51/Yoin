package com.gpo.yoin.ui.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

internal fun markdownBoldAnnotatedString(text: String): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val marker = boldMarkerAt(text, index)
            if (marker == null) {
                append(text[index])
                index += 1
                continue
            }

            val close = text.indexOf(marker, startIndex = index + marker.length)
            if (close == -1) {
                append(marker)
                index += marker.length
                continue
            }

            appendBoldSpan(text.substring(index + marker.length, close))
            index = close + marker.length
        }
    }

private fun boldMarkerAt(text: String, index: Int): String? {
    val next = index + 1
    if (next >= text.length || text[index] != text[next]) return null
    return when (text[index]) {
        '*', '_' -> text.substring(index, index + 2)
        else -> null
    }
}

private fun AnnotatedString.Builder.appendBoldSpan(text: String) {
    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
    append(text)
    pop()
}
