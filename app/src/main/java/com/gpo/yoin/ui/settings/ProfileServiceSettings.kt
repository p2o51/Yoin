@file:Suppress("ktlint:standard:function-naming")

package com.gpo.yoin.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.source.Capability
import com.gpo.yoin.data.source.ServiceFeatureCatalog
import com.gpo.yoin.player.ReplayGainPreference
import com.gpo.yoin.player.StreamQualityPreference
import com.gpo.yoin.symbols.YoinSymbols

/**
 * The service's own playback and storage settings — streaming quality, volume
 * leveling, downloads — at the bottom of an account's page (owner: they belong
 * to the profile, not to global Settings). Only what the service supports; the
 * rows open the same pages Settings used to.
 */
@Composable
internal fun ProfileServiceSettings(providerKey: String) {
    val capabilities = ServiceFeatureCatalog.forProvider(providerKey).capabilities
    val streamQuality = Capability.STREAM_QUALITY in capabilities
    val volumeLeveling = Capability.REPLAY_GAIN in capabilities
    val downloads = Capability.OFFLINE_DOWNLOAD in capabilities
    if (!streamQuality && !volumeLeveling && !downloads) return
    val context = LocalContext.current
    val open: (SettingsFeature) -> Unit = { feature ->
        context.startActivity(SettingsFeatureActivity.intent(context, feature))
    }
    if (streamQuality || volumeLeveling) {
        SettingsGroup(title = stringResource(R.string.settings_section_playback)) {
            if (streamQuality) {
                item(key = SettingsFeature.StreamQuality) {
                    val settings by StreamQualityPreference.observe(context).collectAsState()
                    SettingsItem(
                        icon = YoinSymbols.Equalizer,
                        title = stringResource(R.string.settings_stream_quality_title),
                        summary = stringResource(
                            R.string.settings_stream_quality_summary,
                            streamQualityLabel(settings.wifi),
                            streamQualityLabel(settings.mobile),
                        ),
                        onClick = { open(SettingsFeature.StreamQuality) },
                    )
                }
            }
            if (volumeLeveling) {
                item(key = SettingsFeature.VolumeLeveling) {
                    val settings by ReplayGainPreference.observe(context).collectAsState()
                    SettingsItem(
                        icon = YoinSymbols.VolumeUp,
                        title = stringResource(R.string.settings_volume_leveling_title),
                        summary = replayGainModeLabel(settings.mode),
                        onClick = { open(SettingsFeature.VolumeLeveling) },
                    )
                }
            }
        }
    }
    if (downloads) {
        SettingsGroup(title = stringResource(R.string.settings_section_storage)) {
            item(key = SettingsFeature.Downloads) {
                val offline = (context.applicationContext as YoinApplication).container.offlineDownloads
                SettingsItem(
                    icon = YoinSymbols.Download,
                    title = stringResource(R.string.settings_downloads_title),
                    summary = downloadsSummary(offline),
                    onClick = { open(SettingsFeature.Downloads) },
                )
            }
        }
    }
}
