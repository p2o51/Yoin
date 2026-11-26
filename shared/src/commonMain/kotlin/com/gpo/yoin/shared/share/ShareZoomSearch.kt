package com.gpo.yoin.shared.share

/**
 * How far a Story zooms its content (Android `ZoomToFit`): the content is laid out at the frame's width / zoom
 * (wider when zooming out, so lines wrap less; narrower when zooming in) and drawn back at `zoom` — the largest
 * zoom up to [maxZoom] whose height fits the frame. Fitting is monotonic (a smaller zoom is a wider, shorter
 * layout drawn smaller), so it is searched one measurement at a time: [maxZoom] first; when that doesn't fit, 1
 * (a story that would grow); then [STEPS] halvings of the bracket that fits. It settles on the largest zoom known
 * to fit ([MIN_ZOOM] when nothing does). A new frame size, or content whose height at the settled zoom changes,
 * starts it over.
 *
 * Swift / Dart can run it in one go ([YoinShareCards.storyZoom]) when they can measure synchronously; Android
 * drives it a layout pass at a time.
 */
class ShareZoomSearch(private val maxZoom: Float) {
    /** The zoom to measure next (or, once [settled], the answer). */
    var zoom: Float = maxZoom
        private set

    var settled: Boolean = false
        private set

    private var phase = Phase.Max
    private var low = 0f
    private var high = 0f
    private var steps = 0
    private var frameW = -1
    private var frameH = -1
    private var settledHeight = -1

    private enum class Phase { Max, One, Bisect }

    /** The frame being fitted; a new size starts over. */
    fun frame(width: Int, height: Int) {
        if (width != frameW || height != frameH) {
            frameW = width
            frameH = height
            restart()
        }
    }

    /** The content measured [height] tall at [zoom] and [fits] (or not); moves to the next zoom or settles. */
    fun measured(fits: Boolean, height: Int) {
        if (settled) {
            // Content that grew or shrank under a settled zoom is fitted again.
            if (settledHeight >= 0 && height != settledHeight) restart() else settledHeight = height
            return
        }
        report(fits)
    }

    /** One step of the search for the zoom just tried. */
    fun report(fits: Boolean) {
        if (settled) return
        when (phase) {
            Phase.Max -> when {
                fits -> settle(maxZoom)
                maxZoom > 1f -> {
                    phase = Phase.One
                    zoom = 1f
                }
                else -> bisect(MIN_ZOOM, maxZoom)
            }
            Phase.One -> if (fits) bisect(1f, maxZoom) else bisect(MIN_ZOOM, 1f)
            Phase.Bisect -> {
                if (fits) low = zoom else high = zoom
                steps++
                if (steps >= STEPS) settle(low) else zoom = (low + high) / 2f
            }
        }
    }

    private fun bisect(from: Float, to: Float) {
        phase = Phase.Bisect
        low = from
        high = to
        steps = 0
        zoom = (low + high) / 2f
    }

    private fun settle(value: Float) {
        zoom = value
        settled = true
        settledHeight = -1
    }

    private fun restart() {
        phase = Phase.Max
        steps = 0
        settledHeight = -1
        settled = false
        zoom = maxZoom
    }

    companion object {
        /** Furthest a story zooms out: below this the type is too small to read on a phone. */
        const val MIN_ZOOM = 0.4f

        /** Furthest a story zooms IN on short content (a card on the backdrop; a poster never grows). */
        const val MAX_ZOOM = 1.4f

        /** Halvings of the bracket before it settles. */
        const val STEPS = 8

        /** Rounding: a height a fraction of a pixel over the frame still fits. */
        const val FIT_SLACK_PX = 0.5f
    }
}
