package com.gpo.yoin.player.sing.dsp

/**
 * One call of Lyra-Sing-Lite: a normalised mix spectrogram chunk in, the vocal spectrogram out, both
 * `[1, 4, 512, 256]` floats (NCHW, channels [L_re, L_im, R_re, R_im], bins 1..512).
 * The ONNX Runtime session is one implementation ([com.gpo.yoin.player.sing.OrtSeparationModel]);
 * tests pass their own.
 */
interface SeparationModel : AutoCloseable {
    fun run(input: FloatArray, output: FloatArray)

    override fun close() {}
}
