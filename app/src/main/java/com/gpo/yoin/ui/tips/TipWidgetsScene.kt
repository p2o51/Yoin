package com.gpo.yoin.ui.tips

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinContainerShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.widget.WidgetArt
import com.gpo.yoin.widget.WidgetBitmaps
import com.gpo.yoin.widget.WidgetEntity
import com.gpo.yoin.widget.lastHeardText
import com.gpo.yoin.widget.memoryAnchorBadge
import com.gpo.yoin.widget.widgetCornerClearance
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The home-screen widgets (parity with iOS, 2026-11-13): a home screen in the little phone, Yoin's icon on it.
 * The dot holds an empty spot, the system's widget list comes up with Yoin's two under the labels and
 * descriptions that list shows, and the dot drags Play, then Memory, onto the screen (a tap on one places it too).
 *
 * The real widgets are Glance layouts drawn in the launcher's process, so they cannot sit in a Compose page.
 * These are the same art (WidgetArt's stack and cover, the anchor's own badge) over the made-up albums, laid out
 * as PlayStack and MemoryAnchor lay their 2×2 out.
 */
@Composable
internal fun HomeScreenWidgetsDemo(playing: Boolean) {
    var listOpen by remember { mutableStateOf(false) }
    var playPlaced by remember { mutableStateOf(false) }
    var memoryPlaced by remember { mutableStateOf(false) }
    val art = rememberDemoWidgetArt()
    DemoPhone(playing = playing, script = {
        // As a launcher does: the list steps aside once the widget is picked up, and it lands where the dot lets go.
        suspend fun dragOnto(slot: String, place: () -> Unit) {
            val to = at(slot)
            drag(to.x, to.y, DragMs) { progress -> if (progress > 0.1f) listOpen = false }
            place()
        }
        listOpen = false
        playPlaced = false
        memoryPlaced = false
        delay(500)
        moveTo(EmptySpotFx, EmptySpotFy)
        hold { listOpen = true }
        // The list is up before the dot goes to it.
        delay(ListRiseMs)
        moveTo("widget-play")
        delay(250)
        dragOnto("slot-play") { playPlaced = true }
        delay(1_000)
        moveTo(EmptySpotFx, EmptySpotFy)
        hold { listOpen = true }
        delay(ListRiseMs)
        moveTo("widget-memory")
        delay(250)
        dragOnto("slot-memory") { memoryPlaced = true }
        lift()
        delay(2_400)
    }) {
        DemoHomeScreen(
            art = art,
            playPlaced = playPlaced,
            memoryPlaced = memoryPlaced,
            onLongPress = { listOpen = true },
        )
        DemoWidgetList(
            open = listOpen,
            art = art,
            onDismiss = { listOpen = false },
            onPickPlay = {
                playPlaced = true
                listOpen = false
            },
            onPickMemory = {
                memoryPlaced = true
                listOpen = false
            },
        )
    }
}

/** A home screen: the wallpaper, room for two 2×2 widgets, and Yoin's icon. A long-press anywhere asks for the list. */
@Composable
private fun DemoHomeScreen(
    art: DemoWidgetArt?,
    playPlaced: Boolean,
    memoryPlaced: Boolean,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // A wallpaper keeps its colour whatever the theme: the light scheme's primary, which a dark scheme
    // carries as inversePrimary.
    val dark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) scheme.inversePrimary else scheme.primary)
            .demoLongPress(onLongPress = onLongPress)
            .padding(start = HomeSidePadding, top = 56.dp, end = HomeSidePadding),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HomeCellGap)) {
            DemoWidgetSlot(visible = playPlaced, modifier = Modifier.weight(1f).demoTarget("slot-play")) {
                DemoPlayWidget(art = art, modifier = Modifier.fillMaxSize())
            }
            DemoWidgetSlot(visible = memoryPlaced, modifier = Modifier.weight(1f).demoTarget("slot-memory")) {
                DemoMemoryWidget(art = art, modifier = Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DemoAppIcon(size = 56.dp)
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                color = if (dark) scheme.onSurface else scheme.onPrimary,
            )
        }
    }
}

/** A 2×2 place on the home screen; the widget dropped on it springs in. */
@Composable
private fun DemoWidgetSlot(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.aspectRatio(1f)) {
        AnimatedVisibility(
            visible = visible,
            enter = YoinMotion.fadeIn(YoinMotionRole.Standard) +
                YoinMotion.scaleIn(YoinMotionRole.Expressive, initialScale = 0.8f),
            exit = YoinMotion.fadeOut(YoinMotionRole.Standard) +
                YoinMotion.scaleOut(YoinMotionRole.Standard, targetScale = 0.8f),
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

/**
 * The system's widget list for Yoin, as a sheet over the home screen: the app, then its two widgets, each under
 * the label and description the list reads from the app. A tap on one puts it on the home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.DemoWidgetList(
    open: Boolean,
    art: DemoWidgetArt?,
    onDismiss: () -> Unit,
    onPickPlay: () -> Unit,
    onPickMemory: () -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.fadeIn(YoinMotionRole.Standard),
        exit = YoinMotion.fadeOut(YoinMotionRole.Standard),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BottomSheetDefaults.ScrimColor)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = YoinMotion.slideInVertically(YoinMotionRole.Expressive) { it },
        exit = YoinMotion.slideOutVertically(YoinMotionRole.Standard) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Surface(
            shape = BottomSheetDefaults.ExpandedShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DemoAppIcon(size = 32.dp)
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DemoWidgetListTile(
                        label = stringResource(R.string.widget_play_label),
                        description = stringResource(R.string.widget_play_description),
                        onClick = onPickPlay,
                        modifier = Modifier.weight(1f).demoTarget("widget-play"),
                    ) {
                        DemoPlayWidget(art = art, modifier = Modifier.fillMaxSize())
                    }
                    DemoWidgetListTile(
                        label = stringResource(R.string.widget_memory_label),
                        description = stringResource(R.string.widget_memory_description),
                        onClick = onPickMemory,
                        modifier = Modifier.weight(1f).demoTarget("widget-memory"),
                    ) {
                        DemoMemoryWidget(art = art, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** One widget in the list: its preview, then its label and description. */
@Composable
private fun DemoWidgetListTile(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = YoinContainerShapes.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(bottom = 8.dp)) { preview() }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Play at 2×2 (PlayWidget's PlayStack): the stack art on the widget's container, the play button in the corner
 * the art leaves clear. Positions are PlayStack's, in its 184 units.
 */
@Composable
private fun DemoPlayWidget(art: DemoWidgetArt?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier.background(scheme.surfaceContainerHigh, RoundedCornerShape(36.dp))) {
        val unit = maxWidth / WidgetUnits
        val button = unit * 52f
        if (art != null) {
            Image(bitmap = art.playStack, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .padding(start = unit * 122f, top = unit * 122f)
                .size(button)
                .background(scheme.primary, RoundedCornerShape(if (button >= 60.dp) 24.dp else 20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = YoinSymbols.PlayFilled,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(button * 0.44f),
            )
        }
    }
}

/**
 * Memory at 2×2 (MemoryWidget's MemoryAnchor): the cover bottom-left, the score badge biting its top-right
 * corner, no container, and the same room left for the launcher's corner clip.
 */
@Composable
private fun DemoMemoryWidget(art: DemoWidgetArt?, modifier: Modifier = Modifier) {
    val clearance = widgetCornerClearance(LocalContext.current)
    Box(modifier = modifier.padding(clearance)) {
        if (art != null) {
            Image(
                bitmap = art.memoryCover,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxSize(120f / WidgetUnits),
            )
            Image(
                bitmap = art.memoryBadge,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd).fillMaxSize(110f / WidgetUnits),
            )
        }
    }
}

/** Yoin's launcher icon as a round launcher shows it: the adaptive icon's two layers, their middle two thirds. */
@Composable
private fun DemoAppIcon(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_yoin_launcher_background),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * AdaptiveLayerScale),
        )
        Image(
            painter = painterResource(R.drawable.ic_yoin_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * AdaptiveLayerScale),
        )
    }
}

/** The two widgets' art for the made-up albums. */
private class DemoWidgetArt(val playStack: ImageBitmap, val memoryCover: ImageBitmap, val memoryBadge: ImageBitmap)

/** Drawn once, off the main thread, by the code that draws the real widgets; null until it is ready. */
@Composable
private fun rememberDemoWidgetArt(): DemoWidgetArt? {
    val context = LocalContext.current
    return produceState<DemoWidgetArt?>(null, context) {
        value = withContext(Dispatchers.Default) {
            val bitmaps = WidgetBitmaps(context)
            val coverPx = bitmaps.px(120f)
            fun cover(album: DemoAlbum): Bitmap? =
                ContextCompat.getDrawable(context, album.cover)?.toBitmap(coverPx, coverPx, Bitmap.Config.ARGB_8888)
            val stack = DemoPlayStack.map { (album, entity) ->
                val image = cover(album)
                WidgetArt.StackItem(entity, image, WidgetArt.palette(image).base)
            }
            val memoryCover = cover(DemoMemoryAlbum)
            val memoryPalette = WidgetArt.palette(memoryCover)
            val now = System.currentTimeMillis()
            DemoWidgetArt(
                playStack = WidgetArt.playStack(
                    sizePx = bitmaps.px(WidgetUnits),
                    items = stack,
                    placeholder = android.graphics.Color.GRAY,
                ).asImageBitmap(),
                memoryCover = WidgetArt.rounded(
                    memoryCover,
                    coverPx,
                    bitmaps.px(4f).toFloat(),
                    memoryPalette.base,
                ).asImageBitmap(),
                memoryBadge = memoryAnchorBadge(
                    sizePx = bitmaps.px(110f),
                    unitPx = bitmaps.scale,
                    palette = memoryPalette,
                    score = DemoMemoryScore,
                    lastHeard = context.lastHeardText(now - TimeUnit.DAYS.toMillis(DemoMemoryDaysAgo), now),
                    title = DemoMemoryAlbum.name,
                ).asImageBitmap(),
            )
        }
    }.value
}

/** Play's three: the album to pick up in front, a playlist and an artist behind it (each its own backdrop). */
private val DemoPlayStack = listOf(
    DemoAlbums[0] to WidgetEntity.ALBUM,
    DemoAlbums[1] to WidgetEntity.PLAYLIST,
    DemoAlbums[2] to WidgetEntity.ARTIST,
)
private val DemoMemoryAlbum = DemoAlbums[3]
private const val DemoMemoryScore = 8.5f
private const val DemoMemoryDaysAgo = 3L

/** The side of a 2×2 widget in the widgets' own design units. */
private const val WidgetUnits = 184f

/** An adaptive icon's layers are 108dp for a 72dp icon. */
private const val AdaptiveLayerScale = 108f / 72f

private val HomeSidePadding = 20.dp
private val HomeCellGap = 16.dp

/** An empty spot on the home screen, beside the icon and clear of the list once it is up; fractions of the phone. */
private const val EmptySpotFx = 0.65f
private const val EmptySpotFy = 0.4f

/** How long the list takes to come up and settle before the dot goes to it. */
private const val ListRiseMs = 450L

/** How long the dot takes to carry a widget from the list to its place. */
private const val DragMs = 700
