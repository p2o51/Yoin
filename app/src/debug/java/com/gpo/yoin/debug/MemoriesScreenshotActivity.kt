package com.gpo.yoin.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.home.HomeMemoriesSection
import com.gpo.yoin.ui.home.HomeMemoryWidget
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File

/**
 * Debug-only launcher for visual QA of the home Memories shelf. Not exported
 * in release. Stand-in covers are solid-colour bitmaps written to cacheDir on
 * first launch, so the backdrop palette tints each shape (and its rating) the
 * way real album art would — no external files, reproducible from a clean
 * checkout. Launch with:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesScreenshotActivity
 */
class MemoriesScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        modifier = Modifier
                            .statusBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                    ) {
                        HomeMemoriesSection(
                            memories = fakeMemories(),
                            extractBackdropColors = true,
                            onOpenMemory = {},
                        )
                    }
                }
            }
        }
    }

    private fun swatchCover(name: String, color: Int): String {
        val file = File(cacheDir, "mem_$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }

    private fun fakeMemories(): List<HomeMemoryWidget> = listOf(
        HomeMemoryWidget(
            sessionId = 1,
            entityType = MemoryEntityType.ALBUM,
            title = "This Infinite",
            subtitle = "Album · Vitesse X",
            coverArtUrl = swatchCover("coral", 0xFFD85A30.toInt()),
            ratingText = "8.4",
            ratingBasis = "Jun 26",
            comment = "Six months on it still opens the same door — warmer every time.",
            expanded = true,
        ),
        HomeMemoryWidget(
            sessionId = 2,
            entityType = MemoryEntityType.ALBUM,
            title = "Describe",
            subtitle = "Album · Hannah Jadagu",
            coverArtUrl = swatchCover("green", 0xFF639922.toInt()),
            ratingText = "7.0",
            ratingBasis = "Based on 5/5 tracks",
            comment = null,
            expanded = true,
        ),
        HomeMemoryWidget(
            sessionId = 3,
            entityType = MemoryEntityType.ALBUM,
            title = "Little House",
            subtitle = "Album · Rachel C.",
            coverArtUrl = swatchCover("pink", 0xFFD4537E.toInt()),
            ratingText = "9.1",
            expanded = false,
        ),
        HomeMemoryWidget(
            sessionId = 4,
            entityType = MemoryEntityType.SONG,
            title = "跳不完的舞",
            subtitle = "Single · 秦凡淇",
            coverArtUrl = swatchCover("olive", 0xFF7C9A1E.toInt()),
            ratingText = "9.5",
            expanded = false,
        ),
        HomeMemoryWidget(
            sessionId = 5,
            entityType = MemoryEntityType.PLAYLIST,
            title = "Endless Natsu",
            subtitle = "Playlist · 51",
            coverArtUrl = swatchCover("teal", 0xFF1D9E75.toInt()),
            ratingText = "N/A",
            expanded = false,
        ),
        HomeMemoryWidget(
            sessionId = 6,
            entityType = MemoryEntityType.ALBUM,
            title = "AIと刹那",
            subtitle = "Album · Mom",
            coverArtUrl = swatchCover("blue", 0xFF378ADD.toInt()),
            ratingText = "6.5",
            expanded = false,
        ),
    )
}
