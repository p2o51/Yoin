package com.gpo.yoin.ui.openin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.YoinApplication
import com.gpo.yoin.data.openin.MusicLinkMetadata
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.shared.openin.YoinMusicLinkKind
import com.gpo.yoin.ui.detail.AlbumDetailActivity
import com.gpo.yoin.ui.detail.ArtistDetailActivity
import com.gpo.yoin.ui.detail.PlaylistDetailActivity
import com.gpo.yoin.ui.navigation.playSearchResults

/**
 * Choose a match (docs/open-in-yoin.md): a link from a service the user has no account on, searched on the
 * account in use. Tapping a song plays it and returns to the shell; an album, artist or playlist opens its
 * page over this one.
 *
 * Back surface: a full-screen pushed page without shared chrome → native cross-Activity predictive back
 * (Pattern A). No back code here: the top-bar arrow just finishes.
 */
class OpenInResultsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_LINK)
        if (url == null) {
            finish()
            return
        }
        enableYoinEdgeToEdge()
        val container = (application as YoinApplication).container
        val metadata = metadataFrom(intent)
        setContent {
            YoinActivityRoot {
                val viewModel: OpenInResultsViewModel =
                    viewModel(factory = OpenInResultsViewModel.Factory(container, url, metadata))
                val state by viewModel.state.collectAsState()
                OpenInResultsScreen(
                    state = state,
                    onBackClick = { finish() },
                    onRowClick = { row ->
                        when (row) {
                            is OpenInResultRow.Song -> {
                                container.playSearchResults(listOf(row.track), 0)
                                finish()
                            }
                            is OpenInResultRow.AlbumRow ->
                                startActivity(AlbumDetailActivity.intent(this, row.album.id.toString()))
                            is OpenInResultRow.ArtistRow ->
                                startActivity(ArtistDetailActivity.intent(this, row.artist.id.toString()))
                            is OpenInResultRow.PlaylistRow ->
                                startActivity(PlaylistDetailActivity.intent(this, row.playlist.id.toString()))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_LINK = "openInLink"
        private const val EXTRA_KIND = "openInKind"
        private const val EXTRA_TITLE = "openInTitle"
        private const val EXTRA_ARTIST = "openInArtist"
        private const val EXTRA_ALBUM = "openInAlbum"
        private const val EXTRA_ISRC = "openInIsrc"
        private const val EXTRA_DURATION = "openInDuration"

        /** [linkUrl]: the link, followed if it was short; [metadata]: what the card already read, if anything. */
        fun intent(context: Context, linkUrl: String, metadata: MusicLinkMetadata?): Intent =
            Intent(context, OpenInResultsActivity::class.java)
                .putExtra(EXTRA_LINK, linkUrl)
                .apply {
                    metadata ?: return@apply
                    putExtra(EXTRA_KIND, metadata.kind.name)
                    putExtra(EXTRA_TITLE, metadata.title)
                    putExtra(EXTRA_ARTIST, metadata.artist)
                    putExtra(EXTRA_ALBUM, metadata.album)
                    putExtra(EXTRA_ISRC, metadata.isrc)
                    metadata.durationSec?.let { putExtra(EXTRA_DURATION, it) }
                }

        private fun metadataFrom(intent: Intent): MusicLinkMetadata? {
            val kind = intent.getStringExtra(EXTRA_KIND)
                ?.let { name -> YoinMusicLinkKind.entries.firstOrNull { it.name == name } }
                ?: return null
            return MusicLinkMetadata(
                kind = kind,
                title = intent.getStringExtra(EXTRA_TITLE),
                artist = intent.getStringExtra(EXTRA_ARTIST),
                album = intent.getStringExtra(EXTRA_ALBUM),
                isrc = intent.getStringExtra(EXTRA_ISRC),
                durationSec = intent.getIntExtra(EXTRA_DURATION, 0).takeIf { it > 0 },
            )
        }
    }
}
