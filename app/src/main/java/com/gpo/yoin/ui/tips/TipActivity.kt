package com.gpo.yoin.ui.tips

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.home.HomeBubbleTip

/**
 * One tip on its own page (owner 2026-10-18), opened from Home's bubble or
 * Settings › About › Tips. A pushed page with Pattern A back: native
 * cross-Activity predictive back, no back code; the back button finishes.
 */
class TipActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_TIP)
            ?.let { name -> HomeBubbleTip.entries.firstOrNull { it.name == name } }
            ?: HomeBubbleTip.entries.first()
        setContent {
            YoinActivityRoot {
                TipScreen(
                    startTip = start,
                    onBackClick = { finish() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TIP = "tip"

        internal fun intent(context: Context, tip: HomeBubbleTip): Intent =
            Intent(context, TipActivity::class.java).putExtra(EXTRA_TIP, tip.name)
    }
}
