package com.gpo.yoin.data.memory

/**
 * Deterministic fallback 拟题 —— BYOK 未配置 / Gemini 失败 / 没有占用者文本时
 * 由本地信号合成。拟题槽（Memory 卡印章右列 + 首页 Jump Back In memory 槽位）
 * 永不为空，这是它存在的全部理由；不追求文采，只求诚实。
 */
fun deterministicMemoryTitle(
    ratedTrackCount: Int,
    totalTrackCount: Int,
    noteCount: Int,
    hasAlbumReview: Boolean,
): String = when {
    totalTrackCount > 0 && ratedTrackCount > 0 ->
        "$ratedTrackCount of $totalTrackCount rated"

    noteCount > 0 -> {
        val noun = if (noteCount == 1) "note" else "notes"
        "Kept for $noteCount $noun"
    }

    hasAlbumReview -> "Your album review"

    else -> "From your listening"
}
