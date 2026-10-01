package com.gpo.yoin.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BottomBarShadowHandoffTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_keepOneShadow_whenTransparentDetailMountsAndReturns() {
        val registry = BottomBarShadowRegistry().also { activeRegistry = it }
        var detail by mutableStateOf(false)
        var nested by mutableStateOf(false)
        var sourceBar by mutableStateOf(true)
        rule.setContent {
            YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("scene")) {
                    ProvideBottomBarShadowHost(registry) {
                        if (sourceBar) FloatingBottomBar(
                            modifier = Modifier.align(Alignment.Center),
                        ) { }
                    }
                    if (detail) ProvideBottomBarShadowHost(registry) {
                        FloatingBottomBar(modifier = Modifier.align(Alignment.Center)) { }
                    }
                    if (nested) ProvideBottomBarShadowHost(registry) {
                        FloatingBottomBar(modifier = Modifier.align(Alignment.Center)) { }
                    }
                }
            }
        }
        val single = capture()
        rule.runOnIdle { detail = true }
        assertSameShadow(single, capture())
        rule.runOnIdle { nested = true }
        assertSameShadow(single, capture())
        // Recreating a covered source bar must not move its host above detail.
        rule.runOnIdle { sourceBar = false }
        rule.waitForIdle()
        rule.runOnIdle { sourceBar = true }
        assertSameShadow(single, capture())
        rule.runOnIdle { nested = false }
        assertSameShadow(single, capture())
        rule.runOnIdle { detail = false }
        assertSameShadow(single, capture())
    }

    @Test
    fun should_keepBothShadows_whenHostsOccupySeparatePanes() {
        val registry = BottomBarShadowRegistry().also { activeRegistry = it }
        var second by mutableStateOf(false)
        rule.setContent {
            YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("scene")) {
                    ProvideBottomBarShadowHost(registry) {
                        FloatingBottomBar(modifier = Modifier.width(170.dp).offset(y = 80.dp)) { }
                    }
                    if (second) ProvideBottomBarShadowHost(registry) {
                        FloatingBottomBar(
                            modifier = Modifier.width(170.dp).offset(x = 190.dp, y = 80.dp),
                        ) { }
                    }
                }
            }
        }
        val before = capture().toPixelMap()
        rule.runOnIdle { second = true }
        val after = capture().toPixelMap()
        var retainedShadowPixels = 0
        for (y in 0 until before.height) for (x in 0 until before.width / 3) {
            val color = before[x, y]
            // The gray pixels below/around the left bar are its actual shadow.
            if (color.red < .99f && kotlin.math.abs(color.red - color.green) < .005f &&
                kotlin.math.abs(color.green - color.blue) < .005f
            ) {
                retainedShadowPixels++
                assertTrue("A separate pane must not steal the first shadow", color.toArgb() == after[x, y].toArgb())
            }
        }
        assertTrue("The sample must include a visible shadow", retainedShadowPixels > 20)
    }

    private var captureIndex = 0
    private var activeRegistry: BottomBarShadowRegistry? = null

    private fun capture(): ImageBitmap {
        // A new host takes the shadow over once its window has committed a
        // frame; the bar beneath then fades out on a spring. Compare the
        // settled scene, not the deliberate overlap before it.
        rule.waitUntil(3_000) { activeRegistry?.pendingHostCount == 0 }
        rule.waitForIdle()
        return rule.onNodeWithTag("scene").captureToImage().also { image ->
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory, "shadow-${captureIndex++}.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun assertSameShadow(expected: ImageBitmap, actual: ImageBitmap) {
        val a = expected.toPixelMap()
        val b = actual.toPixelMap()
        // Exclude the filled surface's vertical extent, including its one-pixel
        // antialiased edge. We are comparing the cast shadow, not repeated
        // rasterization of the overlapping opaque rounded-corner fills.
        val surfaceRows = (0 until a.height).filter { y ->
            val color = a[a.width / 2, y]
            kotlin.math.abs(color.red - color.green) > .01f ||
                kotlin.math.abs(color.green - color.blue) > .01f
        }
        assertTrue("The baseline must contain a colored bar", surfaceRows.isNotEmpty())
        val surfaceTop = surfaceRows.first() - 1
        val surfaceBottom = surfaceRows.last() + 1
        var changed = 0
        var shadowPixels = 0
        for (y in 0 until a.height) {
            if (y in surfaceTop..surfaceBottom) continue
            for (x in 0 until a.width) {
                if (a[x, y].toArgb() != b[x, y].toArgb()) changed++
                if (a[x, y].red < .99f) shadowPixels++
            }
        }
        assertTrue("The baseline must include a visible shadow", shadowPixels > 20)
        assertTrue("Shadow must not darken or disappear at handoff ($changed changed pixels)", changed == 0)
    }
}
