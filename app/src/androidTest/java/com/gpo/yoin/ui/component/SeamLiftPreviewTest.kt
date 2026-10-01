package com.gpo.yoin.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinContainerShapes
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A tinted card (Home's Activities card shape: container + cover + text) held
 * across the bar: the card and its cover break up as one print, while the text
 * on it is lifted out of the print and passes under the bar whole.
 *
 *   am instrument -w -e captureSeamLift true -e class com.gpo.yoin.ui.component.SeamLiftPreviewTest \
 *     com.gpo.yoin.test/androidx.test.runner.AndroidJUnitRunner
 */
class SeamLiftPreviewTest {
    @Test
    fun should_keepTextWhole_when_itsCardDissolvesAtTheBar() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureSeamLift") == "true")
        val dark = SeamQa.dark(arguments)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "seam-lift").apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it }
            val h = SeamFrameHarness(activity, File(directory, arguments.getString("seamOut") ?: "frames"))
            h.setContent {
                SeamQaWindow(dark = dark, reduced = false) {
                    val state = rememberLazyListState()
                    val page = MaterialTheme.colorScheme.background
                    LazyColumn(
                        state = state,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(page)
                            .seamDissolveViewport(
                                background = SeamBackground(listOf(page)),
                                remainingPx = { state.seamRemainingPx() },
                            ) { state.seamScrolledPx() },
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 400.dp),
                    ) {
                        item { Spacer(modifier = Modifier.height(1.dp)) }
                        items(count = 12) { index ->
                            LiftCard(index)
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }
            repeat(40) { h.tick(realMillis = 10) }
            h.hold(10, "rest")
            // Bring a card across the bar and hold.
            h.down(h.width * .5f, h.height * .5f)
            h.moveBy(0f, -h.touchSlop - 1f)
            repeat(30) {
                h.moveBy(0f, -3f * h.density)
                h.frame()
            }
            h.hold(30)
            h.up()
            h.hold(40, "card-across-bar")
            h.release()
        }
    }
}

@androidx.compose.runtime.Composable
private fun LiftCard(index: Int) {
    val tints = listOf(Color(0xFF6B2737), Color(0xFF264653), Color(0xFF3D405B), Color(0xFF7B5E2A))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .seamDissolve(),
        shape = YoinContainerShapes.Card,
        color = tints[index % tints.size],
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(YoinArtworkShapes.Thumb)
                    .background(Color(0xFFF3C14B))
                    .seamDissolve(),
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .seamFade(),
            ) {
                Text(
                    text = "Album · ${index + 1}h ago",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = .8f),
                )
                Text(
                    text = "Lifted title $index",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
                Text(
                    text = "Text on a dissolving card stays whole",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = .8f),
                )
            }
        }
    }
}
