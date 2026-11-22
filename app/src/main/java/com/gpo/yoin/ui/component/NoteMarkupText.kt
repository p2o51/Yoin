package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.gpo.yoin.shared.notes.NoteBlock
import com.gpo.yoin.shared.notes.NoteDocument
import com.gpo.yoin.shared.notes.NoteInline
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.ui.theme.YoinTheme

/*
 * Note text as Yoin draws it (notes revamp phase A): headings, list items, to-dos, bold, italic and
 * mentions, from the stored Markdown (shared/notes/NoteMarkup.kt). A mention shows its name: in the
 * mention colour (tappable when the host passes a callback) when it resolves, as plain words in the
 * text's own colour when it doesn't (owner 2026-11-22: not grey). The editor (@ popup, toolbar, chips)
 * is phase B; until then the write fields edit the stored text as it is.
 */

/** Glyphs the read-only text draws for list items and to-dos (phase B draws real boxes). */
internal object NoteMarkupGlyphs {
    const val BULLET = "•  "
    const val TODO = "☐  "
    const val DONE = "☑  "
}

/**
 * [document] as an AnnotatedString. A resolved mention ([isMentionResolved]) wears [mentionStyle], and with
 * [onMentionClick] it is a link; an unresolved one is plain text.
 */
fun buildNoteAnnotatedString(
    document: NoteDocument,
    mentionStyle: SpanStyle,
    isMentionResolved: (NoteMention) -> Boolean = { true },
    onMentionClick: ((NoteMention) -> Unit)? = null,
): AnnotatedString = buildAnnotatedString {
    fun inlines(nodes: List<NoteInline>) {
        nodes.forEach { node ->
            when (node) {
                is NoteInline.Text -> append(node.text)
                is NoteInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlines(node.children) }
                is NoteInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(node.children) }
                is NoteInline.Mention -> {
                    val mention = node.mention
                    when {
                        !isMentionResolved(mention) -> append(mention.title)
                        onMentionClick != null -> withLink(
                            LinkAnnotation.Clickable(
                                tag = mention.mediaId,
                                styles = TextLinkStyles(style = mentionStyle),
                                linkInteractionListener = { onMentionClick(mention) },
                            ),
                        ) { append(mention.title) }
                        else -> withStyle(mentionStyle) { append(mention.title) }
                    }
                }
            }
        }
    }
    document.blocks.forEachIndexed { index, block ->
        if (index > 0) append('\n')
        when (block) {
            is NoteBlock.Paragraph -> inlines(block.inlines)
            is NoteBlock.Heading -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (block.level == 1) 1.15.em else 1.05.em),
            ) { inlines(block.inlines) }
            is NoteBlock.Bullet -> {
                append(NoteMarkupGlyphs.BULLET)
                inlines(block.inlines)
            }
            is NoteBlock.Checklist -> {
                append(if (block.checked) NoteMarkupGlyphs.DONE else NoteMarkupGlyphs.TODO)
                inlines(block.inlines)
            }
        }
    }
}

/** The mention span: the given colour, a touch heavier than the words around it. */
fun noteMentionStyle(color: Color): SpanStyle = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)

/** [text] parsed and built once per text / colour / resolution; the click goes to the latest callback. */
@Composable
fun rememberNoteMarkup(
    text: String,
    mentionColor: Color = MaterialTheme.colorScheme.primary,
    isMentionResolved: (NoteMention) -> Boolean = AllMentionsResolved,
    onMentionClick: ((NoteMention) -> Unit)? = null,
): AnnotatedString {
    val document = remember(text) { NoteMarkup.parse(text) }
    val latestClick by rememberUpdatedState(onMentionClick)
    val clickable = onMentionClick != null
    return remember(document, mentionColor, isMentionResolved, clickable) {
        buildNoteAnnotatedString(
            document = document,
            mentionStyle = noteMentionStyle(mentionColor),
            isMentionResolved = isMentionResolved,
            onMentionClick = if (clickable) { mention -> latestClick?.invoke(mention) } else null,
        )
    }
}

/** Every mention drawn as one (until a resolver says otherwise; phase B passes the resolver's answer). */
val AllMentionsResolved: (NoteMention) -> Boolean = { true }

/** A note, album note or review drawn from its stored text. */
@Composable
fun NoteMarkupText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    mentionColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    isMentionResolved: (NoteMention) -> Boolean = AllMentionsResolved,
    onMentionClick: ((NoteMention) -> Unit)? = null,
) {
    Text(
        text = rememberNoteMarkup(text, mentionColor, isMentionResolved, onMentionClick),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun NoteMarkupTextPreview() {
    YoinTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                NoteMarkupText(
                    text = "# 现场版要记得\n- [x] 开头那段哼唱\n- [ ] 第二段副歌的和声\n" +
                        "**这一句**每次都会跟着唱，像 [@Titanic Rising](yoin:album/applemusic:1?a=Weyes%20Blood) " +
                        "的 _白天版本_。",
                    onMentionClick = {},
                )
                NoteMarkupText(
                    text = "Unresolved: [@Hannah Diamond](yoin:artist/spotify:hd) stays plain.",
                    isMentionResolved = { false },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
