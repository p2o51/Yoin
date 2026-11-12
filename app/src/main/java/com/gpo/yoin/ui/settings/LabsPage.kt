package com.gpo.yoin.ui.settings

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
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.titleRes

/*
 * Settings › Features › Yoin Labs (owner 2026-11-09: "a Yoin Labs in Settings, for experimental features, to manage
 * them or guide people to download"). Gemini Nano's state on this phone, with a download button when it can be
 * fetched, and each beta feature's switch; today that is Home's lyric cards (HomeLyricHighlight.kt). Opening the
 * page asks AICore again, so it is also where a phone that never showed the cards says why.
 */
@Composable
internal fun LabsPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val processor = (context.applicationContext as YoinApplication).container.lyricHighlightProcessor
    val status by processor.status.collectAsState()
    val downloading by processor.downloading.collectAsState()
    val reason by processor.unavailableReason.collectAsState()
    val optedIn by processor.optedIn.collectAsState()
    LaunchedEffect(processor) { processor.checkNow() }
    LabsPageBody(
        state = LabsState(
            nano = if (downloading) NanoStatus.DOWNLOADING else status,
            // AICore's raw words only in debug and the Nano test copy (owner 2026-11-10): on the Play build most
            // phones have no AICore at all, and "NullPointerException" under "not supported" reads as a crash.
            reason = reason.takeIf { BuildConfig.DEBUG || BuildConfig.NANO_TEST },
            lyricsOn = optedIn,
        ),
        onDownload = processor::downloadNow,
        onCheckAgain = processor::checkNow,
        onLyricsChange = processor::setOptedIn,
        onOpenDiagnostics = {
            context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.NanoDiagnostics))
        },
        modifier = modifier,
    )
}

/** What the Labs page shows: Nano here (null while it's never been asked), why not, and the lyric cards' switch. */
internal data class LabsState(
    val nano: NanoStatus?,
    val reason: String? = null,
    val lyricsOn: Boolean,
)

/** The page itself, stateless, so the Tips demo can show the real thing. */
@Composable
internal fun LabsPageBody(
    state: LabsState,
    onDownload: () -> Unit,
    onCheckAgain: () -> Unit,
    onLyricsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    /** Opens Gemini Nano diagnostics; null (the Tips demo) leaves the row out. */
    onOpenDiagnostics: (() -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(
            text = stringResource(R.string.labs_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsGroup(title = stringResource(R.string.labs_nano_section)) {
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
                                }) { Text(stringResource(R.string.labs_nano_download)) }
                                NanoStatus.DOWNLOADING, null -> CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp,
                                )
                                NanoStatus.UNAVAILABLE -> TextButton(onClick = {
                                    haptics.performContextClick()
                                    onCheckAgain()
                                }) { Text(stringResource(R.string.labs_nano_check_again)) }
                                NanoStatus.AVAILABLE -> Unit
                            }
                        },
                    )
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
                                Icon(imageVector = YoinSymbols.ChevronRight, contentDescription = null)
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
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val nanoHere = state.nano != null && state.nano != NanoStatus.UNAVAILABLE
            SettingsGroup(title = stringResource(R.string.labs_features_section)) {
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
                                modifier = switchModifier,
                            )
                        },
                    )
                }
            }
            if (state.nano == NanoStatus.UNAVAILABLE) {
                Text(
                    text = stringResource(R.string.labs_lyrics_no_nano),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
                )
            }
        }
    }
}
