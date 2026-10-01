package com.gpo.yoin.ui.navigation.back

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.ui.navigation.rememberActiveOnlySharedContentConfig
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
class OverlaySharedOpeningReferenceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun should_matchOriginalCoverAndText_whenOpeningAutomatically() {
        var expanded by mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Row(Modifier.size(300.dp, 400.dp).background(Color.White)) {
                SharedTransitionLayout(Modifier.size(150.dp, 400.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        OverlayChromeVisibility(
                            expanded,
                            modifier = Modifier.align(Alignment.BottomStart),
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
                        ) { ArtworkAndText(this, player = false) }
                        OverlayPlayerVisibility(expanded, Modifier.fillMaxSize()) {
                            ArtworkAndText(this, player = true)
                        }
                    }
                }
                SharedTransitionLayout(Modifier.size(150.dp, 400.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !expanded,
                            modifier = Modifier.align(Alignment.BottomStart),
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard) +
                                YoinMotion.slideOutVertically(role = YoinMotionRole.Standard) { it },
                        ) { ArtworkAndText(this, player = false) }
                        androidx.compose.animation.AnimatedVisibility(
                            visible = expanded,
                            enter = YoinMotion.slideInVertically(role = YoinMotionRole.Expressive) { it } +
                                YoinMotion.fadeIn(role = YoinMotionRole.Standard),
                            exit = androidx.compose.animation.ExitTransition.None,
                            modifier = Modifier.fillMaxSize(),
                        ) { ArtworkAndText(this, player = true) }
                    }
                }
            }
        }
        rule.runOnIdle { expanded = true }
        repeat(40) { frame ->
            rule.mainClock.advanceTimeByFrame()
            val captured = rule.onRoot().captureToImage()
            val file = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "opening-reference-$frame.png")
            java.io.FileOutputStream(file).use { captured.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val pixels = captured.toPixelMap()
            val width = pixels.width / 2
            var difference = 0.0
            for (y in 0 until pixels.height) for (x in 0 until width) {
                val actual = pixels[x, y]
                val old = pixels[x + width, y]
                difference += (abs(actual.red - old.red) + abs(actual.green - old.green) + abs(actual.blue - old.blue)) / 3
            }
            val meanError = difference / (width * pixels.height)
            assertTrue("Shared cover/text frame $frame differs from original by $meanError", meanError < .01)
        }
    }

    @Composable
    private fun SharedTransitionScope.ArtworkAndText(
        visibility: AnimatedVisibilityScope,
        player: Boolean,
    ) {
        val nativeSpec = if (player) YoinMotion.slowSpatialSpec<Rect>(role = YoinMotionRole.Expressive)
            else YoinMotion.defaultSpatialSpec<Rect>(role = YoinMotionRole.Standard)
        val bounds = nativeSpec
        val config = rememberActiveOnlySharedContentConfig(visibility)
        fun Modifier.shared(state: SharedTransitionScope.SharedContentState): Modifier =
            sharedBounds(state, visibility, boundsTransform = { _, _ -> bounds })
        Column(if (player) Modifier.fillMaxSize().background(Color.LightGray) else Modifier.background(Color.DarkGray)) {
            Box(Modifier.shared(rememberSharedContentState("cover", config))
                .size(if (player) 130.dp else 28.dp).background(Color.Blue))
            if (player) Spacer(Modifier.height(170.dp))
            Text("Track", color = Color.Red, fontSize = if (player) 25.sp else 12.sp,
                modifier = Modifier.shared(rememberSharedContentState("title", config)))
            Text("Artist", color = Color.Black, fontSize = if (player) 18.sp else 10.sp,
                modifier = Modifier.shared(rememberSharedContentState("artist", config)))
        }
    }
}
