package com.gpo.yoin.player.continueon

import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.gpo.yoin.MainActivity
import com.gpo.yoin.YoinApplication
import kotlinx.coroutines.launch

/**
 * The window side of [ContinueOn]: the shell and the detail pages with a
 * player (album, artist, playlist) offer the hand-off while something plays,
 * each with its own `onHandoffActivityDataRequested` calling [handoffData].
 * Every hand-off opens the shell on the other device ([MainActivity] is the
 * one exported window), which passes the intent to [receive].
 */
internal object ContinueOnWindows {
    /** Android 17, where Continue On's API is. */
    const val API = 37

    /** Offers the hand-off from [activity] whenever [ContinueOn.offered] says so. */
    fun install(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < API) return
        val continueOn = (activity.application as YoinApplication).container.continueOn
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.CREATED) {
                continueOn.offered.collect { offered -> setOffered(activity, offered) }
            }
        }
    }

    @RequiresApi(API)
    private fun setOffered(activity: ComponentActivity, offered: Boolean) {
        if (activity.isHandoffEnabled != offered) activity.setHandoffEnabled(offered, null)
    }

    /**
     * What [activity] gives the system for a hand-off: the shell, with the cursor in its extras. Never
     * null (the system requires data once the hand-off is on); with nothing playing any more, the other
     * device just opens Yoin.
     */
    @RequiresApi(API)
    fun handoffData(activity: ComponentActivity, request: HandoffActivityDataRequestInfo): HandoffActivityData {
        val continueOn = (activity.application as YoinApplication).container.continueOn
        val builder = HandoffActivityData.Builder(ComponentName(activity, MainActivity::class.java))
        continueOn.handoffEntries(request.isActiveRequest)?.let { builder.setExtras(it.toPersistableBundle()) }
        return builder.build()
    }

    /** A hand-off [intent] started the shell: show its card. True when it was one (its extras are then cleared). */
    fun receive(activity: ComponentActivity, intent: Intent?): Boolean {
        val extras = intent?.extras ?: return false
        if (!extras.containsKey(ContinueOnCursor.KEY_VERSION)) return false
        val continueOn = (activity.application as YoinApplication).container.continueOn
        @Suppress("DEPRECATION")
        val received = continueOn.receive { key -> extras.get(key) }
        // Read once: a recreated window must not show the card again.
        ContinueOnCursor.KEY_PREFIXED.forEach(intent::removeExtra)
        return received
    }

    private fun Map<String, Any>.toPersistableBundle(): PersistableBundle = PersistableBundle().also { bundle ->
        forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is Array<*> -> bundle.putStringArray(key, value.filterIsInstance<String>().toTypedArray())
            }
        }
    }
}
