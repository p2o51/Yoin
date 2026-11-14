import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/memory/memory_store.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import 'haptics.dart';
import 'note_controller.dart';
import 'note_line.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';

typedef _N = NowPlayingNoteTokens;

enum _NoteAction { realign, delete }

/// The Expanded Note page (Android NoteFullscreenPane): the song's notes read
/// like its lyrics — a time stamp, then the words, the note the playhead is
/// inside lit, the list gliding to keep it ~22 % down (timeline order) — with
/// the note count and the Timeline ⇄ Created switch above. No cards, no
/// per-row buttons: tap an anchored line to play from its moment, long-press
/// it for "Align to now" / "Delete" (a deleted line offers Undo in its place
/// for a few seconds). Writing happens in the write bar docked below (the
/// accessory slot); while it is open the list holds still and a "Writing…"
/// row shows where the draft will land. Empty: "No notes yet", nothing else.
class NotePage extends StatefulWidget {
  const NotePage({super.key, required this.notes, required this.clock});

  final NowPlayingNotes notes;
  final PlayheadClock clock;

  @override
  State<NotePage> createState() => _NotePageState();
}

class _NotePageState extends State<NotePage> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  late final AnimationController _glide;
  final GlobalKey _content = GlobalKey();
  final Map<Object, GlobalKey> _rowKeys = {};

  /// Rows on screen last build, and undo rows collapsing out after their window closed.
  List<NoteRow> _shown = const [];
  final Map<Object, NoteRow> _leaving = {};
  Set<Object> _known = {};
  bool _firstBuild = true;
  String? _lit;
  bool _userScrolled = false;
  bool _seated = false;
  double _viewport = 0;
  String? _lastSignature;
  NoteSortMode? _lastSort;

  NowPlayingNotes get _notes => widget.notes;

  @override
  void initState() {
    super.initState();
    _glide = AnimationController.unbounded(vsync: this)..addListener(_onGlide);
    widget.clock.addListener(_onTick);
    _notes.addListener(_onNotes);
    _lit = currentAnchoredNoteId(_notes.notes, widget.clock.positionMs());
  }

  @override
  void didUpdateWidget(NotePage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
    }
    if (oldWidget.notes != widget.notes) {
      oldWidget.notes.removeListener(_onNotes);
      widget.notes.addListener(_onNotes);
    }
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    _notes.removeListener(_onNotes);
    _glide.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _onNotes() {
    if (!mounted) return;
    setState(() {});
    WidgetsBinding.instance.addPostFrameCallback((_) => _follow());
  }

  void _onTick() {
    final next = currentAnchoredNoteId(_notes.notes, widget.clock.positionMs());
    if (next == _lit) return;
    setState(() => _lit = next);
    WidgetsBinding.instance.addPostFrameCallback((_) => _follow());
  }

  void _onGlide() {
    if (_scroll.hasClients) _scroll.jumpTo(_glide.value);
  }

  /// Glide to the lit note (timeline order, not dragged, not writing) or,
  /// while writing, to the draft's spot — where the words land.
  void _follow() {
    if (!mounted) return;
    final writing = _notes.open;
    Object? target;
    if (writing && _notes.draftHere) {
      target = const NoteDraftRow(null).key;
    } else {
      final index = _shown.indexWhere((r) => r is NoteLineRow && r.note.id == _lit);
      if (shouldFollowCurrentNote(
        sortMode: _notes.sortMode,
        currentIndex: index,
        userScrolled: _userScrolled,
        writing: writing,
      )) {
        target = _lit;
      }
    }
    if (target != null) _seat(target);
  }

  void _seat(Object key) {
    if (!_scroll.hasClients) return;
    final box = _rowKeys[key]?.currentContext?.findRenderObject() as RenderBox?;
    final content = _content.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || content == null || !box.attached || !content.attached) return;
    final top = box.localToGlobal(Offset.zero, ancestor: content).dy;
    final position = _scroll.position;
    final target = (top - _viewport * noteFollowAnchorFraction).clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );
    if (!_seated || MediaQuery.disableAnimationsOf(context)) {
      _glide.stop();
      _scroll.jumpTo(target);
      _seated = true;
      return;
    }
    _glide.value = _scroll.offset;
    _glide.animateWith(SpringSimulation(YoinSprings.standardDefaultSpatial, _scroll.offset, target, 0));
  }

  bool _onScroll(ScrollNotification n) {
    if (n is ScrollStartNotification && n.dragDetails != null) {
      _glide.stop();
      _userScrolled = true;
    }
    return false;
  }

  Future<void> _openMenu(BuildContext rowContext, SongNote note) async {
    final strings = NowPlayingStrings.of(context);
    YoinHaptics.longPress();
    // "Now" as of the long-press: what the menu shows is what it files.
    final now = widget.clock.positionMs();
    final chosen = await showYoinDropdownMenu<_NoteAction>(
      context: rowContext,
      anchor: globalRectOf(rowContext),
      semanticLabel: strings.noteActions,
      items: [
        YoinMenuItem(value: _NoteAction.realign, label: strings.noteRealign, icon: YoinSymbols.musicNote),
        YoinMenuItem(value: _NoteAction.delete, label: strings.noteDelete, icon: YoinSymbols.delete),
      ],
    );
    switch (chosen) {
      case _NoteAction.realign:
        YoinHaptics.tick();
        await _notes.realignNote(note, now);
      case _NoteAction.delete:
        YoinHaptics.reject();
        _notes.delete(note);
      case null:
        break;
    }
  }

  /// This build's rows, with an undo row whose window just closed kept in
  /// place while it collapses (the rows below glide up into its room).
  List<NoteRow> _rows() {
    final notes = _notes.notes;
    // A drag takes the wheel until the notes or their order change.
    final signature = notes.map((n) => '${n.id}@${n.positionMs}').join('|');
    if (signature != _lastSignature || _lastSort != _notes.sortMode) {
      if (_lastSort != null) _userScrolled = false;
      _lastSignature = signature;
      _lastSort = _notes.sortMode;
    }
    final rows = noteRows(
      notes,
      _notes.sortMode,
      deleted: _notes.pendingDeletion,
      draftAnchorMs: _notes.draft.anchorMs,
      showDraft: _notes.open && _notes.draftHere,
    );
    final keys = {for (final r in rows) r.key};
    for (var i = 0; i < _shown.length; i++) {
      final old = _shown[i];
      if (old is NoteDeletedRow && !keys.contains(old.key)) _leaving[old.key] = old;
    }
    _leaving.removeWhere((key, _) => keys.contains(key));
    if (_leaving.isEmpty) return rows;
    // Put each collapsing row back where it stood.
    final merged = [...rows];
    for (final entry in _leaving.entries) {
      final at = _shown.indexWhere((r) => r.key == entry.key);
      merged.insert(math.min(math.max(at, 0), merged.length), entry.value);
    }
    return merged;
  }

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final notes = _notes.notes;
    final rows = _rows();
    final appearing = _firstBuild ? <Object>{} : {for (final r in rows) r.key}.difference(_known);
    _shown = [
      for (final r in rows)
        if (!_leaving.containsKey(r.key)) r,
    ];
    _known = {for (final r in rows) r.key};
    _firstBuild = false;

    if (rows.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: _N.emptyMinHeight),
            child: Center(
              heightFactor: 1,
              child: Text(strings.noteEmpty, style: text.titleSmall!.copyWith(color: colors.onSurfaceVariant)),
            ),
          ),
        ),
      );
    }

    final litIndex = rows.indexWhere((r) => r is NoteLineRow && r.note.id == _lit);
    final showDate = _notes.sortMode == NoteSortMode.created;
    final nowMs = DateTime.now().millisecondsSinceEpoch;

    Widget row(int index, NoteRow r) {
      final key = _rowKeys.putIfAbsent(r.key, GlobalKey.new);
      final Widget child = switch (r) {
        NoteLineRow(:final note) => Builder(
          builder: (rowContext) => Semantics(
            button: note.positionMs != null,
            onTapHint: note.positionMs == null ? null : strings.notePlayFrom(formatNotePosition(note.positionMs!)),
            onLongPressHint: strings.noteActions,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: note.positionMs == null ? null : () => _notes.playFrom(note),
              onLongPress: () => _openMenu(rowContext, note),
              onSecondaryTap: () => _openMenu(rowContext, note),
              child: NoteLine(
                stamp: note.positionMs == null ? null : formatNotePosition(note.positionMs!),
                text: note.content,
                active: note.id == _lit,
                distance: litIndex >= 0 ? (index - litIndex).abs() : null,
                meta: showDate ? formatNoteDate(note.createdAt, nowMs: nowMs, zh: strings.zh) : null,
              ),
            ),
          ),
        ),
        NoteDeletedRow() => _DeletedLine(onUndo: _notes.undo),
        NoteDraftRow(:final anchorMs) => _DraftLine(anchorMs: anchorMs),
      };
      return _RowSlot(
        key: ValueKey(r.key),
        appear: appearing.contains(r.key),
        leaving: _leaving.containsKey(r.key),
        onGone: () {
          if (!mounted) return;
          setState(() => _leaving.remove(r.key));
        },
        child: KeyedSubtree(key: key, child: child),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            NowPlayingTokens.columnPadding,
            0,
            NowPlayingTokens.columnPadding,
            _N.headerBottomGap,
          ),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  strings.noteCount(notes.length),
                  style: text.labelMedium!.copyWith(color: colors.onSurfaceVariant),
                ),
              ),
              NoteSortToggle(mode: _notes.sortMode, onChanged: _notes.setSortMode),
            ],
          ),
        ),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final viewport = constraints.maxHeight;
              if (viewport > 0 && viewport != _viewport) {
                _viewport = viewport;
                WidgetsBinding.instance.addPostFrameCallback((_) => _follow());
              }
              return Listener(
                // A finger on the list holds the undo window open until it lifts.
                onPointerDown: (_) => _notes.setListHeld(true),
                onPointerUp: (_) => _notes.setListHeld(false),
                onPointerCancel: (_) => _notes.setListHeld(false),
                child: ShaderMask(
                  blendMode: BlendMode.dstIn,
                  shaderCallback: (rect) => LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: const [Color(0x00000000), Color(0xFF000000), Color(0xFF000000), Color(0x00000000)],
                    stops: [
                      0,
                      (_N.pageFadeTop / rect.height).clamp(0.0, 0.5),
                      1 - (_N.pageFadeBottom / rect.height).clamp(0.0, 0.5),
                      1,
                    ],
                  ).createShader(rect),
                  child: NotificationListener<ScrollNotification>(
                    onNotification: _onScroll,
                    child: SingleChildScrollView(
                      controller: _scroll,
                      physics: const BouncingScrollPhysics(),
                      dragStartBehavior: DragStartBehavior.down,
                      padding: const EdgeInsets.fromLTRB(
                        NowPlayingTokens.columnPadding,
                        _N.pageTopPadding,
                        NowPlayingTokens.columnPadding,
                        _N.pageBottomPadding,
                      ),
                      child: Column(
                        key: _content,
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [for (var i = 0; i < rows.length; i++) row(i, rows[i])],
                      ),
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// A row's size and fade: a new row opens from nothing (spatial spring for
/// the height, effects spring for the alpha); a collapsing undo row closes to
/// nothing and is then dropped ([onGone]).
class _RowSlot extends StatefulWidget {
  const _RowSlot({super.key, required this.appear, required this.leaving, required this.onGone, required this.child});

  final bool appear;
  final bool leaving;
  final VoidCallback onGone;
  final Widget child;

  @override
  State<_RowSlot> createState() => _RowSlotState();
}

class _RowSlotState extends State<_RowSlot> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.appear ? 0 : 1,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _fade = SpringValue(
    vsync: this,
    initial: widget.appear ? 0 : 1,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    _size.animation.addStatusListener(_onStatus);
    WidgetsBinding.instance.addPostFrameCallback((_) => _retarget());
  }

  @override
  void didUpdateWidget(_RowSlot oldWidget) {
    super.didUpdateWidget(oldWidget);
    _retarget();
  }

  void _retarget() {
    if (!mounted) return;
    final target = widget.leaving ? 0.0 : 1.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      // Reduced motion: fade in place, no height travel.
      _size.snapTo(target);
      _fade.animateTo(target);
      if (widget.leaving) widget.onGone();
      return;
    }
    _size.animateTo(target);
    _fade.animateTo(target);
  }

  void _onStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed && widget.leaving && _size.value <= 0.001) widget.onGone();
  }

  @override
  void dispose() {
    _size.dispose();
    _fade.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
    ignoring: widget.leaving,
    child: AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _fade.animation]),
      child: widget.child,
      builder: (context, child) => ClipRect(
        child: Align(
          alignment: Alignment.topCenter,
          heightFactor: _size.value.clamp(0.0, 1.0),
          child: Opacity(opacity: _fade.value.clamp(0.0, 1.0), child: child),
        ),
      ),
    ),
  );
}

/// A just-deleted note's place: "Deleted" and the way back.
class _DeletedLine extends StatelessWidget {
  const _DeletedLine({required this.onUndo});

  final VoidCallback onUndo;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: _N.deletedRowHeight),
      child: Row(
        children: [
          const SizedBox(width: _N.stampColumn),
          Expanded(
            child: Text(strings.noteDeleted, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
          ),
          TextButton(
            onPressed: () {
              YoinHaptics.tick();
              onUndo();
            },
            child: Text(strings.noteUndo, style: noteWeighted(text.labelLarge!, 600)),
          ),
        ],
      ),
    );
  }
}

/// Where the note being written will land: its moment, "Writing…" on a dashed line.
class _DraftLine extends StatelessWidget {
  const _DraftLine({required this.anchorMs});

  final int? anchorMs;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: _N.pageRowPadV),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: [
          SizedBox(
            width: _N.stampColumn,
            child: Text(
              anchorMs == null ? '' : formatNotePosition(anchorMs!),
              maxLines: 1,
              style: noteWeighted(text.labelMedium!, 600).copyWith(
                color: colors.primary.withValues(alpha: 0.8),
                fontFeatures: const [FontFeature.tabularFigures()],
              ),
            ),
          ),
          Flexible(
            child: CustomPaint(
              foregroundPainter: _DashedUnderline(colors.primary),
              child: Padding(
                padding: const EdgeInsets.only(bottom: _N.draftUnderlineGap),
                child: Text(strings.noteWriting, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DashedUnderline extends CustomPainter {
  _DashedUnderline(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = _N.draftStroke;
    final y = size.height;
    for (var x = 0.0; x < size.width; x += _N.draftDash + _N.draftDashGap) {
      canvas.drawLine(Offset(x, y), Offset(math.min(x + _N.draftDash, size.width), y), paint);
    }
  }

  @override
  bool shouldRepaint(_DashedUnderline old) => old.color != color;
}
