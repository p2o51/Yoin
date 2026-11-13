package com.gpo.yoin.ui.detail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.enableYoinEdgeToEdge

/**
 * "别人怎么说": what listeners on NeoDB wrote about an album (owner, 2026-11-09: a reference with a bit of a
 * social feel, never shown unasked). Opened only from a small button beside the NeoDB line of the rate sheet and
 * of the Memories diary; the entries are for testing and may move.
 *
 * Back surface: a full-screen page with no shared chrome, so native cross-Activity predictive back (Pattern A),
 * like [com.gpo.yoin.ui.settings.backup.PlaylistBackupActivity]. The top-bar arrow just finishes.
 */
class NeoDbVoicesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val albumName = intent.getStringExtra(EXTRA_ALBUM_NAME)?.takeIf(String::isNotBlank)
        if (albumName == null) {
            finish()
            return
        }
        val artistName = intent.getStringExtra(EXTRA_ARTIST_NAME)?.takeIf(String::isNotBlank)
        val coverArtUrl = intent.getStringExtra(EXTRA_COVER_ART_URL)
        val container = (application as YoinApplication).container
        setContent {
            YoinActivityRoot {
                val viewModel: NeoDbVoicesViewModel = viewModel(
                    factory = NeoDbVoicesViewModel.Factory(container, albumName, artistName),
                )
                NeoDbVoicesRoute(
                    viewModel = viewModel,
                    coverArtUrl = coverArtUrl,
                    onBackClick = { finish() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ALBUM_NAME = "album_name"
        private const val EXTRA_ARTIST_NAME = "artist_name"
        private const val EXTRA_COVER_ART_URL = "cover_art_url"

        fun intent(context: Context, albumName: String, artistName: String?, coverArtUrl: String?): Intent =
            Intent(context, NeoDbVoicesActivity::class.java)
                .putExtra(EXTRA_ALBUM_NAME, albumName)
                .putExtra(EXTRA_ARTIST_NAME, artistName)
                .putExtra(EXTRA_COVER_ART_URL, coverArtUrl)
    }
}
