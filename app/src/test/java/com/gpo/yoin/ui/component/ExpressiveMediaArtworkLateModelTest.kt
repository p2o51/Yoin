package com.gpo.yoin.ui.component

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A cover whose url arrives after the artwork showed its type icon — Home's
 * snapshot paints before the account's source can resolve its covers —
 * reveals over the icon, as a recovered cover does, instead of cutting from it.
 */
@RunWith(RobolectricTestRunner::class)
class ExpressiveMediaArtworkLateModelTest {

    @get:Rule
    val rule = createComposeRule()

    private val attempts = AtomicInteger(0)
    private var originalLoader: ImageLoader? = null
    private var loader: ImageLoader? = null

    @Before
    fun answerWithACover() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        originalLoader = SingletonImageLoader.get(context)
        loader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(
                Interceptor { chain ->
                    attempts.incrementAndGet()
                    SuccessResult(ColorImage(Color.Red.toArgb()), chain.request, DataSource.NETWORK)
                }
            )
        }.build().also(SingletonImageLoader::setUnsafe)
    }

    @After
    fun restore() {
        originalLoader?.let(SingletonImageLoader::setUnsafe)
        loader?.shutdown()
    }

    private fun fallbackIcon() = rule.onNodeWithTag(ARTWORK_FALLBACK_TAG, useUnmergedTree = true)

    @Test
    fun should_revealOverTheIcon_when_aUrlArrivesAfterNone() {
        var model by mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            YoinTheme {
                ExpressiveMediaArtwork(
                    model = model,
                    contentDescription = "Cover",
                    fallbackIcon = YoinSymbols.Album,
                    modifier = Modifier.size(120.dp)
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        fallbackIcon().assertExists()

        rule.runOnIdle { model = "https://images.example/cover.jpg" }
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        assertEquals(1, attempts.get())
        // The cover comes in over the icon, which stays under it until it has.
        rule.onNodeWithContentDescription("Cover").assertExists()
        fallbackIcon().assertExists()

        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        fallbackIcon().assertDoesNotExist()
        rule.onNodeWithContentDescription("Cover").assertExists()
    }
}
