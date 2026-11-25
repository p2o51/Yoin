package com.gpo.yoin.ui.component

import coil3.request.allowHardware
import coil3.request.ImageRequest
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.gpo.yoin.shared.notes.MentionKind
import com.gpo.yoin.shared.notes.NoteBlock
import com.gpo.yoin.shared.notes.NoteDocument
import com.gpo.yoin.shared.notes.NoteInline
import com.gpo.yoin.shared.notes.NoteMarkup
import com.gpo.yoin.shared.notes.NoteMention
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.notes.LocalNoteMentions
import com.gpo.yoin.ui.theme.YoinArtworkShapes
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme
import kotlin.math.roundToInt

/*
 * Note text as Yoin draws it (notes revamp phase A): headings, list items, to-dos, bold, italic and
 * mentions, from the stored Markdown (shared/notes/NoteMarkup.kt). A mention shows its name: with its
 * cover on a faint pill of the mention colour, tappable and long-pressable, when it resolves; as plain
 * words in the text's own colour when it doesn't (owner 2026-11-22: not grey).
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
    onToggleChecklist: ((lineIndex: Int) -> Unit)? = null,
    glyphStyle: SpanStyle? = null,
): AnnotatedString = buildNoteMarkup(
    document = document,
    mentionStyle = mentionStyle,
    isMentionResolved = isMentionResolved,
    onMentionClick = onMentionClick,
    onToggleChecklist = onToggleChecklist,
    glyphStyle = glyphStyle,
    thumbnails = false,
).text

/** The built text and where each resolved mention sits in it (for its pill, thumbnail and press). */
internal data class NoteMarkupBuild(val text: AnnotatedString, val mentions: List<DrawnMention>)

/**
 * [buildNoteAnnotatedString] with, for [thumbnails], each resolved mention as ONE inline slot
 * ([mentionPillId]: cover + name on a pill, its name the slot's alternate text), so a line never breaks inside
 * it: a pill that doesn't fit moves whole to the next line (owner 2026-11-24 tablet QA).
 */
internal fun buildNoteMarkup(
    document: NoteDocument,
    mentionStyle: SpanStyle,
    isMentionResolved: (NoteMention) -> Boolean = { true },
    onMentionClick: ((NoteMention) -> Unit)? = null,
    onToggleChecklist: ((lineIndex: Int) -> Unit)? = null,
    glyphStyle: SpanStyle? = null,
    thumbnails: Boolean = true,
): NoteMarkupBuild {
    val drawn = ArrayList<DrawnMention>()
    val text = buildAnnotatedString {
        fun mentionBody(ordinal: Int, title: String) {
            if (thumbnails) appendInlineContent(mentionPillId(ordinal), title) else append(title)
        }
        fun inlines(nodes: List<NoteInline>, scale: Float = 1f) {
            nodes.forEach { node ->
                when (node) {
                    is NoteInline.Text -> append(node.text)
                    is NoteInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inlines(node.children, scale) }
                    is NoteInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlines(node.children, scale) }
                    is NoteInline.Mention -> {
                        val mention = node.mention
                        if (!isMentionResolved(mention)) {
                            append(mention.title)
                        } else {
                            val ordinal = drawn.size
                            val start = length
                            if (onMentionClick != null) {
                                withLink(
                                    LinkAnnotation.Clickable(
                                        tag = mention.mediaId,
                                        styles = TextLinkStyles(style = mentionStyle),
                                        linkInteractionListener = { onMentionClick(mention) },
                                    ),
                                ) { mentionBody(ordinal, mention.title) }
                            } else {
                                withStyle(mentionStyle) { mentionBody(ordinal, mention.title) }
                            }
                            drawn += DrawnMention(ordinal, mention, start, length, scale)
                        }
                    }
                }
            }
        }
        document.blocks.forEachIndexed { index, block ->
            if (index > 0) append('\n')
            when (block) {
                is NoteBlock.Paragraph -> inlines(block.inlines)
                is NoteBlock.Heading -> {
                    val scale = if (block.level == 1) HeadingScale1 else HeadingScale2
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = scale.em)) { inlines(block.inlines, scale) }
                }
                is NoteBlock.Bullet -> {
                    append(NoteMarkupGlyphs.BULLET)
                    inlines(block.inlines)
                }
                is NoteBlock.Checklist -> {
                    val glyph = if (block.checked) NoteMarkupGlyphs.DONE else NoteMarkupGlyphs.TODO
                    // A tap on the box ticks it (the host saves the note).
                    if (onToggleChecklist != null) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "$TodoTagPrefix$index",
                                styles = glyphStyle?.let { TextLinkStyles(style = it) },
                                linkInteractionListener = { onToggleChecklist(index) },
                            ),
                        ) { append(glyph) }
                    } else if (glyphStyle != null) {
                        withStyle(glyphStyle) { append(glyph) }
                    } else {
                        append(glyph)
                    }
                    if (block.checked) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inlines(block.inlines) }
                    } else {
                        inlines(block.inlines)
                    }
                }
            }
        }
    }
    return NoteMarkupBuild(text, drawn)
}

internal const val TodoTagPrefix = "todo:"

internal fun mentionPillId(ordinal: Int) = "mention-pill:$ordinal"

private const val HeadingScale1 = 1.15f
private const val HeadingScale2 = 1.05f

/** The mention span: the given colour, a touch heavier than the words around it. */
fun noteMentionStyle(color: Color): SpanStyle = SpanStyle(color = color, fontWeight = FontWeight.SemiBold)

/** Every mention drawn as one (until a resolver says otherwise; phase B passes the resolver's answer). */
val AllMentionsResolved: (NoteMention) -> Boolean = { true }

/**
 * A note, album note or review drawn from its stored text. A resolved mention reads as a link without
 * shouting (owner 2026-11-24): a small cover before its name, both on a faint pill of [mentionColor]; a tap
 * opens what it names, a long press peeks at it (cover, title, artist, album) while the finger stays down.
 * Without an explicit [isMentionResolved] / [onMentionClick] the window's [LocalNoteMentions] decides (a
 * resolution landing later redraws). [interactive] false (Now Playing collapsed) draws the same but takes no
 * touch: the host's tap wins. [onToggleChecklist] makes each to-do's box tappable.
 */
@Composable
fun NoteMarkupText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    mentionColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    isMentionResolved: ((NoteMention) -> Boolean)? = null,
    onMentionClick: ((NoteMention) -> Unit)? = null,
    onToggleChecklist: ((lineIndex: Int) -> Unit)? = null,
    interactive: Boolean = true,
) {
    // As Material's Text: the given colour, else the style's, else the content colour.
    val textColor = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    NoteMarkupBody(
        text = text,
        style = LocalTextStyle.current.merge(style),
        color = { textColor },
        mentionColor = mentionColor,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        isMentionResolved = isMentionResolved,
        onMentionClick = onMentionClick,
        onToggleChecklist = onToggleChecklist,
        interactive = interactive,
    )
}

/** [NoteMarkupText]'s body, with the colour read at draw time (Now Playing's note lines animate it). */
@Composable
internal fun NoteMarkupBody(
    text: String,
    style: TextStyle,
    color: ColorProducer,
    mentionColor: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    isMentionResolved: ((NoteMention) -> Boolean)? = null,
    onMentionClick: ((NoteMention) -> Unit)? = null,
    onToggleChecklist: ((lineIndex: Int) -> Unit)? = null,
    interactive: Boolean = true,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val document = remember(text) { NoteMarkup.parse(text) }
    val controller = LocalNoteMentions.current
    val context = LocalContext.current
    val resolvedNow: (NoteMention) -> Boolean = isMentionResolved ?: controller?.let { c -> c::isResolved } ?: AllMentionsResolved
    // Read in composition, so a resolution that lands later (another provider's mention, by name) redraws.
    val resolution = document.mentions.map(resolvedNow)
    val click = noteMentionClick(
        interactive = interactive,
        explicit = onMentionClick,
        fromController = controller?.let { c -> { mention: NoteMention -> c.open(context, mention) } },
    )
    val latestClick by rememberUpdatedState(click)
    val latestToggle by rememberUpdatedState(onToggleChecklist)
    val clickable = click != null
    val toggles = interactive && onToggleChecklist != null
    val glyphColor = MaterialTheme.colorScheme.primary
    val build = remember(document, mentionColor, resolution, clickable, toggles, glyphColor) {
        val answers = document.mentions.zip(resolution).toMap()
        buildNoteMarkup(
            document = document,
            mentionStyle = noteMentionStyle(mentionColor),
            isMentionResolved = { answers[it] ?: true },
            onMentionClick = if (clickable) { mention -> latestClick?.invoke(mention) } else null,
            onToggleChecklist = if (toggles) { line -> latestToggle?.invoke(line) } else null,
            glyphStyle = SpanStyle(color = glyphColor),
        )
    }
    // Covers arrive in the background (read in composition: one landing redraws its thumbnail).
    val artworks = build.mentions.map { controller?.artworkUrl(it.mention) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pressed = remember { mutableStateOf<Int?>(null) }
    var peek by remember { mutableStateOf<DrawnMention?>(null) }
    val haptics = rememberYoinHaptics()
    val press = if (interactive && build.mentions.isNotEmpty()) {
        Modifier.mentionPress(
            mentions = build.mentions,
            layout = { layout },
            onTap = { mention -> latestClick?.invoke(mention) },
            onPressed = { pressed.value = it?.ordinal },
            onPeek = { mention ->
                if (mention != null) haptics.performLongPress()
                peek = mention
            },
        )
    } else {
        Modifier
    }
    val text: @Composable (Map<String, InlineTextContent>) -> Unit = { inline ->
        BasicText(
            text = build.text,
            modifier = press,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            inlineContent = inline,
            onTextLayout = {
                layout = it
                onTextLayout(it)
            },
        )
    }
    Box(modifier = modifier) {
        if (build.mentions.isEmpty()) {
            text(emptyMap())
        } else {
            // A pill's width is its cover and name as they measure, held to the line's width (a longer name
            // ends in an ellipsis inside the pill), so it is laid out as one unbreakable slot.
            BoxWithConstraints {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val lineWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                val inline = remember(build, artworks, mentionColor, style, lineWidth, density) {
                    mentionPills(build.mentions, artworks, mentionColor, style, lineWidth, density, measurer, pressed)
                }
                text(inline)
            }
        }
        MentionPeek(
            peek = peek,
            anchor = { mention -> layout?.let { mentionAnchor(it, mention) } },
            artwork = { mention -> controller?.artworkUrl(mention) },
        )
    }
}

/** One pill slot per drawn mention, each [Placeholder] as wide as its cover and name (capped at [lineWidth]). */
private fun mentionPills(
    mentions: List<DrawnMention>,
    artworks: List<String?>,
    tint: Color,
    style: TextStyle,
    lineWidth: Int,
    density: Density,
    measurer: TextMeasurer,
    pressed: State<Int?>,
): Map<String, InlineTextContent> = buildMap {
    val base = style.fontSize.takeIf { it.isSp } ?: DefaultMentionFontSize
    mentions.forEachIndexed { index, drawn ->
        val nameStyle = style.merge(
            TextStyle(color = tint, fontSize = base * drawn.scale, fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Normal),
        ).copy(lineHeight = TextUnit.Unspecified) // one line in the pill: its own height, centred

        val geometry = with(density) {
            mentionPillGeometry(
                fontPx = nameStyle.fontSize.toPx(),
                nameWidthPx = measurer.measure(drawn.mention.title, nameStyle, maxLines = 1, softWrap = false).size.width,
                lineWidthPx = lineWidth,
            )
        }
        put(
            mentionPillId(drawn.ordinal),
            InlineTextContent(
                Placeholder(
                    width = geometry.widthEm.em,
                    height = MentionPillHeightEm.em,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                MentionPill(
                    drawn = drawn,
                    url = artworks.getOrNull(index),
                    tint = tint,
                    nameStyle = nameStyle,
                    thumb = with(density) { (nameStyle.fontSize.toPx() * MentionThumbEm).toDp() },
                    pressed = { pressed.value == drawn.ordinal },
                )
            },
        )
    }
}

/** A pill's width in ems of its own text, and whether the name had to be cut to fit the line. */
internal data class MentionPillGeometry(val widthEm: Float, val truncated: Boolean)

/**
 * The pill around a name [nameWidthPx] wide in text of [fontPx]: lead, cover, gap, name, tail. Never wider than
 * the line ([lineWidthPx]); a name that would make it so is cut (with an ellipsis) inside the pill.
 */
internal fun mentionPillGeometry(fontPx: Float, nameWidthPx: Int, lineWidthPx: Int): MentionPillGeometry {
    val chrome = fontPx * (MentionPadStartEm + MentionThumbEm + MentionGapEm + MentionPadEndEm)
    val natural = chrome + nameWidthPx + 1f
    val room = (lineWidthPx - 1).toFloat().coerceAtLeast(chrome + fontPx)
    val width = minOf(natural, room)
    return MentionPillGeometry(widthEm = width / fontPx, truncated = natural > room)
}

private val DefaultMentionFontSize = 14.sp
private const val MentionPadStartEm = 0.22f
private const val MentionThumbEm = 1f
private const val MentionGapEm = 0.3f
private const val MentionPadEndEm = 0.45f
private const val MentionPillHeightEm = 1.3f

/** Cover and name on the pill (a shade deeper while pressed); the name ends in an ellipsis when cut. */
@Composable
private fun MentionPill(
    drawn: DrawnMention,
    url: String?,
    tint: Color,
    nameStyle: TextStyle,
    thumb: Dp,
    pressed: () -> Boolean,
) {
    val fill by animateFloatAsState(
        targetValue = if (pressed()) MentionPillAlpha + MentionPillPressedAlpha else MentionPillAlpha,
        animationSpec = YoinMotion.effectsSpring(),
        label = "mentionPill",
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            // The text around it already reads the name (the slot's alternate text, and its link for TalkBack).
            .clearAndSetSemantics {}
            .drawBehind { drawRoundRect(tint.copy(alpha = fill), cornerRadius = CornerRadius(size.height / 2f)) }
            .padding(start = with(LocalDensity.current) { (nameStyle.fontSize.toPx() * MentionPadStartEm).toDp() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MentionThumb(drawn.mention, url, tint, Modifier.size(thumb))
        Spacer(Modifier.width(with(LocalDensity.current) { (nameStyle.fontSize.toPx() * MentionGapEm).toDp() }))
        BasicText(
            text = drawn.mention.title,
            style = nameStyle,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** A mention's cover (an artist's picture, round), or its kind's symbol on the pill's tint while there is none. */
@Composable
private fun MentionThumb(mention: NoteMention, url: String?, tint: Color, modifier: Modifier = Modifier) {
    val shape = if (mention.kind == MentionKind.Artist) CircleShape else MentionThumbShape
    Box(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null || LocalInspectionMode.current) {
            Icon(
                imageVector = mention.kind.symbol(),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize(0.72f),
            )
        } else {
            val software = LocalSoftwareBitmaps.current
            val context = LocalContext.current
            AsyncImage(
                // A share image is read back from a graphics layer: its covers must be software bitmaps.
                model = if (software) {
                    remember(url) { ImageRequest.Builder(context).data(url).allowHardware(false).build() }
                } else {
                    url
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val MentionThumbShape = RoundedCornerShape(percent = 24)

/**
 * True where the text is drawn into an exported image (Now Playing's share images): images load as software
 * bitmaps, which a graphics layer can read back.
 */
val LocalSoftwareBitmaps = staticCompositionLocalOf { false }

internal fun MentionKind.symbol(): ImageVector = when (this) {
    MentionKind.Track -> YoinSymbols.MusicNote
    MentionKind.Album -> YoinSymbols.Album
    MentionKind.Artist -> YoinSymbols.Artist
}

private const val MentionPillAlpha = 0.12f
private const val MentionPillPressedAlpha = 0.14f

/** Where [drawn]'s pill was laid out (null: cut off by maxLines). Mentions are the text's only slots, in order. */
private fun mentionAnchor(layout: TextLayoutResult, drawn: DrawnMention): Rect? =
    layout.placeholderRects.getOrNull(drawn.ordinal)

/**
 * A finger on a mention ([MentionPressTracker]): a tap opens it ([onTap]), a long press peeks while held. It
 * looks before anything under or around it (the Initial pass) and takes the press when it lands on a mention,
 * so neither the text's link (kept for TalkBack's link actions and the mouse pointer) nor the host's own tap or
 * long press (a note line's seek or edit) acts on it too. Moves stay unconsumed until a peek is up, so a drag
 * that starts on a mention still scrolls.
 */
private fun Modifier.mentionPress(
    mentions: List<DrawnMention>,
    layout: () -> TextLayoutResult?,
    onTap: (NoteMention) -> Unit,
    onPressed: (DrawnMention?) -> Unit,
    onPeek: (DrawnMention?) -> Unit,
): Modifier = pointerInput(mentions) {
    val tracker = MentionPressTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val hit = layout()?.let { mentionUnder(it, mentions, down.position, viewConfiguration.touchSlop) }
        if (!tracker.down(hit)) return@awaitEachGesture
        down.consume()
        onPressed(hit)
        try {
            // true: released (a tap, the link opens it); false: a scroll or the pointer went; null: long press.
            val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var outcome: Boolean? = null
                while (outcome == null) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    outcome = when {
                        change == null -> false
                        !change.pressed -> {
                            change.consume()
                            true
                        }
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> {
                            tracker.movedPastSlop()
                            false
                        }
                        else -> null
                    }
                }
                outcome
            }
            when (released) {
                true -> if (tracker.up() == MentionPressTracker.Release.Tap) hit?.let { onTap(it.mention) }
                false -> tracker.cancel()
                null -> {
                    onPeek(tracker.longPress())
                    // Held: the card stays; every change is kept from the link and the host until release.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    tracker.up()
                }
            }
        } finally {
            tracker.cancel()
            onPressed(null)
            onPeek(null)
        }
    }
}

/** The mention whose pill is under [position] (within half the touch slop). */
private fun mentionUnder(layout: TextLayoutResult, mentions: List<DrawnMention>, position: Offset, slop: Float): DrawnMention? =
    mentions.firstOrNull { drawn ->
        val pill = mentionAnchor(layout, drawn) ?: return@firstOrNull false
        position.x >= pill.left - slop / 2 && position.x <= pill.right + slop / 2 &&
            position.y >= pill.top - slop / 2 && position.y <= pill.bottom + slop / 2
    }

/**
 * The card a long press on a mention brings up while the finger stays down: its cover, title, artist and
 * album. It grows out of the mention (above it when there's room) and folds away on release.
 */
@Composable
private fun MentionPeek(
    peek: DrawnMention?,
    anchor: (DrawnMention) -> Rect?,
    artwork: (NoteMention) -> String?,
) {
    var last by remember { mutableStateOf<DrawnMention?>(null) }
    SideEffect { if (peek != null) last = peek }
    val shown = peek ?: last ?: return
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = peek != null
    if (!visible.currentState && !visible.targetState) return
    val box = anchor(shown) ?: return
    val position = remember(box) { MentionPeekPosition(box) }
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, clippingEnabled = true)) {
        AnimatedVisibility(
            visibleState = visible,
            enter = YoinMotion.scaleIn(role = YoinMotionRole.Expressive, initialScale = 0.86f) +
                YoinMotion.fadeIn(role = YoinMotionRole.Expressive),
            exit = YoinMotion.scaleOut(role = YoinMotionRole.Standard, targetScale = 0.92f) +
                YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            MentionPeekCard(mention = shown.mention, artworkUrl = artwork(shown.mention))
        }
    }
}

@Composable
internal fun MentionPeekCard(mention: NoteMention, artworkUrl: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = MentionPeekMaxWidth),
        shape = YoinShapeTokens.LargeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveMediaArtwork(
                model = artworkUrl,
                contentDescription = null,
                shape = if (mention.kind == MentionKind.Artist) CircleShape else YoinArtworkShapes.Thumb,
                fallbackIcon = mention.kind.symbol(),
                tonalElevation = 0.dp,
                modifier = Modifier.size(56.dp),
            )
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = mention.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                mention.artist?.takeIf(String::isNotBlank)?.let { artist ->
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                mention.album?.takeIf(String::isNotBlank)?.let { album ->
                    Text(
                        text = album,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val MentionPeekMaxWidth = 280.dp

/** Centres the card over the mention's pill, above it when there's room, else below. */
private class MentionPeekPosition(private val pill: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 12
        val centre = anchorBounds.left + pill.center.x.roundToInt()
        val x = (centre - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = anchorBounds.top + pill.top.roundToInt() - popupContentSize.height - margin
        val y = if (above >= margin) above else anchorBounds.top + pill.bottom.roundToInt() + margin
        return IntOffset(x, y)
    }
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
