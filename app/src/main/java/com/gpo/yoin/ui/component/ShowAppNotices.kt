package com.gpo.yoin.ui.component

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.ui.common.AppNotices
import com.gpo.yoin.ui.common.asString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * This window shows the app's [AppNotices] on its own snackbar [hostState],
 * while it is resumed — the window in front — and [takesNotices]. A page on
 * its way out turns [takesNotices] off, so a notice that comes as it closes is
 * left to the window it reveals: it is read at the moment a notice arrives,
 * and one that arrives as it turns off is put back for that window. Draws
 * nothing itself.
 */
@Composable
fun ShowAppNotices(
    notices: AppNotices,
    hostState: SnackbarHostState,
    takesNotices: StateFlow<Boolean> = AlwaysTakesNotices,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    LaunchedEffect(notices, hostState, lifecycleOwner, takesNotices) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notices.showWhile(takesNotices) { notice ->
                val action = notice.action
                val result = hostState.showSnackbar(
                    message = notice.message.asString(context),
                    actionLabel = action?.label?.asString(context),
                    // Long enough to reach the button when there is one.
                    duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) action?.onClick?.invoke()
            }
        }
    }
}

private val AlwaysTakesNotices: StateFlow<Boolean> = MutableStateFlow(true)
