package com.gpo.yoin.player.sing

/** Per-sample gain interpolation shape used by [SingMixerAudioProcessor]. */
enum class SingRampCurve {
    /** Click-free 20 ms linear ramp for live vocal slider moves. */
    Linear,

    /** ~0.95 s ease-out cubic cut-in: vocals recede immediately and settle softly into the backing track. */
    EaseOutCubic,

    /** ~0.75 s ease-in-out exit: vocals rise smoothly back to 1.0 before swapping back to original stereo. */
    EaseInOut,
    ;

    fun evaluate(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return when (this) {
            Linear -> u
            EaseOutCubic -> {
                val inv = 1f - u
                1f - inv * inv * inv
            }
            EaseInOut -> {
                if (u < 0.5f) {
                    2f * u * u
                } else {
                    val k = -2f * u + 2f
                    1f - (k * k) * 0.5f
                }
            }
        }
    }
}

/**
 * What the audio thread's [SingMixerAudioProcessor] reads from the rest of the app: whether a 4-channel
 * Sing stream is expected, the gain to head for, how fast, and along which curve. Written from any
 * thread; the processor picks a change up at its next buffer through [generation].
 */
class SingMixControl {
    /**
     * While true a 4-channel PCM stream is a Sing stream [I_L, I_R, V_L, V_R] and is mixed to stereo.
     * False (the default): every format passes through untouched, a genuine quad file included.
     */
    @Volatile var expectQuad: Boolean = false

    /**
     * Where each logical channel sits in the decoded stream: [channelMap][0] is I_L's index, then I_R,
     * V_L, V_R. Identity unless a decoder was found to reorder (see QuadChannelOrderProbe).
     */
    @Volatile var channelMap: IntArray = IDENTITY

    @Volatile var targetGain: Float = 1f
        private set

    @Volatile var rampMs: Int = DEFAULT_RAMP_MS
        private set

    @Volatile var rampCurve: SingRampCurve = SingRampCurve.Linear
        private set

    /** Bumped by every [setTarget]; the processor starts a new ramp when it sees a new value. */
    @Volatile var generation: Int = 0
        private set

    /**
     * Set when Sing turns on: the next Sing stream starts at gain 1.0 — the mix, so swapping the
     * original stream for the 4-channel one is inaudible — and glides from there to [targetGain]
     * along [SingRampCurve.EaseOutCubic].
     */
    @Volatile var startAtUnity: Boolean = false

    @Synchronized
    fun setTarget(
        gain: Float,
        rampMs: Int = DEFAULT_RAMP_MS,
        curve: SingRampCurve = SingRampCurve.Linear,
    ) {
        targetGain = gain.coerceIn(0f, 1f)
        this.rampMs = rampMs.coerceAtLeast(0)
        this.rampCurve = curve
        generation++
    }

    companion object {
        /** Every live slider move ramps linearly over this long to prevent clicks. */
        const val DEFAULT_RAMP_MS = 20

        /** Sing on (1.0 → vocal level) eases out smoothly over ~0.95 s. */
        const val CUT_IN_GLIDE_MS = 950

        /** Sing off (vocal level → 1.0) eases in-out smoothly over ~0.75 s before restoring stereo. */
        const val CUT_OUT_GLIDE_MS = 750

        /** Alias kept for callers referencing the default Sing transition duration. */
        const val GLIDE_MS = CUT_IN_GLIDE_MS

        val IDENTITY = intArrayOf(0, 1, 2, 3)
    }
}
