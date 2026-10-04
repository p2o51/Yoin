package com.gpo.yoin.data.sync.adapters

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.ui.component.SeamTopPreference
import com.gpo.yoin.ui.component.SeamTopStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SharedPrefsSeamStyleGatewayTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gateway = SharedPrefsSeamStyleGateway(context)

    @After
    fun tearDown() {
        SeamTopPreference.preview(null)
    }

    @Test
    fun should_storeAndShowStyle_when_knownKeyApplied() {
        assertNull(gateway.storedKey())

        assertTrue(gateway.apply("dots"))

        assertEquals("dots", gateway.storedKey())
        assertEquals(SeamTopStyle.Dots, SeamTopPreference.style)
    }

    @Test
    fun should_storeAtOnceAndShowOnMainThread_when_appliedFromBackground() {
        val worker = Thread { gateway.apply("cookie") }
        worker.start()
        worker.join()

        assertEquals("cookie", gateway.storedKey())
        ShadowLooper.idleMainLooper()
        assertEquals(SeamTopStyle.Cookie, SeamTopPreference.style)
    }

    @Test
    fun should_keepUsersPick_when_madeBeforeBackgroundApplyIsShown() {
        val worker = Thread { gateway.apply("cookie") }
        worker.start()
        worker.join()

        // The user picks Dots in Settings before the main thread runs the posted repaint.
        SeamTopPreference.select(context, SeamTopStyle.Dots)
        ShadowLooper.idleMainLooper()

        assertEquals(SeamTopStyle.Dots, SeamTopPreference.style)
        assertEquals("dots", gateway.storedKey())
    }

    @Test
    fun should_refuse_when_keyUnknown() {
        assertFalse(gateway.apply("lava"))
        assertNull(gateway.storedKey())
    }
}
