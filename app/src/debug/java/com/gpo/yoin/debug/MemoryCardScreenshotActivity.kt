package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.memories.MemoryCardStandalone
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryEntry
import com.gpo.yoin.ui.memories.MemoryNeoDbState
import com.gpo.yoin.ui.memories.MemoryScoreKind
import com.gpo.yoin.ui.memories.MemoryWriting
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Debug-only visual QA for ONE Memories card at the window's breakpoint
 * (portrait / 16:9 / landscape two-column / Wide spread, 断点交接 §6) with
 * fixed fake data. Launch:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoryCardScreenshotActivity
 *
 * `--es mode emblem` shows the groove emblem harness instead (gallery, award replays with a haptic trace;
 * options in GrooveEmblemHarness.kt).
 */
class MemoryCardScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        if (intent.getStringExtra("mode") == "emblem") {
            val options = GrooveHarnessOptions.from(intent)
            setContent { GrooveEmblemHarness(options) }
            return
        }
        setContent {
            val windowInfo = rememberYoinWindowInfo()
            CompositionLocalProvider(LocalYoinWindowInfo provides windowInfo) {
                YoinTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MemoryCardStandalone(memory = fakeMemory())
                    }
                }
            }
        }
    }
}

private fun fakeMemory(): MemoryEntry {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    return MemoryEntry(
        stableId = "memory-1",
        sourceActivityId = 1L,
        entityType = MemoryEntityType.ALBUM,
        entityId = "album-1",
        entityProvider = "subsonic",
        title = "(What's The Story) Morning Glory?",
        supportingText = "Oasis · 1995",
        metaText = null,
        coverArtUrl = null,
        timestamp = now - 3 * day,
        scoreText = "8.6",
        scoreKind = MemoryScoreKind.AVERAGE_TRACK_RATING,
        scoreSupportingText = null,
        footerText = null,
        noteCount = 3,
        ratedTrackCount = 9,
        totalTrackCount = 12,
        playCount = 14,
        firstPlayedAt = now - 200 * day,
        lastPlayedAt = now - 3 * day,
        neoDbState = MemoryNeoDbState.NEEDS_REVIEW,
        memoryTitle = "Britpop 的顶点，也是一场盛大的告别",
        writings = listOf(
            MemoryWriting(MemoryWriting.Kind.SONG_NOTE, "副歌进来的那一下，整个房间都亮了。", now - 4 * day, "Wonderwall", 95_000L),
            MemoryWriting(MemoryWriting.Kind.SONG_NOTE, "七分半的收尾，听完需要坐一会儿。", now - 9 * day, "Champagne Supernova", 300_000L),
            MemoryWriting(MemoryWriting.Kind.ALBUM_NOTE, "夏天的车窗专辑。", now - 20 * day),
        ),
        narrativeCopy = "这张专辑在你的夏天里反复出现：九首打过分，三条笔记，最近一次是三天前。",
        tracks = emptyList(),
        playbackSongs = emptyList(),
    )
}
