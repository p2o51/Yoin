package com.gpo.yoin.shared.ondevice

/**
 * Whether [codePoint] is Unicode category So (other symbol): ★, ♪, ☕, emoji. Kotlin has no code point category
 * in common code; Android asks the JVM (`Character.getType`, exactly what the app did before the move), iOS
 * Kotlin/Native's table plus the supplementary So blocks.
 */
internal expect fun isOtherSymbolCodePoint(codePoint: Int): Boolean
