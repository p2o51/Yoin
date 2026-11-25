package com.gpo.yoin.ui.settings

import com.gpo.yoin.ui.component.handPointer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.highlight.NanoStatus
import com.gpo.yoin.player.sing.SingAcceleratorReport
import com.gpo.yoin.player.sing.SingBackendLabel
import com.gpo.yoin.player.sing.SingBackendRun
import com.gpo.yoin.player.sing.SingModelOverview
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.titleRes

/*
 * Settings › Features and appearance › AI features › Experimental features (v4: merged single group at the
 * bottom of AI features; LabsPageContent stays addressable for standalone deep links and the Tips demo).
 * Gemini Nano's state on this device, with a download button when it can be fetched, Gemini Nano diagnostics,
 * and each beta feature's switch in one segmented group.
 */
internal fun filterUnavailableReason(reason: String?, showRawDiagnostic: Boolean): String? =
    reason?.takeIf { showRawDiagnostic && it != "FeatureStatus.UNAVAILABLE" }

private fun visibleUnavailableReason(reason: String?): String? =
    filterUnavailableReason(reason, BuildConfig.DEBUG || BuildConfig.NANO_TEST)

@Composable
internal fun ExperimentalFeaturesSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as YoinApplication).container
    val processor = container.lyricHighlightProcessor
    val singManager = container.singModelManager
    val status by processor.status.collectAsState()
    val downloading by processor.downloading.collectAsState()
    val reason by processor.unavailableReason.collectAsState()
    val optedIn by processor.optedIn.collectAsState()
    val greetingsOn by processor.greetingsEnabled.collectAsState()
    val aboutFollowUpsOn by processor.aboutFollowUpsEnabled.collectAsState()
    val singBackend by SingAcceleratorReport.get(context).last.collectAsState()
    val singModel by singManager.state.collectAsState()
    LaunchedEffect(processor, singManager) {
        processor.checkNow()
        singManager.refresh()
    }
    ExperimentalFeaturesGroup(
        state = LabsState(
            nano = if (downloading) NanoStatus.DOWNLOADING else status,
            reason = visibleUnavailableReason(reason),
            lyricsOn = optedIn,
            greetingsOn = greetingsOn,
            aboutFollowUpsOn = aboutFollowUpsOn,
            singBackend = singBackend,
            singModel = singModel,
        ),
        onDownload = processor::downloadNow,
        onCheckAgain = processor::checkNow,
        onLyricsChange = processor::setOptedIn,
        onGreetingsChange = processor::setGreetingsEnabled,
        onAboutFollowUpsChange = processor::setAboutFollowUpsEnabled,
        onDownloadSingModel = singManager::downloadNow,
        onRemoveSingModel = singManager::removeModel,
        onOpenTensorEasterEgg = {
            context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.TensorEasterEgg))
        },
        onOpenDiagnostics = {
            context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.NanoDiagnostics))
        },
        modifier = modifier,
    )
}

@Composable
internal fun LabsPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as YoinApplication).container
    val processor = container.lyricHighlightProcessor
    val singManager = container.singModelManager
    val status by processor.status.collectAsState()
    val downloading by processor.downloading.collectAsState()
    val reason by processor.unavailableReason.collectAsState()
    val optedIn by processor.optedIn.collectAsState()
    val greetingsOn by processor.greetingsEnabled.collectAsState()
    val aboutFollowUpsOn by processor.aboutFollowUpsEnabled.collectAsState()
    val singBackend by SingAcceleratorReport.get(context).last.collectAsState()
    val singModel by singManager.state.collectAsState()
    LaunchedEffect(processor, singManager) {
        processor.checkNow()
        singManager.refresh()
    }
    LabsPageBody(
        state = LabsState(
            nano = if (downloading) NanoStatus.DOWNLOADING else status,
            // AICore's unexpected exception words only in debug and the Nano test copy (owner 2026-11-10);
            // plain FeatureStatus.UNAVAILABLE on unsupported devices is already said by labs_nano_unsupported.
            reason = visibleUnavailableReason(reason),
            lyricsOn = optedIn,
            greetingsOn = greetingsOn,
            aboutFollowUpsOn = aboutFollowUpsOn,
            singBackend = singBackend,
            singModel = singModel,
        ),
        onDownload = processor::downloadNow,
        onCheckAgain = processor::checkNow,
        onLyricsChange = processor::setOptedIn,
        onGreetingsChange = processor::setGreetingsEnabled,
        onAboutFollowUpsChange = processor::setAboutFollowUpsEnabled,
        onDownloadSingModel = singManager::downloadNow,
        onRemoveSingModel = singManager::removeModel,
        onOpenTensorEasterEgg = {
            context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.TensorEasterEgg))
        },
        onOpenDiagnostics = {
            context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.NanoDiagnostics))
        },
        modifier = modifier,
    )
}

/** What the Labs section shows: Nano here (null while it's never been asked), why not, and each beta switch. */
internal data class LabsState(
    val nano: NanoStatus?,
    val reason: String? = null,
    val lyricsOn: Boolean,
    val greetingsOn: Boolean = false,
    val aboutFollowUpsOn: Boolean = false,
    /** What on-device Sing's last session ran on; null until it has run a chunk. */
    val singBackend: SingBackendRun? = null,
    /** Full Lyra-Sing-Lite model distribution, download, and Tensor TPU easter egg state. */
    val singModel: SingModelOverview? = null,
)

/** The standalone Labs page body, stateless, so the Tips demo can show the real thing. */
@Composable
internal fun LabsPageBody(
    state: LabsState,
    onDownload: () -> Unit,
    onCheckAgain: () -> Unit,
    onLyricsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    /** Opens Gemini Nano diagnostics; null leaves the row out. */
    onOpenDiagnostics: (() -> Unit)? = null,
    onGreetingsChange: ((Boolean) -> Unit)? = null,
    onAboutFollowUpsChange: ((Boolean) -> Unit)? = null,
    onDownloadSingModel: (() -> Unit)? = null,
    onRemoveSingModel: (() -> Unit)? = null,
    onOpenTensorEasterEgg: (() -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            text = stringResource(R.string.labs_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
        )
        ExperimentalFeaturesGroup(
            state = state,
            onDownload = onDownload,
            onCheckAgain = onCheckAgain,
            onLyricsChange = onLyricsChange,
            switchModifier = switchModifier,
            onOpenDiagnostics = onOpenDiagnostics,
            onGreetingsChange = onGreetingsChange,
            onAboutFollowUpsChange = onAboutFollowUpsChange,
            onDownloadSingModel = onDownloadSingModel,
            onRemoveSingModel = onRemoveSingModel,
            onOpenTensorEasterEgg = onOpenTensorEasterEgg,
        )
    }
}

/**
 * Single merged 「实验功能」(Experimental features) group (Settings IA v4): on-device Gemini Nano status,
 * diagnostics, and the experimental feature switches together in one segmented list.
 */
@Composable
internal fun ExperimentalFeaturesGroup(
    state: LabsState,
    onDownload: () -> Unit,
    onCheckAgain: () -> Unit,
    onLyricsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    onOpenDiagnostics: (() -> Unit)? = null,
    onGreetingsChange: ((Boolean) -> Unit)? = null,
    onAboutFollowUpsChange: ((Boolean) -> Unit)? = null,
    onDownloadSingModel: (() -> Unit)? = null,
    onRemoveSingModel: (() -> Unit)? = null,
    onOpenTensorEasterEgg: (() -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    val nanoHere = state.nano != null && state.nano != NanoStatus.UNAVAILABLE
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsGroup(title = stringResource(R.string.labs_features_section)) {
            item {
                SettingsItem(
                    icon = YoinSymbols.Sparkle,
                    title = stringResource(R.string.labs_nano_name),
                    summary = stringResource(
                        when (state.nano) {
                            NanoStatus.AVAILABLE -> R.string.labs_nano_ready
                            NanoStatus.DOWNLOADABLE -> R.string.labs_nano_downloadable
                            NanoStatus.DOWNLOADING -> R.string.labs_nano_downloading
                            NanoStatus.UNAVAILABLE -> R.string.labs_nano_unsupported
                            null -> R.string.labs_nano_checking
                        },
                    ),
                    // AICore's own words, small: what a bug report needs when a supported phone says no.
                    summaryDetail = state.reason?.takeIf { state.nano == NanoStatus.UNAVAILABLE },
                    trailing = {
                        when (state.nano) {
                            NanoStatus.DOWNLOADABLE -> FilledTonalButton(onClick = {
                                haptics.performContextClick()
                                onDownload()
                            }, modifier = Modifier.handPointer()) { Text(stringResource(R.string.labs_nano_download)) }
                            NanoStatus.DOWNLOADING, null -> CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                            )
                            NanoStatus.UNAVAILABLE -> TextButton(onClick = {
                                haptics.performContextClick()
                                onCheckAgain()
                            }, modifier = Modifier.handPointer()) { Text(stringResource(R.string.labs_nano_check_again)) }
                            NanoStatus.AVAILABLE -> Unit
                        }
                    },
                )
            }
            if (state.singModel != null) {
                item {
                    LyraSingLabsRow(
                        overview = state.singModel,
                        onDownload = { onDownloadSingModel?.invoke() },
                        onRemove = { onRemoveSingModel?.invoke() },
                        onOpenTensorEasterEgg = onOpenTensorEasterEgg,
                    )
                }
            } else {
                state.singBackend?.let { run ->
                    // Fallback for isolated previews/harnesses that pass only singBackend.
                    item {
                        SettingsItem(
                            icon = YoinSymbols.Mic,
                            title = stringResource(R.string.np_sing),
                            summary = SingBackendLabel.summary(run),
                            summaryDetail = SingBackendLabel.detail(run),
                        )
                    }
                }
            }
            if (onOpenDiagnostics != null) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Insights,
                        title = stringResource(R.string.nano_diag_title),
                        summary = stringResource(R.string.nano_diag_entry_detail),
                        onClick = {
                            haptics.performContextClick()
                            onOpenDiagnostics()
                        },
                        trailing = {
                            Icon(
                                imageVector = YoinSymbols.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            item {
                SettingsItem(
                    icon = YoinSymbols.MusicNote,
                    title = stringResource(HomeSection.LyricHighlight.titleRes),
                    summary = stringResource(R.string.labs_lyrics_detail),
                    onClick = if (nanoHere) {
                        {
                            haptics.performContextClick()
                            onLyricsChange(!state.lyricsOn)
                        }
                    } else {
                        null
                    },
                    trailing = {
                        Switch(
                            checked = state.lyricsOn && nanoHere,
                            onCheckedChange = null,
                            enabled = nanoHere,
                            modifier = switchModifier.handPointer(enabled = nanoHere),
                        )
                    },
                )
            }
            if (onGreetingsChange != null) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.LightMode,
                        title = stringResource(R.string.labs_greetings_title),
                        summary = stringResource(R.string.labs_greetings_detail),
                        onClick = if (nanoHere) {
                            {
                                haptics.performContextClick()
                                onGreetingsChange(!state.greetingsOn)
                            }
                        } else {
                            null
                        },
                        trailing = {
                            Switch(
                                checked = state.greetingsOn && nanoHere,
                                onCheckedChange = null,
                                enabled = nanoHere,
                                modifier = Modifier.handPointer(enabled = nanoHere),
                            )
                        },
                    )
                }
            }
            if (onAboutFollowUpsChange != null) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Sparkle,
                        title = stringResource(R.string.labs_about_followups_title),
                        summary = stringResource(R.string.labs_about_followups_detail),
                        onClick = if (nanoHere) {
                            {
                                haptics.performContextClick()
                                onAboutFollowUpsChange(!state.aboutFollowUpsOn)
                            }
                        } else {
                            null
                        },
                        trailing = {
                            Switch(
                                checked = state.aboutFollowUpsOn && nanoHere,
                                onCheckedChange = null,
                                enabled = nanoHere,
                                modifier = Modifier.handPointer(enabled = nanoHere),
                            )
                        },
                    )
                }
            }
        }
        if (state.nano == NanoStatus.DOWNLOADABLE || state.nano == NanoStatus.DOWNLOADING) {
            Text(
                text = stringResource(R.string.labs_nano_download_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
            )
        } else if (state.nano == NanoStatus.UNAVAILABLE) {
            Text(
                text = stringResource(R.string.labs_lyrics_no_nano),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
            )
        }
    }
}
