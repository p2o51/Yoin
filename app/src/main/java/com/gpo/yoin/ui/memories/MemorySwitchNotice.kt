package com.gpo.yoin.ui.memories

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.ui.home.AccountSwitchNoticeSheet
import com.gpo.yoin.ui.home.OtherProfilesNoticeStore

/*
 * Going to another account's album, or playing one of its tracks, switches accounts, which stops what is
 * playing. The first time, Yoin says so and asks (owner 2026-11-08). It is the same one-off notice as Home's
 * other-accounts shelf, with the same flag: once seen in either place, neither asks again.
 */

/** A switch waiting on the notice: whose account, what it opens or plays, and the switch itself. */
internal class PendingMemorySwitch(
    val owner: MemoryOtherProfile,
    /** The album's or track's name, for the notice's body. */
    val subject: String,
    /** Go to album (true) or play a track (false): the notice's body and button follow. */
    val opensAlbum: Boolean,
    val proceed: () -> Unit,
)

/** Holds a switch back until the one-off notice is confirmed; straight through once it has been seen. */
internal class MemorySwitchGate(private val seen: () -> Boolean, private val markSeen: () -> Unit) {
    var pending: PendingMemorySwitch? by mutableStateOf(null)
        private set

    fun request(owner: MemoryOtherProfile, subject: String, opensAlbum: Boolean, proceed: () -> Unit) {
        if (seen()) {
            proceed()
        } else {
            pending = PendingMemorySwitch(owner, subject, opensAlbum, proceed)
        }
    }

    fun confirm() {
        val switch = pending ?: return
        pending = null
        markSeen()
        switch.proceed()
    }

    fun dismiss() {
        pending = null
    }
}

@Composable
internal fun rememberMemorySwitchGate(): MemorySwitchGate {
    val context = LocalContext.current
    return remember {
        val store = OtherProfilesNoticeStore(context)
        MemorySwitchGate(seen = { store.seen }, markSeen = { store.seen = true })
    }
}

/** The notice for [gate]'s pending switch; nothing while none is waiting. */
@Composable
internal fun MemorySwitchNotice(gate: MemorySwitchGate) {
    val switch = gate.pending ?: return
    AccountSwitchNoticeSheet(
        title = stringResource(R.string.home_other_profiles_switch_title, switch.owner.name),
        body = stringResource(
            if (switch.opensAlbum) R.string.mem_switch_body_album else R.string.home_other_profiles_switch_body,
            switch.subject,
        ),
        confirm = stringResource(
            if (switch.opensAlbum) R.string.mem_switch_confirm_album else R.string.home_other_profiles_switch_confirm,
        ),
        onConfirm = gate::confirm,
        onDismiss = gate::dismiss,
    )
}
