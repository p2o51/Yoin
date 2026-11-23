package com.gpo.yoin.ui.settings

import com.gpo.yoin.AppContainer

/**
 * Removes an account's card from this device (Settings › account › Remove, and 转移 Memories' "remove the
 * original card"): the profile and its stored credentials go, its avatar too; when it was the account in use,
 * the app moves to the next one as on a switch. Synced data in the cloud is untouched.
 */
internal suspend fun removeProfileCard(container: AppContainer, profileId: String) {
    val profileManager = container.profileManager
    val wasActive = profileManager.activeProfileId.value == profileId
    profileManager.delete(profileId)
    container.profileAvatarStore.remove(profileId)
    if (wasActive) {
        // Delete removed the active profile: AppContainer refreshes downstream VMs, as on a switch.
        container.notifyMusicConfigurationChanged()
        container.playbackManager.disconnect()
    }
}
