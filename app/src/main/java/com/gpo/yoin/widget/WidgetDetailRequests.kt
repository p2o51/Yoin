package com.gpo.yoin.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed

/** Wait for a composed, resumed shell; a recreated shell must not replay a consumed widget tap. */
@Composable
internal fun TakeWidgetDetailRequest(
    request: WidgetDetailRequest?,
    enabled: Boolean,
    canOpen: () -> Boolean = { enabled },
    onConsumed: (WidgetDetailRequest) -> Boolean,
    onOpen: (WidgetDetailRequest) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val consume by rememberUpdatedState(onConsumed)
    val open by rememberUpdatedState(onOpen)
    val ready by rememberUpdatedState(canOpen)
    LaunchedEffect(request, enabled, lifecycle) {
        if (request == null || !enabled) return@LaunchedEffect
        lifecycle.withResumed {
            // A newer Intent can arrive before the old effect is cancelled.
            // Only the request still owned by the Activity may navigate.
            if (ready() && consume(request)) open(request)
        }
    }
}
