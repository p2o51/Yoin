package com.gpo.yoin.ui.nowplaying

import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.component.pointerPressPreview
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * The share sheet (owner 2026-11-25, prototype round 2): 歌词 / 关于 / 笔记 on top (the Now Playing tabs' words
 * and look; it opens on the tab it came from), then 版式 — a live thumbnail of each layout — then the preview,
 * 卡片 / Story, the four tones of the tab's role, 保存 / 分享. Each tab remembers its last layout across
 * opens ([ShareLayoutStore]). At Expanded width the sheet is two panes: the preview large on the left, the
 * controls on the right with larger thumbnails in two rows and Save / Share pinned at the bottom.
 */

// ---------------------------------------------------------------------------
// The layout each tab last used.
// ---------------------------------------------------------------------------

/** The layout each tab was last shared in, per device (out of Room and the sync, like Library's sort). */
internal interface ShareLayoutStore {
    fun layoutFor(tab: ShareTab): ShareLayout?

    fun setLayout(tab: ShareTab, layout: ShareLayout)

    /** Process-local, for previews and tests. */
    class InMemory : ShareLayoutStore {
        private val layouts = HashMap<ShareTab, ShareLayout>()

        override fun layoutFor(tab: ShareTab): ShareLayout? = layouts[tab]

        override fun setLayout(tab: ShareTab, layout: ShareLayout) {
            layouts[tab] = layout
        }
    }
}

internal class SharedPrefsShareLayoutStore(context: Context) : ShareLayoutStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun layoutFor(tab: ShareTab): ShareLayout? {
        val stored = prefs.getString(tab.name, null) ?: return null
        // A layout a later build dropped, or one filed under another tab, reads as none chosen.
        return ShareLayout.entries.firstOrNull { it.name == stored && it.tab == tab }
    }

    override fun setLayout(tab: ShareTab, layout: ShareLayout) {
        if (layout.tab == tab) prefs.edit { putString(tab.name, layout.name) }
    }

    private companion object {
        const val PREFS_NAME = "yoin_share_layouts"
    }
}

/** The window's store; tests and previews pass [ShareLayoutStore.InMemory]. */
internal val LocalShareLayoutStore = staticCompositionLocalOf<ShareLayoutStore?> { null }

@Composable
private fun rememberShareLayoutStore(): ShareLayoutStore {
    LocalShareLayoutStore.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { SharedPrefsShareLayoutStore(context) }
}

// ---------------------------------------------------------------------------
// Opening the sheet from About and Note.
// ---------------------------------------------------------------------------

/** A share asked for from About (a long-pressed row) or a note (its edit card); the lyrics' pick opens its own. */
@Immutable
internal data class ShareRequest(
    val tab: ShareTab,
    val aboutFocus: SongAboutEntry? = null,
    val noteId: String? = null,
)

/** The one open request, held by Now Playing's host. */
@Stable
internal class ShareSheetHost {
    var request: ShareRequest? by mutableStateOf(null)
        private set

    /** The open sheet's tab, format and tone, kept with it across a rotation. */
    val ui = ShareSheetUi()

    /** What the open sheet shares, frozen when it first drew (a rotation must not re-read the playhead). */
    internal var frozen: ShareContent? = null

    fun open(request: ShareRequest) {
        ui.reset()
        frozen = null
        this.request = request
    }

    fun close() {
        request = null
        frozen = null
    }
}

/** An open sheet's own choices: the tab (null = the one it opened on), Card / Story, the tone slot. */
@Stable
internal class ShareSheetUi {
    var tab: ShareTab? by mutableStateOf(null)
    var format: LyricsShareFormat by mutableStateOf(LyricsShareFormat.Card)
    var toneSlot: Int by mutableIntStateOf(0)

    fun reset() {
        tab = null
        format = LyricsShareFormat.Card
        toneSlot = 0
    }
}

/**
 * What must outlive a rotation in Now Playing's sharing, held by the ViewModel: the lyrics pick (per song
 * and lyrics), the pick's open share sheet, and the About / Note sheet.
 */
@Stable
internal class ShareRetained {
    val host = ShareSheetHost()
    val lyricsSheet = LyricsShareSheetState()
    private var selectionKey: Pair<String, List<LyricLine>>? = null
    private var selection: LyricsSelectionState? = null

    /** The pick for [songId]'s [lyrics]: the same one while they stay, a fresh one when they change. */
    fun selectionFor(songId: String, lyrics: List<LyricLine>): LyricsSelectionState {
        val key = songId to lyrics
        selection?.takeIf { selectionKey == key }?.let { return it }
        return LyricsSelectionState().also {
            selection = it
            selectionKey = key
        }
    }
}

/** Now Playing's retained sharing state; null in previews and harnesses (state is then per composition). */
internal val LocalShareRetained = staticCompositionLocalOf<ShareRetained?> { null }

/** Null in previews and harnesses: the About row and note card then offer no Share. */
internal val LocalShareSheetHost = staticCompositionLocalOf<ShareSheetHost?> { null }

/**
 * What Now Playing knows about the song on show, read when a sheet opens (and frozen into its
 * [ShareContent] from then on — the song can change under an open sheet).
 */
@Stable
internal class ShareSources(
    val state: () -> NowPlayingUiState.Playing?,
    val aboutEntries: () -> List<SongAboutEntry>,
    val notes: () -> List<SongNote>,
    val positionMs: () -> Long,
    val aiProvider: () -> AiProvider,
)

internal val LocalShareSources = staticCompositionLocalOf<ShareSources?> { null }

/**
 * Everything shareable for the song on show: [lyrics] (the pick, else the current line), its About
 * (focused on [aboutFocus] when one was long-pressed) and one note ([noteId], else the one at the playhead).
 */
internal fun ShareSources.content(
    resources: Resources,
    lyrics: LyricsSharePick? = null,
    aboutFocus: SongAboutEntry? = null,
    noteId: String? = null,
): ShareContent? {
    val playing = state() ?: return null
    val position = positionMs()
    return ShareContent(
        song = ShareSong(
            songId = playing.songId,
            title = playing.songTitle,
            artist = playing.artist,
            coverArtUrl = playing.coverArtUrl,
            albumName = playing.albumName,
            durationMs = playing.durationMs,
        ),
        lyrics = lyrics ?: currentLyricsPick(playing.lyrics, position, playing.showLyricsTranslation),
        about = aboutSharePick(
            entries = aboutEntries(),
            credits = playing.lyricCredits,
            facts = playing.spineFacts,
            labels = resourceAboutLabels(resources),
            reviewLabel = resources.getString(R.string.np_about_review),
            focus = aboutFocus,
        ),
        note = noteSharePick(notes(), noteId, position),
        aiProvider = aiProvider(),
    )
}

// ---------------------------------------------------------------------------
// The sheet.
// ---------------------------------------------------------------------------

private enum class SaveStatus { Idle, Saving, Saved, Failed }

/**
 * Share as image: the tab ([initialTab] first, when it has something), its layout, Card / Story, a tone,
 * then Save (to Pictures/Yoin) and Share. What the sheet shows IS what gets saved or shared — the preview
 * draws through a recorded graphics layer exported as the PNG at a fixed pixel size. [onKept] runs when the
 * lyrics tab's image was saved or shared (Home's 「歌词一句」 keeps the lines as the listener's pick).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(
    content: ShareContent,
    initialTab: ShareTab,
    onShared: () -> Unit,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onKept: () -> Unit = {},
    ui: ShareSheetUi = remember { ShareSheetUi() },
) {
    val tabs = content.tabs
    if (tabs.isEmpty()) {
        onDismiss()
        return
    }
    val store = rememberShareLayoutStore()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberYoinHaptics()
    val tab = ui.tab?.takeIf { it in tabs } ?: initialTab.takeIf { it in tabs } ?: tabs.first()
    // Each tab's layout: what it last used (when this song offers it), else its first.
    val layouts = remember(content) {
        mutableStateMapOf<ShareTab, ShareLayout>().apply {
            tabs.forEach { t -> content.resolveLayout(t, store.layoutFor(t))?.let { put(t, it) } }
        }
    }
    val layout = layouts[tab] ?: return
    val format = ui.format
    // The tone is a slot (0–3), kept across tabs: the lyrics' fourth is Mist, About and Note's Paper.
    val tone = tab.role.tones()[ui.toneSlot]
    var sharing by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf(SaveStatus.Idle) }
    // One layer per shown image: during a cross-fade both draw, and neither may overwrite the other's recording.
    val layers = remember { mutableMapOf<Pair<ShareLayout, LyricsShareFormat>, GraphicsLayer>() }
    val couldntShare = stringResource(R.string.np_share_failed)
    val canSave = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { window.width.toDp() }
    val windowHeight = with(density) { window.height.toDp() }
    val wide = windowWidth >= WideSheetMinWidth && windowHeight >= WideSheetMinHeight
    val title = stringResource(R.string.np_share_sheet_title)

    val pickTab: (ShareTab) -> Unit = { picked ->
        if (picked != tab) {
            haptics.performSegmentTick()
            ui.tab = picked
            saveStatus = SaveStatus.Idle
        }
    }
    val pickLayout: (ShareLayout) -> Unit = { picked ->
        if (picked != layout) {
            haptics.performSegmentTick()
            layouts[picked.tab] = picked
            store.setLayout(picked.tab, picked)
            saveStatus = SaveStatus.Idle
        }
    }
    val pickFormat: (LyricsShareFormat) -> Unit = { picked ->
        if (picked != format) {
            haptics.performSegmentTick()
            ui.format = picked
            saveStatus = SaveStatus.Idle
        }
    }
    val pickTone: (ShareTone) -> Unit = { picked ->
        val slot = tab.role.tones().indexOf(picked)
        if (slot >= 0 && slot != ui.toneSlot) {
            haptics.performSegmentTick()
            ui.toneSlot = slot
            saveStatus = SaveStatus.Idle
        }
    }
    val keep: () -> Unit = { if (tab == ShareTab.Lyrics) onKept() }
    val save: () -> Unit = save@{
        // Save is only offered from Android 10 (MediaStore without a permission).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@save
        if (saveStatus == SaveStatus.Saving || saveStatus == SaveStatus.Saved) return@save
        val layer = layers[layout to format] ?: return@save
        saveStatus = SaveStatus.Saving
        val name = shareFileName(content.song.title, System.currentTimeMillis(), context.resources)
        scope.launch {
            val saved = layer.exportImage()?.let { saveImageToGallery(context, it, name) } == true
            saveStatus = if (saved) SaveStatus.Saved else SaveStatus.Failed
            if (saved) keep()
            if (saved) haptics.performConfirm() else haptics.performReject()
        }
    }
    val share: () -> Unit = share@{
        if (sharing) return@share
        val layer = layers[layout to format] ?: return@share
        sharing = true
        haptics.performConfirm()
        scope.launch {
            val shared = layer.exportImage()?.let { image ->
                shareImageFile(context, image, caption = lyricsCredit(content.song.title, content.song.artist))
            } == true
            sharing = false
            if (shared) {
                keep()
                onShared()
            } else {
                onMessage(couldntShare)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = if (wide) WideSheetMaxWidth else BottomSheetDefaults.SheetMaxWidth,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = {
            BottomSheetDefaults.modalWindowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        },
    ) {
        ShareSheetBody(
            content = content,
            tabs = tabs,
            tab = tab,
            layout = layout,
            format = format,
            tone = tone,
            layers = layers,
            wide = wide,
            windowHeight = windowHeight,
            navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            title = title,
            canSave = canSave,
            saveStatus = saveStatus,
            sharing = sharing,
            onTab = pickTab,
            onLayout = pickLayout,
            onFormat = pickFormat,
            onTone = pickTone,
            onSave = save,
            onShare = share,
        )
    }
}

/** The sheet's content: one column on a phone, two panes at Expanded width. */
@Composable
private fun ShareSheetBody(
    content: ShareContent,
    tabs: List<ShareTab>,
    tab: ShareTab,
    layout: ShareLayout,
    format: LyricsShareFormat,
    tone: ShareTone,
    layers: MutableMap<Pair<ShareLayout, LyricsShareFormat>, GraphicsLayer>,
    wide: Boolean,
    windowHeight: Dp,
    navBottom: Dp,
    title: String,
    canSave: Boolean,
    saveStatus: SaveStatus,
    sharing: Boolean,
    onTab: (ShareTab) -> Unit,
    onLayout: (ShareLayout) -> Unit,
    onFormat: (LyricsShareFormat) -> Unit,
    onTone: (ShareTone) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val preview: @Composable (maxHeight: Dp, maxScale: Float) -> Unit = { maxHeight, maxScale ->
        SharePreview(
            content = content,
            layout = layout,
            format = format,
            tone = tone,
            layers = layers,
            maxHeight = maxHeight,
            maxScale = maxScale,
        )
    }
    val tabRow: @Composable () -> Unit = { ShareTabs(tabs = tabs, selected = tab, onSelect = onTab) }
    val formatAndTones: @Composable () -> Unit = {
        ShareFormatToggle(selected = format, onSelect = onFormat)
        Spacer(Modifier.height(12.dp))
        ShareToneSwatches(role = tab.role, selected = tone, onSelect = onTone)
    }
    val actions: @Composable () -> Unit = {
        ShareActions(
            canSave = canSave,
            saveStatus = saveStatus,
            sharing = sharing,
            onSave = onSave,
            onShare = onShare,
        )
    }
    if (wide) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(windowHeight * WidePaneHeightFraction)
                .padding(horizontal = 32.dp)
                .padding(bottom = 12.dp + navBottom)
                .semantics { paneTitle = title },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                preview(windowHeight * WidePaneHeightFraction - 24.dp, WidePreviewMaxScale)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                tabRow()
                Spacer(Modifier.height(16.dp))
                LayoutLabel()
                ShareLayoutThumbs(
                    content = content,
                    tab = tab,
                    selected = layout,
                    format = format,
                    tone = tone,
                    onSelect = onLayout,
                    thumbWidth = WideThumbWidth,
                    rows = 2,
                )
                Spacer(Modifier.height(20.dp))
                formatAndTones()
                Spacer(Modifier.weight(1f))
                actions()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp + navBottom)
                .semantics { paneTitle = title },
        ) {
            tabRow()
            Spacer(Modifier.height(12.dp))
            LayoutLabel()
            ShareLayoutThumbs(
                content = content,
                tab = tab,
                selected = layout,
                format = format,
                tone = tone,
                onSelect = onLayout,
                thumbWidth = ThumbWidth,
                rows = 1,
            )
            Spacer(Modifier.height(16.dp))
            preview(windowHeight * PreviewMaxHeightFraction, 1f)
            Spacer(Modifier.height(16.dp))
            formatAndTones()
            Spacer(Modifier.height(16.dp))
            actions()
        }
    }
}

/** The preview: the image itself, drawn through its export layer, rounded only here (the PNG is a rectangle). */
@Composable
private fun SharePreview(
    content: ShareContent,
    layout: ShareLayout,
    format: LyricsShareFormat,
    tone: ShareTone,
    layers: MutableMap<Pair<ShareLayout, LyricsShareFormat>, GraphicsLayer>,
    maxHeight: Dp,
    maxScale: Float,
) {
    val sizeSpec = YoinMotion.defaultSpatialSpec<IntSize>(role = YoinMotionRole.Expressive)
    AnimatedContent(
        targetState = layout to format,
        transitionSpec = {
            (
                YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                    YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                ) using SizeTransform(clip = false) { _, _ -> sizeSpec }
        },
        contentAlignment = Alignment.Center,
        label = "sharePreview",
        // A fixed box for every tab, layout and format (owner device check 2026-11-25: the sheet's height
        // jumped on each switch): the image scales to fit inside it, the sheet never resizes.
        modifier = Modifier
            .fillMaxWidth()
            .height(maxHeight)
            .testTag(SharePreviewBoxTag),
    ) { (shownLayout, shownFormat) ->
        val key = shownLayout to shownFormat
        val layer = rememberGraphicsLayer()
        DisposableEffect(key, layer) {
            layers[key] = layer
            onDispose { if (layers[key] === layer) layers.remove(key) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ShareImage(
                content = content,
                layout = shownLayout,
                format = shownFormat,
                tone = tone,
                exportLayer = layer,
                modifier = Modifier
                    .clip(if (shownFormat == LyricsShareFormat.Story) YoinContainerShapes.Card else YoinContainerShapes.Panel)
                    .scaleDownToFit(maxHeight = maxHeight, maxScale = maxScale)
                    .testTag(SharePreviewTag),
            )
        }
    }
}

/** 歌词 / 关于 / 笔记 as the Now Playing text tabs: the picked one bold and stretched, the others at half. */
@Composable
private fun ShareTabs(tabs: List<ShareTab>, selected: ShareTab, onSelect: (ShareTab) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = tab == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val stretch by animateFloatAsState(
                targetValue = when {
                    pressed -> 0.92f
                    isSelected -> 1.08f
                    else -> 1f
                },
                animationSpec = YoinMotion.defaultSpatialSpec(role = YoinMotionRole.Expressive),
                label = "shareTabStretch",
            )
            val alpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.5f,
                animationSpec = YoinMotion.defaultEffectsSpec(),
                label = "shareTabAlpha",
            )
            Text(
                text = stringResource(tab.label),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(start = if (index == 0) 0.dp else 16.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = stretch
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .pointerPressPreview(interaction)
                    .selectable(
                        selected = isSelected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    )
                    .handPointer()
                    .padding(vertical = 6.dp)
                    .testTag("$ShareTabTagPrefix${tab.name}"),
            )
        }
    }
}

@Composable
private fun LayoutLabel() {
    Text(
        text = stringResource(R.string.np_share_layout),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * 版式: each layout the tab offers as a live thumbnail of the image (in the format and tone picked) with its
 * name; the picked one wears a 2dp primary ring and its name in primary. [rows] > 1 (the wide sheet) lays
 * them out in that many rows; one row scrolls sideways if it must.
 */
@Composable
private fun ShareLayoutThumbs(
    content: ShareContent,
    tab: ShareTab,
    selected: ShareLayout,
    format: LyricsShareFormat,
    tone: ShareTone,
    onSelect: (ShareLayout) -> Unit,
    thumbWidth: Dp,
    rows: Int,
) {
    val offered = content.layoutsFor(tab)
    val perRow = ceil(offered.size / rows.toFloat()).toInt().coerceAtLeast(1)
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        offered.chunked(perRow).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = if (rows == 1) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
            ) {
                row.forEach { layout ->
                    LayoutThumb(
                        content = content,
                        layout = layout,
                        format = format,
                        tone = tone,
                        selected = layout == selected,
                        width = thumbWidth,
                        onClick = { onSelect(layout) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayoutThumb(
    content: ShareContent,
    layout: ShareLayout,
    format: LyricsShareFormat,
    tone: ShareTone,
    selected: Boolean,
    width: Dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ring by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "shareThumbRing",
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "shareThumbLabel",
    )
    val height = width * ThumbAspect
    val name = stringResource(layout.label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width + ThumbRingInset * 2)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .handPointer()
            .semantics { this.selected = selected }
            .testTag("$ShareThumbTagPrefix${layout.name}"),
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .border(2.dp, ring, RoundedCornerShape(ThumbCorner + ThumbRingInset))
                .padding(ThumbRingInset)
                .size(width, height)
                .clip(RoundedCornerShape(ThumbCorner))
                .background(scheme.surfaceContainerHigh),
        ) {
            ShareImage(
                content = content,
                layout = layout,
                format = format,
                tone = tone,
                modifier = Modifier.thumbnail(width, height, story = format == LyricsShareFormat.Story),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The image at its own size, scaled to the thumbnail's width (a card: its top shows) or height (a story: whole). */
private fun Modifier.thumbnail(width: Dp, height: Dp, story: Boolean): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints())
    val boxW = width.roundToPx()
    val boxH = height.roundToPx()
    val scale = if (story) boxH / placeable.height.toFloat() else boxW / placeable.width.toFloat()
    layout(boxW, boxH) {
        placeable.placeWithLayer(((boxW - placeable.width * scale) / 2f).roundToInt(), 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}

@Composable
private fun ShareActions(
    canSave: Boolean,
    saveStatus: SaveStatus,
    sharing: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    val labelSizeSpec = YoinMotion.fastSpatialSpec<IntSize>(role = YoinMotionRole.Standard)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (canSave) {
            FilledTonalButton(
                onClick = onSave,
                enabled = saveStatus != SaveStatus.Saving,
                shape = YoinShapeTokens.Full,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .handPointer(enabled = saveStatus != SaveStatus.Saving),
            ) {
                AnimatedContent(
                    targetState = saveStatus,
                    transitionSpec = {
                        (
                            YoinMotion.fadeIn(role = YoinMotionRole.Standard) togetherWith
                                YoinMotion.fadeOut(role = YoinMotionRole.Standard)
                            ) using SizeTransform(clip = false) { _, _ -> labelSizeSpec }
                    },
                    contentAlignment = Alignment.Center,
                    label = "shareSave",
                ) { status ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (status == SaveStatus.Saved) YoinSymbols.DownloadDone else YoinSymbols.Download,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (status) {
                                SaveStatus.Idle -> stringResource(R.string.np_share_save)
                                SaveStatus.Saving -> stringResource(R.string.np_share_saving)
                                SaveStatus.Saved -> stringResource(R.string.np_share_saved)
                                SaveStatus.Failed -> stringResource(R.string.np_share_try_again)
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
        Button(
            onClick = onShare,
            enabled = !sharing,
            shape = YoinShapeTokens.Full,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .handPointer(enabled = !sharing),
        ) {
            Icon(imageVector = YoinSymbols.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(text = stringResource(R.string.np_share_action), style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal const val SharePreviewTag = "sharePreview"
internal const val SharePreviewBoxTag = "sharePreviewBox"
internal const val ShareTabTagPrefix = "shareTab:"
internal const val ShareThumbTagPrefix = "shareThumb:"

/** On a phone the preview takes at most this share of the window, so the controls stay in reach. */
private const val PreviewMaxHeightFraction = 0.45f

private val ThumbWidth = 70.dp
private val WideThumbWidth = 112.dp
private const val ThumbAspect = 88f / 70f
private val ThumbCorner = 12.dp
private val ThumbRingInset = 4.dp

/** Expanded: a two-pane sheet this wide at most, this share of the window tall; the preview may grow 1.5×. */
private val WideSheetMinWidth = 840.dp
private val WideSheetMinHeight = 480.dp
private val WideSheetMaxWidth = 1040.dp
private const val WidePaneHeightFraction = 0.82f
private const val WidePreviewMaxScale = 1.5f

@Preview(name = "Share sheet thumbs", widthDp = 412)
@Composable
private fun ShareLayoutThumbsPreview() {
    YoinTheme {
        Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(24.dp)) {
            ShareLayoutThumbs(
                content = PreviewShareContent,
                tab = ShareTab.Lyrics,
                selected = ShareLayout.LyricsPoster,
                format = LyricsShareFormat.Card,
                tone = ShareTone.Soft,
                onSelect = {},
                thumbWidth = ThumbWidth,
                rows = 1,
            )
        }
    }
}

@Composable
private fun ShareSheetPreviewFrame(tab: ShareTab, layout: ShareLayout, wide: Boolean, windowHeight: Dp) {
    YoinTheme {
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        ) {
            Column(
                modifier = Modifier
                    .then(if (wide) Modifier.width(WideSheetMaxWidth) else Modifier.fillMaxWidth())
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(top = 22.dp),
            ) {
                CompositionLocalProvider(LocalShareLayoutStore provides ShareLayoutStore.InMemory()) {
                    ShareSheetBody(
                        content = PreviewShareContent,
                        tabs = PreviewShareContent.tabs,
                        tab = tab,
                        layout = layout,
                        format = LyricsShareFormat.Card,
                        tone = ShareTone.Soft,
                        layers = remember { mutableMapOf() },
                        wide = wide,
                        windowHeight = windowHeight,
                        navBottom = 0.dp,
                        title = "",
                        canSave = true,
                        saveStatus = SaveStatus.Idle,
                        sharing = false,
                        onTab = {},
                        onLayout = {},
                        onFormat = {},
                        onTone = {},
                        onSave = {},
                        onShare = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "Share sheet · lyrics (phone)", widthDp = 412, heightDp = 915)
@Composable
private fun ShareSheetLyricsPreview() = ShareSheetPreviewFrame(ShareTab.Lyrics, ShareLayout.LyricsPoster, false, 915.dp)

@Preview(name = "Share sheet · about (phone)", widthDp = 412, heightDp = 915)
@Composable
private fun ShareSheetAboutPreview() = ShareSheetPreviewFrame(ShareTab.About, ShareLayout.AboutReceipt, false, 915.dp)

@Preview(name = "Share sheet · note (phone)", widthDp = 412, heightDp = 915)
@Composable
private fun ShareSheetNotePreview() = ShareSheetPreviewFrame(ShareTab.Note, ShareLayout.NoteClipping, false, 915.dp)

@Preview(name = "Share sheet · tablet landscape", widthDp = 1280, heightDp = 800)
@Composable
private fun ShareSheetWidePreview() = ShareSheetPreviewFrame(ShareTab.Lyrics, ShareLayout.LyricsPlayer, true, 800.dp)

/** A note's Share (its edit card): opens Now Playing's share sheet on 笔记 with that note; null outside Now Playing. */
@Composable
internal fun rememberNoteShare(): ((noteId: String) -> Unit)? {
    val host = LocalShareSheetHost.current ?: return null
    return remember(host) { { noteId -> host.open(ShareRequest(ShareTab.Note, noteId = noteId)) } }
}
