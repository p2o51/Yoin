package com.gpo.yoin.ui.notes.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The write bar's formatting row (prototype: @, B, I, H, list, to-do), beside 保存. Each button is an M3
 * Expressive toggle (its shape morphs on press) that reads as pressed where the caret is (bold words, a
 * list line). Bold / italic / heading are letters, as in the prototype; @ and the to-do box are Yoin
 * Symbols; the list is a bullet (Yoin Symbols has no list glyph yet).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NoteFormatToolbar(state: NoteEditorState, modifier: Modifier = Modifier) {
    val haptics = rememberYoinHaptics()
    val active = state.activeTools
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        NoteTool.entries.sortedBy { ToolOrder.indexOf(it) }.forEach { tool ->
            val label = stringResource(tool.label())
            IconToggleButton(
                checked = tool in active,
                onCheckedChange = {
                    haptics.performTick()
                    state.apply(tool)
                },
                shapes = IconButtonDefaults.toggleableShapes(),
                modifier = Modifier.size(36.dp).semantics { contentDescription = label },
            ) {
                when (tool) {
                    NoteTool.Mention -> Icon(YoinSymbols.At, contentDescription = null, modifier = Modifier.size(20.dp))
                    NoteTool.Checklist -> Icon(YoinSymbols.CheckBox, contentDescription = null, modifier = Modifier.size(20.dp))
                    NoteTool.Bold -> Glyph("B", weight = FontWeight.Black)
                    NoteTool.Italic -> Glyph("I", italic = true)
                    NoteTool.Heading -> Glyph("H", weight = FontWeight.Bold)
                    NoteTool.Bullet -> Glyph("•", weight = FontWeight.Black, size = 22)
                }
            }
        }
    }
}

private val ToolOrder = listOf(NoteTool.Mention, NoteTool.Bold, NoteTool.Italic, NoteTool.Heading, NoteTool.Bullet, NoteTool.Checklist)

@Composable
private fun Glyph(text: String, weight: FontWeight = FontWeight.SemiBold, italic: Boolean = false, size: Int = 17) {
    Text(
        text = text, // i18n-allow: formatting glyphs, not words
        style = MaterialTheme.typography.titleMedium.copy(
            fontSize = size.sp,
            fontWeight = weight,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        ),
    )
}

private fun NoteTool.label(): Int = when (this) {
    NoteTool.Bold -> R.string.notes_tool_bold
    NoteTool.Italic -> R.string.notes_tool_italic
    NoteTool.Heading -> R.string.notes_tool_heading
    NoteTool.Bullet -> R.string.notes_tool_bullet
    NoteTool.Checklist -> R.string.notes_tool_checklist
    NoteTool.Mention -> R.string.notes_tool_mention
}

@Preview(showBackground = true)
@Composable
private fun NoteFormatToolbarPreview() {
    YoinTheme { NoteFormatToolbar(state = NoteEditorState("**bold** words")) }
}
