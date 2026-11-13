package com.gpo.yoin.ui.settings.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.local.GeminiConfig
import com.gpo.yoin.data.remote.ai.AiApiFormat
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiSearchService
import com.gpo.yoin.data.remote.ai.AiThinking
import com.gpo.yoin.data.remote.ai.AiTier
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinDropdownMenu
import com.gpo.yoin.ui.component.YoinDropdownMenuItem
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.SecretTextField
import com.gpo.yoin.ui.settings.SettingsFeature
import com.gpo.yoin.ui.settings.SettingsFeatureActivity
import com.gpo.yoin.ui.settings.SettingsItem
import com.gpo.yoin.ui.settings.SettingsSegments
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Settings › AI features (owner 2026-11-12, prototype v2): a service row, the
 * tradeoff chart with 「换个角度」, two tier cards and the key. Custom swaps the
 * tiers for its endpoint, model, thinking and search. Details live on the tier
 * page (ⓘ), the debug run on 「拿一首歌试试」 (both Pattern A pages).
 */
@Composable
internal fun AiSettingsPageContent() {
    val context = LocalContext.current
    val app = context.applicationContext as YoinApplication
    val viewModel: AiSettingsViewModel = viewModel(factory = AiSettingsViewModel.Factory(app.container))
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    AiSettingsPageBody(
        state = state,
        actions = viewModel,
        onOpenTiers = { context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.AiTiers)) },
        onOpenTry = { context.startActivity(SettingsFeatureActivity.intent(context, SettingsFeature.AiTry)) },
        // No app to open a link with (no browser on the device) must not take the page down.
        onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
    )
}

/**
 * The AI page's controls over plain state: what [AiSettingsPageContent] shows,
 * and what the Tips demo and the preview show with state of their own.
 * [providerModifier], [tierModifier] and [tryModifier] name the parts the demo touches.
 */
@Composable
internal fun AiSettingsPageBody(
    state: AiSettingsState,
    actions: AiSettingsActions,
    onOpenTiers: () -> Unit,
    onOpenTry: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    providerModifier: (AiProvider) -> Modifier = { Modifier },
    tierModifier: (AiTier) -> Modifier = { Modifier },
    tryModifier: Modifier = Modifier,
) {
    val prefs = state.prefs
    val haptics = rememberYoinHaptics()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ProviderRow(
            selected = prefs.provider,
            onSelect = {
                haptics.performSegmentTick()
                actions.setProvider(it)
            },
            chipModifier = providerModifier,
        )
        TradeoffChart(provider = prefs.provider, tier = prefs.tier)

        // Tiers ⇄ Custom is the page's one body swap: the two cross-fade and the page takes its new
        // height on the spatial spring. The tiered services share a body, so picking among them
        // swaps nothing.
        val sizeSpring = YoinMotion.spatialSpring<IntSize>()
        AnimatedContent(
            targetState = prefs.provider,
            transitionSpec = {
                val fade = YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                fade.using(SizeTransform { _, _ -> sizeSpring })
            },
            contentKey = { it == AiProvider.Custom },
            label = "aiServiceBody",
            modifier = Modifier.fillMaxWidth(),
        ) { provider ->
            if (provider != AiProvider.Custom) {
                // Everything here reads [provider], not the choice: on its way out for Custom this
                // body still shows the service it was for.
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel(stringResource(R.string.ai_tiers_label), Modifier.weight(1f))
                            IconButton(onClick = {
                                haptics.performClick()
                                onOpenTiers()
                            }) {
                                Icon(
                                    YoinSymbols.Info,
                                    contentDescription = stringResource(R.string.ai_cd_tier_details),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AiTier.entries.forEach { tier ->
                                TierCard(
                                    tier = tier,
                                    selected = prefs.tier == tier,
                                    onClick = {
                                        haptics.performSegmentTick()
                                        actions.setTier(tier)
                                    },
                                    modifier = Modifier.weight(1f).then(tierModifier(tier)),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SecretTextField(
                            value = state.keyFor(provider),
                            onValueChange = { actions.setKey(provider, it) },
                            label = stringResource(R.string.ai_key_label),
                            placeholder = if (provider == AiProvider.Gemini) "AIza…" else "sk-…",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val (linkText, url) = keyLink(provider)
                        TextButton(onClick = { onOpenLink(url) }) {
                            Text(linkText)
                            Spacer(Modifier.width(4.dp))
                            Icon(YoinSymbols.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            } else {
                CustomSection(state = state, actions = actions, onOpenLink = onOpenLink)
            }
        }

        LanguageRow(language = state.targetLanguage, onPick = actions::setTargetLanguage)

        Button(
            onClick = {
                haptics.performClick()
                onOpenTry()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).then(tryModifier),
        ) {
            Icon(YoinSymbols.PlayCircle, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_try))
        }
    }
}

@Composable
private fun keyLink(provider: AiProvider): Pair<String, String> = when (provider) {
    AiProvider.Gemini -> stringResource(R.string.ai_get_key_gemini) to "https://aistudio.google.com/apikey"
    AiProvider.DeepSeek -> stringResource(R.string.ai_get_key_deepseek) to "https://platform.deepseek.com/api_keys"
    AiProvider.Qwen -> stringResource(R.string.ai_get_key_qwen) to "https://bailian.console.aliyun.com/?tab=model#/api-key"
    AiProvider.Custom -> "" to ""
}

@Composable
internal fun providerName(provider: AiProvider): String = when (provider) {
    AiProvider.Gemini -> stringResource(R.string.ai_provider_gemini)
    AiProvider.DeepSeek -> stringResource(R.string.ai_provider_deepseek)
    AiProvider.Qwen -> stringResource(R.string.ai_provider_qwen)
    AiProvider.Custom -> stringResource(R.string.ai_provider_custom)
}

@Composable
internal fun tierName(tier: AiTier): String = when (tier) {
    AiTier.Good -> stringResource(R.string.ai_tier_good)
    AiTier.Best -> stringResource(R.string.ai_tier_best)
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** A choice that rounds into a pill and fills when picked (the services, formats, thinking, search). */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rest: Int = 14,
    height: Int = 44,
    // A chip of a set width (a weighted row) keeps only a thin edge: its word is centred, and a
    // 360dp phone leaves 「DeepSeek」 no room for more.
    horizontalPadding: Int = 12,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        if (selected) (height / 2).dp else rest.dp,
        YoinMotion.fastSpatialSpec(role = YoinMotionRole.Expressive),
        label = "choiceCorner",
    )
    val container by animateColorAsState(
        if (selected) scheme.primary else scheme.surfaceBright,
        YoinMotion.fastEffectsSpec(),
        label = "choiceColor",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        modifier = modifier
            .height(height.dp)
            .semantics { this.selected = selected; role = Role.RadioButton },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = horizontalPadding.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ProviderRow(
    selected: AiProvider,
    onSelect: (AiProvider) -> Unit,
    chipModifier: (AiProvider) -> Modifier = { Modifier },
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AiProvider.entries.forEach { provider ->
            ChoiceChip(
                text = providerName(provider),
                selected = selected == provider,
                onClick = { onSelect(provider) },
                modifier = Modifier.weight(1f).then(chipModifier(provider)),
                horizontalPadding = 4,
            )
        }
    }
}

/** 「好」/「最佳」: just the word; filled when picked, never outlined (owner 2026-11-12). */
@Composable
private fun TierCard(tier: AiTier, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        if (selected) 32.dp else 22.dp,
        YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
        label = "tierCorner",
    )
    val container by animateColorAsState(
        if (selected) scheme.primary else scheme.surfaceBright,
        YoinMotion.fastEffectsSpec(),
        label = "tierColor",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = if (selected) scheme.onPrimary else scheme.onSurface,
        modifier = modifier
            .height(84.dp)
            .semantics { this.selected = selected; role = Role.RadioButton },
    ) {
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 18.dp)) {
            Text(tierName(tier), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun providerColor(provider: AiProvider, dark: Boolean): Color = when (provider) {
    AiProvider.Gemini -> if (dark) Color(0xFF7FA9FF) else Color(0xFF2F6FE4)
    AiProvider.DeepSeek -> if (dark) Color(0xFF5FD3AC) else Color(0xFF1F9A74)
    AiProvider.Qwen -> if (dark) Color(0xFFF2A463) else Color(0xFFC4661B)
    AiProvider.Custom -> if (dark) Color(0xFFFF8FCF) else Color(0xFFC2338A)
}

/**
 * Every tier as a dot on two of speed / cost / accuracy. No axis picker: the
 * button beside the legend steps through [AiChartViews] and the dots spring
 * to their new places. Untested tiers are hollow dots in the corner.
 */
@Composable
private fun TradeoffChart(provider: AiProvider, tier: AiTier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val haptics = rememberYoinHaptics()
    var view by rememberSaveable { mutableIntStateOf(0) }
    var turns by rememberSaveable { mutableIntStateOf(0) }
    val (xAxis, yAxis) = AiChartViews[view % AiChartViews.size]
    val measured = AiTierBenchmark.points.filter { it.measured }
    val untested = AiTierBenchmark.points.filterNot { it.measured }
    val spring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    // Fixed order and count: one pair of springs per measured tier.
    val positions = measured.map { point ->
        val x by animateFloatAsState(xAxis.fraction(point) ?: 0f, spring, label = "dotX")
        val y by animateFloatAsState(yAxis.fraction(point) ?: 0f, spring, label = "dotY")
        Offset(x, y)
    }
    val labels = measured.map { tierName(it.tier) }
    val axisLabel = mapOf(
        AiAxis.Speed to stringResource(R.string.ai_axis_speed),
        AiAxis.Cost to stringResource(R.string.ai_axis_cost),
        AiAxis.Accuracy to stringResource(R.string.ai_axis_accuracy),
    )
    val untestedText = stringResource(R.string.ai_untested)
    val measurer = rememberTextMeasurer()
    // From the type scale: a bare TextStyle has no font family, and the canvas would draw the
    // system font instead of the app's.
    val typography = MaterialTheme.typography
    val small = typography.labelSmall.copy(fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val axisStyle = typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
    val dotLabel = typography.labelMedium.copy(color = scheme.onSurface)
    val iconTurn by animateFloatAsState(turns * 180f, YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive), label = "flipTurn")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val left = 52.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 34.dp.toPx()
            val gridColor = scheme.outlineVariant
            for (i in 0..4) {
                val gx = left + (right - left) * i / 4f
                val gy = top + (bottom - top) * i / 4f
                drawLine(gridColor, Offset(gx, top), Offset(gx, bottom), 1.dp.toPx())
                drawLine(gridColor, Offset(left, gy), Offset(right, gy), 1.dp.toPx())
            }
            fun at(f: Offset) = Offset(left + f.x * (right - left), bottom - f.y * (bottom - top))

            // Axis ends: worst at the origin, best at the far end.
            drawEnd(measurer, axisValue(xAxis, worst = true), small, Offset(left, bottom + 4.dp.toPx()), alignEnd = false)
            drawEnd(measurer, axisValue(xAxis, worst = false), small, Offset(right, bottom + 4.dp.toPx()), alignEnd = true)
            drawEnd(measurer, axisValue(yAxis, worst = true), small, Offset(left - 6.dp.toPx(), bottom - 12.dp.toPx()), alignEnd = true)
            drawEnd(measurer, axisValue(yAxis, worst = false), small, Offset(left - 6.dp.toPx(), top), alignEnd = true)
            val xText = measurer.measure("${axisLabel.getValue(xAxis)} →", axisStyle)
            drawText(xText, topLeft = Offset((left + right - xText.size.width) / 2f, bottom + 16.dp.toPx()))
            val gutter = left - 6.dp.toPx()
            val yText = measurer.measure("↑${axisLabel.getValue(yAxis)}", axisStyle)
            if (yText.size.width <= gutter) {
                drawText(yText, topLeft = Offset(gutter - yText.size.width, (top + bottom - yText.size.height) / 2f))
            } else {
                // Too long to sit beside the plot (「More accurate」): it runs up the axis instead,
                // written like the x label, so its arrow still points at the better end.
                val along = measurer.measure("${axisLabel.getValue(yAxis)} →", axisStyle)
                val centre = Offset(along.size.height / 2f, (top + bottom) / 2f)
                val unturned = Offset(centre.x - along.size.width / 2f, centre.y - along.size.height / 2f)
                rotate(degrees = -90f, pivot = centre) { drawText(along, topLeft = unturned) }
            }

            untested.forEachIndexed { i, point ->
                val mine = point.provider == provider
                val chosen = mine && point.tier == tier
                val c = Offset(right - (14 + i * 18).dp.toPx(), bottom - 14.dp.toPx())
                drawCircle(
                    color = providerColor(point.provider, dark).copy(alpha = if (mine) 1f else 0.45f),
                    radius = (if (chosen) 7.5f else 5.5f).dp.toPx(),
                    center = c,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
                if (mine && i == untested.lastIndex) {
                    val t = measurer.measure(untestedText, small)
                    drawText(t, topLeft = Offset(c.x - 14.dp.toPx() - t.size.width, c.y - t.size.height / 2f))
                }
            }

            measured.forEachIndexed { i, point ->
                val mine = provider == AiProvider.Custom || point.provider == provider
                val chosen = point.provider == provider && point.tier == tier
                val alpha = when {
                    provider == AiProvider.Qwen -> 0.3f
                    mine -> 1f
                    else -> 0.3f
                }
                val color = providerColor(point.provider, dark)
                val c = at(positions[i])
                if (chosen) drawCircle(color.copy(alpha = 0.18f), radius = 13.dp.toPx(), center = c)
                drawCircle(scheme.surface, radius = (if (chosen) 9.5f else 7.5f).dp.toPx(), center = c)
                drawCircle(color.copy(alpha = alpha), radius = (if (chosen) 7.5f else 5.5f).dp.toPx(), center = c)
                if (point.provider == provider) {
                    val t = measurer.measure(
                        labels[i],
                        dotLabel.copy(fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium),
                    )
                    drawText(t, topLeft = Offset(c.x + 10.dp.toPx(), c.y - t.size.height / 2f))
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val showText = maxWidth >= 360.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(dark = dark, modifier = Modifier.weight(1f))
                Surface(
                    onClick = {
                        haptics.performSegmentTick()
                        view = (view + 1) % AiChartViews.size
                        turns += 1
                    },
                    shape = CircleShape,
                    color = scheme.surfaceBright,
                    contentColor = scheme.onSurface,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(36.dp).padding(horizontal = if (showText) 12.dp else 9.dp),
                    ) {
                        Icon(
                            YoinSymbols.Refresh,
                            contentDescription = if (showText) null else stringResource(R.string.ai_flip),
                            modifier = Modifier.size(18.dp).rotate(iconTurn),
                        )
                        if (showText) {
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.ai_flip), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawEnd(
    measurer: androidx.compose.ui.text.TextMeasurer,
    text: String,
    style: TextStyle,
    anchor: Offset,
    alignEnd: Boolean,
) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(if (alignEnd) anchor.x - layout.size.width else anchor.x, anchor.y))
}

/** The value printed at an axis end: [worst] is the origin side. */
private fun axisValue(axis: AiAxis, worst: Boolean): String {
    val atMin = axis.higherIsBetter == worst
    val v = if (atMin) axis.min else axis.max
    return when (axis) {
        AiAxis.Speed -> "${trimNumber(v)}s"
        AiAxis.Cost -> "$${trimNumber(v)}"
        AiAxis.Accuracy -> "${trimNumber(v)}%"
    }
}

internal fun trimNumber(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(dark: Boolean, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(AiProvider.Gemini, AiProvider.DeepSeek, AiProvider.Qwen).forEach { provider ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(providerColor(provider, dark)) }
                Spacer(Modifier.width(4.dp))
                Text(
                    providerName(provider),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomSection(state: AiSettingsState, actions: AiSettingsActions, onOpenLink: (String) -> Unit) {
    val prefs = state.prefs
    val haptics = rememberYoinHaptics()
    // Drafts so a half-typed address or model isn't trimmed under the cursor.
    var baseUrl by rememberSaveable { mutableStateOf(prefs.customBaseUrl) }
    var model by rememberSaveable(prefs.customModel) { mutableStateOf(prefs.customModel) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(R.string.ai_custom_format))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AiApiFormat.entries.forEach { format ->
                ChoiceChip(
                    text = stringResource(
                        if (format == AiApiFormat.OpenAi) R.string.ai_format_openai else R.string.ai_format_anthropic,
                    ),
                    selected = prefs.customFormat == format,
                    onClick = {
                        haptics.performSegmentTick()
                        actions.setCustomFormat(format)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ExpressiveTextField(
            value = baseUrl,
            onValueChange = {
                baseUrl = it
                actions.setCustomBaseUrl(it)
            },
            label = stringResource(R.string.ai_custom_url),
            placeholder = if (prefs.customFormat == AiApiFormat.OpenAi) "https://api.openai.com/v1" else "https://api.anthropic.com",
            modifier = Modifier.fillMaxWidth(),
        )
        SecretTextField(
            value = prefs.customKey,
            onValueChange = { actions.setKey(AiProvider.Custom, it) },
            label = stringResource(R.string.ai_key_label),
            placeholder = "sk-…",
            modifier = Modifier.fillMaxWidth(),
        )
        // The model field and the rows that come and go under it. They bring their own gaps, so a
        // row opening or closing moves what is below it the whole way, with no jump at either end.
        Column {
            ExpressiveTextField(
                value = model,
                onValueChange = {
                    model = it
                    actions.setCustomModel(it)
                },
                label = stringResource(R.string.ai_custom_model),
                placeholder = "",
                trailingContent = {
                    // The trailing slot sits inline with the ~24dp text row: pin it to that height and let
                    // the button overflow into the field's padding, as SecretTextField's eye does. A plain
                    // 40dp button would make this field taller than the address and the key above it.
                    Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
                        TextButton(
                            onClick = {
                                haptics.performClick()
                                actions.pullModels()
                            },
                            enabled = !state.models.loading &&
                                prefs.customBaseUrl.isNotBlank() &&
                                prefs.customKey.isNotBlank(),
                            modifier = Modifier.requiredHeight(40.dp),
                        ) { Text(stringResource(R.string.ai_custom_pull)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val chips = modelChips(state.models.ids, model)
            Column(modifier = Modifier.fillMaxWidth().animateContentSize(YoinMotion.spatialSpring())) {
                state.models.error?.let { error ->
                    Text(
                        stringResource(R.string.ai_models_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    )
                }
                if (chips.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chips.forEach { id ->
                            ChoiceChip(
                                text = id,
                                selected = prefs.customModel == id,
                                onClick = {
                                    haptics.performSegmentTick()
                                    actions.setCustomModel(id)
                                },
                                height = 36,
                                rest = 12,
                            )
                        }
                    }
                }
            }
            // Thinking once there is a model to think with (owner 2026-11-12).
            AnimatedVisibility(
                visible = prefs.customModel.isNotBlank(),
                enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
                Column(modifier = Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(R.string.ai_custom_thinking))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AiThinking.entries.forEach { level ->
                            ChoiceChip(
                                text = stringResource(
                                    when (level) {
                                        AiThinking.Off -> R.string.ai_thinking_off
                                        AiThinking.Low -> R.string.ai_thinking_low
                                        AiThinking.Medium -> R.string.ai_thinking_medium
                                        AiThinking.High -> R.string.ai_thinking_high
                                    },
                                ),
                                selected = prefs.customThinking == level,
                                onClick = {
                                    haptics.performSegmentTick()
                                    actions.setCustomThinking(level)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SectionLabel(stringResource(R.string.ai_search_label))
        Column {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AiSearchService.entries.forEach { service ->
                    ChoiceChip(
                        text = searchServiceName(service),
                        selected = prefs.searchService == service,
                        onClick = {
                            haptics.performSegmentTick()
                            actions.setSearchService(service)
                        },
                    )
                }
            }
            val searchOn = prefs.searchService != AiSearchService.None
            // Keep the last service's field on screen while it folds away for 「不搜索」.
            var keyService by remember { mutableStateOf(prefs.searchService) }
            if (searchOn) keyService = prefs.searchService
            AnimatedVisibility(
                visible = searchOn,
                enter = expandVertically(YoinMotion.spatialSpring(), expandFrom = Alignment.Top) +
                    YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                exit = shrinkVertically(YoinMotion.spatialSpring(), shrinkTowards = Alignment.Top) +
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val name = searchServiceName(keyService)
                    // One field per service: its own key, and a key shown in the clear for one
                    // service is masked again for the next.
                    key(keyService) {
                        SecretTextField(
                            value = prefs.searchKeyFor(keyService),
                            onValueChange = { actions.setSearchKey(keyService, it) },
                            label = stringResource(R.string.ai_search_key, name),
                            placeholder = "",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(onClick = { onOpenLink(searchKeyUrl(keyService)) }) {
                        Text(stringResource(R.string.ai_get_search_key, name))
                        Spacer(Modifier.width(4.dp))
                        Icon(YoinSymbols.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private const val MAX_MODEL_CHIPS = 24

/**
 * The fetched model ids to offer as chips. An endpoint with a short list shows all of it; an
 * aggregator's hundreds are narrowed by what is [typed] in the model field, so any of them can
 * be reached, and never more than [MAX_MODEL_CHIPS] are drawn.
 */
internal fun modelChips(ids: List<String>, typed: String): List<String> {
    if (ids.size <= MAX_MODEL_CHIPS) return ids
    val query = typed.trim()
    return ids.asSequence().filter { it.contains(query, ignoreCase = true) }.take(MAX_MODEL_CHIPS).toList()
}

@Composable
private fun searchServiceName(service: AiSearchService): String = when (service) {
    AiSearchService.DeepSeek -> stringResource(R.string.ai_provider_deepseek)
    AiSearchService.Exa -> "Exa"
    AiSearchService.Tavily -> "Tavily"
    AiSearchService.None -> stringResource(R.string.ai_search_none)
}

private fun searchKeyUrl(service: AiSearchService): String = when (service) {
    AiSearchService.DeepSeek -> "https://platform.deepseek.com/api_keys"
    AiSearchService.Exa -> "https://dashboard.exa.ai/api-keys"
    AiSearchService.Tavily -> "https://app.tavily.com/home"
    AiSearchService.None -> ""
}

/** The answer language: a value row (the language on the right) opening the same menu. */
@Composable
private fun LanguageRow(language: String, onPick: (String) -> Unit) {
    val haptics = rememberYoinHaptics()
    var open by remember { mutableStateOf(false) }
    SettingsSegments {
        item {
            Box {
                SettingsItem(
                    icon = YoinSymbols.Translate,
                    title = stringResource(R.string.ai_answer_language),
                    onClick = { open = true },
                    trailing = {
                        Text(
                            text = language,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    },
                )
                YoinDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    GeminiConfig.SUPPORTED_TARGET_LANGUAGES.forEach { option ->
                        YoinDropdownMenuItem(
                            text = option,
                            onClick = {
                                haptics.performContextClick()
                                open = false
                                onPick(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The page's actions over state the caller holds, with nothing behind them: no file, no Room, no
 * network. For the Tips demo and the preview.
 */
internal class AiSettingsStateActions(private val state: MutableState<AiSettingsState>) : AiSettingsActions {
    private fun prefs(transform: (AiProviderPrefs) -> AiProviderPrefs) {
        state.value = state.value.copy(prefs = transform(state.value.prefs))
    }

    override fun setProvider(provider: AiProvider) = prefs { it.copy(provider = provider) }

    override fun setTier(tier: AiTier) = prefs { it.copy(tier = tier) }

    override fun setKey(provider: AiProvider, key: String) {
        when (provider) {
            AiProvider.Gemini -> state.value = state.value.copy(geminiKey = key)
            AiProvider.DeepSeek -> prefs { it.copy(deepSeekKey = key) }
            AiProvider.Qwen -> prefs { it.copy(qwenKey = key) }
            AiProvider.Custom -> prefs { it.copy(customKey = key) }
        }
    }

    override fun setCustomFormat(format: AiApiFormat) = prefs { it.copy(customFormat = format) }

    override fun setCustomBaseUrl(url: String) = prefs { it.copy(customBaseUrl = url) }

    override fun setCustomModel(model: String) = prefs { it.copy(customModel = model) }

    override fun setCustomThinking(thinking: AiThinking) = prefs { it.copy(customThinking = thinking) }

    override fun setSearchService(service: AiSearchService) = prefs { it.copy(searchService = service) }

    override fun setSearchKey(service: AiSearchService, key: String) = prefs { it.withSearchKey(service, key) }

    override fun setTargetLanguage(language: String) {
        state.value = state.value.copy(targetLanguage = language)
    }

    override fun pullModels() = Unit
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AiSettingsPageBodyPreview() {
    YoinTheme {
        val state = remember {
            mutableStateOf(AiSettingsState(AiProviderPrefs(provider = AiProvider.DeepSeek, tier = AiTier.Best)))
        }
        AiSettingsPageBody(
            state = state.value,
            actions = remember { AiSettingsStateActions(state) },
            onOpenTiers = {},
            onOpenTry = {},
            onOpenLink = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
