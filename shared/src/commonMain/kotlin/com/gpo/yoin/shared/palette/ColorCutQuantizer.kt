/*
 * Copyright 2017 The Android Open Source Project
 *
 * Modified for Yoin: androidx/palette/graphics/ColorCutQuantizer.java (androidx.palette:palette
 * 1.0.0, Apache 2.0) ported to Kotlin Multiplatform common code, kept line-for-line close to the
 * Java so the swatches are byte-identical to Android's (PaletteParityTest in :app). Timing logs
 * dropped; java.util.PriorityQueue replaced by [JavaPriorityQueue] (same heap order).
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

import kotlin.math.min

/**
 * A color quantizer based on the Median-cut algorithm, but optimized for picking out distinct
 * colors rather than representation colors.
 *
 * The color space is represented as a 3-dimensional cube with each dimension being an RGB
 * component. The cube is then repeatedly divided until we have reduced the color space to the
 * requested number of colors. An average color is then generated from each cube.
 *
 * What makes this different to median-cut is that median-cut divided cubes so that all of the
 * cubes have roughly the same population, where this quantizer divides boxes based on their color
 * volume. This means that the color space is divided into distinct colors, rather than
 * representative colors.
 *
 * @param pixels ARGB pixels (Android `Bitmap.getPixels` layout). Like the Java, the array is
 *   overwritten with the quantized colors.
 */
internal class ColorCutQuantizer(
    pixels: IntArray,
    maxColors: Int,
    private val filters: List<Palette.Filter>,
) {
    private val colors: IntArray
    private val histogram: IntArray

    /** The list of quantized colors. */
    val quantizedColors: List<Palette.Swatch>

    init {
        val hist = IntArray(1 shl (QUANTIZE_WORD_WIDTH * 3))
        histogram = hist
        for (i in pixels.indices) {
            val quantizedColor = quantizeFromRgb888(pixels[i])
            // Now update the pixel value to the quantized value
            pixels[i] = quantizedColor
            // And update the histogram
            hist[quantizedColor]++
        }

        // Now let's count the number of distinct colors
        var distinctColorCount = 0
        for (color in hist.indices) {
            if (hist[color] > 0 && shouldIgnoreColor565(color)) {
                // If we should ignore the color, set the population to 0
                hist[color] = 0
            }
            if (hist[color] > 0) {
                // If the color has population, increase the distinct color count
                distinctColorCount++
            }
        }

        // Now lets go through create an array consisting of only distinct colors
        val colors = IntArray(distinctColorCount)
        this.colors = colors
        var distinctColorIndex = 0
        for (color in hist.indices) {
            if (hist[color] > 0) {
                colors[distinctColorIndex++] = color
            }
        }

        quantizedColors = if (distinctColorCount <= maxColors) {
            // The image has fewer colors than the maximum requested, so just return the colors
            colors.map { color -> Palette.Swatch(approximateToRgb888(color), hist[color]) }
        } else {
            // We need use quantization to reduce the number of colors
            quantizePixels(maxColors)
        }
    }

    private fun quantizePixels(maxColors: Int): List<Palette.Swatch> {
        // Create the priority queue which is sorted by volume descending. This means we always
        // split the largest box in the queue
        val pq = JavaPriorityQueue<Vbox> { lhs, rhs -> rhs.volume - lhs.volume }

        // To start, offer a box which contains all of the colors
        pq.offer(Vbox(0, colors.size - 1))

        // Now go through the boxes, splitting them until we have reached maxColors or there are no
        // more boxes to split
        splitBoxes(pq, maxColors)

        // Finally, return the average colors of the color boxes
        return generateAverageColors(pq.elementsInIterationOrder())
    }

    /**
     * Iterate through the queue, popping color boxes and splitting them. Once split, the new box
     * and the remaining box are offered back to the queue.
     */
    private fun splitBoxes(queue: JavaPriorityQueue<Vbox>, maxSize: Int) {
        while (queue.size < maxSize) {
            val vbox = queue.poll()
            if (vbox != null && vbox.canSplit()) {
                // First split the box, and offer the result
                queue.offer(vbox.splitBox())
                // Then offer the box back
                queue.offer(vbox)
            } else {
                // If we get here then there are no more boxes to split, so return
                return
            }
        }
    }

    private fun generateAverageColors(vboxes: List<Vbox>): List<Palette.Swatch> {
        val colors = ArrayList<Palette.Swatch>(vboxes.size)
        for (vbox in vboxes) {
            val swatch = vbox.averageColor()
            if (!shouldIgnoreColor(swatch)) {
                // As we're averaging a color box, we can still get colors which we do not want, so
                // we check again here
                colors.add(swatch)
            }
        }
        return colors
    }

    /** Represents a tightly fitting box around a color space. */
    private inner class Vbox(
        // lower and upper index are inclusive
        private val lowerIndex: Int,
        private var upperIndex: Int,
    ) {
        // Population of colors within this box
        private var population = 0

        private var minRed = 0
        private var maxRed = 0
        private var minGreen = 0
        private var maxGreen = 0
        private var minBlue = 0
        private var maxBlue = 0

        init {
            fitBox()
        }

        val volume: Int
            get() = (maxRed - minRed + 1) * (maxGreen - minGreen + 1) * (maxBlue - minBlue + 1)

        fun canSplit(): Boolean = colorCount > 1

        val colorCount: Int get() = 1 + upperIndex - lowerIndex

        /** Recomputes the boundaries of this box to tightly fit the colors within the box. */
        fun fitBox() {
            val colors = colors
            val hist = histogram

            // Reset the min and max to opposite values
            var minRed = Int.MAX_VALUE
            var minGreen = Int.MAX_VALUE
            var minBlue = Int.MAX_VALUE
            var maxRed = Int.MIN_VALUE
            var maxGreen = Int.MIN_VALUE
            var maxBlue = Int.MIN_VALUE
            var count = 0

            for (i in lowerIndex..upperIndex) {
                val color = colors[i]
                count += hist[color]

                val r = quantizedRed(color)
                val g = quantizedGreen(color)
                val b = quantizedBlue(color)
                if (r > maxRed) maxRed = r
                if (r < minRed) minRed = r
                if (g > maxGreen) maxGreen = g
                if (g < minGreen) minGreen = g
                if (b > maxBlue) maxBlue = b
                if (b < minBlue) minBlue = b
            }

            this.minRed = minRed
            this.maxRed = maxRed
            this.minGreen = minGreen
            this.maxGreen = maxGreen
            this.minBlue = minBlue
            this.maxBlue = maxBlue
            population = count
        }

        /**
         * Split this color box at the mid-point along its longest dimension
         *
         * @return the new ColorBox
         */
        fun splitBox(): Vbox {
            check(canSplit()) { "Can not split a box with only 1 color" }

            // find median along the longest dimension
            val splitPoint = findSplitPoint()

            val newBox = Vbox(splitPoint + 1, upperIndex)

            // Now change this box's upperIndex and recompute the color boundaries
            upperIndex = splitPoint
            fitBox()

            return newBox
        }

        /** @return the dimension which this box is largest in */
        val longestColorDimension: Int
            get() {
                val redLength = maxRed - minRed
                val greenLength = maxGreen - minGreen
                val blueLength = maxBlue - minBlue

                return if (redLength >= greenLength && redLength >= blueLength) {
                    COMPONENT_RED
                } else if (greenLength >= redLength && greenLength >= blueLength) {
                    COMPONENT_GREEN
                } else {
                    COMPONENT_BLUE
                }
            }

        /**
         * Finds the point within this box's lowerIndex and upperIndex index of where to split.
         *
         * This is calculated by finding the longest color dimension, and then sorting the
         * sub-array based on that dimension value in each color. The colors are then iterated over
         * until a color is found with at least the midpoint of the whole box's dimension midpoint.
         *
         * @return the index of the colors array to split from
         */
        fun findSplitPoint(): Int {
            val longestDimension = longestColorDimension
            val colors = colors
            val hist = histogram

            // We need to sort the colors in this box based on the longest color dimension.
            // As we can't use a Comparator to define the sort logic, we modify each color so that
            // its most significant is the desired dimension
            modifySignificantOctet(colors, longestDimension, lowerIndex, upperIndex)

            // Now sort... Arrays.sort uses a exclusive toIndex so we need to add 1
            colors.sort(lowerIndex, upperIndex + 1)

            // Now revert all of the colors so that they are packed as RGB again
            modifySignificantOctet(colors, longestDimension, lowerIndex, upperIndex)

            val midPoint = population / 2
            var count = 0
            for (i in lowerIndex..upperIndex) {
                count += hist[colors[i]]
                if (count >= midPoint) {
                    // we never want to split on the upperIndex, as this will result in the same
                    // box
                    return min(upperIndex - 1, i)
                }
            }

            return lowerIndex
        }

        /** @return the average color of this box. */
        fun averageColor(): Palette.Swatch {
            val colors = colors
            val hist = histogram
            var redSum = 0
            var greenSum = 0
            var blueSum = 0
            var totalPopulation = 0

            for (i in lowerIndex..upperIndex) {
                val color = colors[i]
                val colorPopulation = hist[color]

                totalPopulation += colorPopulation
                redSum += colorPopulation * quantizedRed(color)
                greenSum += colorPopulation * quantizedGreen(color)
                blueSum += colorPopulation * quantizedBlue(color)
            }

            val redMean = javaRound(redSum / totalPopulation.toFloat())
            val greenMean = javaRound(greenSum / totalPopulation.toFloat())
            val blueMean = javaRound(blueSum / totalPopulation.toFloat())

            return Palette.Swatch(approximateToRgb888(redMean, greenMean, blueMean), totalPopulation)
        }
    }

    private fun shouldIgnoreColor565(color565: Int): Boolean {
        val rgb = approximateToRgb888(color565)
        return shouldIgnoreColor(rgb, Palette.rgbToHsl(rgb))
    }

    private fun shouldIgnoreColor(color: Palette.Swatch): Boolean = shouldIgnoreColor(color.rgb, color.hsl)

    private fun shouldIgnoreColor(rgb: Int, hsl: FloatArray): Boolean {
        if (filters.isNotEmpty()) {
            for (filter in filters) {
                if (!filter.isAllowed(rgb, hsl)) {
                    return true
                }
            }
        }
        return false
    }

    internal companion object {
        const val COMPONENT_RED = -3
        const val COMPONENT_GREEN = -2
        const val COMPONENT_BLUE = -1

        private const val QUANTIZE_WORD_WIDTH = 5
        private const val QUANTIZE_WORD_MASK = (1 shl QUANTIZE_WORD_WIDTH) - 1

        /**
         * Modify the significant octet in a packed color int. Allows sorting based on the value of
         * a single color component. This relies on all components being the same word size.
         */
        fun modifySignificantOctet(a: IntArray, dimension: Int, lower: Int, upper: Int) {
            when (dimension) {
                COMPONENT_RED -> {
                    // Already in RGB, no need to do anything
                }
                COMPONENT_GREEN -> {
                    // We need to do a RGB to GRB swap, or vice-versa
                    for (i in lower..upper) {
                        val color = a[i]
                        a[i] = (quantizedGreen(color) shl (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) or
                            (quantizedRed(color) shl QUANTIZE_WORD_WIDTH) or
                            quantizedBlue(color)
                    }
                }
                COMPONENT_BLUE -> {
                    // We need to do a RGB to BGR swap, or vice-versa
                    for (i in lower..upper) {
                        val color = a[i]
                        a[i] = (quantizedBlue(color) shl (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) or
                            (quantizedGreen(color) shl QUANTIZE_WORD_WIDTH) or
                            quantizedRed(color)
                    }
                }
            }
        }

        /** Quantized a RGB888 value to have a word width of [QUANTIZE_WORD_WIDTH]. */
        private fun quantizeFromRgb888(color: Int): Int {
            val r = modifyWordWidth((color shr 16) and 0xFF, 8, QUANTIZE_WORD_WIDTH)
            val g = modifyWordWidth((color shr 8) and 0xFF, 8, QUANTIZE_WORD_WIDTH)
            val b = modifyWordWidth(color and 0xFF, 8, QUANTIZE_WORD_WIDTH)
            return (r shl (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) or (g shl QUANTIZE_WORD_WIDTH) or b
        }

        /** Quantized RGB888 values to have a word width of [QUANTIZE_WORD_WIDTH]. */
        fun approximateToRgb888(r: Int, g: Int, b: Int): Int = argb(
            0xFF,
            modifyWordWidth(r, QUANTIZE_WORD_WIDTH, 8),
            modifyWordWidth(g, QUANTIZE_WORD_WIDTH, 8),
            modifyWordWidth(b, QUANTIZE_WORD_WIDTH, 8),
        )

        private fun approximateToRgb888(color: Int): Int =
            approximateToRgb888(quantizedRed(color), quantizedGreen(color), quantizedBlue(color))

        /** @return red component of the quantized color */
        fun quantizedRed(color: Int): Int = (color shr (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) and QUANTIZE_WORD_MASK

        /** @return green component of a quantized color */
        fun quantizedGreen(color: Int): Int = (color shr QUANTIZE_WORD_WIDTH) and QUANTIZE_WORD_MASK

        /** @return blue component of a quantized color */
        fun quantizedBlue(color: Int): Int = color and QUANTIZE_WORD_MASK

        private fun modifyWordWidth(value: Int, currentWidth: Int, targetWidth: Int): Int {
            val newValue = if (targetWidth > currentWidth) {
                // If we're approximating up in word width, we'll shift up
                value shl (targetWidth - currentWidth)
            } else {
                // Else, we will just shift and keep the MSB
                value shr (currentWidth - targetWidth)
            }
            return newValue and ((1 shl targetWidth) - 1)
        }

        /** android.graphics.Color.argb. */
        private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
            (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

/**
 * java.lang.Math.round(float) (JDK 8+): round half up, computed on the bits so 0.49999997f
 * rounds to 0 like the JVM (floor(x + 0.5f) would give 1).
 */
internal fun javaRound(a: Float): Int {
    val intBits = a.toRawBits()
    val biasedExp = (intBits and 0x7F800000) shr (24 - 1)
    val shift = (24 - 2 + 127) - biasedExp
    if ((shift and -32) == 0) {
        // a is a finite number such that pow(2,-32) <= ulp(a) < 1
        var r = (intBits and 0x007FFFFF) or (0x007FFFFF + 1)
        if (intBits < 0) r = -r
        // In the comments below each Java expression evaluates to the value the corresponding
        // mathematical expression: (r) evaluates to a / ulp(a), (r >> shift) to floor(a * 2),
        // ((r >> shift) + 1) to floor((a + 1/2) * 2), (((r >> shift) + 1) >> 1) to floor(a + 1/2)
        return ((r shr shift) + 1) shr 1
    }
    // a is either
    // - a finite number with abs(a) < exp(2,FloatConsts.SIGNIFICAND_WIDTH-32) < 1/2
    // - a finite number with ulp(a) >= 1 and hence a is a mathematical integer
    // - an infinity or NaN
    return a.toInt()
}

/**
 * `java.util.PriorityQueue` with a comparator: the same binary-heap sift order, so ties between
 * equal-volume boxes and the iteration order of `generateAverageColors` come out as on the JVM.
 */
internal class JavaPriorityQueue<E : Any>(private val comparator: (E, E) -> Int) {
    private val queue = ArrayList<E>()

    val size: Int get() = queue.size

    /** The backing array, which is what Java's `PriorityQueue.iterator()` walks. */
    fun elementsInIterationOrder(): List<E> = queue.toList()

    fun offer(e: E) {
        queue.add(e)
        siftUp(queue.size - 1, e)
    }

    fun poll(): E? {
        if (queue.isEmpty()) return null
        val result = queue[0]
        val x = queue.removeAt(queue.size - 1)
        if (queue.isNotEmpty()) siftDown(0, x)
        return result
    }

    private fun siftUp(start: Int, x: E) {
        var k = start
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val e = queue[parent]
            if (comparator(x, e) >= 0) break
            queue[k] = e
            k = parent
        }
        queue[k] = x
    }

    private fun siftDown(start: Int, x: E) {
        var k = start
        val n = queue.size
        val half = n ushr 1
        while (k < half) {
            var child = (k shl 1) + 1
            var c = queue[child]
            val right = child + 1
            if (right < n && comparator(c, queue[right]) > 0) {
                child = right
                c = queue[child]
            }
            if (comparator(x, c) <= 0) break
            queue[k] = c
            k = child
        }
        queue[k] = x
    }
}
