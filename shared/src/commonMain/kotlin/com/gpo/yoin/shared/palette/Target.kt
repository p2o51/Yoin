/*
 * Copyright 2017 The Android Open Source Project
 *
 * Modified for Yoin: androidx/palette/graphics/Target.java (androidx.palette:palette 1.0.0) ported
 * to Kotlin Multiplatform common code (the six default targets; no Builder). Java floats stay Float.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gpo.yoin.shared.palette

/**
 * A class which allows custom selection of colors in a [Palette]'s generation. Identity (not
 * value) equality, as in Java: the six defaults are the keys of the palette's selected swatches.
 */
class Target private constructor() {
    private val saturationTargets = floatArrayOf(0f, 0.5f, 1f)
    private val lightnessTargets = floatArrayOf(0f, 0.5f, 1f)
    private val weights = floatArrayOf(WEIGHT_SATURATION, WEIGHT_LUMA, WEIGHT_POPULATION)

    /** Whether any color selected for this target is exclusive for this target only. */
    val isExclusive: Boolean = true

    val minimumSaturation: Float get() = saturationTargets[INDEX_MIN]
    val targetSaturation: Float get() = saturationTargets[INDEX_TARGET]
    val maximumSaturation: Float get() = saturationTargets[INDEX_MAX]
    val minimumLightness: Float get() = lightnessTargets[INDEX_MIN]
    val targetLightness: Float get() = lightnessTargets[INDEX_TARGET]
    val maximumLightness: Float get() = lightnessTargets[INDEX_MAX]
    val saturationWeight: Float get() = weights[INDEX_WEIGHT_SAT]
    val lightnessWeight: Float get() = weights[INDEX_WEIGHT_LUMA]
    val populationWeight: Float get() = weights[INDEX_WEIGHT_POP]

    private fun setDefaultDarkLightnessValues() {
        lightnessTargets[INDEX_TARGET] = TARGET_DARK_LUMA
        lightnessTargets[INDEX_MAX] = MAX_DARK_LUMA
    }

    private fun setDefaultNormalLightnessValues() {
        lightnessTargets[INDEX_MIN] = MIN_NORMAL_LUMA
        lightnessTargets[INDEX_TARGET] = TARGET_NORMAL_LUMA
        lightnessTargets[INDEX_MAX] = MAX_NORMAL_LUMA
    }

    private fun setDefaultLightLightnessValues() {
        lightnessTargets[INDEX_MIN] = MIN_LIGHT_LUMA
        lightnessTargets[INDEX_TARGET] = TARGET_LIGHT_LUMA
    }

    private fun setDefaultVibrantSaturationValues() {
        saturationTargets[INDEX_MIN] = MIN_VIBRANT_SATURATION
        saturationTargets[INDEX_TARGET] = TARGET_VIBRANT_SATURATION
    }

    private fun setDefaultMutedSaturationValues() {
        saturationTargets[INDEX_TARGET] = TARGET_MUTED_SATURATION
        saturationTargets[INDEX_MAX] = MAX_MUTED_SATURATION
    }

    /** Normalizes the weights so that they add up to 1 (Java mutates the shared targets in place too). */
    internal fun normalizeWeights() {
        var sum = 0f
        for (weight in weights) {
            if (weight > 0) sum += weight
        }
        if (sum != 0f) {
            for (i in weights.indices) {
                if (weights[i] > 0) weights[i] /= sum
            }
        }
    }

    companion object {
        private const val TARGET_DARK_LUMA = 0.26f
        private const val MAX_DARK_LUMA = 0.45f

        private const val MIN_LIGHT_LUMA = 0.55f
        private const val TARGET_LIGHT_LUMA = 0.74f

        private const val MIN_NORMAL_LUMA = 0.3f
        private const val TARGET_NORMAL_LUMA = 0.5f
        private const val MAX_NORMAL_LUMA = 0.7f

        private const val TARGET_MUTED_SATURATION = 0.3f
        private const val MAX_MUTED_SATURATION = 0.4f

        private const val TARGET_VIBRANT_SATURATION = 1f
        private const val MIN_VIBRANT_SATURATION = 0.35f

        private const val WEIGHT_SATURATION = 0.24f
        private const val WEIGHT_LUMA = 0.52f
        private const val WEIGHT_POPULATION = 0.24f

        private const val INDEX_MIN = 0
        private const val INDEX_TARGET = 1
        private const val INDEX_MAX = 2

        private const val INDEX_WEIGHT_SAT = 0
        private const val INDEX_WEIGHT_LUMA = 1
        private const val INDEX_WEIGHT_POP = 2

        /** A target which has the characteristics of a vibrant color which is light in luminance. */
        val LIGHT_VIBRANT: Target = Target().apply {
            setDefaultLightLightnessValues()
            setDefaultVibrantSaturationValues()
        }

        /** A target which has the characteristics of a vibrant color which is neither light or dark. */
        val VIBRANT: Target = Target().apply {
            setDefaultNormalLightnessValues()
            setDefaultVibrantSaturationValues()
        }

        /** A target which has the characteristics of a vibrant color which is dark in luminance. */
        val DARK_VIBRANT: Target = Target().apply {
            setDefaultDarkLightnessValues()
            setDefaultVibrantSaturationValues()
        }

        /** A target which has the characteristics of a muted color which is light in luminance. */
        val LIGHT_MUTED: Target = Target().apply {
            setDefaultLightLightnessValues()
            setDefaultMutedSaturationValues()
        }

        /** A target which has the characteristics of a muted color which is neither light or dark. */
        val MUTED: Target = Target().apply {
            setDefaultNormalLightnessValues()
            setDefaultMutedSaturationValues()
        }

        /** A target which has the characteristics of a muted color which is dark in luminance. */
        val DARK_MUTED: Target = Target().apply {
            setDefaultDarkLightnessValues()
            setDefaultMutedSaturationValues()
        }

        /** The Builder's default targets, in its order. */
        internal val DEFAULTS: List<Target> = listOf(LIGHT_VIBRANT, VIBRANT, DARK_VIBRANT, LIGHT_MUTED, MUTED, DARK_MUTED)
    }
}
