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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The floating guides ("悬浮窗导航", owner 2026-10-09: like a maps app's floating window telling you what to
 * tap). Picture-in-picture, so they need no "display over other apps" permission: the card shows full screen for
 * a moment, shrinks into the corner, and only then does the other side open beneath it.
 *
 * - [GuideKind.Spotify]: opens Spotify for Developers in the browser. The window's own actions (tap it to show
 *   them) are previous, copy the step's value (redirect URIs, this install's package name and SHA-1), next —
 *   and on the last step, back to Yoin, which reads the Client ID the user copied.
 * - [GuideKind.AppleMusic] (owner 2026-10-10): Yoin opens Apple's sign-in itself once the window is in the
 *   corner ([ConnectGuide.openAndAwaitReady]); the window follows the attempts — a try that didn't take turns
 *   it to "once more", a connection closes it.
 *
 * Expanding the window (the system's full-screen button) or "back to Yoin" returns to Yoin; closing it just ends
 * the guide. A separate task (taskAffinity, excludeFromRecents) so the pinned window never drags Yoin along.
 */
class ConnectGuideActivity : ComponentActivity() {

    private lateinit var kind: GuideKind
    private var step by mutableIntStateOf(0)
    private var flash by mutableStateOf<String?>(null)
    private var inPictureInPicture by mutableStateOf(false)
    private var cardBounds: Rect? = null

    /** The other side has been handed the stage: the browser opened, or Yoin told to start the sign-in. */
    private var handedOff = false
    private var enteredOnce = false
    private var flashJob: Job? = null

    private val steps get() = guideSteps(kind)

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
        kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { GuideKind.valueOf(it) }.getOrNull() } ?: GuideKind.Spotify
        step = savedInstanceState?.getInt(STATE_STEP) ?: 0
        handedOff = savedInstanceState?.getBoolean(STATE_HANDED_OFF) ?: false
        enteredOnce = handedOff
        ConnectGuide.opened(kind)
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_GUIDE), ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycleScope.launch {
            ConnectGuide.commands.collect { command ->
                when (command) {
                    ConnectGuide.Command.Finish -> finishAndRemoveTask()
                    is ConnectGuide.Command.ShowStep -> moveTo(command.index)
                }
            }
        }
        inPictureInPicture = isInPictureInPictureMode
        setContent {
            ConnectGuideScreen(
                kind = kind,
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
                // No picture-in-picture (turned off for Yoin, or unsupported) returns false or throws: hand off
                // anyway so nothing waits on a window that will never float.
                val entered = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
                if (!entered) {
                    handOff()
                    // Apple's sign-in happens in Yoin; with no floating window there is nothing left to show.
                    if (kind == GuideKind.AppleMusic) finishAndRemoveTask()
                }
            }
        } else if (handedOff && !isInPictureInPictureMode) {
            // Expanded back to full screen: the user is done over there.
            returnToYoin()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        if (isInPictureInPictureMode && !handedOff) handOff()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, step)
        outState.putBoolean(STATE_HANDED_OFF, handedOff)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (isFinishing) ConnectGuide.closed(kind)
        super.onDestroy()
    }

    private fun handOff() {
        handedOff = true
        when (kind) {
            GuideKind.Spotify -> {
                val view = Intent(Intent.ACTION_VIEW, SpotifyDashboardUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(view) }
            }
            // Yoin is waiting in openAndAwaitReady and now opens the sign-in beneath the window.
            GuideKind.AppleMusic -> ConnectGuide.ready.tryEmit(kind)
        }
    }

    private fun moveTo(index: Int) {
        val next = index.coerceIn(0, steps.lastIndex)
        if (next == step) return
        step = next
        if (isInPictureInPictureMode) setPictureInPictureParams(pipParams())
    }

    private fun copyStepValue() {
        val value = steps[step].copies?.value(this) ?: return
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
            .putExtra(MainActivity.EXTRA_FROM_SPOTIFY_GUIDE, kind == GuideKind.Spotify)
        runCatching { startActivity(main) }
        finishAndRemoveTask()
    }

    private fun pipParams(): PictureInPictureParams {
        val current = steps[step]
        val max = maxNumPictureInPictureActions.coerceAtLeast(1)
        // Apple's sign-in is Yoin's to drive: the window only shows where things stand, no actions.
        val actions = if (kind == GuideKind.AppleMusic) {
            emptyList()
        } else {
            buildList {
                if (step > 0) add(action(COMMAND_PREV, R.drawable.ic_guide_prev, R.string.guide_action_prev))
                current.copies?.let { add(action(COMMAND_COPY, R.drawable.ic_guide_copy, it.actionLabel)) }
                if (step < steps.lastIndex) {
                    add(action(COMMAND_NEXT, R.drawable.ic_guide_next, R.string.guide_action_next))
                } else {
                    add(action(COMMAND_RETURN, R.drawable.ic_guide_return, R.string.guide_action_back))
                }
            }.takeLast(max)
        }
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(4, 3))
            .setActions(actions)
        // The shrink starts from the centred full-screen card (same 4:3 shape), not from the whole screen.
        cardBounds?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setSeamlessResizeEnabled(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setTitle(
                getString(
                    when (kind) {
                        GuideKind.Spotify -> R.string.landing_spotify_open
                        GuideKind.AppleMusic -> R.string.guide_apple_title
                    },
                ),
            )
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

    private companion object {
        const val ACTION_GUIDE = "com.gpo.yoin.action.CONNECT_GUIDE"
        const val EXTRA_COMMAND = "command"
        const val COMMAND_PREV = "prev"
        const val COMMAND_NEXT = "next"
        const val COMMAND_COPY = "copy"
        const val COMMAND_RETURN = "return"
        const val STATE_STEP = "step"
        const val STATE_HANDED_OFF = "handedOff"
        const val EnterDelayMs = 450L
        const val FlashMs = 1_800L
    }
}

private const val EXTRA_KIND = "kind"

/**
 * Yoin's side of the floating guides: open one, wait until it's in the corner, steer it, close it. One guide at
 * a time; state lives for the process (the window is its own task).
 */
internal object ConnectGuide {
    sealed interface Command {
        data object Finish : Command
        data class ShowStep(val index: Int) : Command
    }

    private val openKind = MutableStateFlow<GuideKind?>(null)
    val open: StateFlow<GuideKind?> = openKind.asStateFlow()
    internal val ready = MutableSharedFlow<GuideKind>(extraBufferCapacity = 1)
    internal val commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)

    fun intent(context: Context, kind: GuideKind): Intent =
        Intent(context, ConnectGuideActivity::class.java).putExtra(EXTRA_KIND, kind.name)

    /**
     * Shows the [kind] guide and suspends until it has floated into the corner (or given up), so whatever Yoin
     * opens next lands beneath it. Already showing: back to its first step at once.
     */
    suspend fun openAndAwaitReady(context: Context, kind: GuideKind): Boolean {
        if (openKind.value == kind) {
            showStep(0)
            return true
        }
        if (openKind.value != null) finishIfOpen()
        context.startActivity(intent(context, kind))
        return withTimeoutOrNull(READY_TIMEOUT_MS) { ready.first { it == kind } } != null
    }

    fun showStep(index: Int) {
        commands.tryEmit(Command.ShowStep(index))
    }

    /** Yoin came back to the front another way, or the job is done: the floating guide goes. */
    fun finishIfOpen() {
        commands.tryEmit(Command.Finish)
    }

    internal fun opened(kind: GuideKind) {
        openKind.value = kind
    }

    internal fun closed(kind: GuideKind) {
        if (openKind.value == kind) openKind.value = null
    }

    private const val READY_TIMEOUT_MS = 3_000L
}
