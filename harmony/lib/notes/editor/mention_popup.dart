/// The @ popup (Android ui/notes/editor/MentionPopup.kt): rows by kind, the
/// highlighted one tinted; a tap inserts it. It never takes focus (the
/// keyboard stays with the field), and it never covers the line being
/// written: above the whole field when there's room, else above the caret's
/// line, else below it, always between the status bar and the keyboard.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/notes/mention_ranking.dart';
import '../../data/notes/note_markup.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../../ui/component/media_artwork.dart';
import '../../ui/component/meta_line.dart';
import '../notes_strings.dart';
import 'mention_candidates_source.dart';

typedef _E = NoteEditorTokens;

/// Where the popup goes: [field] and [caret] in the overlay's coordinates; the
/// popup stays between [topLimit] (the status bar) and [bottomLimit] (the
/// keyboard's top). Above the field if it fits, else above the caret's line,
/// else below it; when neither side holds it whole, the side with more room.
Offset mentionPopupOffset({
  required Rect field,
  required Rect caret,
  required Size window,
  required Size popup,
  required double margin,
  double topLimit = 0,
  double? bottomLimit,
}) {
  final top = topLimit + margin;
  final bottom = (bottomLimit ?? window.height) - margin;
  final maxX = window.width - popup.width - margin;
  final x = (caret.left - margin * 2).clamp(margin, maxX < margin ? margin : maxX);
  final aboveField = field.top - popup.height - margin;
  final aboveLine = caret.top - popup.height - margin;
  final below = caret.bottom + margin;
  final double y;
  if (aboveField >= top) {
    y = aboveField;
  } else if (aboveLine >= top) {
    y = aboveLine;
  } else if (below + popup.height <= bottom) {
    y = below;
  } else if (caret.top - top >= bottom - caret.bottom) {
    y = aboveLine > top ? aboveLine : top;
  } else {
    y = below;
  }
  return Offset(x.toDouble(), y);
}

/// How tall the rows may be: what fits on the roomier side of the caret's line
/// between [topLimit] and [bottomLimit], less the popup's own chrome.
double mentionPopupListMax({
  required double caretTop,
  required double caretBottom,
  required double topLimit,
  required double bottomLimit,
}) {
  final room = (caretTop - topLimit) > (bottomLimit - caretBottom) ? caretTop - topLimit : bottomLimit - caretBottom;
  return (room - _E.popupMargin * 2 - _E.popupChrome).clamp(_E.popupMinListHeight, _E.popupMaxListHeight);
}

/// Lays the popup out at [mentionPopupOffset] once its size is known.
class MentionPopupLayout extends SingleChildLayoutDelegate {
  MentionPopupLayout({required this.field, required this.caret, required this.topLimit, required this.bottomLimit});

  final Rect field;
  final Rect caret;
  final double topLimit;
  final double bottomLimit;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) => BoxConstraints.loose(constraints.biggest);

  @override
  Offset getPositionForChild(Size size, Size childSize) => mentionPopupOffset(
    field: field,
    caret: caret,
    window: size,
    popup: childSize,
    margin: _E.popupMargin,
    topLimit: topLimit,
    bottomLimit: bottomLimit,
  );

  @override
  bool shouldRelayout(MentionPopupLayout old) =>
      old.field != field || old.caret != caret || old.topLimit != topLimit || old.bottomLimit != bottomLimit;
}

/// The popup's card: the title row with "Search all", then the rows.
class MentionPopup extends StatefulWidget {
  const MentionPopup({
    super.key,
    required this.rows,
    required this.loading,
    required this.query,
    required this.highlighted,
    required this.onPick,
    required this.onExpand,
    this.listMaxHeight = _E.popupMaxListHeight,
  });

  final MentionCandidates rows;
  final bool loading;
  final String query;
  final int highlighted;
  final ValueChanged<NoteMention> onPick;
  final VoidCallback onExpand;
  final double listMaxHeight;

  @override
  State<MentionPopup> createState() => _MentionPopupState();
}

class _MentionPopupState extends State<MentionPopup> with TickerProviderStateMixin {
  late final SpringValue _in = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.disableAnimationsOf(context)) {
        _in.snapTo(1);
      } else {
        _in.animateTo(1);
      }
    });
  }

  @override
  void dispose() {
    _in.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NotesStrings.of(context);
    final reduce = MediaQuery.disableAnimationsOf(context);
    final card = Material(
      color: colors.surfaceContainerHigh,
      elevation: _E.popupElevation,
      shadowColor: colors.shadow,
      surfaceTintColor: colors.surfaceTint,
      borderRadius: BorderRadius.circular(_E.popupCorner),
      clipBehavior: Clip.antiAlias,
      child: SizedBox(
        width: _E.popupWidth,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: _E.popupPadV),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsetsDirectional.only(start: _E.popupTitlePadStart, end: _E.popupTitlePadEnd),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        strings.mentionPopupTitle,
                        style: text.labelLarge!.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ),
                    TextButton.icon(
                      onPressed: widget.onExpand,
                      icon: const Icon(YoinSymbols.openInFull, size: _E.popupExpandGlyph),
                      label: Text(strings.mentionExpand),
                    ),
                  ],
                ),
              ),
              ConstrainedBox(
                constraints: BoxConstraints(maxHeight: widget.listMaxHeight),
                child: SingleChildScrollView(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      ...mentionRowWidgets(
                        context,
                        rows: widget.rows,
                        query: widget.query,
                        highlighted: widget.highlighted,
                        onPick: widget.onPick,
                      ),
                      if (widget.rows.isEmpty)
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: _E.popupEmptyPadH,
                            vertical: _E.popupEmptyPadV,
                          ),
                          child: Text(
                            widget.loading ? strings.mentionLoading : strings.mentionEmpty,
                            style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    return AnimatedBuilder(
      animation: _in.animation,
      builder: (context, child) {
        final t = _in.value.clamp(0.0, 1.0);
        final scale = reduce ? 1.0 : _E.popupEnterScale + (1 - _E.popupEnterScale) * _in.value;
        return Opacity(
          opacity: t,
          child: Transform.scale(scale: scale, alignment: Alignment.bottomLeft, child: child),
        );
      },
      child: card,
    );
  }
}

/// The rows by kind with section labels; [highlighted] indexes [MentionCandidates.all].
List<Widget> mentionRowWidgets(
  BuildContext context, {
  required MentionCandidates rows,
  required String query,
  required int highlighted,
  required ValueChanged<NoteMention> onPick,
}) {
  final colors = Theme.of(context).colorScheme;
  final text = Theme.of(context).textTheme;
  final strings = NotesStrings.of(context);
  final out = <Widget>[];
  var index = 0;
  for (final kind in rows.order) {
    final list = rows.of(kind);
    if (list.isEmpty) continue;
    out.add(
      Padding(
        padding: const EdgeInsetsDirectional.only(
          start: _E.sectionPadStart,
          top: _E.sectionPadTop,
          bottom: _E.sectionPadBottom,
        ),
        child: Text(switch (kind) {
          MentionKind.track => strings.mentionSectionTracks,
          MentionKind.album => strings.mentionSectionAlbums,
          MentionKind.artist => strings.mentionSectionArtists,
        }, style: text.labelMedium!.copyWith(color: colors.primary)),
      ),
    );
    for (final row in list) {
      out.add(MentionRow(row: row, query: query, highlighted: index == highlighted, onTap: () => onPick(row.mention)));
      index++;
    }
  }
  return out;
}

/// One candidate: cover (an artist's round), title with the typed words lit, artist and album.
class MentionRow extends StatelessWidget {
  const MentionRow({super.key, required this.row, required this.query, required this.highlighted, required this.onTap});

  final MentionCandidate row;
  final String query;
  final bool highlighted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NotesStrings.of(context);
    final mention = row.mention;
    final label = switch (mention.kind) {
      MentionKind.track => strings.mentionCdTrack(mention.title),
      MentionKind.album => strings.mentionCdAlbum(mention.title),
      MentionKind.artist => strings.mentionCdArtist(mention.title),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: _E.rowInsetH),
      child: Semantics(
        button: true,
        label: label,
        excludeSemantics: true,
        child: Material(
          color: highlighted ? colors.secondaryContainer : Colors.transparent,
          borderRadius: BorderRadius.circular(_E.rowCorner),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: _E.rowPadH, vertical: _E.rowPadV),
              child: Row(
                children: [
                  SizedBox.square(
                    dimension: _E.rowArt,
                    child: MediaArtwork(
                      url: row.coverUrl,
                      fallbackIcon: mentionKindSymbol(mention.kind),
                      circle: mention.kind == MentionKind.artist,
                      radius: ShapeTokens.thumb,
                    ),
                  ),
                  const SizedBox(width: _E.rowGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text.rich(
                          _highlightMatch(mention.title, query, colors.primary),
                          style: text.bodyLarge,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (row.artist != null || row.album != null)
                          MetaLine(groups: [?_plain(row.artist), ?_muted(row.album)], color: colors.onSurfaceVariant),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static MetaGroup? _plain(String? s) => s == null ? null : MetaPlain(s);
  static MetaGroup? _muted(String? s) => s == null ? null : MetaMuted(s);
}

TextSpan _highlightMatch(String title, String query, Color color) {
  final q = query.trim();
  final at = q.isEmpty ? -1 : title.toLowerCase().indexOf(q.toLowerCase());
  if (at < 0) return TextSpan(text: title);
  return TextSpan(
    children: [
      TextSpan(text: title.substring(0, at)),
      TextSpan(
        text: title.substring(at, at + q.length),
        style: TextStyle(fontWeight: FontWeight.w700, color: color),
      ),
      TextSpan(text: title.substring(at + q.length)),
    ],
  );
}

/// A mention kind's symbol (no picture yet).
IconData mentionKindSymbol(MentionKind kind) => switch (kind) {
  MentionKind.track => YoinSymbols.musicNote,
  MentionKind.album => YoinSymbols.album,
  MentionKind.artist => YoinSymbols.artist,
};

/// "Search all" (展开搜索): the whole search as a sheet; a pick goes back into the note at the `@`.
Future<NoteMention?> showMentionSearchSheet(
  BuildContext context, {
  required String initialQuery,
  required MentionCandidateSource candidates,
}) => showModalBottomSheet<NoteMention>(
  context: context,
  useSafeArea: true,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
  builder: (_) => _MentionSearchSheet(initialQuery: initialQuery, candidates: candidates),
);

class _MentionSearchSheet extends StatefulWidget {
  const _MentionSearchSheet({required this.initialQuery, required this.candidates});

  final String initialQuery;
  final MentionCandidateSource candidates;

  @override
  State<_MentionSearchSheet> createState() => _MentionSearchSheetState();
}

class _MentionSearchSheetState extends State<_MentionSearchSheet> {
  late final TextEditingController _field = TextEditingController(text: widget.initialQuery);
  MentionCandidates _rows = MentionCandidates.empty;
  bool _loading = true;
  Timer? _debounce;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    _field.addListener(_onQuery);
    _search(widget.initialQuery);
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _field.dispose();
    super.dispose();
  }

  String _last = '';

  void _onQuery() {
    if (_field.text == _last) return;
    _last = _field.text;
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: _E.sheetDebounceMs), () => _search(_field.text));
  }

  Future<void> _search(String q) async {
    final generation = ++_generation;
    setState(() => _loading = true);
    final rows = await widget.candidates.candidates(q, _E.sheetRowsPerKind);
    if (!mounted || generation != _generation) return;
    setState(() {
      _rows = rows;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NotesStrings.of(context);
    final height = MediaQuery.sizeOf(context).height;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SizedBox(
        height: height * _E.sheetHeightFraction,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: _E.sheetFieldPadH, vertical: _E.sheetFieldPadV),
              child: TextField(
                controller: _field,
                autofocus: true,
                decoration: InputDecoration(
                  hintText: strings.mentionSearchHint,
                  prefixIcon: const Icon(YoinSymbols.search),
                  border: OutlineInputBorder(borderRadius: BorderRadius.circular(_E.sheetFieldCorner)),
                ),
              ),
            ),
            Expanded(
              child: _rows.isEmpty
                  ? Padding(
                      padding: const EdgeInsets.all(_E.sheetEmptyPad),
                      child: Text(
                        _loading ? strings.mentionLoading : strings.mentionEmpty,
                        style: text.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                      ),
                    )
                  : ListView(
                      padding: const EdgeInsets.only(bottom: _E.sheetBottomPad),
                      children: mentionRowWidgets(
                        context,
                        rows: _rows,
                        query: _field.text,
                        highlighted: -1,
                        onPick: (m) => Navigator.of(context).pop(m),
                      ),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}
