package com.gpo.yoin.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.gpo.yoin.data.notes.BacklinkTarget
import com.gpo.yoin.data.notes.NoteBacklink
import com.gpo.yoin.data.notes.NoteBacklinkRepository
import kotlinx.coroutines.flow.flowOf

/** The window's 被提及 source (the account in use); null in previews. */
val LocalNoteBacklinks = staticCompositionLocalOf<NoteBacklinkRepository?> { null }

/** The writings that mention [target], live; empty without a target or a repository. */
@Composable
fun rememberNoteBacklinks(target: BacklinkTarget?): List<NoteBacklink> {
    val repository = LocalNoteBacklinks.current
    val flow = remember(repository, target) {
        if (repository == null || target == null) flowOf(emptyList()) else repository.observe(target)
    }
    return flow.collectAsState(initial = emptyList()).value
}
