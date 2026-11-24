package com.gpo.yoin.player.sing.dsp

/**
 * The fixed shape of Lyra-Sing-Lite's input and output, as the model was trained and as
 * Prism `lyra-sing-lite/infer_and_eval.py` runs it. Changing any of these needs a new model.
 */
object LyraSingSpec {
    /** The model's core rate; everything above ~10.8 kHz bypasses it (I = X − V keeps it). */
    const val CORE_RATE = 22_050
    const val N_FFT = 1024
    const val HOP = 256

    /** One-sided bins 0..512; the model sees 1..512 (DC dropped, restored as 0). */
    const val ONE_SIDED_BINS = N_FFT / 2 + 1
    const val MODEL_BINS = N_FFT / 2

    /** STFT frames per model call. */
    const val CHUNK_FRAMES = 256

    /** [L_re, L_im, R_re, R_im]. */
    const val MODEL_CHANNELS = 4

    /** Floats in one [1, 4, 512, 256] tensor. */
    const val CHUNK_FLOATS = MODEL_CHANNELS * MODEL_BINS * CHUNK_FRAMES

    /** Chunk starts are this many frames apart (the reference's ear-audit runs use 160; 128 is 50 %). */
    const val DEFAULT_STEP_FRAMES = 160

    /** Per-chunk RMS normalisation: rms = sqrt(mean(x²) + RMS_EPSILON). */
    const val RMS_EPSILON = 1e-6

    /** The OLA crossfade (symmetric Hann over a chunk) is floored here, as `np.maximum(np.hanning(256), 1e-4)`. */
    const val CROSSFADE_FLOOR = 1e-4f

    const val INPUT_NAME = "mix_spec"
    const val OUTPUT_NAME = "voc_spec"

    /** Seconds of audio one chunk covers at the core rate (~2.97 s). */
    const val CHUNK_SECONDS: Double = ((CHUNK_FRAMES - 1) * HOP).toDouble() / CORE_RATE
}
