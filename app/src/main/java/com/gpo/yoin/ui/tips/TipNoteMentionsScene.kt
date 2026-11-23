package com.gpo.yoin.ui.tips

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.ui.component.NoteLine
import com.gpo.yoin.ui.notes.editor.MentionCandidate
import com.gpo.yoin.ui.notes.editor.MentionRow
import com.gpo.yoin.ui.notes.editor.NoteEditorState
import com.gpo.yoin.ui.notes.editor.NoteFormatToolbar
import com.gpo.yoin.ui.notes.editor.NoteMarkupField
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import kotlinx.coroutines.delay

/**
 * The @ mention tip (notes revamp): the real note editor — the field, its chips and its formatting row —
 * with the popup's real rows drawn inside the phone. The finger types "@", picks an album, and the note
 * saves into a line where the mention reads as a link.
 */
@Composable
internal fun NoteMentionsDemo(playing: Boolean) {
    val editor = remember { NoteEditorState("") }
    val covers = demoCoverUrls()
    val rows = remember(covers) {
        DemoAlbums.take(3).mapIndexed { i, album ->
            MentionCandidate(
                mention = NoteMention(MentionKind.Album, "subsonic", "demo-al-$i", album.name, album.artist),
                coverUrl = covers[i],
                artist = album.artist,
            )
        }
    }
    var popup by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    DemoPhone(playing = playing, script = {
        editor.setText("")
        saved = null
        popup = false
        delay(700)
        // i18n-allow: demo note words, data like DemoTrackTitles
        "Sounds like ".forEach { c ->
            editor.fieldState.edit {
                append(c)
                placeCursorAtEnd()
            }
            delay(55)
        }
        moveTo("mentionTool", fx = 0.08f)
        tap { editor.apply(com.gpo.yoin.ui.notes.editor.NoteTool.Mention) }
        popup = true
        delay(900)
        moveTo("mentionRow1")
        tap {
            editor.insertMention(rows[1].mention)
            popup = false
        }
        delay(1_400)
        lift()
        saved = editor.text
        editor.setText("")
        delay(2_400)
    }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            AnimatedVisibility(
                visible = saved != null,
                enter = YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
            ) {
                NoteLine(stamp = "1:12", text = saved.orEmpty(), isActive = true)
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(
                visible = popup,
                enter = YoinMotion.scaleIn(role = YoinMotionRole.Expressive, initialScale = 0.86f) +
                    YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
                exit = YoinMotion.fadeOut(role = YoinMotionRole.Expressive),
            ) {
                Surface(
                    shape = YoinShapeTokens.LargeIncreased,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        rows.forEachIndexed { i, row ->
                            Box(Modifier.demoTarget("mentionRow$i")) {
                                MentionRow(row = row, query = "", highlighted = i == 0, onClick = {})
                            }
                        }
                    }
                }
            }
            Surface(
                shape = YoinShapeTokens.LargeIncreased,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoteMarkupField(
                        state = editor,
                        modifier = Modifier.fillMaxWidth(),
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 3),
                        mentionPopup = false,
                    )
                    Row {
                        Box(Modifier.demoTarget("mentionTool")) { NoteFormatToolbar(state = editor) }
                    }
                }
            }
        }
    }
}
