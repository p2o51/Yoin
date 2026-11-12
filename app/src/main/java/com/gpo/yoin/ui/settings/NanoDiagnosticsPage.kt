package com.gpo.yoin.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.highlight.NanoDiagnostics
import com.gpo.yoin.data.highlight.NanoStatus
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.seamFade
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import kotlinx.coroutines.launch

/*
 * Settings › Yoin Labs › Gemini Nano diagnostics (owner 2026-11-12, after a Pixel 11 Pro on Discord showed
 * "not supported" while AICore listed Gemini Nano 4 as available): this phone, the system apps the model lives in,
 * AICore's answer for every model variant with its raw words, a test prompt, and the ways out (download the model,
 * update AICore, copy everything for a bug report). Its own SettingsFeatureActivity: Pattern A, native back.
 */
@Composable
internal fun NanoDiagnosticsPageContent(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val processor = (context.applicationContext as YoinApplication).container.lyricHighlightProcessor
    val downloading by processor.downloading.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    var report by remember { mutableStateOf<NanoDiagnostics.Report?>(null) }
    var checking by remember { mutableStateOf(true) }
    var testing by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<NanoDiagnostics.TestResult?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round, downloading) {
        checking = true
        report = NanoDiagnostics.run(context)
        checking = false
        // The lyric cards' own check follows, so Labs and Home agree with what this page just found.
        processor.checkNow()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Text(
            text = stringResource(R.string.nano_diag_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
        )

        val current = report
        SettingsGroup(title = stringResource(R.string.nano_diag_models_section)) {
            if (current == null) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Sparkle,
                        title = stringResource(R.string.labs_nano_checking),
                        trailing = { Spinner() },
                    )
                }
            } else {
                current.probes.forEach { probe ->
                    item(key = probe.label) {
                        SettingsItem(
                            icon = when (probe.status) {
                                NanoStatus.AVAILABLE -> YoinSymbols.Check
                                NanoStatus.DOWNLOADABLE, NanoStatus.DOWNLOADING -> YoinSymbols.Download
                                NanoStatus.UNAVAILABLE -> YoinSymbols.Error
                            },
                            title = probe.label,
                            summary = stringResource(probe.status.labelRes()),
                            summaryDetail = probe.baseModel ?: probe.error,
                            trailing = if (checking) {
                                { Spinner() }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (current != null) {
            SettingsGroup(title = stringResource(R.string.nano_diag_actions_section)) {
                if (current.canDownload) {
                    item {
                        SettingsItem(
                            icon = YoinSymbols.Download,
                            title = stringResource(R.string.labs_nano_download),
                            summary = stringResource(R.string.labs_nano_download_note),
                            trailing = {
                                if (downloading) {
                                    Spinner()
                                } else {
                                    FilledTonalButton(onClick = {
                                        haptics.performContextClick()
                                        processor.downloadNow()
                                    }) { Text(stringResource(R.string.labs_nano_download)) }
                                }
                            },
                        )
                    }
                }
                if (current.anyAvailable) {
                    item {
                        val result = test
                        SettingsItem(
                            icon = YoinSymbols.Send,
                            title = stringResource(R.string.nano_diag_test),
                            summary = when {
                                testing -> stringResource(R.string.nano_diag_test_running)
                                result == null -> stringResource(R.string.nano_diag_test_detail)
                                result.error != null -> stringResource(R.string.nano_diag_test_failed, result.millis)
                                else -> stringResource(
                                    R.string.nano_diag_test_reply,
                                    result.reply.orEmpty(),
                                    result.millis,
                                )
                            },
                            summaryDetail = result?.let { it.error ?: it.variant },
                            onClick = if (testing) {
                                null
                            } else {
                                {
                                    testing = true
                                    scope.launch {
                                        test = NanoDiagnostics.testPrompt(current)
                                        testing = false
                                    }
                                }
                            },
                            trailing = if (testing) {
                                { Spinner() }
                            } else {
                                null
                            },
                        )
                    }
                }
                item {
                    SettingsItem(
                        icon = YoinSymbols.Refresh,
                        title = stringResource(R.string.labs_nano_check_again),
                        onClick = if (checking) {
                            null
                        } else {
                            { round += 1 }
                        },
                    )
                }
                item {
                    SettingsItem(
                        icon = YoinSymbols.Launch,
                        title = stringResource(R.string.nano_diag_update_aicore),
                        summary = stringResource(R.string.nano_diag_update_aicore_detail),
                        onClick = { openAiCoreInPlay(context) },
                    )
                }
                item {
                    SettingsItem(
                        icon = YoinSymbols.Copy,
                        title = stringResource(R.string.nano_diag_copy),
                        summary = stringResource(R.string.nano_diag_copy_detail),
                        onClick = { copyReport(context, current, test) },
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.nano_diag_device_section)) {
                item {
                    SettingsItem(
                        icon = YoinSymbols.Smartphone,
                        title = current.device,
                        summary = stringResource(R.string.nano_diag_android, current.android),
                        summaryDetail = stringResource(R.string.nano_diag_app, current.app),
                    )
                }
                current.components.forEach { component ->
                    item(key = component.packageName) {
                        SettingsItem(
                            icon = YoinSymbols.Memory,
                            title = component.name,
                            summary = component.version ?: stringResource(R.string.nano_diag_not_installed),
                            summaryDetail = component.packageName,
                        )
                    }
                }
            }

            if (!current.anyAvailable && !current.canDownload) {
                Text(
                    text = stringResource(R.string.nano_diag_unavailable_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).seamFade(),
                )
            }
        }
    }
}

@Composable
private fun Spinner() {
    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
}

private fun NanoStatus.labelRes(): Int = when (this) {
    NanoStatus.AVAILABLE -> R.string.labs_nano_ready
    NanoStatus.DOWNLOADABLE -> R.string.labs_nano_downloadable
    NanoStatus.DOWNLOADING -> R.string.labs_nano_downloading
    NanoStatus.UNAVAILABLE -> R.string.nano_diag_variant_unavailable
}

private const val AICORE_PACKAGE = "com.google.android.aicore"

private fun openAiCoreInPlay(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$AICORE_PACKAGE".toUri())
    val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$AICORE_PACKAGE".toUri())
    try {
        context.startActivity(market)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(web) }
    }
}

private fun copyReport(context: Context, report: NanoDiagnostics.Report, test: NanoDiagnostics.TestResult?) {
    val text = buildString {
        append(report.asText())
        test?.let {
            appendLine()
            append("Test (${it.variant}): ${it.error ?: it.reply} in ${it.millis} ms")
        }
    }
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Nano diagnostics", text))
    // Android 13+ confirms a copy itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.nano_diag_copied, Toast.LENGTH_SHORT).show()
    }
}
