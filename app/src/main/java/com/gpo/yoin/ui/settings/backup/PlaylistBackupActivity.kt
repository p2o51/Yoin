package com.gpo.yoin.ui.settings.backup

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
 * One playlist backup, opened from Settings › Playlist backups
 * (docs/playlist-transfer.md §7): its songs, what couldn't be backed up, its
 * restores, Restore and Delete.
 *
 * Back surface: full-screen destination without shared chrome → native
 * cross-Activity predictive back (Pattern A). No back code here: the top-bar
 * arrow just finishes, and the restore sheet's own back is the shared
 * YoinModalBottomSheet bridge. In the Settings list-detail it opens over the
 * backups list in the right pane.
 */
class PlaylistBackupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val backupId = intent.getStringExtra(EXTRA_BACKUP_ID)
        if (backupId == null) {
            finish()
            return
        }
        val container = (application as YoinApplication).container
        setContent {
            YoinActivityRoot {
                val viewModel: PlaylistBackupViewModel = viewModel(
                    factory = PlaylistBackupViewModel.Factory(container, backupId),
                )
                PlaylistBackupRoute(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        private const val EXTRA_BACKUP_ID = "backup_id"

        fun intent(context: Context, backupId: String): Intent =
            Intent(context, PlaylistBackupActivity::class.java).putExtra(EXTRA_BACKUP_ID, backupId)
    }
}
