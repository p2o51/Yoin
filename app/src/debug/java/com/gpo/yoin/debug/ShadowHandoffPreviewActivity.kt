package com.gpo.yoin.debug

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.component.BottomBarShadowPageCoverEffect
import com.gpo.yoin.ui.component.FloatingBottomBar
import com.gpo.yoin.ui.component.rememberBottomBarShadowHandBack
import com.gpo.yoin.ui.detail.applyDetailCloseTransition
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import kotlinx.coroutines.delay

/**
 * Account-free repro of the cross-window bottom-bar shadow hand-off: a
 * shell-like window and a translucent detail-like window, each with the real
 * [FloatingBottomBar] (so the real shadow registry), the real hand-off window
 * animations, and the real close dissolve. The detail shows only its bar for
 * the 200ms bar-hold, then its page slides in from 96dp; on close the page
 * dissolves, the bar hands its shadow back, then the window finishes — the
 * same order the real detail pages use. (ShadowReturnAuditActivity drives the
 * real AlbumDetailActivity back commit instead.)
 *
 *   adb shell am start -n com.gpo.yoin/.debug.ShadowHandoffPreviewActivity --ei cycles 2
 *   (warm re-run: add -f 0x20000000)
 */
class ShadowHandoffPreviewActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var cyclesLeft = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        cyclesLeft = intent.getIntExtra("cycles", 0)
        setContent {
            YoinActivityRoot {
                FakePage(title = "Home", tint = MaterialTheme.colorScheme.surface)
            }
        }
    }

    // Re-trigger on a warm instance (am start -f 0x20000000 --ei cycles N),
    // so a recording can start after the cold-launch splash has settled.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // onResume follows (pause → onNewIntent → resume) and schedules it.
        cyclesLeft = intent.getIntExtra("cycles", 0)
    }

    override fun onResume() {
        super.onResume()
        if (cyclesLeft > 0) {
            cyclesLeft--
            handler.postDelayed(::openDetail, AUTO_OPEN_DELAY_MS)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun openDetail() {
        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.detail_bar_handoff_enter,
            R.anim.detail_bar_handoff_exit,
        )
        startActivity(Intent(this, ShadowHandoffDetailActivity::class.java), options.toBundle())
    }

    private companion object {
        const val AUTO_OPEN_DELAY_MS = 1_400L
    }
}

class ShadowHandoffDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        applyDetailCloseTransition()
        setContent {
            YoinActivityRoot(deferBottomBarShadow = true) {
                var pageShown by remember { mutableStateOf(false) }
                val slide = remember { Animatable(1f) }
                val pageAlpha = remember { Animatable(1f) }
                val handBackBarShadow = rememberBottomBarShadowHandBack()
                val exitSpec = YoinMotion.defaultEffectsSpec<Float>(role = YoinMotionRole.Standard)
                LaunchedEffect(Unit) {
                    delay(BAR_HOLD_MS)
                    pageShown = true
                    slide.animateTo(0f, tween(ENTER_MS, easing = EmphasizedEasing))
                    delay(AUTO_CLOSE_AFTER_MS)
                    // The shipping close order: the page dissolves, the bar's
                    // shadow is handed back to the bar beneath, then finish.
                    pageAlpha.animateTo(0f, exitSpec)
                    handBackBarShadow(exitSpec)
                    finish()
                }
                Box(Modifier.fillMaxSize()) {
                    if (pageShown) {
                        BottomBarShadowPageCoverEffect()
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    translationX = 96.dp.toPx() * slide.value
                                    alpha = pageAlpha.value
                                },
                        ) {
                            FakePage(
                                title = "Album",
                                tint = MaterialTheme.colorScheme.surfaceContainerLow,
                                showBar = false,
                            )
                        }
                    }
                    PreviewBar(Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    private companion object {
        const val BAR_HOLD_MS = 200L
        const val ENTER_MS = 450
        const val AUTO_CLOSE_AFTER_MS = 1_200L
        val EmphasizedEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }
}

@Composable
private fun FakePage(
    title: String,
    tint: androidx.compose.ui.graphics.Color,
    showBar: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(tint),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) { col ->
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if ((row + col) % 2 == 0) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    },
                                    RoundedCornerShape(16.dp),
                                ),
                        )
                    }
                }
            }
        }
        if (showBar) PreviewBar(Modifier.align(Alignment.BottomCenter))
    }
}

/** Identical in both windows, like the real pixel-twin bars. */
@Composable
private fun PreviewBar(modifier: Modifier) {
    FloatingBottomBar(modifier = modifier) {
        Text("Home", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(1.dp))
        Text("Library", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    }
}
