package com.gpo.yoin.ui.settings.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiSettings
import com.gpo.yoin.data.remote.ai.AiTier
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole

/** The tier page (from ⓘ): both tiers of the chosen service side by side, and what they cost. */
@Composable
internal fun AiTiersPageContent() {
    val app = LocalContext.current.applicationContext as YoinApplication
    val viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory(app.container))
    val prefs by viewModel.state.collectAsState()
    val provider = prefs.prefs.provider.takeIf { it != AiProvider.Custom } ?: AiProvider.Gemini
    val current = prefs.prefs.tier

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.ai_tiers_title, providerName(provider)),
            style = MaterialTheme.typography.titleLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiTier.entries.forEach { tier ->
                TierFacts(provider, tier, chosen = tier == current, modifier = Modifier.weight(1f))
            }
        }
        Text(
            stringResource(
                when (provider) {
                    AiProvider.DeepSeek -> R.string.ai_note_deepseek
                    AiProvider.Qwen -> R.string.ai_note_qwen
                    else -> R.string.ai_note_gemini
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.ai_tiers_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TierFacts(provider: AiProvider, tier: AiTier, chosen: Boolean, modifier: Modifier = Modifier) {
    val point = AiTierBenchmark.point(provider, tier)
    val model = AiSettings(
        com.gpo.yoin.data.remote.ai.AiProviderPrefs(provider = provider, tier = tier),
        geminiKey = "",
    ).modelId
    val search = stringResource(
        when (provider) {
            AiProvider.Gemini -> R.string.ai_search_google
            AiProvider.Qwen -> R.string.ai_search_qwen
            else -> if (tier == AiTier.Best) R.string.ai_search_deepseek_twice else R.string.ai_search_deepseek_once
        },
    )
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(if (chosen) 28.dp else 20.dp),
        color = if (chosen) scheme.primaryContainer else scheme.surfaceBright,
        contentColor = if (chosen) scheme.onPrimaryContainer else scheme.onSurface,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tierName(tier), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Fact(stringResource(R.string.ai_row_model), model)
            Fact(stringResource(R.string.ai_row_search), search)
            Fact(
                stringResource(R.string.ai_row_speed),
                point?.seconds?.let { stringResource(R.string.ai_seconds, trimNumber(it)) } ?: "—",
            )
            Fact(stringResource(R.string.ai_row_accuracy), point?.accuracy?.let { "${trimNumber(it)}%" } ?: "—")
            Fact(
                stringResource(R.string.ai_row_cost),
                point?.costPer1000?.let {
                    if (point.costEstimated) {
                        stringResource(R.string.ai_cost_estimated, trimNumber(it))
                    } else {
                        stringResource(R.string.ai_cost, trimNumber(it))
                    }
                } ?: "—",
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 「拿一首歌试试」: write About for one song with the settings as they are,
 * nothing saved. Opens on the song playing now.
 */
@Composable
internal fun AiTryPageContent() {
    val app = LocalContext.current.applicationContext as YoinApplication
    val viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory(app.container))
    val run by viewModel.test.collectAsState()
    val haptics = rememberYoinHaptics()
    val playing = remember { viewModel.nowPlaying() }
    var title by rememberSaveable { mutableStateOf(playing?.title.orEmpty()) }
    var artist by rememberSaveable { mutableStateOf(playing?.artist.orEmpty()) }
    var album by rememberSaveable { mutableStateOf(playing?.album.orEmpty()) }
    val running = run is TestRun.Running

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExpressiveTextField(title, { title = it }, stringResource(R.string.ai_try_song), "", Modifier.fillMaxWidth())
        ExpressiveTextField(artist, { artist = it }, stringResource(R.string.ai_try_artist), "", Modifier.fillMaxWidth())
        ExpressiveTextField(album, { album = it }, stringResource(R.string.ai_try_album), "", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                haptics.performClick()
                viewModel.runTest(TestSong(title.trim(), artist.trim(), album.trim()))
            },
            enabled = !running && title.isNotBlank() && artist.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.ai_try_run)) }
        Spacer(Modifier.height(8.dp))
        // Waiting, the answer and a failure take each other's place: they cross-fade and the
        // page takes its new height on the spatial spring.
        val sizeSpring = YoinMotion.spatialSpring<IntSize>()
        AnimatedContent(
            targetState = run,
            transitionSpec = {
                val fade = YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                fade.using(SizeTransform { _, _ -> sizeSpring })
            },
            contentKey = { it::class },
            label = "aiTryRun",
            modifier = Modifier.fillMaxWidth(),
        ) { r ->
            when (r) {
                TestRun.Idle -> Unit
                is TestRun.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp)) { YoinLoadingIndicator() }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ai_try_running), style = MaterialTheme.typography.bodyLarge)
                }
                is TestRun.Failed -> Text(
                    if (r.message == null) {
                        stringResource(R.string.ai_try_no_key)
                    } else {
                        stringResource(R.string.ai_try_error, r.message)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                is TestRun.Done -> TestResult(r)
            }
        }
    }
}

@Composable
private fun TestResult(run: TestRun.Done) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text(
                run.model,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.ai_seconds, "%.1f".format(java.util.Locale.ROOT, run.millis / 1000f)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when (run.search) {
                AiSettings.SearchKind.BuiltIn -> stringResource(R.string.ai_try_builtin)
                AiSettings.SearchKind.None -> stringResource(R.string.ai_search_none)
                AiSettings.SearchKind.External ->
                    stringResource(R.string.ai_try_queries, run.queries.joinToString(" / ")) + "\n" +
                        stringResource(R.string.ai_try_pages, run.pages)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SongAboutEntry.CANONICAL_ORDER.forEach { key ->
                    val value = run.fields[key]
                    Column(modifier = Modifier.widthIn(max = 640.dp)) {
                        Text(
                            stringResource(fieldLabel(key)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            value ?: "—",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (value == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

private fun fieldLabel(key: String): Int = when (key) {
    SongAboutEntry.CANON_CREATION_TIME -> R.string.np_about_creation_time
    SongAboutEntry.CANON_CREATION_LOCATION -> R.string.np_about_creation_location
    SongAboutEntry.CANON_LYRICIST -> R.string.np_about_lyricist
    SongAboutEntry.CANON_COMPOSER -> R.string.np_about_composer
    SongAboutEntry.CANON_PRODUCER -> R.string.np_about_producer
    else -> R.string.np_about_review
}
