package com.gpo.yoin.ui.landing.guide

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.gpo.yoin.MainActivity
import com.gpo.yoin.R
import com.gpo.yoin.ui.experience.YoinHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The floating guide for Spotify for Developers ("悬浮窗导航", owner 2026-10-09: like a maps app's floating
 * window telling you what to tap). Picture-in-picture, so it needs no "display over other apps" permission: it
 * opens full screen for a moment, shrinks into the corner, and only then opens the dashboard in the browser
 * beneath it. Its actions are the system's PiP actions (tap the window to show them): previous, copy the
 * step's redirect URI, next — and on the last step, back to Yoin, which reads the Client ID the user copied.
 *
 * Expanding the window (the system's full-screen button) or "back to Yoin" returns to the landing; closing it
 * just ends the guide. A separate task (taskAffinity, excludeFromRecents) so the pinned window never drags the
 * main task along.
 */
class SpotifyGuideActivity : ComponentActivity() {

    private var step by mutableIntStateOf(0)
    private var flash by mutableStateOf<String?>(null)
    private var inPictureInPicture by mutableStateOf(false)
    private var cardBounds: Rect? = null
    private var browserOpened = false
    private var enteredOnce = false
    private var flashJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(EXTRA_COMMAND)) {
                COMMAND_PREV -> moveTo(step - 1)
                COMMAND_NEXT -> moveTo(step + 1)
                COMMAND_COPY -> copyStepValue()
                COMMAND_RETURN -> returnToYoin()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        step = savedInstanceState?.getInt(STATE_STEP) ?: 0
        browserOpened = savedInstanceState?.getBoolean(STATE_BROWSER) ?: false
        enteredOnce = browserOpened
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_GUIDE), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch { finishRequests.collect { finishAndRemoveTask() } }
        inPictureInPicture = isInPictureInPictureMode
        setContent {
            SpotifyGuideScreen(
                inPictureInPicture = inPictureInPicture,
                stepIndex = step,
                flash = flash,
                onCardBounds = { bounds ->
                    cardBounds = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!enteredOnce) {
            enteredOnce = true
            // Let the full-screen card show for a beat, then shrink into the corner.
            lifecycleScope.launch {
                delay(EnterDelayMs)
                runCatching { enterPictureInPictureMode(pipParams()) }.onFailure { openBrowser() }
            }
        } else if (browserOpened && !isInPictureInPictureMode) {
            // Expanded back to full screen: the user is done with the browser.
            returnToYoin()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode && !browserOpened) openBrowser()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
        outState.putBoolean(STATE_BROWSER, browserOpened)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    private fun openBrowser() {
        browserOpened = true
        val view = Intent(Intent.ACTION_VIEW, SpotifyDashboardUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(view) }
    }

    private fun moveTo(index: Int) {
        val next = index.coerceIn(0, SpotifyGuideSteps.lastIndex)
        if (next == step) return
        step = next
        setPictureInPictureParams(pipParams())
    }

    private fun copyStepValue() {
        val value = SpotifyGuideSteps[step].copies ?: return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Yoin", value))
        YoinHaptics(window.decorView).performConfirm()
        flashJob?.cancel()
        flash = getString(R.string.guide_copied)
        flashJob = lifecycleScope.launch {
            delay(FlashMs)
            flash = null
        }
    }

    private fun returnToYoin() {
        val main = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_FROM_SPOTIFY_GUIDE, true)
        runCatching { startActivity(main) }
        finishAndRemoveTask()
    }

    private fun pipParams(): PictureInPictureParams {
        val current = SpotifyGuideSteps[step]
        val max = maxNumPictureInPictureActions.coerceAtLeast(1)
        val actions = buildList {
            if (step > 0) add(action(COMMAND_PREV, R.drawable.ic_guide_prev, R.string.guide_action_prev))
            if (current.copies != null) {
                val label = if (current.copies == SpotifyGuideSteps[2].copies) R.string.guide_action_copy_first else R.string.guide_action_copy_second
                add(action(COMMAND_COPY, R.drawable.ic_guide_copy, label))
            }
            if (step < SpotifyGuideSteps.lastIndex) {
                add(action(COMMAND_NEXT, R.drawable.ic_guide_next, R.string.guide_action_next))
            } else {
                add(action(COMMAND_RETURN, R.drawable.ic_guide_return, R.string.guide_action_back))
            }
        }.takeLast(max)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(4, 3))
            .setActions(actions)
        // The shrink starts from the centred full-screen card (same 4:3 shape), not from the whole screen.
        cardBounds?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setSeamlessResizeEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setTitle(getString(R.string.landing_spotify_open))
        }
        return builder.build()
    }

    private fun action(command: String, icon: Int, label: Int): RemoteAction {
        val intent = Intent(ACTION_GUIDE).setPackage(packageName).putExtra(EXTRA_COMMAND, command)
        val pending = PendingIntent.getBroadcast(
            this,
            command.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = getString(label)
        return RemoteAction(Icon.createWithResource(this, icon), text, text, pending)
    }

    companion object {
        private const val ACTION_GUIDE = "com.gpo.yoin.action.SPOTIFY_GUIDE"
        private const val EXTRA_COMMAND = "command"
        private const val COMMAND_PREV = "prev"
        private const val COMMAND_NEXT = "next"
        private const val COMMAND_COPY = "copy"
        private const val COMMAND_RETURN = "return"
        private const val STATE_STEP = "step"
        private const val STATE_BROWSER = "browser"
        private const val EnterDelayMs = 450L
        private const val FlashMs = 1_800L

        private val finishRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun intent(context: Context): Intent = Intent(context, SpotifyGuideActivity::class.java)

        /** The landing came back to the front another way (recents, launcher): the floating guide goes. */
        fun finishIfOpen() {
            finishRequests.tryEmit(Unit)
        }
    }
}
