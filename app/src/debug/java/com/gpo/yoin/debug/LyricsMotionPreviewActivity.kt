package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.component.LyricsDisplay
import com.gpo.yoin.ui.nowplaying.LyricLine
import com.gpo.yoin.ui.nowplaying.LyricsFullscreenPane
import com.gpo.yoin.ui.nowplaying.UpNextLyrics

/**
 * Playback-free QA for lyrics motion: translation expand/collapse, song
 * change inside an open lyrics view, the intro title card, and the
 * loading → loaded dissolve. The playhead is a local clock.
 *
 *   adb shell am start -n com.gpo.yoin/.debug.LyricsMotionPreviewActivity
 *   (--ez translation true | --ei song 1 | --es mode compact | --el startAt 22000)
 */
class LyricsMotionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val startTranslation = intent.getBooleanExtra("translation", false)
        // --el startAt 22000: begin the first song near its outro.
        val startAtMs = intent.getLongExtra("startAt", 0L)
        val startSong = intent.getIntExtra("song", 0)
        val compact = intent.getStringExtra("mode") == "compact"
        setContent {
            YoinActivityRoot {
                var songIndex by remember { mutableIntStateOf(startSong) }
                var showTranslation by remember { mutableStateOf(startTranslation) }
                // Auto-advance models the real outro: the next song's lyrics
                // were prefetched, so it arrives with no loading beat.
                var arrivedPrefetched by remember { mutableStateOf(false) }
                val song = SampleSongs[songIndex]
                val nextSong = SampleSongs[(songIndex + 1) % SampleSongs.size]
                // Keyed on the song so the reset lands in the SAME frame as the
                // song change (the real VM clears lyrics + playhead with it).
                var loading by remember(songIndex) { mutableStateOf(!arrivedPrefetched) }
                val firstSong = remember { booleanArrayOf(true) }
                val position = remember(songIndex) {
                    mutableLongStateOf(if (firstSong[0]) startAtMs else SongStartMs)
                }
                // Local playhead: restarts per song, like a real track change.
                LaunchedEffect(songIndex) {
                    if (loading) {
                        kotlinx.coroutines.delay(LoadingMs)
                        loading = false
                    }
                    val startFrom = position.longValue
                    firstSong[0] = false
                    val origin = withFrameMillis { it }
                    while (position.longValue < song.durationMs) {
                        withFrameMillis { now -> position.longValue = startFrom + now - origin }
                    }
                    arrivedPrefetched = true
                    songIndex = (songIndex + 1) % SampleSongs.size
                }
                val lyrics = if (loading) emptyList() else song.lines
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                arrivedPrefetched = false
                                songIndex = (songIndex - 1 + SampleSongs.size) % SampleSongs.size
                            }) { Text("Prev") }
                            FilledTonalButton(onClick = {
                                arrivedPrefetched = false
                                songIndex = (songIndex + 1) % SampleSongs.size
                            }) {
                                Text("Next")
                            }
                            FilledTonalButton(onClick = { showTranslation = !showTranslation }) {
                                Text(if (showTranslation) "译 off" else "译 on")
                            }
                        }
                        if (compact) {
                            LyricsDisplay(
                                lyrics = lyrics,
                                positionMs = { position.longValue },
                                loading = loading,
                                trackKey = song.title,
                                queueIndex = songIndex,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 120.dp)
                                    .weight(1f, fill = false),
                            )
                        } else {
                            LyricsFullscreenPane(
                                lyrics = lyrics,
                                positionMs = { position.longValue },
                                loading = loading,
                                showTranslation = showTranslation,
                                autoScrollEnabled = true,
                                recenterRequestKey = 0,
                                onUserScroll = {},
                                onSeekToMs = {},
                                trackKey = song.title,
                                queueIndex = songIndex,
                                songTitle = song.title,
                                artist = song.artist,
                                upNext = UpNextLyrics(
                                    songId = nextSong.title,
                                    title = nextSong.title,
                                    artist = nextSong.artist,
                                    lines = nextSong.lines,
                                ),
                                durationMs = song.durationMs,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    private class SampleSong(
        val title: String,
        val artist: String,
        val lines: List<LyricLine>,
        val durationMs: Long,
    )

    private companion object {
        const val SongStartMs = 0L
        const val LoadingMs = 450L

        fun timed(vararg lines: Pair<String, String>, firstMs: Long, stepMs: Long) =
            lines.mapIndexed { i, (text, tr) -> LyricLine(firstMs + i * stepMs, text, tr) }

        // Original sample lines (not real song lyrics).
        val SampleSongs = listOf(
            SampleSong(
                title = "Harbor Lights",
                artist = "Sample Artist",
                lines = timed(
                    "Morning folds the harbor into blue" to "清晨把港口折进蓝色里",
                    "Every rope remembers where it's tied" to "每根缆绳都记得系在哪里",
                    "I count the gulls and lose the count again" to "我数着海鸥，又一次数乱",
                    "The ferry hums a song it never learned" to "渡轮哼着一首从没学过的歌",
                    "Salt on the window, light on the floor" to "窗上有盐，地上有光",
                    "We stay a little longer than we planned" to "我们比计划多待了一会儿",
                    "And the tide keeps time for both of us" to "潮水替我们两个打着拍子",
                    "Until the evening calls the boats back home" to "直到傍晚把船只唤回家",
                    firstMs = 3_000L,
                    stepMs = 2_600L,
                ),
                durationMs = 30_000L,
            ),
            SampleSong(
                title = "Paper Satellites",
                artist = "Another Sample",
                lines = timed(
                    "We folded maps into little planes" to "我们把地图折成小飞机",
                    "And threw them past the streetlight's edge" to "扔过路灯照不到的边缘",
                    "Some came back with rain on their wings" to "有的带着翅膀上的雨回来",
                    "Some are orbiting the roof tonight" to "有的今晚还在屋顶上盘旋",
                    "Your voice is static on a clear channel" to "你的声音是晴朗频道里的杂音",
                    "Still I hear every word you mean" to "可我听得见你想说的每个字",
                    firstMs = 4_000L,
                    stepMs = 2_400L,
                ),
                durationMs = 24_000L,
            ),
        )
    }
}
