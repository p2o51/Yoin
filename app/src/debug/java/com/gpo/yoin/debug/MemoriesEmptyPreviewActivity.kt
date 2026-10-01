package com.gpo.yoin.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.ExpressiveMediaArtwork
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.YoinPageWidths
import com.gpo.yoin.ui.component.rememberExpressiveBackdropColors
import com.gpo.yoin.ui.component.yoinPageContentWidth
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.memories.memoriesAuroraBackground
import com.gpo.yoin.ui.theme.ContinuousRoundedCornerShape
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File

/**
 * Debug-only prototype for the redesigned Memories empty state — NOT shipped.
 * Two variants, picked with a string extra:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesEmptyPreviewActivity --es variant fresh
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesEmptyPreviewActivity --es variant almost
 *
 * `fresh`  = brand-new user, nothing close to eligible: ghost seal mold +
 *            what-this-page-is + the three real gates.
 * `almost` = a near-miss album exists: progress + Go-to-album CTA.
 *
 * Design concept: the page hero is the 空印模 — the MemoryScoreKind.NONE seal
 * geometry (Cookie12Sided outline + halo + slow spin), i.e. the stamp mold
 * waiting for its first stamp. All copy states the REAL eligibility gates
 * (rating coverage ≥60% / album review / ≥2 notes) instead of promising that
 * listening alone surfaces memories.
 */
class MemoriesEmptyPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val variant = intent.getStringExtra("variant") ?: "fresh"
        setContent {
            YoinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MemoriesEmptyPrototype(
                        variant = variant,
                        nearMissCover = swatchCover("littlehouse", 0xFF639922.toInt()),
                    )
                }
            }
        }
    }

    private fun swatchCover(name: String, color: Int): String {
        val file = File(cacheDir, "memempty_$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }
}

@Composable
private fun MemoriesEmptyPrototype(
    variant: String,
    nearMissCover: String,
) {
    // Neutral ambient wash — the Content deck tints from the current memory's
    // cover; with no memories yet it falls back to the quiet theme tones.
    val auroraColors = rememberExpressiveBackdropColors(
        model = null,
        fallbackBaseColor = MaterialTheme.colorScheme.primaryContainer,
        fallbackAccentColor = MaterialTheme.colorScheme.tertiaryContainer,
    )
    ExpressivePageBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .memoriesAuroraBackground(
                        baseColor = auroraColors.baseColor,
                        accentColor = auroraColors.accentColor,
                        visible = true,
                    )
                    .padding(
                        top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 12.dp,
                    ),
            ) {
                PrototypeHeader()
                when (variant) {
                    "almost" -> AlmostBody(nearMissCover = nearMissCover)
                    else -> FreshBody()
                }
            }
            // Same return-to-home hint as the loaded deck.
            Icon(
                imageVector = YoinSymbols.ChevronUp,
                contentDescription = "Back to Home",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .size(28.dp),
            )
        }
    }
}

@Composable
private fun PrototypeHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Memories",
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontFamily = GoogleSansFlex,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // design.md: 页面需要 "Album memories" 副标题说明 v1 实体范围。
        Text(
            text = "Album memories",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Variant `fresh`: 空印模 + 页面定义 + 三条真实门槛。 */
@Composable
private fun ColumnScope.FreshBody() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .yoinPageContentWidth(YoinPageWidths.Card)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GhostSeal()
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "No memories yet",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "This page keeps the albums you've made your own. " +
                "One of these marks turns an album into a memory:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        MemoryGatesCard()
    }
}

@Composable
private fun MemoryGatesCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousRoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GateRow(
                icon = { Icon(YoinSymbols.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                text = "Rate most of an album's songs (60%+)",
            )
            GateRow(
                icon = { Icon(YoinSymbols.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                text = "Write an album review",
            )
            GateRow(
                icon = { Icon(YoinSymbols.EditNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                text = "Save two notes on an album",
            )
            Text(
                text = "Any one is enough — listening alone doesn't write memories.",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GateRow(
    icon: @Composable () -> Unit,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Variant `almost`: 差一步的专辑 + 进度 + CTA。 */
@Composable
private fun ColumnScope.AlmostBody(nearMissCover: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .yoinPageContentWidth(YoinPageWidths.Card)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Closest to a memory",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ContinuousRoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ExpressiveMediaArtwork(
                        model = nearMissCover,
                        contentDescription = "Little House",
                        modifier = Modifier.size(72.dp),
                        shape = YoinArtworkShapes.Cover,
                        fallbackIcon = YoinSymbols.Album,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Little House",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Rachel Chinouriri · 2024",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                LinearWavyProgressIndicator(
                    progress = { 0.5f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Rated 5 of 10 songs — rate 1 more to unlock",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {},
            modifier = Modifier.height(48.dp),
        ) {
            Text(text = "Go to album", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "→", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Or write a review / save two notes on it — any of these stamps it.",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 空印模：与 MemorySeal 的 NONE 态同几何（Cookie12Sided 描边 + 中心光晕 +
 * 60s 慢转），里面不放数字 —— 星标代表「你的评分会落在这里」。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GhostSeal() {
    val sealShape = MaterialShapes.Cookie12Sided.toShape()
    val reduceMotion = LocalMotionProfile.current == MotionProfile.AdaptiveReduced
    val rotation = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "ghostSealSpin")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 60_000, easing = LinearEasing),
            ),
            label = "ghostSealRotation",
        )
        animated
    }
    Box(
        modifier = Modifier.size(148.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(148.dp * 1.7f)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .border(
                    width = 2.dp,
                    // outlineVariant 在深色下几乎隐形；空印模是页面主角，
                    // 需要比卡内 NONE 态更实一档的 outline。
                    color = MaterialTheme.colorScheme.outline,
                    shape = sealShape,
                ),
        )
        Icon(
            imageVector = YoinSymbols.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(44.dp),
        )
    }
}
