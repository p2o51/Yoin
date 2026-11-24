/// Note text as Yoin draws it (Android ui/component/NoteMarkupText.kt):
/// headings, list items, to-dos, bold, italic and mentions, from the stored
/// Markdown ([NoteMarkup]). A resolved mention reads as a link without
/// shouting (owner 2026-11-24): a small cover before its name, both on a
/// faint pill of the mention colour, one unbreakable piece (a pill that
/// doesn't fit moves whole to the next line; a name longer than the line ends
/// in an ellipsis inside it). A tap opens what it names; a long press peeks
/// at it (cover, title, artist, album) while the finger stays down. An
/// unresolved mention is plain words in the text's own colour (owner
/// 2026-11-22: not grey). [NoteMarkupText.interactive] false (Now Playing
/// collapsed) draws the same but takes no touch: the host's tap wins.
/// [NoteMarkupText.onToggleChecklist] makes each to-do's box tappable.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/notes/note_markup.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import 'editor/mention_popup.dart' show mentionKindSymbol;
import 'note_mentions.dart';

typedef _M = NoteMentionTokens;

/// Glyphs the read-only text draws for list items and to-dos.
abstract final class NoteMarkupGlyphs {
  static const bullet = '•  ';
  static const todo = '☐  ';
  static const done = '☑  ';
}

/// The mention's weight (Android noteMentionStyle: SemiBold).
const int noteMentionWeight = 600;
const int _boldWeight = 700;
const double _heading1 = 1.15;
const double _heading2 = 1.05;

/// Builds a resolved mention's inline piece ([scale]: a heading's size factor).
typedef NoteMentionSpanBuilder = InlineSpan Function(NoteMention mention, double scale);

/// [document] as one span. A resolved mention ([isResolved]) is [mentionSpan]'s
/// piece, or, without one, its name in [mentionColor] at [noteMentionWeight]
/// with [recognizerFor]'s recognizer; an unresolved one is plain text.
/// [todoRecognizer] makes a to-do's box tappable (its line index).
TextSpan buildNoteSpan(
  NoteDocument document, {
  required TextStyle base,
  required Color mentionColor,
  required Color glyphColor,
  bool Function(NoteMention mention)? isResolved,
  GestureRecognizer? Function(NoteMention mention)? recognizerFor,
  NoteMentionSpanBuilder? mentionSpan,
  GestureRecognizer? Function(int lineIndex)? todoRecognizer,
}) {
  List<InlineSpan> inlines(List<NoteInline> nodes, double scale) => [
    for (final node in nodes)
      switch (node) {
        NoteText(:final text) => TextSpan(text: text),
        NoteBold(:final children) => TextSpan(style: _weighted(base, _boldWeight), children: inlines(children, scale)),
        NoteItalic(:final children) => TextSpan(
          style: const TextStyle(fontStyle: FontStyle.italic),
          children: inlines(children, scale),
        ),
        NoteMentionInline(:final mention) =>
          !(isResolved?.call(mention) ?? true)
              ? TextSpan(text: mention.title)
              : mentionSpan?.call(mention, scale) ??
                    TextSpan(
                      text: mention.title,
                      style: _weighted(base, noteMentionWeight).copyWith(color: mentionColor),
                      recognizer: recognizerFor?.call(mention),
                    ),
      },
  ];

  final glyph = TextStyle(color: glyphColor);
  final spans = <InlineSpan>[];
  for (final (index, block) in document.blocks.indexed) {
    if (index > 0) spans.add(const TextSpan(text: '\n'));
    switch (block) {
      case NoteParagraph():
        spans.addAll(inlines(block.inlines, 1));
      case NoteHeading(:final level):
        final scale = level == 1 ? _heading1 : _heading2;
        spans.add(
          TextSpan(
            style: _weighted(base, _boldWeight).copyWith(fontSize: (base.fontSize ?? 14) * scale),
            children: inlines(block.inlines, scale),
          ),
        );
      case NoteBullet():
        spans
          ..add(const TextSpan(text: NoteMarkupGlyphs.bullet))
          ..addAll(inlines(block.inlines, 1));
      case NoteChecklist(:final checked):
        spans
          ..add(
            TextSpan(
              text: checked ? NoteMarkupGlyphs.done : NoteMarkupGlyphs.todo,
              style: glyph,
              recognizer: todoRecognizer?.call(index),
            ),
          )
          ..add(
            TextSpan(
              style: checked ? const TextStyle(decoration: TextDecoration.lineThrough) : null,
              children: inlines(block.inlines, 1),
            ),
          );
    }
  }
  return TextSpan(children: spans);
}

/// [base]'s font at [weight]: the weight and, for a variable font, its `wght` axis.
TextStyle _weighted(TextStyle base, int weight) {
  final axes = base.fontVariations;
  return TextStyle(
    fontWeight: FontWeight.values.firstWhere((w) => w.value == weight, orElse: () => FontWeight.w700),
    fontVariations: axes == null
        ? null
        : [
            for (final axis in axes)
              if (axis.axis != 'wght') axis,
            FontVariation.weight(weight.toDouble()),
          ],
  );
}

/// A pill's width in ems of its own text, and whether the name had to be cut to fit the line.
@immutable
final class MentionPillGeometry {
  const MentionPillGeometry(this.widthEm, this.truncated);

  final double widthEm;
  final bool truncated;
}

/// The pill around a name [nameWidthPx] wide in text of [fontPx]: lead,
/// cover, gap, name, tail. Never wider than the line ([lineWidthPx]); a name
/// that would make it so is cut (with an ellipsis) inside the pill.
MentionPillGeometry mentionPillGeometry({
  required double fontPx,
  required double nameWidthPx,
  required double lineWidthPx,
}) {
  final chrome = fontPx * (_M.pillPadStartEm + _M.pillThumbEm + _M.pillGapEm + _M.pillPadEndEm);
  final natural = chrome + nameWidthPx + 1;
  final roomRaw = lineWidthPx - 1;
  final room = roomRaw < chrome + fontPx ? chrome + fontPx : roomRaw;
  final width = natural < room ? natural : room;
  return MentionPillGeometry(width / fontPx, natural > room);
}

/// A note, album note or review drawn from its stored text. Without
/// [isMentionResolved] the account in use decides ([noteMentionsProvider]), and
/// a resolved mention opens what it names unless [interactive] is false.
class NoteMarkupText extends ConsumerStatefulWidget {
  const NoteMarkupText(
    this.text, {
    super.key,
    this.style,
    this.mentionColor,
    this.maxLines,
    this.overflow,
    this.textAlign,
    this.interactive = true,
    this.isMentionResolved,
    this.onMentionTap,
    this.onToggleChecklist,
    this.artworkUrl,
  });

  final String text;
  final TextStyle? style;

  /// Defaults to the scheme's primary (Android NoteMarkupText).
  final Color? mentionColor;
  final int? maxLines;
  final TextOverflow? overflow;
  final TextAlign? textAlign;
  final bool interactive;
  final bool Function(NoteMention mention)? isMentionResolved;

  /// Replaces opening the mention (tests, previews).
  final void Function(NoteMention mention)? onMentionTap;

  /// A tap on a to-do's box (its line index); the host saves the note.
  final void Function(int lineIndex)? onToggleChecklist;

  /// Replaces the account's artwork lookup (tests, previews).
  final String? Function(NoteMention mention)? artworkUrl;

  @override
  ConsumerState<NoteMarkupText> createState() => _NoteMarkupTextState();
}

class _NoteMarkupTextState extends ConsumerState<NoteMarkupText> {
  late NoteDocument _document = NoteMarkup.parse(widget.text);
  final List<TapGestureRecognizer> _recognizers = [];

  @override
  void didUpdateWidget(NoteMarkupText oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.text != widget.text) _document = NoteMarkup.parse(widget.text);
  }

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  void _disposeRecognizers() {
    for (final r in _recognizers) {
      r.dispose();
    }
    _recognizers.clear();
  }

  void _open(NoteMention mention) {
    final tap = widget.onMentionTap;
    if (tap != null) {
      tap(mention);
      return;
    }
    ref.read(noteMentionsProvider.notifier).open(context, mention);
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final hasMentions = _document.mentions.isNotEmpty;
    bool Function(NoteMention)? resolved = widget.isMentionResolved;
    String? Function(NoteMention)? artwork = widget.artworkUrl;
    if (hasMentions && (resolved == null || artwork == null)) {
      // Redraw when an answer (or a cover) lands or the account changes.
      ref.watch(noteMentionsProvider);
      ref.watch(activeSourceProvider);
      final notes = ref.read(noteMentionsProvider.notifier);
      resolved ??= notes.isResolved;
      artwork ??= notes.artworkUrl;
    }
    _disposeRecognizers();
    final base = DefaultTextStyle.of(context).style.merge(widget.style);
    final tint = widget.mentionColor ?? scheme.primary;
    final toggle = widget.interactive ? widget.onToggleChecklist : null;
    GestureRecognizer? todo(int line) {
      if (toggle == null) return null;
      final r = TapGestureRecognizer()
        ..onTap = () {
          YoinHaptics.tick();
          toggle(line);
        };
      _recognizers.add(r);
      return r;
    }

    Widget text(double lineWidth) {
      final span = buildNoteSpan(
        _document,
        base: base,
        mentionColor: tint,
        glyphColor: scheme.primary,
        isResolved: resolved,
        todoRecognizer: todo,
        mentionSpan: (mention, scale) {
          // The pill is laid out at the text's scaled size; its name draws at that size unscaled.
          final scaledFont = MediaQuery.textScalerOf(context).scale((base.fontSize ?? 14) * scale);
          final nameStyle = base
              .merge(_weighted(base, noteMentionWeight))
              .copyWith(color: tint, fontSize: scaledFont, fontStyle: FontStyle.normal, height: 1);
          final painter = TextPainter(
            text: TextSpan(text: mention.title, style: nameStyle),
            textDirection: Directionality.of(context),
            maxLines: 1,
          )..layout();
          final geometry = mentionPillGeometry(fontPx: scaledFont, nameWidthPx: painter.width, lineWidthPx: lineWidth);
          painter.dispose();
          return WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: MentionPill(
              mention: mention,
              url: artwork?.call(mention),
              tint: tint,
              nameStyle: nameStyle,
              fontPx: scaledFont,
              widthEm: geometry.widthEm,
              interactive: widget.interactive,
              onTap: () => _open(mention),
            ),
          );
        },
      );
      return Text.rich(
        span,
        style: widget.style,
        maxLines: widget.maxLines,
        overflow: widget.overflow,
        textAlign: widget.textAlign,
      );
    }

    if (!hasMentions) return text(double.infinity);
    return LayoutBuilder(builder: (context, constraints) => text(constraints.maxWidth));
  }
}

/// Cover and name on the pill (a shade deeper while pressed); a tap opens the
/// mention, a long press peeks at it while held.
class MentionPill extends StatefulWidget {
  const MentionPill({
    super.key,
    required this.mention,
    required this.url,
    required this.tint,
    required this.nameStyle,
    required this.fontPx,
    required this.widthEm,
    required this.interactive,
    required this.onTap,
  });

  final NoteMention mention;
  final String? url;
  final Color tint;
  final TextStyle nameStyle;

  /// The name's laid-out size (the text scale applied): every em of the pill is this.
  final double fontPx;
  final double widthEm;
  final bool interactive;
  final VoidCallback onTap;

  @override
  State<MentionPill> createState() => _MentionPillState();
}

class _MentionPillState extends State<MentionPill> with TickerProviderStateMixin {
  late final SpringValue _fill = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _peek = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);
  final OverlayPortalController _portal = OverlayPortalController();
  bool _peeking = false;

  @override
  void initState() {
    super.initState();
    _peek.animation.addListener(_onPeek);
  }

  @override
  void dispose() {
    _peek.animation.removeListener(_onPeek);
    _fill.dispose();
    _peek.dispose();
    super.dispose();
  }

  void _onPeek() {
    // Folded away: off the overlay.
    if (!_peeking && _peek.value <= 0.01 && _portal.isShowing) _portal.hide();
  }

  void _press(bool down) => _fill.animateTo(down ? 1 : 0);

  void _startPeek() {
    YoinHaptics.longPress();
    setState(() => _peeking = true);
    _portal.show();
    if (MediaQuery.disableAnimationsOf(context)) {
      _peek.snapTo(1);
    } else {
      _peek.animateTo(1);
    }
  }

  void _endPeek() {
    _press(false);
    if (!_peeking) return;
    setState(() => _peeking = false);
    if (MediaQuery.disableAnimationsOf(context)) {
      _peek.snapTo(0);
      _portal.hide();
    } else {
      _peek.animateTo(0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final em = widget.fontPx;
    final height = em * _M.pillHeightEm;
    final thumb = em * _M.pillThumbEm;
    final pill = AnimatedBuilder(
      animation: _fill.animation,
      builder: (context, child) => DecoratedBox(
        decoration: BoxDecoration(
          color: widget.tint.withValues(alpha: _M.pillAlpha + _M.pillPressedAlpha * _fill.value.clamp(0.0, 1.0)),
          borderRadius: BorderRadius.circular(height / 2),
        ),
        child: child,
      ),
      child: SizedBox(
        width: widget.widthEm * em,
        height: height,
        child: Padding(
          padding: EdgeInsetsDirectional.only(start: em * _M.pillPadStartEm, end: em * _M.pillPadEndEm),
          child: Row(
            children: [
              SizedBox.square(
                dimension: thumb,
                child: MentionThumb(mention: widget.mention, url: widget.url, tint: widget.tint),
              ),
              SizedBox(width: em * _M.pillGapEm),
              Flexible(
                child: Text(
                  widget.mention.title,
                  style: widget.nameStyle,
                  maxLines: 1,
                  softWrap: false,
                  overflow: TextOverflow.ellipsis,
                  textScaler: TextScaler.noScaling,
                ),
              ),
            ],
          ),
        ),
      ),
    );
    final drawn = Semantics(
      link: widget.interactive,
      label: widget.mention.title,
      onTap: widget.interactive ? widget.onTap : null,
      excludeSemantics: true,
      child: pill,
    );
    if (!widget.interactive) return IgnorePointer(child: drawn);
    return OverlayPortal(
      controller: _portal,
      overlayChildBuilder: _peekCard,
      child: MouseRegion(
        cursor: SystemMouseCursors.click,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (_) => _press(true),
          onTapCancel: () => _press(false),
          onTap: () {
            _press(false);
            YoinHaptics.tick();
            widget.onTap();
          },
          onLongPressStart: (_) => _startPeek(),
          onLongPressEnd: (_) => _endPeek(),
          onLongPressCancel: _endPeek,
          child: drawn,
        ),
      ),
    );
  }

  Widget _peekCard(BuildContext overlayContext) {
    final box = context.findRenderObject() as RenderBox?;
    final overlay = Overlay.of(overlayContext).context.findRenderObject() as RenderBox?;
    if (box == null || overlay == null || !box.attached) return const SizedBox.shrink();
    final pill = MatrixUtils.transformRect(box.getTransformTo(overlay), Offset.zero & box.size);
    final reduce = MediaQuery.disableAnimationsOf(overlayContext);
    return Positioned.fill(
      child: IgnorePointer(
        child: CustomSingleChildLayout(
          delegate: _PeekLayout(pill),
          child: AnimatedBuilder(
            animation: _peek.animation,
            builder: (context, child) {
              final t = _peek.value.clamp(0.0, 1.0);
              final from = _peeking ? _M.peekEnterScale : _M.peekExitScale;
              return Opacity(
                opacity: t,
                child: Transform.scale(scale: reduce ? 1 : from + (1 - from) * _peek.value, child: child),
              );
            },
            child: MentionPeekCard(mention: widget.mention, artworkUrl: widget.url),
          ),
        ),
      ),
    );
  }
}

/// Centres the card over the pill, above it when there's room, else below.
class _PeekLayout extends SingleChildLayoutDelegate {
  _PeekLayout(this.pill);

  final Rect pill;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) => BoxConstraints.loose(constraints.biggest);

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    const margin = _M.peekMargin;
    final maxX = size.width - childSize.width - margin;
    final x = (pill.center.dx - childSize.width / 2).clamp(margin, maxX < margin ? margin : maxX);
    final above = pill.top - childSize.height - margin;
    return Offset(x, above >= margin ? above : pill.bottom + margin);
  }

  @override
  bool shouldRelayout(_PeekLayout old) => old.pill != pill;
}

/// A mention's cover (an artist's picture, round), or its kind's symbol on the pill's tint while there is none.
class MentionThumb extends StatelessWidget {
  const MentionThumb({super.key, required this.mention, required this.url, required this.tint});

  final NoteMention mention;
  final String? url;
  final Color tint;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final side = constraints.biggest.shortestSide;
      final artist = mention.kind == MentionKind.artist;
      if (url == null) {
        final symbol = DecoratedBox(
          decoration: BoxDecoration(
            color: tint.withValues(alpha: _M.thumbTintAlpha),
            shape: artist ? BoxShape.circle : BoxShape.rectangle,
            borderRadius: artist ? null : BorderRadius.circular(side * _M.thumbCornerFraction),
          ),
          child: Center(
            child: Icon(mentionKindSymbol(mention.kind), size: side * _M.thumbSymbolFraction, color: tint),
          ),
        );
        return symbol;
      }
      return MediaArtwork(
        url: url,
        fallbackIcon: mentionKindSymbol(mention.kind),
        circle: artist,
        radius: side * _M.thumbCornerFraction,
        fallbackIconSize: side * _M.thumbSymbolFraction,
      );
    },
  );
}

/// The card a long press on a mention brings up: its cover, title, artist and album.
class MentionPeekCard extends StatelessWidget {
  const MentionPeekCard({super.key, required this.mention, required this.artworkUrl});

  final NoteMention mention;
  final String? artworkUrl;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final artist = mention.artist?.trim();
    final album = mention.album?.trim();
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: _M.peekMaxWidth),
      child: Material(
        color: colors.surfaceContainerHigh,
        elevation: _M.peekElevation,
        shadowColor: colors.shadow,
        surfaceTintColor: colors.surfaceTint,
        borderRadius: BorderRadius.circular(_M.peekCorner),
        child: Padding(
          padding: const EdgeInsets.all(_M.peekPadding),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox.square(
                dimension: _M.peekArt,
                child: MediaArtwork(
                  url: artworkUrl,
                  fallbackIcon: mentionKindSymbol(mention.kind),
                  circle: mention.kind == MentionKind.artist,
                  radius: ShapeTokens.thumb,
                ),
              ),
              const SizedBox(width: _M.peekGap),
              Flexible(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      mention.title,
                      style: text.titleMedium!.copyWith(color: colors.onSurface),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (artist != null && artist.isNotEmpty)
                      Text(
                        artist,
                        style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    if (album != null && album.isNotEmpty)
                      Text(
                        album,
                        style: text.bodySmall!.copyWith(
                          color: colors.onSurfaceVariant.withValues(alpha: _M.peekAlbumAlpha),
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
