package com.gpo.yoin.ui.detail

/**
 * Synchronous one-shot gate for detail→detail Activity launches.
 *
 * Lifecycle state alone has a small gap between startActivity() and ON_PAUSE;
 * a second tap in that gap can merge two translucent OPEN/CLOSE transitions
 * and strand the newest surface without a committed buffer. The owner resets
 * this latch only when it is actually resumed again.
 */
internal class DetailActivityLaunchGate {
    private var pending = false

    fun tryAcquire(ownerResumed: Boolean): Boolean {
        if (!ownerResumed || pending) return false
        pending = true
        return true
    }

    fun release() {
        pending = false
    }
}
