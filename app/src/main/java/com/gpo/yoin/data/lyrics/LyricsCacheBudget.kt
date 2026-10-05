package com.gpo.yoin.data.lyrics

import android.util.Log
import com.gpo.yoin.data.local.LyricsCacheDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 让歌词缓存不超过 [LyricsCachePolicy.BUDGET_BYTES] 的大小预算。
 *
 * 每次写入歌词行或免费译文行之后调 [onWrite]。进程里第一次写入时扫一遍表算出精确总量，
 * 之后只在内存里累加估计值（替换行时不减，所以只会高估），估计值越过预算才再扫表、
 * 按 [LyricsCachePolicy.planEviction] 淘汰。所以平时一次写入不多查一次库。
 *
 * 手写 / 粘贴的歌词（`manual`）永远不淘汰。只剩它们和刚写的那首还压不到目标时就停手，
 * 记一条警告（停在超预算状态期间只记一次）；之后每次写入都会重新扫表——要攒到这种程度
 * 得手写两千多首，不值得为它另做一套计数。
 */
class LyricsCacheBudget(
    private val dao: LyricsCacheDao,
    private val budgetBytes: Long = LyricsCachePolicy.BUDGET_BYTES,
    private val targetBytes: Long = LyricsCachePolicy.TRIM_TARGET_BYTES,
) {
    private val mutex = Mutex()

    /** 估计的总占用；[UNKNOWN] 表示还没扫过表。只在 [mutex] 里读写。 */
    private var estimatedBytes: Long = UNKNOWN

    /** 已经为"淘汰完还超预算"记过日志了，压回目标以内之前不再记。只在 [mutex] 里读写。 */
    private var stuckReported = false

    /**
     * 刚给 ([trackProvider], [trackRawId]) 写了 [addedBytes] 字节。需要的话淘汰别的歌；
     * 刚写的这首不淘汰。出错只记日志——清理失败不能让歌词加载 / 应用失败。
     */
    suspend fun onWrite(trackProvider: String, trackRawId: String, addedBytes: Long) {
        try {
            mutex.withLock {
                val estimate = estimatedBytes
                if (estimate != UNKNOWN && estimate + addedBytes <= budgetBytes) {
                    estimatedBytes = estimate + addedBytes
                } else {
                    trimLocked(protectedTrack = trackProvider to trackRawId)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mutex.withLock { estimatedBytes = UNKNOWN }
            Log.w(TAG, "Lyrics cache trim failed", error)
        }
    }

    /** 立刻按精确总量检查一次，返回淘汰了几首。 */
    suspend fun trim(): Int = mutex.withLock { trimLocked(protectedTrack = null) }

    private suspend fun trimLocked(protectedTrack: Pair<String, String>?): Int {
        val footprints = footprints()
        val total = footprints.sumOf(LyricsCachePolicy.Footprint::bytes)
        val victims = LyricsCachePolicy.planEviction(
            footprints = footprints,
            budgetBytes = budgetBytes,
            targetBytes = targetBytes,
            protectedTrack = protectedTrack,
        )
        reportIfStuck(footprints, total = total, left = total - victims.sumOf(LyricsCachePolicy.Footprint::bytes))
        var freed = 0L
        var evicted = 0
        for (victim in victims) {
            if (evict(victim)) {
                freed += victim.bytes
                evicted++
            }
        }
        estimatedBytes = total - freed
        return evicted
    }

    /** 能淘汰的都排上了还压不到 [targetBytes]：剩下的是手写歌词和刚写的那首。记一次日志。 */
    private fun reportIfStuck(footprints: List<LyricsCachePolicy.Footprint>, total: Long, left: Long) {
        val stuck = total > budgetBytes && left > targetBytes
        if (stuck && !stuckReported) {
            val typedBytes = footprints.filter(LyricsCachePolicy.Footprint::typed)
                .sumOf(LyricsCachePolicy.Footprint::bytes)
            Log.w(
                TAG,
                "Lyrics cache stays over budget: $left bytes left after eviction (target $targetBytes); " +
                    "$typedBytes bytes are typed lyrics, which are never evicted",
            )
        }
        stuckReported = stuck
    }

    /** 先删歌词行（这期间被重写过就放弃），再删它的免费译文行。孤儿译文直接删。 */
    private suspend fun evict(victim: LyricsCachePolicy.Footprint): Boolean {
        val lyricsCachedAt = victim.lyricsCachedAt
        if (lyricsCachedAt != null &&
            dao.deleteIfUnchanged(victim.trackProvider, victim.trackRawId, lyricsCachedAt) == 0
        ) {
            return false
        }
        dao.deleteProviderTranslations(victim.trackProvider, victim.trackRawId)
        return true
    }

    private suspend fun footprints(): List<LyricsCachePolicy.Footprint> {
        val translations = dao.providerTranslationFootprints()
            .associateBy { it.trackProvider to it.trackRawId }
        val lyrics = dao.lyricsFootprints()
        val withLyrics = lyrics.map { row ->
            val translation = translations[row.trackProvider to row.trackRawId]
            LyricsCachePolicy.Footprint(
                trackProvider = row.trackProvider,
                trackRawId = row.trackRawId,
                userChosen = LyricsCachePolicy.isUserChosen(row.lyricsProvider, row.lyricsProviderSongId),
                lastUsedAt = row.cachedAt,
                bytes = row.lrcBytes + (translation?.bytes ?: 0L),
                lyricsCachedAt = row.cachedAt,
                typed = LyricsCachePolicy.isTyped(row.lyricsProvider),
            )
        }
        val lyricKeys = lyrics.mapTo(HashSet()) { it.trackProvider to it.trackRawId }
        val orphans = translations.values
            .filter { (it.trackProvider to it.trackRawId) !in lyricKeys }
            .map { row ->
                LyricsCachePolicy.Footprint(
                    trackProvider = row.trackProvider,
                    trackRawId = row.trackRawId,
                    userChosen = false,
                    lastUsedAt = row.lastCachedAt,
                    bytes = row.bytes,
                    lyricsCachedAt = null,
                )
            }
        return withLyrics + orphans
    }

    private companion object {
        const val TAG = "LyricsCacheBudget"
        const val UNKNOWN = -1L
    }
}
