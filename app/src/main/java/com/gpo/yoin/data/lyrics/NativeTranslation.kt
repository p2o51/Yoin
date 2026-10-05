package com.gpo.yoin.data.lyrics

/**
 * 歌词源随歌词一起下发的译文是什么语言。挂在 [LyricProvider.nativeTranslation] 上，
 * 仓库据此决定"点翻译"时先用歌词源自带的译文，还是交给 Gemini。
 */
enum class NativeTranslation {
    /** 简体中文（QQ 音乐、网易云、华为音乐）。 */
    SimplifiedChinese,
    ;

    /**
     * 这份译文能不能当作 [targetLanguage] 的翻译。简体译文对任何中文目标都算数，
     * 包括繁体中文——沿用接入多源之前的口径，繁体用户拿到的是简体。
     */
    fun serves(targetLanguage: String): Boolean = when (this) {
        SimplifiedChinese -> targetLanguage.isChineseTargetLanguage()
    }
}

/** 翻译设置里的目标语言是不是中文（简体、繁体都算）。 */
internal fun String.isChineseTargetLanguage(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "chinese" ||
        "中文" in normalized ||
        "汉语" in normalized ||
        "simplified chinese" in normalized ||
        "traditional chinese" in normalized ||
        "chinese (" in normalized
}
