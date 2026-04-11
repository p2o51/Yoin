package com.gpo.yoin.ui.component

import com.gpo.yoin.player.VisualizerData

internal fun playbackBackdropSignal(
    visualizerData: VisualizerData,
    isPlaying: Boolean,
): Float {
    if (!isPlaying) return 0f
    val fft = visualizerData.fft
    if (fft.isEmpty()) return 0.18f

    val sampleCount = minOf(fft.size, 12)
    var sum = 0f
    var peak = 0f
    for (index in 0 until sampleCount) {
        val value = fft[index].coerceIn(0f, 1f)
        sum += value
        if (value > peak) peak = value
    }
    val average = sum / sampleCount
    return (average * 0.7f + peak * 0.3f)
        .coerceIn(0.12f, 1f)
}
