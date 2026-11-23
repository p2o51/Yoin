package com.gpo.yoin.player.sing

/**
 * What the audio thread's [SingMixerAudioProcessor] reads from the rest of the app: whether a 4-channel
 * Sing stream is expected, the gain to head for, and how fast. Written from any thread; the processor
 * picks a change up at its next buffer through [generation].
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

    /** Bumped by every [setTarget]; the processor starts a new ramp when it sees a new value. */
    @Volatile var generation: Int = 0
        private set

    /**
     * Set when Sing turns on: the next Sing stream starts at gain 1.0 — the mix, so swapping the
     * original stream for the 4-channel one is inaudible — and glides from there to [targetGain].
     */
    @Volatile var startAtUnity: Boolean = false

    @Synchronized
    fun setTarget(gain: Float, rampMs: Int = DEFAULT_RAMP_MS) {
        targetGain = gain.coerceIn(0f, 1f)
        this.rampMs = rampMs.coerceAtLeast(0)
        generation++
    }

    companion object {
        /** Every gain change (the slider) ramps linearly over this long. */
        const val DEFAULT_RAMP_MS = 20

        /** Sing on (1.0 → level) and off (level → 1.0) glide over this long. */
        const val GLIDE_MS = 250

        val IDENTITY = intArrayOf(0, 1, 2, 3)
    }
}
