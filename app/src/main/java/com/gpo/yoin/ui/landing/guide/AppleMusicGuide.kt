package com.gpo.yoin.ui.landing.guide

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
    // An attempt is out: its outcome steers the window. Saveable: the result still arrives (the registry and the
    // ViewModel survive) when this activity is recreated while Apple's sign-in is open.
    var awaiting by rememberSaveable { mutableStateOf(false) }
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
    LaunchedEffect(awaiting, state.busy, state.retryable, state.status) {
        // The attempt's own outcome, by its status: `connected` alone may be left over from the account being
        // reconnected (Settings), and `connect()` sets "authorize" before handing over the sign-in.
        if (!awaiting || state.busy || state.status == Authorizing) return@LaunchedEffect
        when {
            state.status == Connected -> {
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
            // Leaving the screen ends the guide; a recreation (rotation, resize) is not leaving.
            val recreating = context.findActivity()?.isChangingConfigurations == true
            if (!recreating && ConnectGuide.open.value == GuideKind.AppleMusic) ConnectGuide.finishIfOpen()
        }
    }
}

private val Authorizing = UiText.Res(R.string.settings_apple_status_authorize)
private val Connected = UiText.Res(R.string.settings_apple_status_connected_profiles)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
