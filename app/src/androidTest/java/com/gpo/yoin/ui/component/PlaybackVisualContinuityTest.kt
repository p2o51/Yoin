package com.gpo.yoin.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinLightColorScheme
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(coil3.annotation.DelicateCoilApi::class)
class PlaybackVisualContinuityTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun should_keepTintOutsideImage_whenIncomingArtworkIsTranslucent() {
        rule.setContent {
            Column {
                for (tinted in listOf(false, true)) {
                    val halftone = remember { ArtworkHalftone() }
                    Canvas(Modifier.size(176.dp).testTag(if (tinted) "tinted" else "plain")) {
                        drawRect(Color.Red)
                        halftone.draw(
                            this,
                            .5f,
                            1,
                            if (tinted) Color.Cyan else Color.Transparent,
                            if (tinted) Color.Magenta else Color.Transparent
                        ) { drawRect(Color.Blue, alpha = .8f) }
                    }
                }
            }
        }
        val plain = rule.onNodeWithTag("plain").captureToImage().toPixelMap()
        val tinted = rule.onNodeWithTag("tinted").captureToImage().toPixelMap()
        var imagePixels = 0
        var stainedPixels = 0
        for (y in 1 until plain.height - 1) for (x in 1 until plain.width - 1) {
            val reference = plain[x, y]
            // Only inspect fully covered image pixels, including joined circles
            // inside the moving band, away from the antialiased mask boundary.
            if (reference.blue in 0.798f..0.802f && reference.red in 0.198f..0.202f && reference.green < .002f) {
                imagePixels++
                val actual = tinted[x, y]
                if (kotlin.math.abs(actual.red - reference.red) > .012f ||
                    kotlin.math.abs(actual.green - reference.green) > .012f ||
                    kotlin.math.abs(actual.blue - reference.blue) > .012f
                ) {
                    stainedPixels++
                }
            }
        }
        assertTrue("Must inspect the developed image, including its moving front", imagePixels > 1_000)
        assertEquals("Palette light must not leave a second edge inside the image", 0, stainedPixels)
    }

    @Test
    fun should_keepRevealedRegionSolid_whenHalftoneFrontTravelsInEitherDirection() {
        var direction by mutableStateOf(1)
        var progress by mutableStateOf(.7f)
        rule.setContent {
            val halftone = remember { ArtworkHalftone() }
            Canvas(Modifier.size(240.dp).testTag("artwork")) {
                drawRect(Color.Red)
                halftone.draw(this, progress, direction, Color.Cyan, Color.Magenta) {
                    drawRect(Color.Blue)
                }
            }
        }
        for (travelDirection in listOf(1, -1)) {
            for (amount in listOf(.7f, .99f)) {
                rule.runOnIdle {
                    direction = travelDirection
                    progress = amount
                }
                val pixels = rule.onNodeWithTag("artwork").captureToImage().toPixelMap()
                val left = if (amount > .9f || direction < 0) 1 else pixels.width * 9 / 10
                val right = if (amount > .9f || direction > 0) pixels.width - 1 else pixels.width / 10
                var seams = 0
                for (y in 1 until pixels.height - 1) for (x in left until right) {
                    val color = pixels[x, y]
                    if (color.blue < .99f || color.red > .01f || color.green > .01f) seams++
                }
                assertEquals("The solid region must have no row seams or old-cover pinholes", 0, seams)
            }
        }
    }

    @Test
    fun should_keepWaveAligned_whenDetailPillMountsAfterShell() {
        var detail by mutableStateOf(false)
        var shell by mutableStateOf(true)
        var playing by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val wave = remember { PlaybackWaveState() }
            CompositionLocalProvider(LocalPlaybackWaveState provides wave) {
                YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                    Column {
                        listOfNotNull("shell".takeIf { shell }, "detail".takeIf { detail }).forEach { tag ->
                            NowPlayingPill(
                                currentTrackId = "track", currentTrackTitle = "", currentTrackArtist = "",
                                currentTrackCoverArtUrl = null, connectionErrorMessage = null,
                                playbackProgress = 0.65f, isPlaying = playing, onClick = {},
                                modifier = Modifier.width(240.dp).height(48.dp).testTag(tag)
                            )
                        }
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(752)
        rule.runOnIdle { detail = true }
        rule.mainClock.advanceTimeBy(32)
        assertWavesMatch()
        rule.runOnIdle {
            detail = false
            playing = false
        }
        rule.mainClock.advanceTimeBy(64)
        rule.runOnIdle { detail = true }
        rule.mainClock.advanceTimeBy(32)
        assertWavesMatch()
        rule.runOnIdle {
            playing = true
            shell = false
        }
        rule.mainClock.advanceTimeBy(80)
        rule.runOnIdle { shell = true }
        rule.mainClock.advanceTimeBy(32)
        assertWavesMatch()
    }

    private fun assertWavesMatch() {
        val shell = rule.onNodeWithTag("shell").captureToImage().toPixelMap()
        val incoming = rule.onNodeWithTag("detail").captureToImage().toPixelMap()
        var mismatches = 0
        // Compare the wave front only, away from artwork, text and rounded corners.
        for (y in shell.height / 4 until shell.height * 3 / 4) {
            for (x in shell.width * 3 / 5 until shell.width * 7 / 10) {
                if (shell[x, y].toArgb() != incoming[x, y].toArgb()) mismatches++
            }
        }
        assertEquals("Incoming and returning pills must draw the same wave", 0, mismatches)
    }

    @Test
    fun should_keepArtworkOpaque_whenDissolveIsInterruptedByAnotherSlowCover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLoader = SingletonImageLoader.get(context)
        val thirdReady = CompletableDeferred<Unit>()
        val loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    val color = when (chain.request.data) {
                        "red" -> Color.Red
                        "blue" -> Color.Blue
                        else -> {
                            thirdReady.await()
                            Color.Green
                        }
                    }
                    SuccessResult(ColorImage(color.toArgb()), chain.request, DataSource.NETWORK)
                }
            )
        }.build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            var model by mutableStateOf("red")
            rule.mainClock.autoAdvance = false
            rule.setContent {
                YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                    ExpressiveMediaArtwork(
                        model = model,
                        contentDescription = "Cover",
                        fallbackIcon = YoinSymbols.MusicNote,
                        modifier = Modifier.size(240.dp).testTag("artwork"),
                        reveal = ArtworkReveal.DotDissolve
                    )
                }
            }
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeByFrame()
                artworkColor().let { it.red > .99f && it.green < .01f }
            }
            rule.mainClock.advanceTimeBy(1_000)
            rule.runOnIdle { model = "blue" }
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeByFrame()
                coverPixelCounts().second > 50
            }
            val partial = coverPixelCounts("dissolve-midway")
            assertTrue("Must inspect a real mixed frame", partial.first > 50 && partial.second > 50)
            rule.runOnIdle { model = "green" }
            rule.mainClock.advanceTimeBy(32)
            val held = coverPixelCounts("dissolve-interrupted")
            assertTrue("Both images must survive an interrupted reveal", held.first > 50 && held.second > 50)
            repeat(5) {
                rule.mainClock.advanceTimeBy(64)
                assertEquals("Waiting for a cover must not expose the pale container", 0, coverPixelCounts().third)
            }
            thirdReady.complete(Unit)
            rule.waitUntil(5_000) {
                rule.mainClock.advanceTimeBy(32)
                artworkColor().let { it.green > .99f && it.red < .01f }
            }
            rule.mainClock.advanceTimeBy(1_000)
            coverPixelCounts("dissolve-complete")
        } finally {
            thirdReady.complete(Unit)
            SingletonImageLoader.setUnsafe(originalLoader)
            loader.shutdown()
        }
    }

    @Test
    fun should_drawCachedTwinOpaque_whenDetailCoverMounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLoader = SingletonImageLoader.get(context)
        val loader = ImageLoader.Builder(context).build()
        val file = File(context.cacheDir, "handoff-cover.png")
        file.outputStream().use {
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.RED)
            }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val model = file.toURI().toString()
        runBlocking {
            assertTrue(
                loader.execute(
                    ImageRequest.Builder(context).data(model).size(96, 96).memoryCacheKey("$model#96").build()
                ) is SuccessResult
            )
        }
        // Only the memory cache can now supply the twin's first frame.
        file.delete()
        SingletonImageLoader.setUnsafe(loader)
        try {
            rule.mainClock.autoAdvance = false
            rule.setContent {
                YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                    ExpressiveMediaArtwork(
                        model = model,
                        contentDescription = "Twin",
                        fallbackIcon = YoinSymbols.MusicNote,
                        modifier = Modifier.size(34.dp).testTag("artwork"),
                        requestSizePx = 96
                    )
                }
            }
            rule.mainClock.advanceTimeByFrame()
            assertTrue(
                "A cached twin must be opaque on arrival",
                artworkColor().let { it.red > .99f && it.green < .01f }
            )
        } finally {
            SingletonImageLoader.setUnsafe(originalLoader)
            loader.shutdown()
        }
    }

    private fun coverPixelCounts(name: String? = null): Triple<Int, Int, Int> {
        val image = rule.onNodeWithTag("artwork").captureToImage()
        if (name != null) {
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            File(directory, "$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        val pixels = image.toPixelMap()
        var red = 0
        var blue = 0
        var pale = 0
        for (y in pixels.height / 5 until pixels.height * 4 / 5) {
            for (x in pixels.width / 5 until pixels.width * 4 / 5) {
                val c = pixels[x, y]
                if (c.red > .99f && c.blue < .01f && c.green < .01f) red++
                // The new cover now develops softly over the opaque old one.
                // Blue contribution identifies it before full opacity is reached.
                if (c.blue > .1f && c.green < .05f) blue++
                if (minOf(c.red, c.green, c.blue) > .8f) pale++
            }
        }
        return Triple(red, blue, pale)
    }

    @Test
    fun should_retainVisibleArtwork_whenOldCoverIsEvictedAndNewCoverIsSlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLoader = SingletonImageLoader.get(context)
        val oldRequests = AtomicInteger()
        val newRequests = AtomicInteger()
        val newCoverReady = CompletableDeferred<Unit>()
        val oldReloadReady = CompletableDeferred<Unit>()
        val loader = ImageLoader.Builder(context)
            .memoryCache(null)
            .diskCache(null)
            .components {
                add(
                    Interceptor { chain ->
                        val isOld = chain.request.data == "old-cover"
                        if (isOld) {
                            if (oldRequests.incrementAndGet() > 1) oldReloadReady.await()
                        } else {
                            newRequests.incrementAndGet()
                            newCoverReady.await()
                        }
                        SuccessResult(
                            image = ColorImage(if (isOld) Color.Red.toArgb() else Color.Blue.toArgb()),
                            request = chain.request,
                            dataSource = DataSource.NETWORK
                        )
                    }
                )
            }
            .build()
        SingletonImageLoader.setUnsafe(loader)
        try {
            var model by mutableStateOf("old-cover")
            rule.setContent {
                YoinTheme(colorSchemeOverride = YoinLightColorScheme) {
                    ExpressiveMediaArtwork(
                        model = model,
                        contentDescription = "Cover",
                        fallbackIcon = YoinSymbols.MusicNote,
                        modifier = Modifier.size(160.dp).testTag("artwork")
                    )
                }
            }
            rule.waitUntil(5_000) { artworkColor().let { it.red > .99f && it.green < .01f } }
            rule.mainClock.advanceTimeBy(2_000)
            rule.mainClock.autoAdvance = false
            rule.runOnIdle { model = "new-cover" }
            rule.mainClock.advanceTimeBy(32)
            rule.waitUntil(5_000) { newRequests.get() > 0 }
            val held = artworkColor()
            assertTrue(
                "Old artwork must remain opaque during a cache miss: $held",
                held.red > .99f && held.green < .01f
            )
            assertEquals("Keeping artwork must not re-fetch its URL", 1, oldRequests.get())
            // A -> slow B -> A: matching the old URL is not proof that the
            // new foreground request can already draw it (the cache is empty).
            rule.runOnIdle { model = "old-cover" }
            rule.mainClock.advanceTimeBy(32)
            rule.waitUntil(5_000) { oldRequests.get() > 1 }
            val returned = artworkColor()
            assertTrue("Returning to held artwork must not blank", returned.red > .99f && returned.green < .01f)
            oldReloadReady.complete(Unit)
            rule.runOnIdle { model = "new-cover" }
            newCoverReady.complete(Unit)
            rule.mainClock.autoAdvance = true
            rule.waitUntil(5_000) { artworkColor().let { it.blue > .99f && it.red < .01f } }
        } finally {
            oldReloadReady.complete(Unit)
            newCoverReady.complete(Unit)
            SingletonImageLoader.setUnsafe(originalLoader)
            loader.shutdown()
        }
    }

    private fun artworkColor(): Color {
        val pixels = rule.onNodeWithTag("artwork").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }
}
