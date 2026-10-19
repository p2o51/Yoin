package com.gpo.yoin.ui.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Opens this screen's Now Playing when [ExperienceSessionStore.requestNowPlaying]
 * asks while the screen is resumed (it is the one in front) — the system
 * media controls' tap. Only one screen takes each request.
 */
@Composable
fun TakeNowPlayingRequests(store: ExperienceSessionStore, onOpen: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(store, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            store.nowPlayingRequest.collect { token ->
                if (store.consumeNowPlayingRequest(token)) open()
            }
        }
    }
}
