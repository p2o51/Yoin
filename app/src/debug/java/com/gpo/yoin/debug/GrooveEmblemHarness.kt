package com.gpo.yoin.debug

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.memories.emblem.GrooveAwardMode
import com.gpo.yoin.ui.memories.emblem.GrooveAwardState
import com.gpo.yoin.ui.memories.emblem.GrooveBeat
import com.gpo.yoin.ui.memories.emblem.GrooveEmblem
import com.gpo.yoin.ui.memories.emblem.GrooveHapticRoute
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTrace
import com.gpo.yoin.ui.memories.emblem.GrooveHapticTraceEvent
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveModel
import com.gpo.yoin.ui.memories.emblem.GroovePrimitive
import com.gpo.yoin.ui.memories.emblem.GrooveSamples
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.emblem.LocalHapticTrace
import com.gpo.yoin.ui.memories.emblem.grooveScriptFor
import com.gpo.yoin.ui.memories.emblem.rememberGrooveAwardState
import com.gpo.yoin.ui.memories.emblem.rememberGrooveReducedMotion
import com.gpo.yoin.ui.memories.emblem.rememberGrooveTilt
import com.gpo.yoin.ui.theme.YoinTheme
import kotlinx.coroutines.delay

/*
 * Debug-only harness for the 唱片刻纹 groove emblem (Memories P3). Launched through
 * MemoryCardScreenshotActivity with `--es mode emblem`:
 *
 *   adb shell am start -n com.gpo.yoin/.debug.MemoryCardScreenshotActivity --es mode emblem \
 *       [--es panel gallery|award] [--es set tiers|samples] [--es theme light|dark] [--ez rm true] \
 *       [--es tilt 0.8,-0.4] [--ez sensor true] [--es kind album|average] [--es autoplay first|replay|nod|interrupt]
 *
 * gallery: every tier × kind at 124 / 96 / 72 (cover), 48 (bar), 44 / 40 (cover), on the prototype's own theme
 * tokens so it compares 1:1 with the Chrome render. award: the four tiers of one kind, each with its 48dp diary
 * copy, buttons, and a haptic trace strip (hollow = planned beat, filled = beat as it fired, with its route).
 * The tablet has no vibrator: the trace is the evidence. Every fired beat is also logged under "GrooveTrace".
 */

internal data class GrooveHarnessOptions(
    val panel: String,
    val set: String,
    val dark: Boolean,
    val reducedMotion: Boolean?,
    val tilt: Offset?,
    val sensor: Boolean,
    val kind: GrooveKind,
    val autoplay: String?,
) {
    companion object {
        fun from(intent: Intent): GrooveHarnessOptions = GrooveHarnessOptions(
            panel = intent.getStringExtra("panel") ?: "gallery",
            set = intent.getStringExtra("set") ?: "tiers",
            dark = intent.getStringExtra("theme") == "dark",
            reducedMotion = if (intent.hasExtra("rm")) intent.getBooleanExtra("rm", false) else null,
            tilt = intent.getStringExtra("tilt")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) },
            sensor = intent.getBooleanExtra("sensor", false),
            kind = if (intent.getStringExtra("kind") == "average") GrooveKind.Average else GrooveKind.Album,
            autoplay = intent.getStringExtra("autoplay"),
        )
    }
}

/** The prototype's theme tokens (memories-showcase-v4.html :root / dark), so neutral colours compare 1:1. */
private fun prototypeScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        background = Color(0xFF141218),
        surface = Color(0xFF1D1B20),
        onSurface = Color(0xFFE7E0E8),
        onSurfaceVariant = Color(0xFFCBC4CF),
        outline = Color(0xFF958E99),
        outlineVariant = Color(0xFF4A4550),
        onBackground = Color(0xFFE7E0E8),
    )
} else {
    lightColorScheme(
        background = Color(0xFFF7F2F8),
        surface = Color(0xFFFDF7FD),
        onSurface = Color(0xFF1D1B20),
        onSurfaceVariant = Color(0xFF4A4550),
        outline = Color(0xFF7B7581),
        outlineVariant = Color(0xFFCBC4CF),
        onBackground = Color(0xFF1D1B20),
    )
}

/** Collects trace events per tag; draws them and logs them. */
private class HarnessTrace : GrooveHapticTrace {
    val planned = mutableStateMapOf<String, List<GrooveBeat>>()
    val routes = mutableStateMapOf<String, GrooveHapticRoute>()
    val fired = mutableStateMapOf<String, SnapshotStateList<GrooveHapticTraceEvent>>()

    override fun onRunStart(tag: String, beats: List<GrooveBeat>, route: GrooveHapticRoute) {
        planned[tag] = beats
        routes[tag] = route
        fired.getOrPut(tag) { mutableStateListOf() }.clear()
        Log.i(TAG, "start $tag route=$route plan=" + beats.joinToString { "${it.primitive}@${it.atMs}×${it.scale}" })
    }

    override fun onBeat(event: GrooveHapticTraceEvent) {
        fired.getOrPut(event.tag) { mutableStateListOf() } += event
        val b = event.beat
        Log.i(
            TAG,
            "beat ${event.tag} ${b.primitive}×${b.scale} planned=${b.atMs}ms fired=${event.firedAtMs}ms " +
                "drift=${event.firedAtMs - b.atMs}ms route=${event.route} fallback=${b.fallback}",
        )
    }

    companion object {
        const val TAG = "GrooveTrace"
    }
}

@Composable
internal fun GrooveEmblemHarness(options: GrooveHarnessOptions) {
    val trace = remember { HarnessTrace() }
    YoinTheme(colorSchemeOverride = prototypeScheme(options.dark), darkTheme = options.dark) {
        CompositionLocalProvider(LocalHapticTrace provides trace) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                val reduced = options.reducedMotion ?: rememberGrooveReducedMotion()
                when (options.panel) {
                    "award" -> AwardPanel(options, reduced, trace)
                    else -> GalleryPanel(options, reduced)
                }
            }
        }
    }
}

private val GallerySizes = listOf(
    124 to GrooveSurface.Cover,
    96 to GrooveSurface.Cover,
    72 to GrooveSurface.Cover,
    48 to GrooveSurface.Bar,
    44 to GrooveSurface.Cover,
    40 to GrooveSurface.Cover,
)

private fun galleryRows(set: String): List<Pair<String, GrooveModel>> = if (set == "samples") {
    listOf(
        "m1 album 9.5" to GrooveSamples.M1,
        "m3 avg 7.8" to GrooveSamples.M3,
        "m4 album 10" to GrooveSamples.M4,
        "m2 unrated" to GrooveSamples.M2,
    )
} else {
    listOf(GrooveKind.Album, GrooveKind.Average).flatMap { kind ->
        GrooveSamples.TierScores.mapIndexed { i, score ->
            "${if (kind == GrooveKind.Album) "album" else "avg"} t${i + 1}" to GrooveSamples.tier(score, kind)
        }
    }
}

@Composable
private fun GalleryPanel(options: GrooveHarnessOptions, reduced: Boolean) {
    val sensorTilt = rememberGrooveTilt(active = options.sensor, reducedMotion = reduced)
    val tilt: () -> Offset = when {
        options.tilt != null -> {
            { options.tilt }
        }
        else -> {
            { sensorTilt.offset }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "groove v4 · ${options.set} · ${if (options.dark) "dark" else "light"} · " +
                "tilt ${options.tilt ?: if (options.sensor) "sensor" else "rest"}" +
                if (reduced) " · reduced" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        galleryRows(options.set).forEach { (label, model) ->
            Row(
                modifier = Modifier.height(124.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GallerySizes.forEach { (size, surface) ->
                    GrooveEmblem(
                        model = model,
                        size = size.dp,
                        surface = surface,
                        tilt = tilt,
                        reducedMotion = reduced,
                    )
                }
            }
        }
    }
}

private class TierRow(val tier: Int, val model: GrooveModel, val card: GrooveAwardState, val bar: GrooveAwardState)

@Composable
private fun AwardPanel(options: GrooveHarnessOptions, reduced: Boolean, trace: HarnessTrace) {
    val kindTag = if (options.kind == GrooveKind.Album) "album" else "avg"
    val rows = GrooveSamples.TierScores.mapIndexed { i, score ->
        val model = GrooveSamples.tier(score, options.kind)
        TierRow(
            tier = i + 1,
            model = model,
            card = rememberGrooveAwardState(model, 96.dp, GrooveSurface.Cover, reduced, tag = "t${i + 1}-$kindTag-96"),
            bar = rememberGrooveAwardState(model, 48.dp, GrooveSurface.Bar, reduced, tag = "t${i + 1}-$kindTag-48"),
        )
    }
    LaunchedEffect(options.autoplay, rows) {
        val mode = options.autoplay ?: return@LaunchedEffect
        delay(900)
        rows.forEach { row ->
            val script = grooveScriptFor(row.model, 96.0) ?: return@forEach
            when (mode) {
                "replay" -> row.card.play(GrooveAwardMode.Replay)
                "nod" -> {
                    row.bar.setPending(true)
                    delay(300)
                    row.bar.nod(again = false)
                    delay(900)
                    row.bar.nod(again = true)
                }
                "interrupt" -> {
                    row.card.setPending(true)
                    delay(300)
                    row.card.play(GrooveAwardMode.First)
                    delay(450)
                    row.card.interrupt()
                }
                else -> {
                    row.card.setPending(true)
                    delay(400)
                    row.card.play(GrooveAwardMode.First)
                }
            }
            delay((script.durationS * 1000).toLong() + 700)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "award · $kindTag · ${if (reduced) "reduced motion" else "full motion"} · ${deviceHaptics(
                LocalContext.current,
            )}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row -> AwardRow(row, trace) }
    }
}

@Composable
private fun AwardRow(row: TierRow, trace: HarnessTrace) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GrooveEmblem(row.model, 96.dp, GrooveSurface.Cover, award = row.card)
            GrooveEmblem(row.model, 48.dp, GrooveSurface.Bar, award = row.bar)
            Column {
                Text(
                    "tier ${row.tier} · ${row.model.scoreText} · ${GrooveScriptLabels[row.tier]}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    HarnessButton("Pending") { row.card.setPending(true) }
                    HarnessButton("First") { row.card.play(GrooveAwardMode.First) }
                    HarnessButton("Replay") { row.card.play(GrooveAwardMode.Replay) }
                    HarnessButton("Interrupt") { row.card.interrupt() }
                }
                Row {
                    HarnessButton("48 wait") { row.bar.setPending(true) }
                    HarnessButton("Nod") { row.bar.nod(again = false) }
                    HarnessButton("Nod again") { row.bar.nod(again = true) }
                    HarnessButton("Reset") {
                        row.card.reset()
                        row.bar.reset()
                    }
                }
            }
        }
        val cardTag = "t${row.tier}-${if (row.model.kind == GrooveKind.Album) "album" else "avg"}-96"
        val barTag = cardTag.replace("-96", "-48")
        TraceStrip(cardTag, trace)
        TraceStrip(barTag, trace)
    }
}

private val GrooveScriptLabels = mapOf(1 to "Settle", 2 to "One turn", 3 to "Ring by ring", 4 to "Full marks")

@Composable
private fun HarnessButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

/** Planned beats (hollow, upper lane) and fired beats (filled, lower lane) on a 0–1600ms axis. */
@Composable
private fun TraceStrip(tag: String, trace: HarnessTrace) {
    val planned = trace.planned[tag].orEmpty()
    val fired = trace.fired[tag].orEmpty()
    val route = trace.routes[tag]
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val small = TextStyle(fontSize = 9.sp, color = ink, fontFamily = FontFamily.Monospace)
    Column {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val span = 1600f
            val x = { ms: Number -> size.width * (ms.toFloat() / span).coerceIn(0f, 1f) }
            drawLine(
                ink.copy(alpha = 0.3f),
                Offset(0f, 18.dp.toPx()),
                Offset(size.width, 18.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            listOf(0, 500, 1000, 1500).forEach { ms ->
                drawLine(
                    ink.copy(alpha = 0.3f),
                    Offset(x(ms), 14.dp.toPx()),
                    Offset(x(ms), 22.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                drawText(
                    measurer,
                    if (ms == 0) "0" else "${ms / 1000f}s",
                    Offset(x(ms) + 2.dp.toPx(), 24.dp.toPx()),
                    small,
                )
            }
            planned.forEach { b -> glyph(b, Offset(x(b.atMs), 9.dp.toPx()), ink, filled = false) }
            fired.forEach { e ->
                glyph(
                    e.beat,
                    Offset(x(e.firedAtMs), 18.dp.toPx()),
                    if (e.route == GrooveHapticRoute.Skipped) ink else accent,
                    filled = true,
                )
            }
        }
        Text(
            text = "$tag · ${route ?: "–"} · " + if (fired.isEmpty()) {
                "planned " + planned.joinToString { "${it.primitive.name}@${it.atMs}" }
            } else {
                fired.joinToString {
                    val skip = if (it.route == GrooveHapticRoute.Skipped) " skip" else ""
                    "${it.beat.primitive.name}×${it.beat.scale} ${it.beat.atMs}→${it.firedAtMs}ms$skip"
                }
            },
            style = small,
        )
    }
}

/** groove.js `hx.glyph`: one shape per primitive, bigger for a stronger beat. */
private fun DrawScope.glyph(b: GrooveBeat, at: Offset, color: Color, filled: Boolean) {
    val k = (0.72f + 0.45f * b.scale) * density
    val style = if (filled) Fill else Stroke(width = 1.2f * density)
    when (b.primitive) {
        GroovePrimitive.TICK -> drawCircle(color, 2.6f * k, at, style = style)
        GroovePrimitive.LOW_TICK -> drawCircle(color, 3f * k, at, style = Stroke(width = 1.5f * density))
        GroovePrimitive.CLICK -> drawCircle(color, 4.6f * k, at, style = style)
        GroovePrimitive.THUD -> drawRoundRect(
            color,
            Offset(at.x - 5.2f * k, at.y - 5.2f * k),
            Size(10.4f * k, 10.4f * k),
            CornerRadius(2.2f * k),
            style = style,
        )
        GroovePrimitive.SPIN -> drawArc(
            color,
            -20f,
            300f,
            false,
            Offset(at.x - 5f * k, at.y - 5f * k),
            Size(10f * k, 10f * k),
            style = Stroke(width = 1.7f * density),
        )
        GroovePrimitive.QUICK_RISE -> drawPath(
            Path().apply {
                moveTo(at.x - 6.5f * k, at.y + 4.5f * k)
                lineTo(at.x + 6.5f * k, at.y - 4.5f * k)
                lineTo(at.x + 6.5f * k, at.y + 4.5f * k)
                close()
            },
            color,
            style = style,
        )
    }
}

/** API level, vibrator presence and primitive support: which haptic route this device takes. */
private fun deviceHaptics(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "API ${Build.VERSION.SDK_INT} · View fallback"
    val v = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    val has = v?.hasVibrator() == true
    val all = v != null && v.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
        VibrationEffect.Composition.PRIMITIVE_SPIN,
    )
    return "API ${Build.VERSION.SDK_INT} · vibrator ${if (has) "yes" else "no"} · " +
        "primitives ${if (all) "all" else "not all"}"
}
