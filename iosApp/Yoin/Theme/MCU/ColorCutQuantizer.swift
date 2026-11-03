/*
 * Copyright 2017 The Android Open Source Project
 *
 * Modified for Yoin (Swift port) of androidx/palette/graphics/ColorCutQuantizer.java
 * (androidx.palette:palette, Apache 2.0), kept close to the Java source so
 * vibrant / dominant / muted picks match Android's.
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

import Foundation

/// An color quantizer based on the Median-cut algorithm, but optimized for picking out distinct
/// colors rather than representation colors.
///
/// The color space is represented as a 3-dimensional cube with each dimension being an RGB
/// component. The cube is then repeatedly divided until we have reduced the color space to the
/// requested number of colors. An average color is then generated from each cube.
///
/// What makes this different to median-cut is that median-cut divided cubes so that all of the
/// cubes have roughly the same population, where this quantizer divides boxes based on their color
/// volume. This means that the color space is divided into distinct colors, rather than
/// representative colors.
final class ColorCutQuantizer {
    private static let COMPONENT_RED = -3
    private static let COMPONENT_GREEN = -2
    private static let COMPONENT_BLUE = -1
    private static let QUANTIZE_WORD_WIDTH = 5
    private static let QUANTIZE_WORD_MASK = (1 << QUANTIZE_WORD_WIDTH) - 1

    fileprivate var colors: [Int]
    fileprivate let histogram: [Int]
    private(set) var quantizedColors: [Palette.Swatch] = []
    private let filters: [Palette.Filter]

    /// - Parameter pixels: ARGB pixels (Android `Bitmap.getPixels` layout).
    init(pixels: [UInt32], maxColors: Int, filters: [Palette.Filter]) {
        self.filters = filters
        var hist = [Int](repeating: 0, count: 1 << (ColorCutQuantizer.QUANTIZE_WORD_WIDTH * 3))
        for pixel in pixels {
            let quantizedColor = ColorCutQuantizer.quantizeFromRgb888(Int(pixel))
            hist[quantizedColor] += 1
        }
        var distinctColorCount = 0
        for color in 0..<hist.count {
            if hist[color] > 0 && ColorCutQuantizer.shouldIgnoreColor565(color, filters) {
                hist[color] = 0
            }
            if hist[color] > 0 {
                distinctColorCount += 1
            }
        }
        var colors: [Int] = []
        colors.reserveCapacity(distinctColorCount)
        for color in 0..<hist.count where hist[color] > 0 {
            colors.append(color)
        }
        self.colors = colors
        self.histogram = hist

        if distinctColorCount <= maxColors {
            quantizedColors = colors.map { color in
                Palette.Swatch(rgb: ColorCutQuantizer.approximateToRgb888(color), population: hist[color])
            }
        } else {
            quantizedColors = quantizePixels(maxColors)
        }
    }

    private func quantizePixels(_ maxColors: Int) -> [Palette.Swatch] {
        // Create the priority queue which is sorted by volume descending. This means we always
        // split the largest box in the queue
        var pq = JavaPriorityQueue<Vbox> { lhs, rhs in rhs.volume - lhs.volume }
        pq.offer(Vbox(self, lowerIndex: 0, upperIndex: colors.count - 1))
        splitBoxes(&pq, maxColors)
        return generateAverageColors(pq.elementsInIterationOrder)
    }

    private func splitBoxes(_ queue: inout JavaPriorityQueue<Vbox>, _ maxSize: Int) {
        while queue.count < maxSize {
            if let vbox = queue.poll(), vbox.canSplit() {
                queue.offer(vbox.splitBox())
                queue.offer(vbox)
            } else {
                return
            }
        }
    }

    private func generateAverageColors(_ vboxes: [Vbox]) -> [Palette.Swatch] {
        var colors: [Palette.Swatch] = []
        for vbox in vboxes {
            let swatch = vbox.averageColor()
            if !ColorCutQuantizer.shouldIgnoreColor(swatch.rgb, swatch.hsl, filters) {
                colors.append(swatch)
            }
        }
        return colors
    }

    /// Represents a tightly fitting box around a color space.
    fileprivate final class Vbox {
        unowned let q: ColorCutQuantizer
        private var lowerIndex: Int
        private var upperIndex: Int
        private var population = 0
        private var minRed = 0, maxRed = 0
        private var minGreen = 0, maxGreen = 0
        private var minBlue = 0, maxBlue = 0

        init(_ q: ColorCutQuantizer, lowerIndex: Int, upperIndex: Int) {
            self.q = q
            self.lowerIndex = lowerIndex
            self.upperIndex = upperIndex
            fitBox()
        }

        var volume: Int { (maxRed - minRed + 1) * (maxGreen - minGreen + 1) * (maxBlue - minBlue + 1) }

        func canSplit() -> Bool { colorCount > 1 }

        var colorCount: Int { 1 + upperIndex - lowerIndex }

        /// Recomputes the boundaries of this box to tightly fit the colors within the box.
        func fitBox() {
            var minRed = Int.max, minGreen = Int.max, minBlue = Int.max
            var maxRed = Int.min, maxGreen = Int.min, maxBlue = Int.min
            var count = 0
            for i in lowerIndex...upperIndex {
                let color = q.colors[i]
                count += q.histogram[color]
                let r = ColorCutQuantizer.quantizedRed(color)
                let g = ColorCutQuantizer.quantizedGreen(color)
                let b = ColorCutQuantizer.quantizedBlue(color)
                if r > maxRed { maxRed = r }
                if r < minRed { minRed = r }
                if g > maxGreen { maxGreen = g }
                if g < minGreen { minGreen = g }
                if b > maxBlue { maxBlue = b }
                if b < minBlue { minBlue = b }
            }
            self.minRed = minRed
            self.maxRed = maxRed
            self.minGreen = minGreen
            self.maxGreen = maxGreen
            self.minBlue = minBlue
            self.maxBlue = maxBlue
            population = count
        }

        /// Split this color box at the mid-point along its longest dimension.
        func splitBox() -> Vbox {
            precondition(canSplit(), "Can not split a box with only 1 color")
            let splitPoint = findSplitPoint()
            let newBox = Vbox(q, lowerIndex: splitPoint + 1, upperIndex: upperIndex)
            upperIndex = splitPoint
            fitBox()
            return newBox
        }

        var longestColorDimension: Int {
            let redLength = maxRed - minRed
            let greenLength = maxGreen - minGreen
            let blueLength = maxBlue - minBlue
            if redLength >= greenLength && redLength >= blueLength {
                return ColorCutQuantizer.COMPONENT_RED
            } else if greenLength >= redLength && greenLength >= blueLength {
                return ColorCutQuantizer.COMPONENT_GREEN
            } else {
                return ColorCutQuantizer.COMPONENT_BLUE
            }
        }

        /// Finds the point within this box's lowerIndex and upperIndex index of where to split.
        func findSplitPoint() -> Int {
            let longestDimension = longestColorDimension
            // We need to sort the colors in this box based on the longest color dimension.
            // As we can't use a Comparator to define the sort logic, we modify each color so that
            // its most significant is the desired dimension
            ColorCutQuantizer.modifySignificantOctet(&q.colors, longestDimension, lowerIndex, upperIndex)
            // Now sort... (the values are distinct, so any sort gives Java's order)
            q.colors[lowerIndex...upperIndex].sort()
            // Now revert all of the colors so that they are packed as RGB again
            ColorCutQuantizer.modifySignificantOctet(&q.colors, longestDimension, lowerIndex, upperIndex)

            let midPoint = population / 2
            var count = 0
            for i in lowerIndex...upperIndex {
                count += q.histogram[q.colors[i]]
                if count >= midPoint {
                    // we never want to split on the upperIndex, as this will result in the same box
                    return min(upperIndex - 1, i)
                }
            }
            return lowerIndex
        }

        /// Returns the average color of this box.
        func averageColor() -> Palette.Swatch {
            var redSum = 0, greenSum = 0, blueSum = 0, totalPopulation = 0
            for i in lowerIndex...upperIndex {
                let color = q.colors[i]
                let colorPopulation = q.histogram[color]
                totalPopulation += colorPopulation
                redSum += colorPopulation * ColorCutQuantizer.quantizedRed(color)
                greenSum += colorPopulation * ColorCutQuantizer.quantizedGreen(color)
                blueSum += colorPopulation * ColorCutQuantizer.quantizedBlue(color)
            }
            // Java: Math.round(sum / (float) totalPopulation)
            let redMean = jvmRoundToInt(Float(redSum) / Float(totalPopulation))
            let greenMean = jvmRoundToInt(Float(greenSum) / Float(totalPopulation))
            let blueMean = jvmRoundToInt(Float(blueSum) / Float(totalPopulation))
            return Palette.Swatch(
                rgb: ColorCutQuantizer.approximateToRgb888(redMean, greenMean, blueMean),
                population: totalPopulation
            )
        }
    }

    /// Modify the significant octet in a packed color int. Allows sorting based on the value of a
    /// single color component. This relies on all components being the same word size.
    private static func modifySignificantOctet(_ a: inout [Int], _ dimension: Int, _ lower: Int, _ upper: Int) {
        switch dimension {
        case COMPONENT_GREEN:
            // Need to do a RGB to GRB swap
            for i in lower...upper {
                let color = a[i]
                a[i] = (quantizedGreen(color) << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH))
                    | (quantizedRed(color) << QUANTIZE_WORD_WIDTH)
                    | quantizedBlue(color)
            }
        case COMPONENT_BLUE:
            // Need to do a RGB to BGR swap
            for i in lower...upper {
                let color = a[i]
                a[i] = (quantizedBlue(color) << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH))
                    | (quantizedGreen(color) << QUANTIZE_WORD_WIDTH)
                    | quantizedRed(color)
            }
        default:
            // Already in RGB, no need to do anything
            break
        }
    }

    private static func shouldIgnoreColor565(_ color565: Int, _ filters: [Palette.Filter]) -> Bool {
        let rgb = approximateToRgb888(color565)
        return shouldIgnoreColor(rgb, Palette.rgbToHsl(rgb), filters)
    }

    private static func shouldIgnoreColor(_ rgb: Int, _ hsl: [Float], _ filters: [Palette.Filter]) -> Bool {
        for filter in filters where !filter.isAllowed(rgb, hsl) {
            return true
        }
        return false
    }

    /// Quantized a RGB888 value to have a word width of [QUANTIZE_WORD_WIDTH].
    private static func quantizeFromRgb888(_ color: Int) -> Int {
        let r = modifyWordWidth((color >> 16) & 0xff, 8, QUANTIZE_WORD_WIDTH)
        let g = modifyWordWidth((color >> 8) & 0xff, 8, QUANTIZE_WORD_WIDTH)
        let b = modifyWordWidth(color & 0xff, 8, QUANTIZE_WORD_WIDTH)
        return (r << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) | (g << QUANTIZE_WORD_WIDTH) | b
    }

    /// Quantized RGB888 values to have a word width of [QUANTIZE_WORD_WIDTH].
    static func approximateToRgb888(_ r: Int, _ g: Int, _ b: Int) -> Int {
        // Color.rgb
        (0xff << 24)
            | (modifyWordWidth(r, QUANTIZE_WORD_WIDTH, 8) << 16)
            | (modifyWordWidth(g, QUANTIZE_WORD_WIDTH, 8) << 8)
            | modifyWordWidth(b, QUANTIZE_WORD_WIDTH, 8)
    }

    private static func approximateToRgb888(_ color: Int) -> Int {
        approximateToRgb888(quantizedRed(color), quantizedGreen(color), quantizedBlue(color))
    }

    static func quantizedRed(_ color: Int) -> Int {
        (color >> (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) & QUANTIZE_WORD_MASK
    }

    static func quantizedGreen(_ color: Int) -> Int { (color >> QUANTIZE_WORD_WIDTH) & QUANTIZE_WORD_MASK }

    static func quantizedBlue(_ color: Int) -> Int { color & QUANTIZE_WORD_MASK }

    private static func modifyWordWidth(_ value: Int, _ currentWidth: Int, _ targetWidth: Int) -> Int {
        let newValue: Int
        if targetWidth > currentWidth {
            // If we're approximating up in word width, we'll shift up
            newValue = value << (targetWidth - currentWidth)
        } else {
            // Else, we will just shift and keep the MSB
            newValue = value >> (currentWidth - targetWidth)
        }
        return newValue & ((1 << targetWidth) - 1)
    }
}

/// `java.util.PriorityQueue` with a comparator: the same binary-heap sift
/// order, so ties between equal-volume boxes and the iteration order of
/// `generateAverageColors` come out as on Android.
struct JavaPriorityQueue<Element> {
    private var queue: [Element] = []
    private let compare: (Element, Element) -> Int

    init(comparator: @escaping (Element, Element) -> Int) {
        compare = comparator
    }

    var count: Int { queue.count }

    /// The backing array, which is what Java's `PriorityQueue.iterator()` walks.
    var elementsInIterationOrder: [Element] { queue }

    mutating func offer(_ e: Element) {
        queue.append(e)
        siftUp(queue.count - 1, e)
    }

    mutating func poll() -> Element? {
        guard !queue.isEmpty else { return nil }
        let result = queue[0]
        let x = queue.removeLast()
        if !queue.isEmpty {
            siftDown(0, x)
        }
        return result
    }

    private mutating func siftUp(_ k: Int, _ x: Element) {
        var k = k
        while k > 0 {
            let parent = (k - 1) >> 1
            let e = queue[parent]
            if compare(x, e) >= 0 { break }
            queue[k] = e
            k = parent
        }
        queue[k] = x
    }

    private mutating func siftDown(_ k: Int, _ x: Element) {
        var k = k
        let n = queue.count
        let half = n >> 1
        while k < half {
            var child = (k << 1) + 1
            var c = queue[child]
            let right = child + 1
            if right < n && compare(c, queue[right]) > 0 {
                child = right
                c = queue[child]
            }
            if compare(x, c) <= 0 { break }
            queue[k] = c
            k = child
        }
        queue[k] = x
    }
}
