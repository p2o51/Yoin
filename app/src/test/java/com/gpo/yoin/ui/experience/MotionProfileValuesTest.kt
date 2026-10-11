@file:Suppress("ktlint:standard:function-naming") // Composables are PascalCase

package com.gpo.yoin.ui.experience

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.gpo.yoin.symbols.LocalSymbolMotion
import com.gpo.yoin.symbols.SymbolMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The motion tier flips at runtime — Home's loading pressure comes and goes
 * on every cold start — so its flip recomposes only what reads it, never the
 * whole app under the root (QA SP-RO-1: a static local recomposed the feed
 * that had just composed, in the frame right after it).
 */
@RunWith(RobolectricTestRunner::class)
class MotionProfileValuesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun should_recomposeOnlyItsReaders_when_theProfileFlips() {
        var profile by mutableStateOf(MotionProfile.Full)
        Probe.reset()
        rule.setContent {
            CompositionLocalProvider(*motionProfileValues(profile)) {
                Unrelated()
                ReadsProfile()
                ReadsSymbolMotion()
            }
        }
        rule.waitForIdle()

        rule.runOnIdle { profile = MotionProfile.AdaptiveReduced }
        rule.waitForIdle()
        rule.runOnIdle { profile = MotionProfile.Full }
        rule.waitForIdle()

        assertEquals(1, Probe.unrelatedCompositions)
        assertEquals(listOf(MotionProfile.Full, MotionProfile.AdaptiveReduced, MotionProfile.Full), Probe.profiles)
        // Yoin Symbols follow the tier (design.md: AdaptiveReduced → Reduced).
        assertEquals(listOf(SymbolMotion.Default, SymbolMotion.Reduced, SymbolMotion.Default), Probe.symbolMotions)
    }
}

/** What the composables below saw, across their compositions. */
private object Probe {
    var unrelatedCompositions = 0
    val profiles = mutableListOf<MotionProfile>()
    val symbolMotions = mutableListOf<SymbolMotion>()

    fun reset() {
        unrelatedCompositions = 0
        profiles.clear()
        symbolMotions.clear()
    }
}

/** Reads neither local: a flip has no reason to compose it again. */
@Composable
private fun Unrelated() {
    Probe.unrelatedCompositions++
}

@Composable
private fun ReadsProfile() {
    Probe.profiles += LocalMotionProfile.current
}

@Composable
private fun ReadsSymbolMotion() {
    Probe.symbolMotions += LocalSymbolMotion.current
}
