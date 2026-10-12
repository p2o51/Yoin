package com.gpo.yoin.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * The row Settings' list marks as the one open in the right pane
 * (list-detail, design.md: the open row detaches in the pane's colour), for
 * the pages one result launcher opens there. A page opened while another is
 * in the pane replaces it (the split's clearTop), and the replaced page
 * answers its launcher only after the new one was launched: that answer is
 * not the open page closing. So the mark goes when the last page this
 * launcher opened has answered, not on the first answer.
 */
@Stable
internal class OpenPageMark<T : Any>(open: T? = null, pending: Int = 0) {
    /** What the open page is for (null: none, or a page that marks no row). */
    var open: T? by mutableStateOf(open)
        private set

    // This launcher's pages launched and not answered yet. Nothing draws it.
    private var pending = pending

    /** A page for [item] is launched into the pane. */
    fun opened(item: T?) {
        open = item
        pending++
    }

    /** Another launcher's page takes the pane: no row of this one is open, though its replaced page still answers. */
    fun clear() {
        open = null
    }

    /** One of this launcher's pages answered: closed, or replaced by a newer one. */
    fun answered() {
        pending = (pending - 1).coerceAtLeast(0)
        if (pending == 0) open = null
    }

    companion object {
        /** Kept across recreation with the launches it waits on (the result registry keeps those too). */
        @Suppress("UNCHECKED_CAST")
        fun <T : Any> saver(): Saver<OpenPageMark<T>, Any> = listSaver(
            save = { mark -> listOf(mark.open, mark.pending) },
            restore = { saved -> OpenPageMark(open = saved[0] as T?, pending = saved[1] as Int) }
        )
    }
}
