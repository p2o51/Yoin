package com.gpo.yoin.data.lyrics

import com.gpo.yoin.data.local.LyricsCache

/**
 * 歌词缓存（`lyrics_cache`）的寿命规则。2026-10-05 owner 拍板：用户选择的歌词不按天数过期，
 * 整个缓存按总大小封顶。
 *
 * 表里有两种行，不改 schema 就能区分：
 * - **自动行**：自动兜底链（QQ → 网易云 → LRCLIB）挑的。[AUTOMATIC_TTL_MS] 后重新匹配，这样更好的
 *   来源或者 matcher 的修正还能把它换掉。
 * - **用户选的行**：手写 / 粘贴（`lyricsProvider` = [MANUAL_PROVIDER]）、从歌词搜索面板应用的任何
 *   一家的结果、接受了的"整套切换到带译文的源"。非 manual 的这种行在 `lyricsProviderSongId` 前面
 *   带 [USER_CHOSEN_SONG_ID_PREFIX]，读出来用 [providerSongId] 去掉。它们不按天数过期；重新加载时
 *   压过自动歌词，也压过 Subsonic 服务端歌词；`cachedAt` 对它们表示"最近一次用到"，读的时候刷新
 *   （最多每 [RECENCY_REFRESH_MS] 写一次库）。换回自动：重新搜索 / 应用别的结果会覆盖它。
 *
 * 大小预算：每首歌的占用 = 歌词行 `lrc` 的 UTF-8 字节 + 这首歌免费的 `provider:*` 译文行
 * `translationsJson` 的 UTF-8 字节（译文跟着歌词行走，一起计数、一起淘汰）。总量超过 [BUDGET_BYTES]
 * 就按 [planEviction] 的顺序淘汰到 [TRIM_TARGET_BYTES]。付费的 Gemini 译文行不计入、也不淘汰：
 * 重做要花钱，而且云同步对这类行是 write-once union、不传播删除，本地删掉下一轮就会被同步补回来。
 * 手写 / 粘贴的歌词行（[MANUAL_PROVIDER]）计入总量，但预算永远不淘汰它们：哪家都拉不回来。
 * 只剩它们（和刚写的那首）还压不到目标时就停手，由 [LyricsCacheBudget] 记一条日志。
 */
object LyricsCachePolicy {

    /** 手写 / 粘贴的歌词行用的 `lyricsProvider`。云同步那边也认这个值（永不过期、从不覆盖）。 */
    const val MANUAL_PROVIDER = "manual"

    /** 非 manual 的用户选择行在 `lyricsProviderSongId` 前加的标记。各家 provider 的 id 都不会以它开头。 */
    const val USER_CHOSEN_SONG_ID_PREFIX = "user|"

    /** 自动行的有效期：30 天。 */
    const val AUTOMATIC_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

    /** 用户选的行"最近使用"时间的刷新粒度：一天最多写一次库。 */
    const val RECENCY_REFRESH_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * 整个歌词缓存的预算：10 MiB。一首歌的同步 LRC 大约 2–4 KB（中日韩字符 UTF-8 每字 3 字节），
     * 带上自带译文约 4–6 KB，所以能放大约 2,000 首。30 天内常听的歌（重度用户约 1,000 首、约 5 MB）
     * 加上用户选过的歌都放得下；超过 30 天的自动行反正要重新匹配，最先被淘汰也几乎没有损失。
     */
    const val BUDGET_BYTES: Long = 10L * 1024L * 1024L

    /** 一旦超出预算就清到这里，留 1 MiB 余量，免得之后每写一次都要清一次。 */
    const val TRIM_TARGET_BYTES: Long = 9L * 1024L * 1024L

    /** 免费的、能重新拉的歌词源自带译文在 `lyrics_translation_cache.model` 里的前缀。 */
    const val PROVIDER_TRANSLATION_MODEL_PREFIX = "provider:"

    fun isUserChosen(entry: LyricsCache): Boolean =
        isUserChosen(entry.lyricsProvider, entry.lyricsProviderSongId)

    fun isUserChosen(lyricsProvider: String, storedSongId: String?): Boolean =
        isTyped(lyricsProvider) ||
            storedSongId?.startsWith(USER_CHOSEN_SONG_ID_PREFIX) == true

    /** 手写 / 粘贴的歌词：没有来源可以重新拉，预算永远不淘汰。 */
    fun isTyped(lyricsProvider: String): Boolean = lyricsProvider == MANUAL_PROVIDER

    /** provider 自己的 song id，去掉用户选择标记；空串当 null。 */
    fun providerSongId(entry: LyricsCache): String? {
        val stored = entry.lyricsProviderSongId ?: return null
        if (!stored.startsWith(USER_CHOSEN_SONG_ID_PREFIX)) return stored
        return stored.removePrefix(USER_CHOSEN_SONG_ID_PREFIX).ifEmpty { null }
    }

    /** 能直接拿来显示：用户选的行任何年龄都算；自动行要在 TTL 内。 */
    fun isUsable(entry: LyricsCache, now: Long): Boolean =
        isUserChosen(entry) || entry.cachedAt >= now - AUTOMATIC_TTL_MS

    /** 用户选的行这次读取要不要把 `cachedAt` 刷成现在。自动行的 `cachedAt` 是 TTL 起点，永远不碰。 */
    fun needsRecencyRefresh(entry: LyricsCache, now: Long): Boolean =
        isUserChosen(entry) && now - entry.cachedAt >= RECENCY_REFRESH_MS

    /** 组一行缓存。[userChosen] 为 true 且不是 manual 时，在 song id 上打标记。 */
    fun entry(
        trackProvider: String,
        trackRawId: String,
        lyricsProvider: String,
        lyricsProviderSongId: String?,
        lrc: String,
        cachedAt: Long,
        userChosen: Boolean,
    ): LyricsCache = LyricsCache(
        trackProvider = trackProvider,
        trackRawId = trackRawId,
        lyricsProvider = lyricsProvider,
        lyricsProviderSongId = if (userChosen && lyricsProvider != MANUAL_PROVIDER) {
            USER_CHOSEN_SONG_ID_PREFIX + lyricsProviderSongId.orEmpty()
        } else {
            lyricsProviderSongId
        },
        lrc = lrc,
        cachedAt = cachedAt,
    )

    fun utf8Bytes(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 一首歌在预算里的占用。[lyricsCachedAt] 是歌词行的 `cachedAt`（淘汰时用它确认行没被改过），
     * 只剩译文、没有歌词行的孤儿为 null。[typed] = 手写 / 粘贴的歌词（见 [isTyped]），永远不淘汰。
     */
    data class Footprint(
        val trackProvider: String,
        val trackRawId: String,
        val userChosen: Boolean,
        val lastUsedAt: Long,
        val bytes: Long,
        val lyricsCachedAt: Long?,
        val typed: Boolean = false,
    ) {
        fun isTrack(provider: String, rawId: String): Boolean =
            trackProvider == provider && trackRawId == rawId
    }

    /**
     * 总占用超过 [budgetBytes] 时，返回要淘汰的歌，按淘汰先后排列，淘汰到不超过 [targetBytes] 为止：
     * 先淘汰自动行（和孤儿译文），旧的先走；再淘汰用户从搜索里选的行，最久没用的先走。
     * 手写 / 粘贴的行（[Footprint.typed]）和 [protectedTrack]（刚写入的那首，哪怕它一首就超过预算）
     * 永远不淘汰；剩下的全淘汰了还超过 [targetBytes] 就到此为止，返回的列表可能压不到目标。
     */
    fun planEviction(
        footprints: List<Footprint>,
        budgetBytes: Long = BUDGET_BYTES,
        targetBytes: Long = TRIM_TARGET_BYTES,
        protectedTrack: Pair<String, String>? = null,
    ): List<Footprint> {
        var remaining = footprints.sumOf(Footprint::bytes)
        if (remaining <= budgetBytes) return emptyList()
        val victims = ArrayList<Footprint>()
        for (candidate in footprints.sortedWith(EVICTION_ORDER)) {
            if (remaining <= targetBytes) break
            if (candidate.typed) continue
            if (protectedTrack != null && candidate.isTrack(protectedTrack.first, protectedTrack.second)) continue
            victims += candidate
            remaining -= candidate.bytes
        }
        return victims
    }

    private val EVICTION_ORDER: Comparator<Footprint> =
        compareBy<Footprint>({ it.userChosen }, { it.lastUsedAt }, { it.trackProvider }, { it.trackRawId })
}
