package com.gpo.yoin.edition

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gpo.yoin.AppContainer
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.SyncBackendOverride

/**
 * Standard (public Yoin) edition hooks: every Lyra-only extension point compiles away to a no-op
 * so the Play release stays completely clean and decoupled from Lyra-only client surfaces.
 */
object EditionFeatures {
    const val isLyraOnly: Boolean = false

    fun createSyncBackendOverride(
        context: Context,
        profileManager: ProfileManager,
    ): SyncBackendOverride? = null

    fun onAppForeground(context: Context, container: AppContainer) = Unit

    fun onAppBackground(context: Context, container: AppContainer) = Unit

    fun onProfileDeleted(context: Context, container: AppContainer, profileId: String) = Unit

    @Composable
    fun ShellOverlay(
        visible: Boolean,
        modifier: Modifier = Modifier,
    ) = Unit

    @Composable
    fun SettingsLyraSection(listDetail: Boolean) = Unit
}
