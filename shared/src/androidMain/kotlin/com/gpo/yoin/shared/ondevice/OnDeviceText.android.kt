package com.gpo.yoin.shared.ondevice

/** The JVM's own Unicode table: exactly what Android's HomeGreetingPrompt asked before the move. */
internal actual fun isOtherSymbolCodePoint(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.OTHER_SYMBOL.toInt()
