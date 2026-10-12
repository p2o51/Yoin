package com.gpo.yoin.ui.component

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveBackdropArtworkTest {

    @get:Rule
    val rule = createComposeRule()

    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null

    @After
    fun restore() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
    }

    @Test
    fun should_keepCoverVisibleAndReuseItsPalette_when_extractionIsEnabledAfterDisplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = "https://images.example/backdrop-${System.nanoTime()}.png"
        val reads = AtomicInteger(0)
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color(0xFF2255AA).toArgb())
        }.asImage()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    reads.incrementAndGet()
                    SuccessResult(cover, chain.request, DataSource.NETWORK)
                }
            )
        }.build().also(SingletonImageLoader::setUnsafe)
        var extract by mutableStateOf(false)
        rule.setContent {
            YoinTheme {
                ExpressiveBackdropArtwork(
                    model = model,
                    contentDescription = "Cover",
                    variant = ExpressiveBackdropVariant.Bun,
                    modifier = Modifier.size(120.dp),
                    shape = RectangleShape,
                    fallbackIcon = YoinSymbols.Album,
                    extractBackdropColors = extract
                )
            }
        }
        rule.waitUntil(5_000) { reads.get() == 1 }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Cover").assertIsDisplayed()
        assertNull(cachedBackdropColors(model))

        rule.runOnIdle { extract = true }
        rule.waitForIdle()
        rule.waitUntil(5_000) { reads.get() >= 2 }
        rule.waitUntil(5_000) { cachedBackdropColors(model) != null }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Cover").assertIsDisplayed()
        val palette = cachedBackdropColors(model)
        assertNotNull(palette)
        assertTrue(palette!!.isResolvedFromPalette)
        assertEquals(2, reads.get())

        rule.runOnIdle { extract = false }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Cover").assertIsDisplayed()
        assertEquals(palette, cachedBackdropColors(model))
        rule.runOnIdle { extract = true }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Cover").assertIsDisplayed()
        assertEquals(palette, cachedBackdropColors(model))
        assertEquals(2, reads.get())
    }
}
