package com.gpo.yoin.edition

import android.content.Context
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.AppContainer
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.sync.SyncBackendOverride
import com.gpo.yoin.lyra.data.LyraAdminTokenStore
import com.gpo.yoin.lyra.data.LyraCredentialsProvider
import com.gpo.yoin.lyra.data.LyraSessionManager
import com.gpo.yoin.lyra.data.LyraSyncAuthorizer
import com.gpo.yoin.lyra.data.LyraSyncTransport
import com.gpo.yoin.lyra.ui.LyraHubActivity
import com.gpo.yoin.lyra.ui.LyraShellOverlay
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsItem

/**
 * `lyra` flavor edition hooks: wires Lyra native sync (`/api/v1/sync`), live capabilities &
 * SSE job updates, the shell Hub/Live Activity pill, and the Settings Lyra Hub entry.
 */
object EditionFeatures {
    const val isLyraOnly: Boolean = true

    @Volatile private var sessionInstance: LyraSessionManager? = null
    @Volatile private var credentialsInstance: LyraCredentialsProvider? = null

    private fun credentialsProvider(context: Context, profileManager: ProfileManager): LyraCredentialsProvider =
        credentialsInstance ?: synchronized(this) {
            credentialsInstance ?: LyraCredentialsProvider(
                profileManager = profileManager,
                adminTokenStore = LyraAdminTokenStore(context.applicationContext),
            ).also { credentialsInstance = it }
        }

    internal fun sessionManager(context: Context, container: AppContainer): LyraSessionManager =
        sessionInstance ?: synchronized(this) {
            sessionInstance ?: LyraSessionManager(
                context = context.applicationContext,
                container = container,
                credentialsProvider = credentialsProvider(context, container.profileManager),
            ).also { sessionInstance = it }
        }

    fun createSyncBackendOverride(
        context: Context,
        profileManager: ProfileManager,
    ): SyncBackendOverride {
        val creds = credentialsProvider(context, profileManager)
        val appContext = context.applicationContext
        return SyncBackendOverride(
            transport = LyraSyncTransport(authProvider = creds::currentAuth),
            authorizer = LyraSyncAuthorizer(
                authProvider = creds::currentAuth,
                missingServerMessage = appContext.getString(R.string.lyra_server_not_connected),
            ),
            skipPlayServicesCheck = true,
        )
    }

    fun onAppForeground(context: Context, container: AppContainer) {
        sessionManager(context, container).onAppForeground()
    }

    fun onAppBackground(context: Context, container: AppContainer) {
        sessionManager(context, container).onAppBackground()
    }

    @Composable
    fun ShellOverlay(
        visible: Boolean,
        modifier: Modifier = Modifier,
    ) {
        LyraShellOverlay(visible = visible, modifier = modifier)
    }

    @Composable
    fun SettingsLyraSection(listDetail: Boolean) {
        val context = LocalContext.current
        val app = context.applicationContext as? YoinApplication ?: return
        val session = sessionManager(context, app.container)
        val state by session.state.collectAsState()
        val summary = when {
            state.activeJobs.isNotEmpty() ->
                stringResource(R.string.lyra_hub_summary_jobs_active, state.activeJobs.size)
            state.isLyraServer ->
                stringResource(R.string.lyra_hub_summary_connected, state.lyraVersion.orEmpty())
            else ->
                stringResource(R.string.lyra_hub_summary_disconnected)
        }
        SettingsGroup(title = stringResource(R.string.lyra_settings_section)) {
            item(key = "lyra-hub") {
                SettingsItem(
                    icon = YoinSymbols.Cloud,
                    title = stringResource(R.string.lyra_hub_title),
                    summary = summary,
                    onClick = { context.startActivity(LyraHubActivity.intent(context)) },
                    trailing = if (listDetail) {
                        {
                            Icon(
                                imageVector = YoinSymbols.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
