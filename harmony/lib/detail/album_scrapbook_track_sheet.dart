/// Page 2's track sheet (Android AlbumScrapbookTrackSheet.kt, owner-approved
/// prototype v3, 2026-11-02). One sheet per track: the number, the title and
/// the score as a pill (tap it for the bar), then the button group —
/// About | Note · N.
///
/// About: every fact and question, each with a pencil; a long press edits
/// too. The editor has Delete, Regenerate (not on the listener's own
/// entries) and Done; a delete or a regenerate can be undone from the
/// notice. Under them, a new entry: a title first, then Ask Gemini or Write it
/// yourself; while the empty title has the keyboard, the quick questions not
/// asked yet sit under it.
///
/// Note: each note with the three lyric lines above and below it (passages
/// that touch merge, "· · ·" between the rest); a note offers Edit / Play
/// from here, a lyric line New note / Play from here; "All lyrics" puts the
/// notes into the whole text. An emptied note is deleted, with Undo.
///
/// Opened by long-pressing a ticket, tapping the track's question / About
/// card or a fact tag ("+N notes" opens it on Note), or from Now Playing's
/// About ("Manage in Scrapbook", on that row's editor).
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/about/song_about_store.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../memories/memory_rate_sheet.dart' show HorizontalRatingBar;
import '../notes/note_markup_text.dart';
import '../nowplaying/about_logic.dart' show remainingQuickPrompts;
import '../nowplaying/about_widgets.dart' show QuickPrompts, ThinkingDots, markdownBoldSpan;
import '../nowplaying/haptics.dart';
import '../nowplaying/note_logic.dart' show formatNotePosition;
import '../nowplaying/nowplaying_strings.dart';
import '../settings/ai_settings_screen.dart' show AiSpringSwap;
import '../settings/settings_components.dart' show SpringReveal;
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import 'album_scrapbook.dart';
import 'album_scrapbook_editor.dart';
import 'album_scrapbook_page.dart' show ScrapbookColors;
import 'album_scrapbook_sheet_model.dart';
import 'detail_play.dart';
import 'detail_strings.dart';

/// The sheet's numbers (AlbumScrapbookTrackSheet's SheetGutter, InnerGutter …).
abstract final class ScrapSheetMetrics {
  static const double gutter = 16;
  static const double innerGutter = 8;
  static const double factLabelWidth = 76;
  static const double editorPadH = 14;
  static const double editorPadV = 8;
  static const double newEntryPadding = 14;
  static const double scorePillMinHeight = 40;
  static const double scorePillMinWidth = 60;
  static const double tabHeight = 40;
  static const double tabGap = 2;
  static const double editButton = 40;
  static const double editGlyph = 18;
  static const double cardCorner = 20;
  static const double newEntryCorner = 24;
  static const double fieldCorner = 16;
  static const double noteCorner = 16;
  static const double buttonMinHeight = 44;
  static const double bodyMinHeight = 84;
  static const Duration notice = Duration(seconds: 4);
  static const Duration noticeWithUndo = Duration(seconds: 7);
}

/// Which album pages have a track sheet open (the page leaves the AI's outcomes to it meanwhile).
abstract final class ScrapTrackSheetScope {
  static final Map<MediaId, int> _open = {};

  static bool isOpen(MediaId albumId) => (_open[albumId] ?? 0) > 0;
}

/// What the sheet says for a failed AI call.
String scrapFailureText(DetailStrings strings, ScrapFailure failure) => switch (failure) {
  ScrapFailure.keyMissing => strings.scrapApiKeyMissing,
  ScrapFailure.failed => strings.scrapGeminiFailed,
  ScrapFailure.nothingNew => strings.scrapGeminiNothing,
};

/// Opens [trackId]'s sheet on [tab] (on the About row [editing], from Now Playing's Manage).
Future<void> showScrapTrackSheet(
  BuildContext context, {
  required Album album,
  required MediaId trackId,
  required ScrapbookColors colors,
  ScrapSheetTab tab = ScrapSheetTab.about,
  AboutRef? editing,
}) {
  final pieces = ProviderScope.containerOf(context, listen: false).read(albumScrapbookProvider(album.id));
  if (pieces == null || trackSheetFor(pieces, trackId) == null) return Future.value();
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (_) => ChromeBarSuppression(
      child: ScrapTrackSheet(
        album: album,
        trackId: trackId,
        colors: colors,
        initialTab: editing != null ? ScrapSheetTab.about : tab,
        initialEditing: editing,
      ),
    ),
  );
}

/// A notice at the sheet's foot; [undo] is written back on Undo.
final class _Notice {
  _Notice(this.message, {this.icon, this.undo});
  final String message;
  final IconData? icon;
  final ScrapEdit? undo;
}

class ScrapTrackSheet extends ConsumerStatefulWidget {
  const ScrapTrackSheet({
    super.key,
    required this.album,
    required this.trackId,
    required this.colors,
    this.initialTab = ScrapSheetTab.about,
    this.initialEditing,
  });

  final Album album;
  final MediaId trackId;
  final ScrapbookColors colors;
  final ScrapSheetTab initialTab;
  final AboutRef? initialEditing;

  @override
  ConsumerState<ScrapTrackSheet> createState() => _ScrapTrackSheetState();
}

class _ScrapTrackSheetState extends ConsumerState<ScrapTrackSheet> {
  late ScrapSheetTab _tab = widget.initialTab;
  ScrapTrackSheetTarget? _last;

  /// The Note tab's lyrics, read once it has been opened (About alone never fetches them).
  Future<ScrapLyrics>? _lyrics;
  _Notice? _notice;
  Timer? _noticeTimer;
  StreamSubscription<ScrapEvent>? _events;

  AlbumScrapbookEditor get _editor => ref.read(albumScrapbookEditorProvider(widget.album.id).notifier);

  @override
  void initState() {
    super.initState();
    final open = ScrapTrackSheetScope._open;
    open[widget.album.id] = (open[widget.album.id] ?? 0) + 1;
    if (_tab == ScrapSheetTab.notes) _lyrics = _editor.lyrics(widget.trackId);
    _events = _editor.events.listen(_onEvent);
  }

  @override
  void dispose() {
    final open = ScrapTrackSheetScope._open;
    open[widget.album.id] = (open[widget.album.id] ?? 1) - 1;
    _events?.cancel();
    _noticeTimer?.cancel();
    super.dispose();
  }

  void _onEvent(ScrapEvent event) {
    if (!mounted) return;
    final strings = DetailStrings.of(context);
    switch (event) {
      case ScrapRegenerated(:final restore):
        _say(_Notice(strings.scrapRegenerated, icon: YoinSymbols.sparkle, undo: restore));
      case ScrapFailed(:final failure):
        _say(_Notice(scrapFailureText(strings, failure), icon: YoinSymbols.error));
    }
  }

  /// One notice at a time: a second replaces the first (what the first did stays done).
  void _say(_Notice notice) {
    _noticeTimer?.cancel();
    setState(() => _notice = notice);
    _noticeTimer = Timer(notice.undo != null ? ScrapSheetMetrics.noticeWithUndo : ScrapSheetMetrics.notice, () {
      if (mounted) setState(() => _notice = null);
    });
  }

  void _edit(ScrapEdit edit) => _editor.edit(edit);

  void _selectTab(ScrapSheetTab tab) {
    if (tab == _tab) return;
    YoinHaptics.tick();
    setState(() {
      _tab = tab;
      if (tab == ScrapSheetTab.notes) _lyrics ??= _editor.lyrics(widget.trackId);
    });
  }

  void _playFrom(int? positionMs) {
    final index = widget.album.tracks.indexWhere((t) => t.id == widget.trackId);
    if (index < 0) return;
    DetailPlayer.widget(ref).controller.playTracks(
      widget.album.tracks,
      startIndex: index,
      context: albumPlaybackContext(widget.album),
      startPositionMs: positionMs ?? 0,
    );
    if (positionMs != null) {
      _say(
        _Notice(
          DetailStrings.of(context).scrapPlayingFrom(formatNotePosition(positionMs)),
          icon: YoinSymbols.playArrow,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final pieces = ref.watch(albumScrapbookProvider(widget.album.id));
    final gemini = ref.watch(albumScrapbookEditorProvider(widget.album.id));
    // A cleared score or a removed question can take the ticket off the page: the sheet keeps
    // what it last showed until it is closed.
    final fresh = pieces == null ? null : trackSheetFor(pieces, widget.trackId);
    final target = fresh ?? _last;
    _last = target;
    if (target == null) return const SizedBox.shrink();
    final strings = DetailStrings.of(context);
    final notice = _notice;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // The track's name and score stay put; everything under them scrolls.
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.gutter),
            child: _ScoreHeader(
              track: target.track,
              colors: widget.colors,
              onEdit: _edit,
              onCleared: (was) => _say(
                _Notice(strings.scrapScoreCleared, icon: YoinSymbols.delete, undo: ScrapScoreEdit(widget.trackId, was)),
              ),
            ),
          ),
          Flexible(
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                ScrapSheetMetrics.gutter,
                0,
                ScrapSheetMetrics.gutter,
                24 + MediaQuery.paddingOf(context).bottom,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.innerGutter, vertical: 14),
                    child: _SheetTabs(
                      labels: [strings.scrapTabAbout, strings.scrapTabNotes(target.notes.length)],
                      selected: _tab.index,
                      colors: widget.colors,
                      onSelect: (i) => _selectTab(ScrapSheetTab.values[i]),
                    ),
                  ),
                  AiSpringSwap(
                    childKey: _tab,
                    child: switch (_tab) {
                      ScrapSheetTab.about => _AboutTab(
                        key: ValueKey('about:${widget.trackId}'),
                        target: target,
                        colors: widget.colors,
                        gemini: gemini,
                        initialEditing: widget.initialEditing,
                        onEdit: _edit,
                        onNotice: _say,
                      ),
                      ScrapSheetTab.notes => FutureBuilder<ScrapLyrics>(
                        future: _lyrics,
                        builder: (context, snap) => _NotesTab(
                          key: ValueKey('notes:${widget.trackId}'),
                          target: target,
                          lyrics: snap.data ?? ScrapLyrics.pending,
                          colors: widget.colors,
                          onEdit: _edit,
                          onNotice: _say,
                          onPlayFrom: _playFrom,
                        ),
                      ),
                    },
                  ),
                ],
              ),
            ),
          ),
          SpringReveal(
            visible: notice != null,
            child: notice == null
                ? null
                : _NoticeBar(
                    notice: notice,
                    onUndo: () {
                      final undo = notice.undo;
                      setState(() => _notice = null);
                      if (undo != null) _edit(undo);
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _NoticeBar extends StatelessWidget {
  const _NoticeBar({required this.notice, required this.onUndo});

  final _Notice notice;
  final VoidCallback onUndo;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        ScrapSheetMetrics.gutter,
        4,
        ScrapSheetMetrics.gutter,
        8 + MediaQuery.paddingOf(context).bottom,
      ),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.inverseSurface,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.fieldCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
          child: Row(
            children: [
              if (notice.icon case final icon?) ...[
                Icon(icon, size: 18, color: colors.onInverseSurface),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    notice.message,
                    style: theme.textTheme.bodyMedium?.copyWith(color: colors.onInverseSurface),
                  ),
                ),
              ),
              if (notice.undo != null)
                TextButton(
                  onPressed: onUndo,
                  child: Text(DetailStrings.of(context).scrapUndo, style: TextStyle(color: colors.inversePrimary)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// About | Note · N: equal capsules, the selected one in the page's ink (YoinTabGroup).
class _SheetTabs extends StatelessWidget {
  const _SheetTabs({required this.labels, required this.selected, required this.colors, required this.onSelect});

  final List<String> labels;
  final int selected;
  final ScrapbookColors colors;
  final ValueChanged<int> onSelect;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: ScrapSheetMetrics.tabHeight,
    child: Row(
      children: [
        for (final (i, label) in labels.indexed) ...[
          if (i > 0) const SizedBox(width: ScrapSheetMetrics.tabGap),
          Expanded(
            child: _SheetTab(label: label, selected: i == selected, colors: colors, onTap: () => onSelect(i)),
          ),
        ],
      ],
    ),
  );
}

class _SheetTab extends StatefulWidget {
  const _SheetTab({required this.label, required this.selected, required this.colors, required this.onTap});

  final String label;
  final bool selected;
  final ScrapbookColors colors;
  final VoidCallback onTap;

  @override
  State<_SheetTab> createState() => _SheetTabState();
}

class _SheetTabState extends State<_SheetTab> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_SheetTab old) {
    super.didUpdateWidget(old);
    _on.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.colors;
    final base = Theme.of(context).textTheme.labelLarge;
    return Semantics(
      button: true,
      selected: widget.selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _on.animation,
          builder: (context, _) {
            final on = _on.value.clamp(0.0, 1.0);
            return DecoratedBox(
              decoration: ShapeDecoration(color: Color.lerp(c.review, c.ink, on), shape: const StadiumBorder()),
              child: Center(
                child: Text(
                  widget.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: base?.copyWith(
                    fontWeight: widget.selected ? FontWeight.w700 : FontWeight.w500,
                    color: Color.lerp(c.onReview, c.onInk, on),
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Header: number, title, the score pill and its bar.
// ---------------------------------------------------------------------------

class _ScoreHeader extends StatefulWidget {
  const _ScoreHeader({required this.track, required this.colors, required this.onEdit, required this.onCleared});

  final ScrapTrack track;
  final ScrapbookColors colors;
  final ValueChanged<ScrapEdit> onEdit;

  /// The score was cleared from [was] (the notice offers it back).
  final ValueChanged<double> onCleared;

  @override
  State<_ScoreHeader> createState() => _ScoreHeaderState();
}

class _ScoreHeaderState extends State<_ScoreHeader> {
  /// The bar's own value (null: not rated); saved when the finger lifts.
  late double? _live = widget.track.score;
  late double? _saved = widget.track.score;
  bool _held = false;
  bool _open = false;

  @override
  void didUpdateWidget(_ScoreHeader old) {
    super.didUpdateWidget(old);
    // A score written from elsewhere (the clear's Undo, Now Playing) shows, unless a finger is on the bar.
    if (!_held && widget.track.score != _saved) {
      _live = widget.track.score;
      _saved = widget.track.score;
    }
  }

  void _commit(double? score) {
    if (score == _saved) return;
    _saved = score;
    widget.onEdit(ScrapScoreEdit(widget.track.track.id, score));
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final c = widget.colors;
    final scoreText = _live == null ? null : scrapScoreText(_live!);
    const tabular = [FontFeature.tabularFigures()];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(ScrapSheetMetrics.innerGutter, 6, ScrapSheetMetrics.innerGutter, 0),
          child: Row(
            children: [
              Text(
                '${widget.track.number}'.padLeft(2, '0'),
                style: theme.textTheme.labelLarge?.copyWith(
                  letterSpacing: 0.5,
                  fontFeatures: tabular,
                  color: colors.onSurfaceVariant,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  widget.track.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600, color: colors.onSurface),
                ),
              ),
              const SizedBox(width: 12),
              Semantics(
                button: true,
                label: scoreText == null ? strings.scrapNotRated : strings.scrapCdScore(scoreText),
                value: _open ? strings.scrapCdScoreOpen : strings.scrapCdScoreClosed,
                excludeSemantics: true,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(
                    minHeight: ScrapSheetMetrics.scorePillMinHeight,
                    minWidth: ScrapSheetMetrics.scorePillMinWidth,
                  ),
                  child: Material(
                    color: c.review,
                    shape: const StadiumBorder(),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () {
                        YoinHaptics.tick();
                        setState(() => _open = !_open);
                      },
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                        child: Center(
                          widthFactor: 1,
                          child: Text(
                            // Unrated: the word, not a dash (a lone "—" read as a collapse button).
                            scoreText ?? strings.scrapRate,
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w600,
                              fontFeatures: tabular,
                              color: c.onReview,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        SpringReveal(
          visible: _open,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(ScrapSheetMetrics.innerGutter, 12, ScrapSheetMetrics.innerGutter, 0),
            child: Row(
              children: [
                Expanded(
                  child: HorizontalRatingBar(
                    rating: _live,
                    fill: c.ink,
                    onFill: c.onInk,
                    unratedHint: strings.scrapRateHint,
                    onChanged: (v) => setState(() => _live = v),
                    onActive: (active) {
                      _held = active;
                      if (!active) _commit(_live);
                    },
                  ),
                ),
                // The only clear in the app: back to "not rated", never 0.0. Undo puts the score back.
                _ClearScore(
                  visible: _live != null,
                  onTap: () {
                    final was = _saved;
                    setState(() => _live = null);
                    _commit(null);
                    if (was != null) widget.onCleared(was);
                  },
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// "Clear rating" beside the bar: unrated, it takes no room and the bar runs the full row.
class _ClearScore extends StatefulWidget {
  const _ClearScore({required this.visible, required this.onTap});

  final bool visible;
  final VoidCallback onTap;

  @override
  State<_ClearScore> createState() => _ClearScoreState();
}

class _ClearScoreState extends State<_ClearScore> with TickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  @override
  void didUpdateWidget(_ClearScore old) {
    super.didUpdateWidget(old);
    MediaQuery.disableAnimationsOf(context)
        ? _shown.snapTo(widget.visible ? 1 : 0)
        : _shown.animateTo(widget.visible ? 1 : 0);
  }

  @override
  void dispose() {
    _shown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _shown.animation,
    builder: (context, child) {
      final v = _shown.value.clamp(0.0, 1.0);
      if (v <= 0.001) return const SizedBox.shrink();
      return ClipRect(
        child: Align(
          alignment: AlignmentDirectional.centerStart,
          widthFactor: v,
          child: Opacity(
            opacity: v,
            child: IgnorePointer(ignoring: !widget.visible, child: child),
          ),
        ),
      );
    },
    child: Padding(
      padding: const EdgeInsetsDirectional.only(start: 8),
      child: TextButton(onPressed: widget.onTap, child: Text(DetailStrings.of(context).scrapClear)),
    ),
  );
}

// ---------------------------------------------------------------------------
// About.
// ---------------------------------------------------------------------------

class _AboutTab extends StatefulWidget {
  const _AboutTab({
    super.key,
    required this.target,
    required this.colors,
    required this.gemini,
    required this.initialEditing,
    required this.onEdit,
    required this.onNotice,
  });

  final ScrapTrackSheetTarget target;
  final ScrapbookColors colors;
  final ScrapGemini gemini;
  final AboutRef? initialEditing;
  final ValueChanged<ScrapEdit> onEdit;
  final ValueChanged<_Notice> onNotice;

  @override
  State<_AboutTab> createState() => _AboutTabState();
}

class _AboutTabState extends State<_AboutTab> {
  /// One row at a time is open; one opened from Now Playing is brought into view without the keyboard.
  late AboutRef? _editing = widget.initialEditing;
  late bool _focusEditor = widget.initialEditing == null;

  void _startEdit(AboutRef? ref) {
    if (ref == null) return;
    YoinHaptics.longPress();
    setState(() {
      _focusEditor = true;
      _editing = ref;
    });
  }

  void _remove(AboutRef ref, String? wasTitle, String wasAnswer, String? name) {
    final strings = DetailStrings.of(context);
    setState(() => _editing = null);
    widget.onEdit(ScrapAboutEdit(ref, answer: ''));
    widget.onNotice(
      _Notice(
        name == null ? strings.scrapDeleted : strings.scrapDeletedNamed(name),
        icon: YoinSymbols.delete,
        undo: ScrapAboutEdit(ref, title: wasTitle, answer: wasAnswer),
      ),
    );
  }

  void _done(AboutRef ref, String? wasTitle, String wasAnswer, String? title, String answer, String? name) {
    if (answer.trim().isEmpty) return _remove(ref, wasTitle, wasAnswer, name);
    setState(() => _editing = null);
    final titleChanged = title != null && title.trim() != (wasTitle ?? '');
    if (answer.trim() != wasAnswer || titleChanged) widget.onEdit(ScrapAboutEdit(ref, title: title, answer: answer));
  }

  void _regenerate(AboutRef ref) {
    setState(() => _editing = null);
    widget.onEdit(ScrapRegenerateEdit(ref));
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final trackId = widget.target.track.track.id;
    final about = widget.target.about;
    final facts = about?.facts ?? const <ScrapTag>[];
    final paragraphs = facts.where((f) => f.isParagraph).toList();
    final shortFacts = facts.where((f) => !f.isParagraph).toList();
    // Oldest first here, so a new answer lands just above the entry it came from.
    final asks = (about?.asks ?? const <ScrapAsk>[]).reversed.toList();
    final pending = widget.gemini.asking.where((a) => a.trackId == trackId).toList();
    final pendingKeys = {for (final a in pending) normalizeAboutKey(a.question)};
    final shownKeys = {
      for (final a in asks)
        if (!a.own) normalizeAboutKey(a.question),
    };
    // A heading the listener wrote counts as asked too: the same chip would only repeat it.
    final asked = [for (final a in about?.asks ?? const <ScrapAsk>[]) a.question, for (final a in pending) a.question];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Nothing about the track yet: the AI's About in one tap.
        if (facts.isEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: _GenerateAbout(
              generating: widget.gemini.generating.contains(trackId),
              colors: widget.colors,
              onGenerate: () => widget.onEdit(ScrapGenerateEdit(trackId)),
            ),
          ),
        for (final (i, fact) in shortFacts.indexed) ...[
          if (i > 0 && fact.ref != _editing && shortFacts[i - 1].ref != _editing)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.innerGutter),
              child: Divider(height: 1, color: colors.outlineVariant.withValues(alpha: 0.6)),
            ),
          _FactEntry(
            key: ValueKey(fact.ref ?? fact.key),
            fact: fact,
            label: strings.scrapFact(fact.key),
            colors: widget.colors,
            paragraph: false,
            editing: fact.ref != null && fact.ref == _editing,
            focusEditor: _focusEditor,
            thinking: widget.gemini.regenerating.contains(fact.ref),
            onStartEdit: () => _startEdit(fact.ref),
            onDelete: () => _remove(fact.ref!, null, fact.value, strings.scrapFact(fact.key)),
            onRegenerate: () => _regenerate(fact.ref!),
            onDone: (answer) => _done(fact.ref!, null, fact.value, null, answer, strings.scrapFact(fact.key)),
          ),
        ],
        for (final fact in paragraphs)
          Padding(
            key: ValueKey(fact.ref ?? fact.key),
            padding: const EdgeInsets.only(top: 12),
            child: _FactEntry(
              fact: fact,
              label: strings.scrapFact(fact.key),
              colors: widget.colors,
              paragraph: true,
              editing: fact.ref != null && fact.ref == _editing,
              focusEditor: _focusEditor,
              thinking: widget.gemini.regenerating.contains(fact.ref),
              onStartEdit: () => _startEdit(fact.ref),
              onDelete: () => _remove(fact.ref!, null, fact.value, strings.scrapFact(fact.key)),
              onRegenerate: () => _regenerate(fact.ref!),
              onDone: (answer) => _done(fact.ref!, null, fact.value, null, answer, strings.scrapFact(fact.key)),
            ),
          ),
        for (final ask in asks)
          _AskEntry(
            key: ValueKey(ask.ref ?? ask.question),
            ask: ask,
            colors: widget.colors,
            editing: ask.ref != null && ask.ref == _editing,
            focusEditor: _focusEditor,
            thinking:
                (!ask.own && pendingKeys.contains(normalizeAboutKey(ask.question))) ||
                widget.gemini.regenerating.contains(ask.ref),
            onStartEdit: () => _startEdit(ask.ref),
            // What Undo writes back: an own entry's heading is its question; the AI's title otherwise.
            onDelete: () {
              final wasTitle = ask.own ? ask.question : ask.title;
              _remove(ask.ref!, wasTitle, ask.answer, wasTitle ?? ask.question);
            },
            onRegenerate: () => _regenerate(ask.ref!),
            onDone: (title, answer) =>
                _done(ask.ref!, ask.own ? ask.question : ask.title, ask.answer, title, answer, null),
          ),
        for (final a in pending)
          if (!shownKeys.contains(normalizeAboutKey(a.question)))
            _PendingAsk(key: ValueKey('asking:${a.question}'), question: a.question, colors: widget.colors),
        Padding(
          padding: const EdgeInsets.only(top: 22),
          child: _NewEntry(
            colors: widget.colors,
            prompts: remainingQuickPrompts(NowPlayingStrings.of(context).quickPrompts, asked),
            onAsk: (question) => widget.onEdit(ScrapAskEdit(trackId, question)),
            onWrite: (heading, text) => widget.onEdit(ScrapWriteEdit(trackId, heading, text)),
          ),
        ),
      ],
    );
  }
}

/// The empty sheet's offer: the AI writes the track's About (when, where, who, a review) in one go.
class _GenerateAbout extends StatelessWidget {
  const _GenerateAbout({required this.generating, required this.colors, required this.onGenerate});

  final bool generating;
  final ScrapbookColors colors;
  final VoidCallback onGenerate;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.innerGutter, vertical: 12),
      child: Column(
        children: [
          Text(
            strings.scrapAboutEmpty,
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
          ),
          const SizedBox(height: 12),
          AiSpringSwap(
            childKey: generating,
            child: generating
                ? SizedBox(
                    height: 40,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        ThinkingDots(color: scheme.tertiary),
                        const SizedBox(width: 10),
                        Text(
                          strings.scrapAboutGenerating,
                          style: theme.textTheme.labelLarge?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  )
                : Center(
                    child: _EditorButton(
                      label: strings.scrapAboutGenerate,
                      icon: YoinSymbols.sparkle,
                      container: colors.review,
                      content: colors.onReview,
                      onTap: onGenerate,
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

TextStyle? _factLabelStyle(BuildContext context, Color color) =>
    Theme.of(context).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600, letterSpacing: 0.2, color: color);

/// One About fact: label and value with a pencil (a long press edits too), or its editor.
class _FactEntry extends StatelessWidget {
  const _FactEntry({
    super.key,
    required this.fact,
    required this.label,
    required this.colors,
    required this.paragraph,
    required this.editing,
    required this.focusEditor,
    required this.thinking,
    required this.onStartEdit,
    required this.onDelete,
    required this.onRegenerate,
    required this.onDone,
  });

  final ScrapTag fact;
  final String label;
  final ScrapbookColors colors;
  final bool paragraph;
  final bool editing;
  final bool focusEditor;
  final bool thinking;
  final VoidCallback onStartEdit;
  final VoidCallback onDelete;
  final VoidCallback onRegenerate;
  final ValueChanged<String> onDone;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final canEdit = fact.ref != null && !thinking;
    Widget value() => thinking
        ? Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: ThinkingDots(color: colors.ink),
          )
        : Text.rich(
            markdownBoldSpan(fact.value),
            style: theme.textTheme.bodyLarge?.copyWith(height: paragraph ? 1.6 : 1.5, color: scheme.onSurface),
          );
    final Widget body = editing
        ? DecoratedBox(
            decoration: ShapeDecoration(
              color: scheme.surfaceContainerHigh,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.cardCorner)),
            ),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(label, style: _factLabelStyle(context, colors.ink)),
                  _AboutEditor(
                    title: null,
                    text: fact.value,
                    canRegenerate: true,
                    autoFocus: focusEditor,
                    fieldColor: scheme.surface,
                    onDelete: onDelete,
                    onRegenerate: onRegenerate,
                    onDone: (_, answer) => onDone(answer),
                  ),
                ],
              ),
            ),
          )
        : Semantics(
            onLongPressHint: DetailStrings.of(context).scrapCdEdit,
            child: InkWell(
              customBorder: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              onTap: canEdit ? () {} : null,
              onLongPress: canEdit ? onStartEdit : null,
              onSecondaryTap: canEdit ? onStartEdit : null,
              child: Padding(
                padding: const EdgeInsetsDirectional.only(start: ScrapSheetMetrics.innerGutter, top: 8, bottom: 8),
                child: paragraph
                    ? Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(label, style: _factLabelStyle(context, colors.ink)),
                                Padding(padding: const EdgeInsets.only(top: 4), child: value()),
                              ],
                            ),
                          ),
                          _EditButton(visible: canEdit, onTap: onStartEdit),
                        ],
                      )
                    : Row(
                        children: [
                          SizedBox(
                            width: ScrapSheetMetrics.factLabelWidth,
                            child: Text(label, style: _factLabelStyle(context, colors.ink)),
                          ),
                          const SizedBox(width: 10),
                          Expanded(child: value()),
                          _EditButton(visible: canEdit, onTap: onStartEdit),
                        ],
                      ),
              ),
            ),
          );
    return AiSpringSwap(childKey: editing, child: body);
  }
}

/// A question in full (the listener's words, the AI's title and answer), or one of the listener's own entries.
class _AskEntry extends StatelessWidget {
  const _AskEntry({
    super.key,
    required this.ask,
    required this.colors,
    required this.editing,
    required this.focusEditor,
    required this.thinking,
    required this.onStartEdit,
    required this.onDelete,
    required this.onRegenerate,
    required this.onDone,
  });

  final ScrapAsk ask;
  final ScrapbookColors colors;
  final bool editing;
  final bool focusEditor;
  final bool thinking;
  final VoidCallback onStartEdit;
  final VoidCallback onDelete;
  final VoidCallback onRegenerate;
  final void Function(String? title, String answer) onDone;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final container = ask.own ? colors.review : colors.ask;
    final content = ask.own ? colors.onReview : colors.onAsk;
    final canEdit = ask.ref != null && !thinking;
    final userText = theme.textTheme.bodyLarge?.copyWith(
      fontSize: 13.5,
      height: 1.5,
      color: content.withValues(alpha: 0.75),
    );
    final heading = ask.own ? ask.question : ask.title;
    final Widget body = editing
        ? Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (!ask.own) Text(strings.scrapAsked(ask.question), style: userText),
                _AboutEditor(
                  // An own entry's heading is its "question"; the AI's title on an asked one.
                  title: ask.own ? ask.question : (ask.title ?? ''),
                  text: ask.answer,
                  canRegenerate: !ask.own,
                  autoFocus: focusEditor,
                  fieldColor: scheme.surface,
                  onDelete: onDelete,
                  onRegenerate: onRegenerate,
                  onDone: onDone,
                ),
              ],
            ),
          )
        : Semantics(
            onLongPressHint: strings.scrapCdEdit,
            child: InkWell(
              onTap: canEdit ? () {} : null,
              onLongPress: canEdit ? onStartEdit : null,
              onSecondaryTap: canEdit ? onStartEdit : null,
              child: Stack(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        if (!ask.own)
                          Padding(
                            padding: const EdgeInsetsDirectional.only(end: 32),
                            child: Text(ask.question, style: userText),
                          ),
                        if (heading != null)
                          Padding(
                            padding: EdgeInsetsDirectional.only(top: ask.own ? 0 : 6, end: ask.own ? 32 : 0),
                            child: Text(
                              heading,
                              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600, color: content),
                            ),
                          ),
                        if (thinking)
                          Padding(
                            padding: const EdgeInsets.only(top: 10, bottom: 4),
                            child: Align(
                              alignment: AlignmentDirectional.centerStart,
                              child: ThinkingDots(color: content),
                            ),
                          )
                        else
                          Padding(
                            padding: const EdgeInsets.only(top: 4),
                            child: Text.rich(
                              markdownBoldSpan(ask.answer),
                              style: theme.textTheme.bodyLarge?.copyWith(height: 1.55, color: content),
                            ),
                          ),
                        Padding(
                          padding: const EdgeInsets.only(top: 8),
                          child: _Byline(own: ask.own, color: content),
                        ),
                      ],
                    ),
                  ),
                  PositionedDirectional(
                    top: 4,
                    end: 4,
                    child: _EditButton(visible: canEdit, onTap: onStartEdit, tint: content),
                  ),
                ],
              ),
            ),
          );
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: Material(
        color: container,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.cardCorner)),
        clipBehavior: Clip.antiAlias,
        child: AiSpringSwap(childKey: editing, child: body),
      ),
    );
  }
}

/// A question asked from the sheet that the AI has not answered yet.
class _PendingAsk extends StatelessWidget {
  const _PendingAsk({super.key, required this.question, required this.colors});

  final String question;
  final ScrapbookColors colors;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 14),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.ask,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.cardCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                question,
                style: theme.textTheme.bodyLarge?.copyWith(
                  fontSize: 13.5,
                  height: 1.5,
                  color: colors.onAsk.withValues(alpha: 0.75),
                ),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 12, bottom: 4),
                child: ThinkingDots(color: colors.onAsk),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: _Byline(own: false, color: colors.onAsk),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Byline extends StatelessWidget {
  const _Byline({required this.own, required this.color});

  final bool own;
  final Color color;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    return Row(
      children: [
        if (!own) ...[Icon(YoinSymbols.sparkle, size: 14, color: color), const SizedBox(width: 4)],
        Text(
          own ? strings.scrapByYou : strings.scrapByGemini,
          style: Theme.of(
            context,
          ).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w600, letterSpacing: 0.2, color: color),
        ),
      ],
    );
  }
}

/// Lays [child] [amount] wider on both sides than its parent allows, keeping its own place.
class _Bleed extends StatelessWidget {
  const _Bleed({required this.amount, required this.child});

  final double amount;
  final Widget child;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      if (!constraints.hasBoundedWidth) return child;
      final width = constraints.maxWidth + amount * 2;
      return SizedBox(
        width: constraints.maxWidth,
        child: OverflowBox(minWidth: width, maxWidth: width, alignment: Alignment.center, child: child),
      );
    },
  );
}

/// Keeps its slot while hidden, so the row beside it never jumps when an
/// editor opens; it fades in and out on the effects spring.
class _EditButton extends StatefulWidget {
  const _EditButton({required this.visible, required this.onTap, this.tint});

  final bool visible;
  final VoidCallback onTap;
  final Color? tint;

  @override
  State<_EditButton> createState() => _EditButtonState();
}

class _EditButtonState extends State<_EditButton> with TickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_EditButton old) {
    super.didUpdateWidget(old);
    MediaQuery.disableAnimationsOf(context)
        ? _alpha.snapTo(widget.visible ? 1 : 0)
        : _alpha.animateTo(widget.visible ? 1 : 0);
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: ScrapSheetMetrics.editButton,
    child: IgnorePointer(
      ignoring: !widget.visible,
      child: FadeTransition(
        opacity: _alpha.animation.drive(Tween(begin: 0.0, end: 1.0)),
        child: IconButton(
          tooltip: DetailStrings.of(context).scrapCdEdit,
          onPressed: widget.onTap,
          icon: Icon(
            YoinSymbols.edit,
            size: ScrapSheetMetrics.editGlyph,
            color: widget.tint ?? Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ),
    ),
  );
}

/// The editor for one row: [title] (null: no title field) and the text.
/// Delete removes the row; Regenerate asks the AI again (both undoable);
/// Done keeps what is in the fields, and an emptied text is a delete.
class _AboutEditor extends StatefulWidget {
  const _AboutEditor({
    required this.title,
    required this.text,
    required this.canRegenerate,
    required this.autoFocus,
    required this.fieldColor,
    required this.onDelete,
    required this.onRegenerate,
    required this.onDone,
  });

  final String? title;
  final String text;
  final bool canRegenerate;
  final bool autoFocus;
  final Color fieldColor;
  final VoidCallback onDelete;
  final VoidCallback onRegenerate;
  final void Function(String? title, String answer) onDone;

  @override
  State<_AboutEditor> createState() => _AboutEditorState();
}

class _AboutEditorState extends State<_AboutEditor> {
  late final TextEditingController _title = TextEditingController(text: widget.title ?? '');
  late final TextEditingController _text = TextEditingController(text: widget.text)
    ..selection = TextSelection.collapsed(offset: widget.text.length);
  final FocusNode _focus = FocusNode();
  final GlobalKey _actions = GlobalKey();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (widget.autoFocus) {
        _focus.requestFocus();
      } else {
        _bringActionsIntoView();
      }
    });
  }

  /// Delete / Regenerate / Done stay in view (above the keyboard) while it rises.
  void _bringActionsIntoView() {
    final ctx = _actions.currentContext;
    if (ctx != null) Scrollable.ensureVisible(ctx, alignmentPolicy: ScrollPositionAlignmentPolicy.keepVisibleAtEnd);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.viewInsetsOf(context).bottom > 0) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _bringActionsIntoView();
      });
    }
  }

  @override
  void dispose() {
    _title.dispose();
    _text.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (widget.title != null) ...[
            _EditField(
              controller: _title,
              hint: strings.scrapEditTitleHint,
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
              color: widget.fieldColor,
              singleLine: true,
            ),
            const SizedBox(height: 8),
          ],
          _EditField(
            controller: _text,
            focusNode: _focus,
            hint: strings.scrapEditTextHint,
            style: theme.textTheme.bodyLarge?.copyWith(height: 1.5),
            color: widget.fieldColor,
          ),
          const SizedBox(height: 8),
          Row(
            key: _actions,
            children: [
              _EditorButton(
                label: strings.scrapDelete,
                icon: YoinSymbols.delete,
                container: widget.fieldColor,
                content: scheme.error,
                onTap: widget.onDelete,
              ),
              if (widget.canRegenerate) ...[
                const SizedBox(width: 8),
                Flexible(
                  child: _EditorButton(
                    label: strings.scrapRegenerate,
                    icon: YoinSymbols.sparkle,
                    container: widget.fieldColor,
                    content: scheme.tertiary,
                    onTap: widget.onRegenerate,
                  ),
                ),
              ],
              const Spacer(),
              FilledButton(
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(
                    horizontal: ScrapSheetMetrics.editorPadH,
                    vertical: ScrapSheetMetrics.editorPadV,
                  ),
                ),
                onPressed: () => widget.onDone(widget.title != null ? _title.text : null, _text.text),
                child: Text(strings.scrapDone),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _EditorButton extends StatelessWidget {
  const _EditorButton({
    required this.label,
    required this.icon,
    required this.container,
    required this.content,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final Color container;
  final Color content;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => FilledButton.tonal(
    style: FilledButton.styleFrom(
      backgroundColor: container,
      foregroundColor: content,
      padding: const EdgeInsets.symmetric(
        horizontal: ScrapSheetMetrics.editorPadH,
        vertical: ScrapSheetMetrics.editorPadV,
      ),
    ),
    onPressed: onTap,
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 16),
        const SizedBox(width: 6),
        Flexible(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis)),
      ],
    ),
  );
}

class _EditField extends StatelessWidget {
  const _EditField({
    required this.controller,
    required this.hint,
    required this.style,
    required this.color,
    this.focusNode,
    this.singleLine = false,
    this.minHeight = 0,
  });

  final TextEditingController controller;
  final FocusNode? focusNode;
  final String hint;
  final TextStyle? style;
  final Color color;
  final bool singleLine;
  final double minHeight;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final field = TextField(
      controller: controller,
      focusNode: focusNode,
      maxLines: singleLine ? 1 : null,
      textCapitalization: TextCapitalization.sentences,
      style: style?.copyWith(color: scheme.onSurface),
      cursorColor: scheme.primary,
      decoration: InputDecoration.collapsed(
        hintText: hint,
        hintStyle: style?.copyWith(color: scheme.onSurfaceVariant),
      ),
    );
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: color,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.fieldCorner)),
      ),
      child: ConstrainedBox(
        constraints: BoxConstraints(minHeight: minHeight),
        child: Padding(padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12), child: field),
      ),
    );
  }
}

/// A new entry: its title first, then Ask Gemini, or Write to type it
/// yourself (the button becomes Save). While the empty title has the
/// keyboard, the quick questions not asked yet sit under it; one fills the title.
class _NewEntry extends StatefulWidget {
  const _NewEntry({required this.colors, required this.prompts, required this.onAsk, required this.onWrite});

  final ScrapbookColors colors;
  final List<String> prompts;
  final ValueChanged<String> onAsk;
  final void Function(String heading, String text) onWrite;

  @override
  State<_NewEntry> createState() => _NewEntryState();
}

class _NewEntryState extends State<_NewEntry> {
  final TextEditingController _title = TextEditingController();
  final TextEditingController _body = TextEditingController();
  final FocusNode _titleFocus = FocusNode();
  final FocusNode _bodyFocus = FocusNode();
  bool _typing = false;
  bool _titleFocused = false;

  @override
  void initState() {
    super.initState();
    _title.addListener(() => setState(() {}));
    _body.addListener(() => setState(() {}));
    _titleFocus.addListener(() => setState(() => _titleFocused = _titleFocus.hasFocus));
  }

  @override
  void dispose() {
    _title.dispose();
    _body.dispose();
    _titleFocus.dispose();
    _bodyFocus.dispose();
    super.dispose();
  }

  void _reset() {
    _title.clear();
    _body.clear();
    setState(() => _typing = false);
    FocusScope.of(context).unfocus();
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final c = widget.colors;
    final hasTitle = _title.text.trim().isNotEmpty;
    final hasBody = _body.text.trim().isNotEmpty;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: scheme.surfaceContainerHigh,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.newEntryCorner)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(ScrapSheetMetrics.newEntryPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _EditField(
              controller: _title,
              focusNode: _titleFocus,
              hint: strings.scrapNewTitleHint,
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
              color: scheme.surface,
              singleLine: true,
            ),
            SpringReveal(
              visible: _titleFocused && !hasTitle && widget.prompts.isNotEmpty,
              child: Padding(
                padding: const EdgeInsets.only(top: 10),
                // The pills scroll out to the card's own edges (fading there), not to its padding.
                child: _Bleed(
                  amount: ScrapSheetMetrics.newEntryPadding,
                  child: QuickPrompts(
                    prompts: widget.prompts,
                    container: c.ask,
                    content: c.onAsk,
                    fadeEdges: true,
                    padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.newEntryPadding),
                    onPick: (prompt) {
                      _title.value = TextEditingValue(
                        text: prompt,
                        selection: TextSelection.collapsed(offset: prompt.length),
                      );
                      _titleFocus.requestFocus();
                    },
                  ),
                ),
              ),
            ),
            SpringReveal(
              visible: _typing,
              child: Padding(
                padding: const EdgeInsets.only(top: 10),
                child: _EditField(
                  controller: _body,
                  focusNode: _bodyFocus,
                  hint: strings.scrapNewTextHint,
                  style: theme.textTheme.bodyLarge?.copyWith(height: 1.5),
                  color: scheme.surface,
                  minHeight: ScrapSheetMetrics.bodyMinHeight,
                ),
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minHeight: ScrapSheetMetrics.buttonMinHeight),
                    child: FilledButton.tonal(
                      style: FilledButton.styleFrom(backgroundColor: c.ask, foregroundColor: c.onAsk),
                      onPressed: hasTitle
                          ? () {
                              widget.onAsk(_title.text.trim());
                              _reset();
                            }
                          : null,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(YoinSymbols.sparkle, size: 18),
                          const SizedBox(width: 6),
                          Flexible(child: Text(strings.scrapAskGemini, maxLines: 1, overflow: TextOverflow.ellipsis)),
                        ],
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minHeight: ScrapSheetMetrics.buttonMinHeight),
                    child: FilledButton(
                      style: FilledButton.styleFrom(backgroundColor: c.ink, foregroundColor: c.onInk),
                      onPressed: hasTitle && (!_typing || hasBody)
                          ? () {
                              if (!_typing) {
                                setState(() => _typing = true);
                                WidgetsBinding.instance.addPostFrameCallback((_) {
                                  if (mounted) _bodyFocus.requestFocus();
                                });
                              } else {
                                widget.onWrite(_title.text.trim(), _body.text.trim());
                                _reset();
                              }
                            }
                          : null,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (!_typing) ...[const Icon(YoinSymbols.edit, size: 18), const SizedBox(width: 6)],
                          Flexible(
                            child: Text(
                              _typing ? strings.scrapSave : strings.scrapWrite,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Note.
// ---------------------------------------------------------------------------

class _NotesTab extends StatefulWidget {
  const _NotesTab({
    super.key,
    required this.target,
    required this.lyrics,
    required this.colors,
    required this.onEdit,
    required this.onNotice,
    required this.onPlayFrom,
  });

  final ScrapTrackSheetTarget target;
  final ScrapLyrics lyrics;
  final ScrapbookColors colors;
  final ValueChanged<ScrapEdit> onEdit;
  final ValueChanged<_Notice> onNotice;
  final ValueChanged<int?> onPlayFrom;

  @override
  State<_NotesTab> createState() => _NotesTabState();
}

enum _LineAction { newNote, play }

enum _NoteAction { edit, play }

class _NotesTabState extends State<_NotesTab> {
  bool _expanded = false;
  String? _editingNote;
  ScrapDraftRow? _draft;
  String? _picked;

  void _deleteNote(String id) {
    setState(() => _editingNote = null);
    widget.onEdit(ScrapNoteTextEdit(id, ''));
    widget.onNotice(
      _Notice(DetailStrings.of(context).scrapNoteDeleted, icon: YoinSymbols.delete, undo: ScrapRestoreNoteEdit(id)),
    );
  }

  Future<void> _lineMenu(BuildContext rowContext, ScrapLyricRow row) async {
    final strings = DetailStrings.of(context);
    setState(() => _picked = row.key);
    final action = await showYoinDropdownMenu<_LineAction>(
      context: rowContext,
      anchor: globalRectOf(rowContext),
      items: [
        YoinMenuItem(value: _LineAction.newNote, label: strings.scrapNewNote, icon: YoinSymbols.add),
        YoinMenuItem(
          value: _LineAction.play,
          label: strings.scrapPlayFrom(formatNotePosition(row.line.startMs)),
          icon: YoinSymbols.playArrow,
        ),
      ],
    );
    if (!mounted) return;
    setState(() {
      _picked = null;
      if (action == _LineAction.newNote) {
        _editingNote = null;
        _draft = ScrapDraftRow(row.line.startMs);
      }
    });
    if (action == _LineAction.play) widget.onPlayFrom(row.line.startMs);
  }

  Future<void> _noteMenu(BuildContext rowContext, SongNote note) async {
    final strings = DetailStrings.of(context);
    final at = note.positionMs == null ? null : formatNotePosition(note.positionMs!);
    setState(() => _picked = 'note:${note.id}');
    final action = await showYoinDropdownMenu<_NoteAction>(
      context: rowContext,
      anchor: globalRectOf(rowContext),
      items: [
        YoinMenuItem(value: _NoteAction.edit, label: strings.scrapEditNote, icon: YoinSymbols.edit),
        YoinMenuItem(
          value: _NoteAction.play,
          label: at == null ? strings.scrapCdPlay : strings.scrapPlayFrom(at),
          icon: YoinSymbols.playArrow,
        ),
      ],
    );
    if (!mounted) return;
    setState(() {
      _picked = null;
      if (action == _NoteAction.edit) {
        _draft = null;
        _editingNote = note.id;
      }
    });
    if (action == _NoteAction.play) widget.onPlayFrom(note.positionMs);
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final notes = widget.target.notes;
    final lyrics = widget.lyrics;
    final timed = lyrics.lines.isNotEmpty;
    final rows = scrapNoteRows(lyrics.lines, notes, expanded: _expanded, draft: _draft);
    Widget hint(String text, EdgeInsets padding) => Padding(
      padding: padding.add(const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.innerGutter)),
      child: Text(
        text,
        textAlign: TextAlign.center,
        style: theme.textTheme.bodySmall?.copyWith(color: scheme.outline),
      ),
    );
    final tonal = FilledButton.styleFrom(
      backgroundColor: scheme.surfaceContainerHigh,
      foregroundColor: scheme.onSurface,
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (timed)
          Center(
            child: FilledButton.tonal(
              style: tonal,
              onPressed: () => setState(() {
                _draft = null;
                _picked = null;
                _expanded = !_expanded;
              }),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(_expanded ? strings.scrapCollapseLyrics : strings.scrapExpandLyrics),
                  const SizedBox(width: 6),
                  Icon(_expanded ? YoinSymbols.chevronUp : YoinSymbols.chevronDown, size: 18),
                ],
              ),
            ),
          )
        else if (lyrics.loading && notes.isEmpty)
          hint(strings.scrapLyricsLoading, const EdgeInsets.symmetric(vertical: 8)),
        for (final row in rows)
          KeyedSubtree(
            key: ValueKey(row.key),
            child: switch (row) {
              ScrapLyricRow() => Builder(
                builder: (rowContext) =>
                    _LyricLine(row: row, highlighted: _picked == row.key, onTap: () => _lineMenu(rowContext, row)),
              ),
              ScrapNoteCardRow(:final note) when _editingNote == note.id => _NoteEditor(
                positionMs: note.positionMs,
                text: note.content,
                colors: widget.colors,
                canDelete: true,
                onDelete: () => _deleteNote(note.id),
                onDone: (text) {
                  if (text.trim().isEmpty) return _deleteNote(note.id);
                  setState(() => _editingNote = null);
                  // Words left as they were write nothing: the stored text (its markup, an
                  // Android to-do's trailing space) stays exactly as it is.
                  if (text.trim() != note.content.trim()) widget.onEdit(ScrapNoteTextEdit(note.id, text));
                },
              ),
              ScrapNoteCardRow(:final note) => Builder(
                builder: (rowContext) => _NoteCard(
                  note: note,
                  colors: widget.colors,
                  highlighted: _picked == row.key,
                  onTap: () => _noteMenu(rowContext, note),
                ),
              ),
              ScrapDraftRow(:final positionMs) => _NoteEditor(
                positionMs: positionMs,
                text: '',
                colors: widget.colors,
                canDelete: false,
                onDelete: () => setState(() => _draft = null),
                onDone: (text) {
                  setState(() => _draft = null);
                  if (text.trim().isNotEmpty) {
                    widget.onEdit(ScrapNewNoteEdit(widget.target.track.track.id, positionMs, text));
                  }
                },
              ),
              ScrapGapRow() => Padding(
                padding: const EdgeInsets.symmetric(vertical: 10),
                child: Text(
                  '·  ·  ·',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.labelMedium?.copyWith(color: scheme.outline),
                ),
              ),
            },
          ),
        // Only the label: no how-to line (the same as Now Playing's Note page).
        if (notes.isEmpty && _draft == null && !lyrics.loading)
          hint(strings.scrapNotesEmpty, const EdgeInsets.only(top: 12)),
        // Without timed lyrics there is no line to start from: a note without a moment.
        if (!timed && !lyrics.loading && _draft == null && _editingNote == null)
          Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Center(
              child: FilledButton.tonal(
                style: tonal,
                onPressed: () => setState(() => _draft = const ScrapDraftRow(null)),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(YoinSymbols.add, size: 18),
                    const SizedBox(width: 6),
                    Text(strings.scrapNewNote),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _LyricLine extends StatelessWidget {
  const _LyricLine({required this.row, required this.highlighted, required this.onTap});

  final ScrapLyricRow row;
  final bool highlighted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Material(
      color: highlighted ? scheme.surfaceContainerHigh : Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: ScrapSheetMetrics.innerGutter),
          child: Text(
            row.line.text,
            style: theme.textTheme.bodyMedium?.copyWith(
              fontSize: 13,
              height: 1.75,
              fontWeight: row.noted ? FontWeight.w500 : FontWeight.w400,
              color: row.noted ? scheme.onSurfaceVariant : scheme.outline,
            ),
          ),
        ),
      ),
    );
  }
}

/// The journal rail at a note's start (the notes' mark alone).
class _Rail extends CustomPainter {
  _Rail(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const inset = 14.0;
    if (size.height <= inset * 2) return;
    canvas.drawRRect(
      RRect.fromRectAndRadius(Rect.fromLTWH(11, inset, 3, size.height - inset * 2), const Radius.circular(1.5)),
      Paint()..color = color,
    );
  }

  @override
  bool shouldRepaint(_Rail old) => old.color != color;
}

class _NoteStamp extends StatelessWidget {
  const _NoteStamp(this.at, this.colors);
  final String at;
  final ScrapbookColors colors;

  @override
  Widget build(BuildContext context) => Text(
    at,
    style: Theme.of(context).textTheme.labelMedium?.copyWith(
      fontFeatures: const [FontFeature.tabularFigures()],
      fontWeight: FontWeight.w600,
      fontSize: 12,
      letterSpacing: 0.3,
      color: colors.ink,
    ),
  );
}

class _NoteCard extends StatelessWidget {
  const _NoteCard({required this.note, required this.colors, required this.highlighted, required this.onTap});

  final SongNote note;
  final ScrapbookColors colors;
  final bool highlighted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final at = note.positionMs == null ? null : formatNotePosition(note.positionMs!);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Material(
        color: highlighted ? colors.note.withValues(alpha: 0.8) : colors.note,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.noteCorner)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: CustomPaint(
            painter: _Rail(colors.ink),
            child: Padding(
              padding: const EdgeInsetsDirectional.fromSTEB(24, 10, 14, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (at != null) _NoteStamp(at, colors),
                  Padding(
                    padding: const EdgeInsets.only(top: 2),
                    child: NoteMarkupText(
                      note.content,
                      style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                        fontSize: 15,
                        height: 1.5,
                        letterSpacing: 0,
                        color: colors.onNote,
                      ),
                      mentionColor: colors.ink,
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
}

/// A note being written or changed in place: an emptied existing note is deleted (with Undo).
class _NoteEditor extends StatefulWidget {
  const _NoteEditor({
    required this.positionMs,
    required this.text,
    required this.colors,
    required this.canDelete,
    required this.onDelete,
    required this.onDone,
  });

  final int? positionMs;
  final String text;
  final ScrapbookColors colors;
  final bool canDelete;
  final VoidCallback onDelete;
  final ValueChanged<String> onDone;

  @override
  State<_NoteEditor> createState() => _NoteEditorState();
}

class _NoteEditorState extends State<_NoteEditor> {
  late final TextEditingController _field = TextEditingController(text: widget.text)
    ..selection = TextSelection.collapsed(offset: widget.text.length);
  final FocusNode _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _focus.requestFocus();
    });
  }

  @override
  void dispose() {
    _field.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final c = widget.colors;
    final at = widget.positionMs == null ? null : formatNotePosition(widget.positionMs!);
    final userText = theme.textTheme.bodyLarge?.copyWith(fontSize: 15, height: 1.5, letterSpacing: 0);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: c.note,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ScrapSheetMetrics.noteCorner)),
        ),
        child: CustomPaint(
          painter: _Rail(c.ink),
          child: Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(24, 10, 14, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (at != null) _NoteStamp(at, c),
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: TextField(
                    controller: _field,
                    focusNode: _focus,
                    maxLines: null,
                    textCapitalization: TextCapitalization.sentences,
                    style: userText?.copyWith(color: c.onNote),
                    cursorColor: c.ink,
                    decoration: InputDecoration(
                      isCollapsed: true,
                      hintText: at == null ? strings.scrapNoteHintPlain : strings.scrapNoteHint(at),
                      hintStyle: userText?.copyWith(color: c.onNote.withValues(alpha: 0.5)),
                      contentPadding: const EdgeInsets.only(bottom: 4),
                      enabledBorder: UnderlineInputBorder(
                        borderSide: BorderSide(color: c.ink.withValues(alpha: 0.6), width: 2),
                      ),
                      focusedBorder: UnderlineInputBorder(
                        borderSide: BorderSide(color: c.ink.withValues(alpha: 0.6), width: 2),
                      ),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(top: 10),
                  child: Row(
                    children: [
                      if (widget.canDelete)
                        _EditorButton(
                          label: strings.scrapDelete,
                          icon: YoinSymbols.delete,
                          container: scheme.surface.withValues(alpha: 0.7),
                          content: scheme.error,
                          onTap: widget.onDelete,
                        )
                      else
                        TextButton(onPressed: widget.onDelete, child: Text(strings.scrapCancel)),
                      const Spacer(),
                      FilledButton(
                        style: FilledButton.styleFrom(
                          backgroundColor: c.ink,
                          foregroundColor: c.onInk,
                          padding: const EdgeInsets.symmetric(
                            horizontal: ScrapSheetMetrics.editorPadH,
                            vertical: ScrapSheetMetrics.editorPadV,
                          ),
                        ),
                        onPressed: () => widget.onDone(_field.text),
                        child: Text(strings.scrapDone),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
