package com.gpo.yoin.ui.landing.guide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gpo.yoin.R
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.settings.applemusic.AppleMusicValidationViewModel

/**
 * Runs MusicKit's sign-in for [vm] with the Apple Music floating guide beside it (owner 2026-10-10: one sign-in
 * often doesn't take). The window floats into the corner first and the sign-in opens beneath it; a try that
 * didn't take turns the window to "once more", a connection — or leaving this screen — closes it. Used by the
 * landing's Apple Music step and Settings › Apple Music alike.
 */
@Composable
internal fun AppleMusicSignInWithGuide(vm: AppleMusicValidationViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    // An attempt is out: its outcome steers the window.
    var awaiting by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.authorizationResult(it.data)
    }
    LaunchedEffect(vm) {
        vm.authorization.collect { signIn ->
            ConnectGuide.openAndAwaitReady(context, GuideKind.AppleMusic)
            awaiting = true
            launcher.launch(signIn)
        }
    }
    LaunchedEffect(awaiting, state.busy, state.connected, state.retryable, state.status) {
        if (!awaiting || state.busy) return@LaunchedEffect
        when {
            state.connected -> {
                awaiting = false
                ConnectGuide.finishIfOpen()
            }
            state.retryable -> {
                awaiting = false
                // No subscription won't change with another try; everything else might.
                if (state.status == UiText.Res(R.string.settings_apple_status_subscription)) {
                    ConnectGuide.finishIfOpen()
                } else {
                    ConnectGuide.showStep(AppleMusicRetryStep)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (ConnectGuide.open.value == GuideKind.AppleMusic) ConnectGuide.finishIfOpen()
        }
    }
}
