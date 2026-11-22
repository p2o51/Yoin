package com.gpo.yoin.ui.settings.sync

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.profile.ProviderKind
import com.gpo.yoin.data.sync.CloudScopeCounts
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.MetaGroup
import com.gpo.yoin.ui.component.SeamTop
import com.gpo.yoin.ui.component.YoinSnackbarHost
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.seamDissolveViewport
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.ServiceIdentity
import com.gpo.yoin.ui.settings.SettingsBackButton
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.SettingsRowIcon
import com.gpo.yoin.ui.settings.handoffProgress
import com.gpo.yoin.ui.settings.serviceIdentity
import com.gpo.yoin.ui.settings.serviceLineGroups
import com.gpo.yoin.ui.settings.tone
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import java.util.Locale

// Pieces the redone Cloud sync page and its sub-pages share (prototype: artifacts/sync-proto).

/**
 * A Cloud sync sub-page: the Settings bar (back arrow, or close for a flow) whose title takes over from the
 * large [headline] as it scrolls away, the column (crumb, headline, lead, [content]) and an optional pinned
 * [footer] for the page's main action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncPage(
    barTitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    crumb: String? = null,
    headline: String? = null,
    lead: String? = null,
    close: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    header: (@Composable ColumnScope.(titleModifier: Modifier) -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val viewportTop = remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    val headlineTop = remember { mutableFloatStateOf(Float.NaN) }
    val headlineHeight = remember { mutableFloatStateOf(1f) }
    val titleModifier = Modifier.onGloballyPositioned {
        headlineTop.floatValue = it.positionInWindow().y
        headlineHeight.floatValue = it.size.height.toFloat().coerceAtLeast(1f)
    }
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        SettingsPageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = barTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.graphicsLayer {
                                    alpha = if (headline == null && header == null) {
                                        1f
                                    } else {
                                        handoffProgress(viewportTop, headlineTop, headlineHeight)
                                    }
                                    translationY = (1f - alpha) * size.height * 0.5f
                                },
                            )
                        },
                        navigationIcon = {
                            if (close) SyncCloseButton(onBack) else SettingsBackButton(onClick = onBack)
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
                snackbarHost = { YoinSnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (footer != null) {
                        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            content = footer,
                        )
                    }
                },
            ) { innerPadding ->
                val navBottom = if (footer == null) {
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                } else {
                    0.dp
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .onGloballyPositioned { viewportTop.floatValue = it.positionInWindow().y }
                        .seamDissolveViewport(top = SeamTop.Chrome) { scroll.value.toFloat() }
                        .verticalScroll(scroll)
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + navBottom),
                ) {
                    if (header != null) {
                        header(titleModifier)
                    } else if (headline != null || crumb != null) {
                        SyncHeadline(crumb = crumb, headline = headline, lead = lead, titleModifier = titleModifier)
                    }
                    content()
                }
            }
        }
    }
}

/** The page header: crumb, large title on the 24dp line, lead; 36dp of air under it. */
@Composable
internal fun SyncHeadline(
    crumb: String?,
    headline: String?,
    lead: String?,
    titleModifier: Modifier = Modifier,
    small: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(if (small) 16.dp else 32.dp))
        if (crumb != null) {
            Text(
                text = crumb,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (headline != null) {
            Text(
                text = headline,
                style = if (small) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = titleModifier
                    .padding(horizontal = 8.dp)
                    .semantics { heading() },
            )
        }
        if (lead != null) {
            Text(
                text = lead,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
            )
        }
        Spacer(Modifier.height(if (small) 24.dp else 36.dp))
    }
}

@Composable
private fun SyncCloseButton(onClick: () -> Unit) {
    val haptics = rememberYoinHaptics()
    FilledTonalIconButton(
        onClick = {
            haptics.performClick()
            onClick()
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        modifier = Modifier
            .padding(start = 20.dp, end = 14.dp)
            .handPointer(),
    ) {
        Icon(YoinSymbols.Close, contentDescription = stringResource(R.string.settings_xfer_close))
    }
}

/** Space between a page's groups. */
internal val SyncGroupGap = 28.dp

enum class SyncPillTone { Neutral, Primary, Muted, Accent }

/** A small status pill (仅在云端, 当前, 即将支持, 新…). */
@Composable
internal fun SyncPill(text: String, tone: SyncPillTone = SyncPillTone.Neutral, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        SyncPillTone.Neutral -> colors.secondaryContainer to colors.onSecondaryContainer
        SyncPillTone.Primary -> colors.primaryContainer to colors.onPrimaryContainer
        SyncPillTone.Muted -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        SyncPillTone.Accent -> colors.primary to colors.onPrimary
    }
    Box(
        modifier = modifier
            .background(container, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** A row title with a pill after it, wrapping under it when the name is long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TitleWithPill(
    title: String,
    pill: (@Composable () -> Unit)?,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = style, color = color)
        pill?.invoke()
    }
}

/** A sentence under a group: small icon and muted text (footer notes, read-only hints). */
@Composable
internal fun SyncNote(icon: ImageVector, text: String, modifier: Modifier = Modifier, centered: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            modifier = if (centered) Modifier else Modifier.weight(1f),
        )
    }
}

/** A service's identity (glyph, hue, name) for [provider], if Yoin knows the service. */
internal fun identityOf(provider: String): ServiceIdentity? = ProviderKind.fromKey(provider)?.serviceIdentity

/** The service line under an account name: service (unless the name says it), hint, "from <device>". */
@Composable
internal fun accountLineGroups(provider: String, name: String, hint: String?, fromDevice: String?): List<MetaGroup> = buildList {
    val identity = identityOf(provider)
    val service = identity?.let { serviceLineGroups(stringResource(it.nameRes), name, hint) }
    if (service != null) {
        addAll(service)
    } else {
        hint?.takeIf { it.isNotBlank() }?.let { add(MetaGroup.Plain(it)) }
    }
    fromDevice?.takeIf { it.isNotBlank() }?.let {
        add(MetaGroup.Plain(stringResource(R.string.settings_sync_from_device, it), muted = true))
    }
}

/** The tone-circled service glyph of an account row. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountGlyph(provider: String, size: Dp = 40.dp) {
    val identity = identityOf(provider)
    if (size == 40.dp) {
        SettingsRowIcon(identity?.glyph ?: YoinSymbols.Person, tone = identity?.hue?.tone())
        return
    }
    val tone = identity?.hue?.tone()
    Box(
        modifier = Modifier
            .size(size)
            .background(tone?.iconContainer ?: MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie6Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            identity?.glyph ?: YoinSymbols.Person,
            contentDescription = null,
            tint = tone?.iconContent ?: MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** An account's counts as meta groups ("128 notes", "43 album ratings & reviews"…), the empty ones left out. */
@Composable
internal fun scopeStatGroups(counts: CloudScopeCounts): List<MetaGroup> = buildList {
    if (counts.notes > 0) add(stat(counts.notes, R.plurals.settings_sync_unit_notes))
    if (counts.albums > 0) add(stat(counts.albums, R.plurals.settings_sync_unit_albums))
    if (counts.songRatings > 0) add(stat(counts.songRatings, R.plurals.settings_sync_unit_song_ratings))
    if (counts.memoryTitles > 0) add(stat(counts.memoryTitles, R.plurals.settings_sync_unit_titles))
}

/** "128" + its unit, the unit from a plurals resource. */
@Composable
internal fun stat(count: Int, unitPlural: Int): MetaGroup.Stat =
    MetaGroup.Stat(formatCount(count), pluralStringResource(unitPlural, count))

internal fun formatCount(count: Int): String = String.format(Locale.getDefault(), "%,d", count)

/** A size as value + unit (KB under a megabyte, else MB with one decimal). */
@Composable
internal fun sizeStat(bytes: Long): MetaGroup.Stat {
    val kb = bytes / 1024.0
    return if (kb < 1024) {
        MetaGroup.Stat(formatCount(kotlin.math.ceil(kb).toInt().coerceAtLeast(if (bytes > 0) 1 else 0)), stringResource(R.string.settings_sync_unit_kb))
    } else {
        MetaGroup.Stat(String.format(Locale.getDefault(), "%.1f", kb / 1024.0), stringResource(R.string.settings_sync_unit_mb))
    }
}

/** "a, b and c" in the UI language. */
@Composable
internal fun joinedList(parts: List<String>): String {
    if (parts.isEmpty()) return ""
    if (parts.size == 1) return parts.first()
    val separator = stringResource(R.string.settings_sync_list_separator)
    return stringResource(R.string.settings_sync_list_last, parts.dropLast(1).joinToString(separator), parts.last())
}

/** "128 notes, 43 album ratings & reviews and 89 song ratings" for a removal's confirmation. */
@Composable
internal fun countsSentence(counts: CloudScopeCounts?): String {
    if (counts == null || counts.total == 0) return stringResource(R.string.settings_sync_remove_all_data)
    val parts = buildList {
        if (counts.notes > 0) add(pluralStringResource(R.plurals.settings_sync_count_notes, counts.notes, counts.notes))
        if (counts.albums > 0) add(pluralStringResource(R.plurals.settings_sync_count_albums, counts.albums, counts.albums))
        if (counts.songRatings > 0) {
            add(pluralStringResource(R.plurals.settings_sync_count_song_ratings, counts.songRatings, counts.songRatings))
        }
        if (counts.memoryTitles > 0) {
            add(pluralStringResource(R.plurals.settings_sync_count_titles, counts.memoryTitles, counts.memoryTitles))
        }
    }
    return joinedList(parts)
}

/**
 * Work under way: a determinate (when [progress] is known) or running Expressive loading indicator on a 9-sided
 * cookie, an optional big count, the wavy bar and a caption.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ProgressHero(
    progress: Float?,
    modifier: Modifier = Modifier,
    count: String? = null,
    caption: (@Composable () -> Unit)? = null,
    belowCaption: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val animated by animateFloatAsState(progress ?: 0f, YoinMotion.spatialSpring(), label = "syncProgress")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            if (progress != null) {
                LoadingIndicator(
                    progress = { animated },
                    modifier = Modifier.size(72.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                LoadingIndicator(modifier = Modifier.size(72.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (count != null) {
            Text(text = count, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (progress != null) {
            LinearWavyProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        caption?.invoke()
        belowCaption?.invoke(this)
    }
}

/** A finished step: a soft burst that springs in with its icon, the title and a line under it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OutcomeHero(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val appear = remember { Animatable(0f) }
    val spring = YoinMotion.defaultSpatialSpec<Float>(role = YoinMotionRole.Expressive)
    LaunchedEffect(Unit) { appear.animateTo(1f, spring) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer {
                    scaleX = appear.value
                    scaleY = appear.value
                    rotationZ = (1f - appear.value) * -60f
                }
                .background(
                    if (failed) colors.errorContainer else colors.primaryContainer,
                    (if (failed) MaterialShapes.Cookie4Sided else MaterialShapes.SoftBurst).toShape(),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (failed) colors.onErrorContainer else colors.onPrimaryContainer,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        extra?.invoke(this)
    }
}

/** A flow's pinned buttons: an optional text button, then the main one filling the row (with a count). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RowScope.FlowButtons(
    primary: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    badge: Int? = null,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    val haptics = rememberYoinHaptics()
    if (secondary != null) {
        TextButton(onClick = onSecondary, modifier = Modifier.height(56.dp).handPointer()) { Text(secondary) }
    }
    Button(
        onClick = {
            haptics.performConfirm()
            onPrimary()
        },
        enabled = primaryEnabled,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .handPointer(enabled = primaryEnabled),
    ) {
        Text(primary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) { Text(formatCount(badge), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** A pill search field. */
@Composable
internal fun SyncSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(YoinSymbols.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                androidx.compose.material3.IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp).handPointer(),
                ) {
                    Icon(
                        YoinSymbols.Close,
                        contentDescription = stringResource(R.string.settings_xfer_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** A row of tabs with counts, scrolling sideways when they don't fit. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> SyncTabs(tabs: List<Triple<T, String, Int>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { (key, label, count) ->
            ToggleButton(
                checked = key == selected,
                onCheckedChange = {
                    haptics.performSegmentTick()
                    onSelect(key)
                },
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier
                    .semantics { role = Role.Tab }
                    .handPointer(),
            ) {
                Text(label, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(formatCount(count), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** The tinted colour a selectable row takes when chosen (radio rows in the flows). */
@Composable
internal fun selectedRowColor(selected: Boolean): Color {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = YoinMotion.effectsSpring(),
        label = "syncPickRow",
    )
    return color
}
