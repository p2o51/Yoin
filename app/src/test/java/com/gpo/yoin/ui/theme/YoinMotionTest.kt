package com.gpo.yoin.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoinMotionTest {

    @Test
    fun should_route_expressive_default_spatial_to_expressive_scheme() {
        val expressive = RecordingMotionScheme()
        val standard = RecordingMotionScheme()

        YoinMotion.defaultSpatialSpec<Float>(
            role = YoinMotionRole.Expressive,
            expressiveScheme = expressive,
            standardScheme = standard,
        )

        assertEquals(listOf("defaultSpatial"), expressive.calls)
        assertTrue(standard.calls.isEmpty())
    }

    @Test
    fun should_route_standard_fast_effects_to_standard_scheme() {
        val expressive = RecordingMotionScheme()
        val standard = RecordingMotionScheme()

        YoinMotion.fastEffectsSpec<Float>(
            role = YoinMotionRole.Standard,
            expressiveScheme = expressive,
            standardScheme = standard,
        )

        assertTrue(expressive.calls.isEmpty())
        assertEquals(listOf("fastEffects"), standard.calls)
    }

    @Test
    fun reduced_bucket_should_not_change_scene_role() {
        val expressive = RecordingMotionScheme()
        val standard = RecordingMotionScheme()

        YoinMotion.fastSpatialSpec<Float>(
            role = YoinMotionRole.Expressive,
            expressiveScheme = expressive,
            standardScheme = standard,
        )

        assertEquals(listOf("fastSpatial"), expressive.calls)
        assertTrue(standard.calls.isEmpty())
    }

    private class RecordingMotionScheme : MotionScheme {
        val calls = mutableListOf<String>()

        override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> {
            calls += "defaultSpatial"
            return spring()
        }

        override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> {
            calls += "fastSpatial"
            return spring()
        }

        override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> {
            calls += "slowSpatial"
            return spring()
        }

        override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> {
            calls += "defaultEffects"
            return spring()
        }

        override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> {
            calls += "fastEffects"
            return spring()
        }

        override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> {
            calls += "slowEffects"
            return spring()
        }
    }
}
