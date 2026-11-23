package com.gpo.yoin.ui.notes.editor

/**
 * A field that writes its words up to a host (a draft, a sheet's state) and takes the host's value back
 * down: the host's value arrives a frame later, when the field may already hold more. [sent] records each
 * write; [isEcho] says whether a value coming down is one of them (it and the writes before it are then
 * consumed, first in, first out) or a change from elsewhere (a cleared draft, a reverted text), which the
 * field must take — even when it equals something the field wrote long ago.
 */
class EchoGuard {
    private val pending = ArrayDeque<String>()

    fun sent(text: String) {
        pending.addLast(text)
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    fun isEcho(text: String): Boolean {
        val at = pending.indexOf(text)
        if (at < 0) {
            pending.clear()
            return false
        }
        repeat(at + 1) { pending.removeFirst() }
        return true
    }

    private companion object {
        const val MAX_PENDING = 32
    }
}
