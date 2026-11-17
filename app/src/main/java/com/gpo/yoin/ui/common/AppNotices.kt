package com.gpo.yoin.ui.common

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A one-line notice that outlives the page it came from: the result of a
 * write the page no longer waits for (a playlist deleted, or not). Shown by
 * whichever Yoin window is in front, on its own snackbar.
 */
data class AppNotice(val message: UiText, val action: Action? = null) {
    /** The button beside the text; a tap runs [onClick] once and the notice goes. */
    class Action(val label: UiText, val onClick: () -> Unit)
}

/**
 * The process-wide queue of [AppNotice]s (AppContainer.appNotices). Each
 * notice reaches one window, once: the window that takes it off the queue
 * shows it. A notice posted while no window takes notices (between two, or
 * with Yoin in the background) waits for the next one.
 *
 * A window takes notices only while it is resumed (ShowAppNotices), so the
 * shell beneath a detail page leaves them to the page, and a page that is
 * closing puts back what it takes, for the window it reveals.
 */
class AppNotices {
    // A notice handed to a window whose turn ended before it got to it (the
    // page closing, the window pausing) goes back on the queue for the next one.
    private val queue: Channel<AppNotice> = Channel(Channel.UNLIMITED, onUndeliveredElement = ::post)

    fun post(notice: AppNotice) {
        queue.trySend(notice)
    }

    /** The notices, each delivered to one collector. */
    val notices: Flow<AppNotice> = queue.receiveAsFlow()

    /**
     * One window's turn at the notices: each it takes goes to [show], one
     * after another, while [takes] is on. [takes] is read as a notice arrives:
     * one that arrives as it turns off is put back for the next window, so a
     * page that is closing never shows it.
     */
    suspend fun showWhile(takes: StateFlow<Boolean>, show: suspend (AppNotice) -> Unit) {
        takes.collectLatest { taking ->
            while (taking) {
                val notice = queue.receive()
                if (!takes.value) {
                    post(notice)
                    break
                }
                show(notice)
            }
        }
    }
}
