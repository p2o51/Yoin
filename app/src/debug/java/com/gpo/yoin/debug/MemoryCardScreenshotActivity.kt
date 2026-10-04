package com.gpo.yoin.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gpo.yoin.enableYoinEdgeToEdge

/**
 * Debug-only visual QA for Memories, on fixed fake data (no ViewModel). Launch:
 *   adb shell am start -n com.gpo.yoin/com.gpo.yoin.debug.MemoryCardScreenshotActivity --es mode showcase
 *
 * `--es mode showcase` (the default): the whole showcase deck from the prototype's m1–m5 fixtures, with the
 * real gesture router, award lifecycle and a stand-in host q; options in MemoriesShowcaseHarness.kt
 * (`--ei page`, `--ez dark`, `--ez reduced`, `--ez trace`).
 * `--es mode emblem`: the groove emblem harness (gallery, award replays with a haptic trace; options in
 * GrooveEmblemHarness.kt).
 */
class MemoryCardScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        when (intent.getStringExtra("mode")) {
            "emblem" -> {
                val options = GrooveHarnessOptions.from(intent)
                setContent { GrooveEmblemHarness(options) }
            }
            else -> {
                val options = ShowcaseHarnessOptions.from(intent)
                setContent { MemoriesShowcaseHarness(options) }
            }
        }
    }
}
