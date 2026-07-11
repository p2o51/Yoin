package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.memories.memoriesAuroraBackground
import com.gpo.yoin.ui.nowplaying.nowPlayingAuroraBackground
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Debug-only visual QA for the two aurora effects, no playback needed: the
 * top half runs the Now Playing Gemini-thinking wash pinned active, the
 * bottom half the Memories ambient wash. Launch:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.AuroraPreviewActivity
 */
class AuroraPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        val scheme = MaterialTheme.colorScheme
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .nowPlayingAuroraBackground(
                                    baseTop = scheme.surfaceContainer,
                                    baseBottom = scheme.background,
                                    auroraColors = listOf(
                                        scheme.primary,
                                        scheme.tertiary,
                                        scheme.secondary,
                                        scheme.primaryContainer,
                                    ),
                                    auroraActive = true,
                                    playColor = scheme.primary,
                                    pauseColor = scheme.tertiary,
                                    pulseTrigger = null,
                                    isPlaying = false,
                                    pressActive = false,
                                    gatherFocalRoot = null,
                                    burstFocalRoot = null,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "NP · Ask Gemini thinking",
                                style = MaterialTheme.typography.titleMedium,
                                color = scheme.onSurface,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(top = 2.dp)
                                .memoriesAuroraBackground(
                                    baseColor = Color(0xFF639922),
                                    accentColor = Color(0xFFD85A30),
                                    visible = true,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Memories · ambient wash",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
