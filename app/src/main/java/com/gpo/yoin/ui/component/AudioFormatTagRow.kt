package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.ui.theme.YoinTheme
import com.gpo.yoin.ui.theme.withTabularFigures

/**
 * Format tags as small tonal blocks ("FLAC 24/96", "5.1"), one block each, no
 * separators. [transcodedFrom] only changes what TalkBack reads. Nothing is
 * drawn when [tags] is empty.
 */
@Composable
internal fun AudioFormatTagRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    transcodedFrom: String? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    if (tags.isEmpty()) return
    val joined = tags.joinToString(" ")
    val description = if (transcodedFrom != null) {
        stringResource(R.string.np_format_transcoded_description, joined, transcodedFrom)
    } else {
        stringResource(R.string.np_format_description, joined)
    }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            Surface(shape = TagShape, color = container, contentColor = content) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

private val TagShape = RoundedCornerShape(6.dp)

@Preview(showBackground = true)
@Composable
private fun AudioFormatTagRowPreview() {
    YoinTheme {
        AudioFormatTagRow(tags = listOf("FLAC 16/44.1", "5.1"), modifier = Modifier.padding(8.dp))
    }
}
