import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'haptics.dart';
import 'lyric_halftone.dart';
import 'lyric_tools.dart';
import 'lyrics_logic.dart';
import 'lyrics_selection.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';
import 'overlay_controller.dart';
import 'playhead_clock.dart';
import 'transport_signal.dart';
import 'up_next_timing.dart';

/// Expanded list geometry (LyricsFullscreenPane.kt; not in tokens.dart yet).
abstract final class LyricsPaneMetrics {
  static const double rowInset = 12;
  static const double rowPadV = 6;
  static const double spacing = 4;
  static const double contentPad = 48;
  static const double tail = 96;
  static const double fade = 64;
  static const double fadeMaxShare = 0.2;
  static const double focusFraction = 0.5;
  static const double screenPull = 0.4;
  static const double windowFadeTop = 16;

  /// The window's (and focus's) bottom fade ends this far inside the
  /// window, so no line is ever cut by the edge the transport sits on.
  static const double windowFadeClear = 4;

  /// The title card's artist under its title (LyricRow secondary).
  static const double secondaryGap = 4;

  /// Select mode's label: its padding above and below (the band it owns).
  static const double captionPadV = 8;

  /// A picked block's outer corners (SelectedLineCorner) and where two picks meet (SelectedRunInnerCorner).
  static const double pickCorner = 16;
  static const double pickInnerCorner = 4;

  /// Unpicked lines while the pick has room (SelectModeLineAlpha); lines a tap can't add (OutsideLineAlpha).
  static const double selectModeLineAlpha = 0.7;
  static const double outsideLineAlpha = 0.42;
}

/// The largest scroll offset that still leaves the song's last row ending
/// [endTail] above the window's bottom, where the full list rests when nothing
/// follows it (Android lyricsEndClampScrollOffsetPx). [endRowBottom]: the last
/// row's bottom in the list's content, under [contentPadTop] of padding.
double lyricsEndClampOffset({
  required double viewport,
  required double contentPadTop,
  required double endRowBottom,
  required double endTail,
}) => contentPadTop + endRowBottom + endTail - viewport;

/// The lyric list's edge mask (LyricsDisplay `edgeFade(top, bottom)`) for a
/// box [height] tall: opaque between the fades, transparent above [clearTop]
/// (select mode's label band) and [clearBottom] above the bottom edge. The bottom fade eases out (alpha
/// (1 − t)²), so a line straddling it dissolves instead of reading as cut.
({List<Color> colors, List<double> stops}) lyricsEdgeMask(
  double height, {
  required double fadeTop,
  required double fadeBottom,
  double clearBottom = 0,
  bool easeBottom = false,
  double clearTop = 0,
}) {
  const clear = Color(0x00000000);
  const opaque = Color(0xFF000000);
  if (height <= 0) return (colors: const [opaque, opaque], stops: const [0.0, 1.0]);
  final double band = (clearTop / height).clamp(0.0, 0.5);
  final double top = math.min(((clearTop + fadeTop) / height).clamp(band, 0.5 + band), 1.0);
  final double end = (1 - clearBottom / height).clamp(0.5, 1.0);
  final double fadeIn = math.min(top, end);
  final double start = (end - fadeBottom / height).clamp(fadeIn, end);
  final colors = <Color>[clear, if (band > 0) clear, opaque, opaque];
  final stops = <double>[0, if (band > 0) band, fadeIn, start];
  if (easeBottom) {
    const steps = 4;
    for (var i = 1; i < steps; i++) {
      final t = i / steps;
      colors.add(opaque.withValues(alpha: (1 - t) * (1 - t)));
      stops.add(start + (end - start) * t);
    }
  }
  colors.add(clear);
  stops.add(end);
  if (end < 1) {
    colors.add(clear);
    stops.add(1);
  }
  return (colors: colors, stops: stops);
}

/// The focus list's scroll offset: the current line pinned under the lines
/// just sung ([aboveTop] is the first of them), unless that would push the
/// current line into the bottom fade — then the sung lines give way, so the
/// whole current line always clears the fade and the transport under it
/// (LyricsDisplay: `roomAbove = shown − listTopPad − current`).
double focusSeatOffset({
  required double currentTop,
  required double currentHeight,
  required double aboveTop,
  required double viewport,
}) {
  const pad = NowPlayingTokens.lyricsListPadding;
  final bottomBand = NowPlayingTokens.lyricsFadeBottom + LyricsPaneMetrics.windowFadeClear;
  final roomAbove = math.max(0.0, viewport - pad - currentHeight - bottomBand);
  final wanted = math.max(0.0, currentTop - aboveTop);
  return currentTop - pad - math.min(wanted, roomAbove);
}

/// Which list: the Compact window (read-only, anchored at 22 %), the
/// Expanded stage's full list (tap a line to seek, scroll frees it), or the
/// FOCUS list (printed larger, the current line pinned to the top with the
/// line just sung above it, fading).
enum LyricsListMode { window, full, focus }

/// Lyrics for the stage: loading indicator, "No lyrics available", or the
/// list. The active line follows the shared clock; the widget rebuilds only
/// when the active line (or the outro's quantized step) changes.
class LyricsView extends StatelessWidget {
  const LyricsView({
    super.key,
    required this.mode,
    required this.lyrics,
    required this.songKey,
    required this.clock,
    required this.onSeek,
    this.title,
    this.artist,
    this.autoFollow,
    this.windowTop = 0,
    this.upNext,
    this.durationMs,
    this.horizontalInset = 0,
    this.linesAbove = NowPlayingFocusTokens.linesAbove,
    this.showTranslation = false,
  });

  /// Full list: each line's translation shows under it (Android LyricsFullscreenPane).
  final bool showTranslation;

  final LyricsListMode mode;
  final LyricsState lyrics;
  final String songKey;
  final PlayheadClock clock;
  final ValueChanged<int> onSeek;
  final String? title;
  final String? artist;

  /// Full list only: auto-follow on/off, shared with the Recenter key.
  final ValueNotifier<bool>? autoFollow;

  /// Full list only: the viewport's top in screen px (the seat pulls toward 40 % of the screen).
  final double windowTop;

  /// Full and focus lists: the next song, staged in the outro.
  final UpNextLyrics? upNext;

  /// The song's length (the outro's clock); null: no outro.
  final int? durationMs;

  /// Extra side inset (short-window focus lines the lyrics up with the cover).
  final double horizontalInset;

  /// Focus list: whole lines kept above the current one (landscape keeps two).
  final int linesAbove;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final full = mode == LyricsListMode.full;
    final side = NowPlayingTokens.columnPadding + horizontalInset;
    return switch (lyrics) {
      LyricsLoading() => Align(
        alignment: full ? Alignment.center : AlignmentDirectional.centerStart,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: full ? 0 : side),
          child: SizedBox.square(
            dimension: full ? 36 : 32,
            child: CircularProgressIndicator(color: colors.primary, strokeWidth: 3),
          ),
        ),
      ),
      LyricsReady(:final sheet) when sheet.isEmpty => Align(
        alignment: full ? Alignment.center : AlignmentDirectional.topStart,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: full ? 0 : side),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: Align(
              alignment: full ? Alignment.center : AlignmentDirectional.centerStart,
              widthFactor: 1,
              heightFactor: 1,
              child: Text(
                strings.lyricsEmpty,
                style: (full ? Theme.of(context).textTheme.titleMedium : Theme.of(context).textTheme.bodyMedium)!
                    .copyWith(color: colors.onSurfaceVariant),
              ),
            ),
          ),
        ),
      ),
      LyricsReady(:final sheet) => _LyricsList(
        key: ValueKey('$songKey/$mode'),
        mode: mode,
        songKey: songKey,
        sheet: sheet,
        clock: clock,
        onSeek: onSeek,
        title: title,
        artist: artist,
        autoFollow: autoFollow,
        windowTop: windowTop,
        upNext: mode == LyricsListMode.window ? null : upNext,
        durationMs: durationMs,
        horizontalInset: horizontalInset,
        linesAbove: linesAbove,
        showTranslation: showTranslation && mode == LyricsListMode.full,
      ),
    };
  }
}

class _LyricsList extends StatefulWidget {
  const _LyricsList({
    super.key,
    required this.mode,
    required this.songKey,
    required this.sheet,
    required this.clock,
    required this.onSeek,
    required this.title,
    required this.artist,
    required this.autoFollow,
    required this.windowTop,
    required this.upNext,
    required this.durationMs,
    required this.horizontalInset,
    required this.linesAbove,
    required this.showTranslation,
  });

  final bool showTranslation;
  final LyricsListMode mode;
  final String songKey;
  final LyricSheet sheet;
  final PlayheadClock clock;
  final ValueChanged<int> onSeek;
  final String? title;
  final String? artist;
  final ValueNotifier<bool>? autoFollow;
  final double windowTop;
  final UpNextLyrics? upNext;
  final int? durationMs;
  final double horizontalInset;
  final int linesAbove;

  @override
  State<_LyricsList> createState() => _LyricsListState();
}

class _LyricsListState extends State<_LyricsList> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  late final AnimationController _glide = AnimationController.unbounded(vsync: this)..addListener(_onGlide);

  /// Entrance: slides in from height / 12 and fades (song change, loaded).
  late final SpringValue _enter = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  final GlobalKey _content = GlobalKey();
  final Map<int, GlobalKey> _rowKeys = {};
  int _active = -1;
  bool _seated = false;
  double _viewport = 0;

  /// What the seat depends on besides the rows: the viewport, the screen
  /// height (the full list's 40 % pull) and [windowTop]. Any change re-seats
  /// at once, before a frame shows the list off its line.
  (Size, double, double)? _seatInputs;

  /// The song the last outro staged in place (LyricsTrackTransition): a list
  /// that opens for it swaps in without the entrance slide.
  static String? _stagedTo;

  // ── Outro (UpNextTiming.kt) ─────────────────────────────────────────────
  UpNextTiming? _outro;
  UpNextTiming? _upNextTiming;
  int _lastTimed = -1;

  /// Quantized: the held last line's stretch, the next song's reveal.
  double _stretch = 0;
  double _reveal = 0;
  bool _handingOver = false;
  static final _slowEffects = YoinSprings.of(MotionTokens.expressiveSlowEffects);
  late final SpringValue _upNextAlpha = SpringValue(vsync: this, initial: 0, spring: _slowEffects);
  late final SpringValue _upNextLinesAlpha = SpringValue(vsync: this, initial: 0, spring: _slowEffects);
  late final SpringValue _upNextRise = SpringValue(vsync: this, initial: 0, spring: YoinSprings.slowSpatial);
  late final SpringValue _currentBlock = SpringValue(vsync: this, initial: 1, spring: _slowEffects);

  /// Select mode (the ✓ lyric tool; LyricsSelection), shared with the tools
  /// through [LyricToolsScope]. Full list only.
  LyricsSelection? _selection;

  /// A tap just tried to grow a full pick: the label names the cap for a moment.
  bool _limitNudge = false;
  Timer? _nudgeTimer;

  /// The label's band above the list's window (0 → its height), standard spatial.
  late final SpringValue _captionBand = SpringValue(
    vsync: this,
    initial: 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  bool _wasSelecting = false;

  bool get _selecting => _selection?.active ?? false;

  void _onSelection() {
    if (!mounted) return;
    final selecting = _selecting;
    if (selecting != _wasSelecting) {
      _wasSelecting = selecting;
      _setNudge(false);
      if (!selecting) {
        // Leaving the mode glides back to the playhead (browsing for lines isn't "stop following").
        WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: true));
      }
    } else {
      // Any accepted tap retires the nudge at once.
      _setNudge(false);
    }
    _evaluateOutro();
    setState(() {});
  }

  void _setNudge(bool on) {
    _nudgeTimer?.cancel();
    _nudgeTimer = null;
    _limitNudge = on;
    if (on) {
      _nudgeTimer = Timer(selectLimitNudge, () {
        if (mounted) setState(() => _limitNudge = false);
      });
    }
  }

  /// A tap on line [index] in select mode: a full pick refuses it with the
  /// reject haptic and the cap on the label (nextLyricSelection).
  void _tapInSelectMode(int index) {
    final selection = _selection;
    if (selection == null) return;
    if (nextLyricSelection(selection.selected, index, maxLines: selection.maxLines).hitLimit) {
      YoinHaptics.reject();
      setState(() => _setNudge(true));
    } else {
      selection.toggle(index);
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final next = _full ? LyricToolsScope.maybeOf(context)?.selection : null;
    if (next == _selection) return;
    _selection?.removeListener(_onSelection);
    _selection = next;
    _selection?.addListener(_onSelection);
    _wasSelecting = _selecting;
  }

  bool get _full => widget.mode == LyricsListMode.full;
  bool get _focusMode => widget.mode == LyricsListMode.focus;

  /// The full list's header row (title / artist) sits at index -1 → key 'header'.
  static const int _header = -1;

  /// The staged next song's title row and lines.
  static const int _upNextHeader = -3;
  static int _upNextLine(int i) => -1000 - i;

  /// The outro follows the playhead: not while the reader scrolled away or picks lines.
  bool get _following => !_selecting && (!_full || (widget.autoFollow?.value ?? true));

  bool get _upNextShown => _upNextTiming != null && widget.upNext != null;

  @override
  void initState() {
    super.initState();
    _active = _activeNow();
    _recomputeOutro();
    _evaluateOutro(initial: true);
    widget.clock.addListener(_onTick);
    widget.autoFollow?.addListener(_onAutoFollow);
    if (_full) {
      // A list that opens (Expanded, Full, a new song) follows the playhead,
      // as Android's pane does (its auto-scroll is remembered per pane).
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.autoFollow?.value = true;
      });
    }
    if (_stagedTo != null && _stagedTo == widget.songKey) {
      // The previous song handed over to this one: swap in place.
      _enter.snapTo(1);
    } else {
      _enter.animateTo(1);
    }
  }

  @override
  void didUpdateWidget(_LyricsList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
    }
    if (oldWidget.autoFollow != widget.autoFollow) {
      oldWidget.autoFollow?.removeListener(_onAutoFollow);
      widget.autoFollow?.addListener(_onAutoFollow);
    }
    if (oldWidget.sheet != widget.sheet ||
        oldWidget.upNext != widget.upNext ||
        oldWidget.durationMs != widget.durationMs) {
      _recomputeOutro();
      _evaluateOutro();
    }
  }

  @override
  void dispose() {
    _nudgeTimer?.cancel();
    _selection?.removeListener(_onSelection);
    _captionBand.dispose();
    widget.clock.removeListener(_onTick);
    widget.autoFollow?.removeListener(_onAutoFollow);
    _glide.dispose();
    _enter.dispose();
    _upNextAlpha.dispose();
    _upNextLinesAlpha.dispose();
    _upNextRise.dispose();
    _currentBlock.dispose();
    _scroll.dispose();
    super.dispose();
  }

  int _activeNow() => activeLyricIndex(widget.sheet.rows, widget.clock.positionMs());

  void _recomputeOutro() {
    final rows = widget.sheet.rows;
    _outro = lastLineOutroFor(rows, widget.durationMs);
    _upNextTiming = widget.mode == LyricsListMode.window
        ? null
        : upNextTimingFor(rows, widget.upNext, widget.durationMs);
    _lastTimed = rows.lastIndexWhere((r) => r.startMs != null);
  }

  /// Reads the playhead into the outro's quantized steps; true when any moved.
  bool _evaluateOutro({bool initial = false}) {
    final pos = widget.clock.positionMs();
    final stretch = _outro?.outroProgress(pos) ?? 0.0;
    final timing = _following ? _upNextTiming : null;
    final reveal = timing?.outroProgress(pos) ?? 0.0;
    final handing = timing != null && widget.upNext != null && pos >= timing.handoffAtMs;
    if (timing != null &&
        widget.upNext != null &&
        pos >= timing.handoffAtMs - NowPlayingFocusTokens.upNextHandoffTickSlackMs) {
      _stagedTo = widget.upNext!.songKey;
    }
    final changed = stretch != _stretch || reveal != _reveal || handing != _handingOver;
    _stretch = stretch;
    _reveal = reveal;
    _handingOver = handing;
    if (changed || initial) _applyOutro(snap: initial);
    if (!initial) _publishHandover(timing != null && widget.upNext != null && pos >= _handoverStagedAt(timing));
    return changed;
  }

  int _handoverStagedAt(UpNextTiming timing) => timing.handoffAtMs - NowPlayingFocusTokens.upNextHandoffTickSlackMs;

  /// Whether this list last told the background it staged the next song.
  bool _publishedStaged = false;

  /// The outro handed the next song over in place: that reveal IS the song
  /// change, so the background stays quiet for it (TransportPulse.kt). Only
  /// this list's own word: an unstaged list never clears another's.
  void _publishHandover(bool staged) {
    if (!staged && !_publishedStaged) return;
    _publishedStaged = staged;
    NowPlayingSignalScope.maybeOf(
      context,
    )?.publishLyricsHandover(widget.songKey, widget.upNext?.songKey, staged: staged);
  }

  void _applyOutro({bool snap = false}) {
    final shown = _upNextShown && _following;
    final late =
        ((_reveal - NowPlayingFocusTokens.upNextRevealLateStart) / (1 - NowPlayingFocusTokens.upNextRevealLateStart))
            .clamp(0.0, 1.0);
    final ease = Curves.fastOutSlowIn.transform(late);
    final rise = _focusMode ? NowPlayingFocusTokens.upNextRiseWindow : NowPlayingFocusTokens.upNextRise;
    final targets = <(SpringValue, double)>[
      (_upNextAlpha, !shown ? 0 : (_handingOver ? 1 : NowPlayingFocusTokens.upNextRevealAlpha * ease)),
      (_upNextLinesAlpha, shown && _handingOver ? 1 : 0),
      (_upNextRise, _handingOver ? 0 : (1 - ease) * rise),
      (_currentBlock, _handingOver ? 0 : 1),
    ];
    // initState snaps (no inherited lookups before it completes).
    final reduced = !snap && MediaQuery.maybeDisableAnimationsOf(context) == true;
    for (final (spring, target) in targets) {
      if (snap || reduced) {
        spring.snapTo(target);
      } else {
        spring.animateTo(target);
      }
    }
  }

  void _onTick() {
    final next = _activeNow();
    final handingBefore = _handingOver;
    final outroMoved = _evaluateOutro();
    if (next == _active && !outroMoved) return;
    final reseat = next != _active || handingBefore != _handingOver;
    // Focus's edge band swells once per new line (EdgeDotBand.kt): told by
    // the lists focus shows (the focus list, two columns' halftone list).
    if (next != _active && next >= 0 && (_focusMode || LyricHalftoneScope.isIn(context))) {
      NowPlayingSignalScope.maybeOf(context)?.lyricLineChanged();
    }
    setState(() => _active = next);
    if (reseat) WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: true));
  }

  void _onAutoFollow() {
    if (_evaluateOutro()) setState(() {});
    if (widget.autoFollow?.value ?? true) _seat(animate: true);
  }

  void _onGlide() {
    if (_scroll.hasClients) _scroll.jumpTo(_glide.value);
  }

  /// The focus row: the active line, or in the full list the header during the intro.
  int get _focus => _active >= 0 ? _active : (_full ? _header : -2);

  double? _rowTop(int index) {
    final box = _rowKeys[index]?.currentContext?.findRenderObject() as RenderBox?;
    final content = _content.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || content == null || !box.attached || !content.attached) return null;
    return box.localToGlobal(Offset.zero, ancestor: content).dy;
  }

  double? _rowHeight(int index) => (_rowKeys[index]?.currentContext?.findRenderObject() as RenderBox?)?.size.height;

  /// The full list's seat for [row]: its centre in the clean band between
  /// the fades, pulled toward 40 % of the screen.
  double _seatFull(int row, double top) {
    final viewport = _viewport;
    final fade = math.min(LyricsPaneMetrics.fade, viewport * LyricsPaneMetrics.fadeMaxShare);
    final band = math.max(1.0, viewport - 2 * fade);
    final screenH = MediaQuery.sizeOf(context).height;
    var fraction = ((screenH * LyricsPaneMetrics.screenPull - widget.windowTop - fade) / band).clamp(0.2, 0.5);
    fraction = (fraction * 50).round() / 50;
    final rowH = _rowHeight(row) ?? 0;
    final rowTop = (fade + band * fraction - rowH / 2).clamp(fade, math.max(fade, fade + band - rowH));
    // [top] is measured inside the list's top padding: a scroll offset of s
    // puts it at contentPad + top - s in the window (Android's scrollToItem
    // offset is from the content padding too), so the seat lands on [rowTop].
    return LyricsPaneMetrics.contentPad + top - rowTop;
  }

  double _targetOffset() {
    final current = _scroll.hasClients ? _scroll.offset : 0.0;
    if (_handingOver && _upNextShown) {
      // The hand-over parks the next song where its own list opens.
      if (_focusMode) {
        final top = _rowTop(_upNextLine(0));
        return top == null ? current : top - NowPlayingTokens.lyricsListPadding;
      }
      final top = _rowTop(_upNextHeader);
      return top == null ? current : _seatFull(_upNextHeader, top);
    }
    final focus = _focus;
    if (focus == -2) return 0;
    if (_focusMode) {
      // Pinned to the top with the line just sung whole above it.
      if (focus <= 0) return 0;
      final above = _rowTop(math.max(0, focus - widget.linesAbove));
      final top = _rowTop(focus);
      if (above == null || top == null) return current;
      return focusSeatOffset(
        currentTop: top,
        currentHeight: _rowHeight(focus) ?? 0,
        aboveTop: above,
        viewport: _viewport,
      );
    }
    final top = _rowTop(focus);
    if (top == null) return current;
    if (!_full) return top - _viewport * NowPlayingTokens.lyricsAnchorFraction;
    return _clampToSongEnd(_seatFull(focus, top));
  }

  /// Once the song's last line is on screen there is nothing left to scroll
  /// in from below: the staged up-next block (a viewport-tall tail) must not
  /// let the sung line hold its raised seat over an empty bottom. The list
  /// stops where its natural end would, and the highlight walks on down the
  /// screen (Android 11-16, lyricsFocusScrollOffsetPx's end clamp).
  double _clampToSongEnd(double seated) {
    final last = widget.sheet.rows.length - 1;
    if (last < 0) return seated;
    final top = _rowTop(last);
    final height = _rowHeight(last);
    if (top == null || height == null) return seated;
    return math.min(
      seated,
      lyricsEndClampOffset(
        viewport: _viewport,
        contentPadTop: LyricsPaneMetrics.contentPad,
        endRowBottom: top + height,
        endTail: LyricsPaneMetrics.contentPad + LyricsPaneMetrics.tail,
      ),
    );
  }

  void _seat({required bool animate}) {
    if (!mounted || !_scroll.hasClients) return;
    if (!widget.sheet.isSynced) return;
    if (_full && !(widget.autoFollow?.value ?? true)) return;
    // Select mode: the page holds still under the reader's taps.
    if (_selecting) return;
    final position = _scroll.position;
    final target = _targetOffset().clamp(position.minScrollExtent, position.maxScrollExtent);
    if (!animate || !_seated || MediaQuery.disableAnimationsOf(context)) {
      _glide.stop();
      _scroll.jumpTo(target);
      _seated = true;
      return;
    }
    _glide.value = _scroll.offset;
    _glide.animateWith(SpringSimulation(YoinSprings.standardDefaultSpatial, _scroll.offset, target, 0));
  }

  /// The content changed size (re-wrapped to a new width, a line's type
  /// stepped): re-seat at once unless a glide is already on its way.
  bool _onContentResized(SizeChangedLayoutNotification n) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_glide.isAnimating) _seat(animate: false);
    });
    return true;
  }

  bool _onScrollNotification(ScrollNotification n) {
    if (_full && n is ScrollStartNotification && n.dragDetails != null) {
      _glide.stop();
      // Browsing for lines in select mode isn't "stop following".
      if (!_selecting) widget.autoFollow?.value = false;
    }
    return false;
  }

  Widget _titleRow(BuildContext context, {required String title, String? artist, required bool lit}) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: LyricsPaneMetrics.rowPadV),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: text.headlineSmall!.copyWith(color: lit ? colors.primary : colors.onSurface)),
          if (artist != null && artist.isNotEmpty)
            Text(artist, style: text.titleMedium!.copyWith(color: colors.onSurfaceVariant)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final rows = widget.sheet.rows;
    final synced = widget.sheet.isSynced;
    final full = _full;
    final focusMode = _focusMode;
    final focus = _focus;
    final gap = full ? LyricsPaneMetrics.spacing : NowPlayingTokens.lyricsListLineGap;
    final selecting = full && _selecting;
    final selected = _selection?.selected ?? const <int>{};
    final hasHeader = full && (widget.title?.trim().isNotEmpty ?? false);

    Widget row(int index, LyricRow line) {
      final key = _rowKeys.putIfAbsent(index, GlobalKey.new);
      final int distance = focus == -2 ? 99 : (index - focus).abs();
      final active = index == _active;
      final child = _LyricLineView(
        key: key,
        text: line.text,
        active: active && !selecting,
        distance: synced ? distance : 0,
        above: synced && focus >= 0 && index < focus,
        // One of the next lines, or the line just sung: in focus its dots stay full enough to read.
        readable: synced && lyricRowReadable(index, _active, introOnHeader: focus == _header && hasHeader),
        linesAbove: widget.linesAbove,
        mode: widget.mode,
        synced: synced,
        stretch: index == _lastTimed && !selecting ? _stretch : 0,
        selectMode: selecting,
        role: selecting ? lyricSelectionRole(index, selected) : LyricSelectionRole.idle,
        runTop: !selected.contains(index - 1),
        runBottom: !selected.contains(index + 1),
        // spotoolfy keeps the line being sung lit while you pick.
        playingInSelect: selecting && active,
        secondary: full ? line.translation : null,
        showSecondary: widget.showTranslation,
      );
      if (!full) return child;
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: selecting
            ? () => _tapInSelectMode(index)
            : line.startMs == null
            ? null
            : () {
                widget.autoFollow?.value = true;
                widget.onSeek(line.startMs!);
              },
        child: child,
      );
    }

    List<Widget> spaced(List<Widget> items) => [
      for (var i = 0; i < items.length; i++) ...[if (i > 0) SizedBox(height: gap), items[i]],
    ];

    final current = <Widget>[
      if (hasHeader)
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: synced && !selecting
              ? () {
                  widget.autoFollow?.value = true;
                  widget.onSeek(0);
                }
              : null,
          child: KeyedSubtree(
            key: _rowKeys.putIfAbsent(_header, GlobalKey.new),
            // The song's title card (LyricRow "header"): a lyric row with the
            // artist as its second line; never pickable, so it steps back once
            // a pick exists.
            child: _LyricLineView(
              text: widget.title!,
              secondary: widget.artist,
              active: focus == _header && !selecting,
              distance: focus >= 0 ? focus + 1 : 0,
              above: false,
              linesAbove: widget.linesAbove,
              mode: widget.mode,
              synced: true,
              stretch: 0,
              selectMode: selecting,
              role: selected.isNotEmpty ? LyricSelectionRole.outside : LyricSelectionRole.idle,
            ),
          ),
        ),
      for (var i = 0; i < rows.length; i++) row(i, rows[i]),
    ];

    final upNext = widget.upNext;
    final staged = _upNextShown && upNext != null;

    Widget rising(int key, Widget child, SpringValue alpha) => KeyedSubtree(
      key: _rowKeys.putIfAbsent(key, GlobalKey.new),
      child: AnimatedBuilder(
        animation: Listenable.merge([alpha.animation, _upNextRise.animation]),
        child: child,
        builder: (context, child) => Opacity(
          opacity: alpha.value.clamp(0.0, 1.0),
          child: Transform.translate(offset: Offset(0, _upNextRise.value), child: child),
        ),
      ),
    );

    final upNextRows = !staged
        ? const <Widget>[]
        : <Widget>[
            SizedBox(height: NowPlayingFocusTokens.upNextGap),
            rising(
              _upNextHeader,
              full
                  // Rendered as the next song's own list renders its title card.
                  ? _LyricLineView(
                      text: upNext.title,
                      secondary: upNext.artist,
                      active: _handingOver,
                      distance: _handingOver ? 0 : 99,
                      above: false,
                      linesAbove: widget.linesAbove,
                      mode: widget.mode,
                      synced: true,
                      stretch: 0,
                    )
                  : _titleRow(context, title: upNext.title, artist: upNext.artist, lit: false),
              _upNextAlpha,
            ),
            for (var i = 0; i < upNext.sheet.rows.length; i++)
              rising(
                _upNextLine(i),
                _LyricLineView(
                  text: upNext.sheet.rows[i].text,
                  active: false,
                  // As the next song's own list opens: its title (full) or nothing (focus) in focus.
                  distance: full ? i + 1 : 99,
                  above: false,
                  linesAbove: widget.linesAbove,
                  mode: widget.mode,
                  synced: true,
                  stretch: 0,
                ),
                _upNextLinesAlpha,
              ),
          ];

    final side = NowPlayingTokens.columnPadding + widget.horizontalInset;
    // Full: each row carries its own 12 dp inset, so a picked line's block
    // reaches past the text into the page margin while the text stays on the
    // page's edge line (LyricsRowInset).
    final padding = full
        ? const EdgeInsets.fromLTRB(
            NowPlayingTokens.columnPadding - LyricsPaneMetrics.rowInset,
            LyricsPaneMetrics.contentPad,
            NowPlayingTokens.columnPadding - LyricsPaneMetrics.rowInset,
            LyricsPaneMetrics.contentPad + LyricsPaneMetrics.tail,
          )
        : EdgeInsets.symmetric(horizontal: side, vertical: NowPlayingTokens.lyricsListPadding);

    final screenH = MediaQuery.sizeOf(context).height;
    final strings = NowPlayingStrings.of(context);
    final textTheme = Theme.of(context).textTheme;
    final captionStyle = textTheme.labelLarge!;
    final captionHeight =
        MediaQuery.textScalerOf(context).scale(captionStyle.fontSize!) * (captionStyle.height ?? 1.4) +
        2 * LyricsPaneMetrics.captionPadV;
    if (full) {
      final target = selecting ? captionHeight : 0.0;
      if (MediaQuery.disableAnimationsOf(context)) {
        _captionBand.snapTo(target);
      } else {
        _captionBand.animateTo(target);
      }
    }
    return LayoutBuilder(
      builder: (context, constraints) {
        final viewport = constraints.maxHeight;
        final inputs = (constraints.biggest, screenH, widget.windowTop);
        if (viewport > 0 && inputs != _seatInputs) {
          _viewport = viewport;
          _seatInputs = inputs;
          // Resized in place (the stage reshape, Full, a window resize): re-anchor instantly.
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted) return;
            _glide.stop();
            _seat(animate: false);
          });
        }
        final fadeTop = full
            ? math.min(LyricsPaneMetrics.fade, viewport * LyricsPaneMetrics.fadeMaxShare)
            : (focusMode ? NowPlayingFocusTokens.fadeTop : LyricsPaneMetrics.windowFadeTop);
        final fadeBottom = full ? fadeTop : NowPlayingTokens.lyricsFadeBottom;
        final list = NotificationListener<ScrollNotification>(
          onNotification: _onScrollNotification,
          child: SingleChildScrollView(
            controller: _scroll,
            physics: full || !synced ? const BouncingScrollPhysics() : const NeverScrollableScrollPhysics(),
            padding: padding,
            child: NotificationListener<SizeChangedLayoutNotification>(
              onNotification: _onContentResized,
              child: SizeChangedLayoutNotifier(
                child: Column(
                  key: _content,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    AnimatedBuilder(
                      animation: _currentBlock.animation,
                      builder: (context, child) => Opacity(opacity: _currentBlock.value.clamp(0.0, 1.0), child: child),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: spaced(current)),
                    ),
                    if (staged) ...[
                      ...spaced(upNextRows),
                      // Long enough that the hand-over can park the next title where its own list opens.
                      SizedBox(height: viewport),
                    ],
                  ],
                ),
              ),
            ),
          ),
        );
        Widget masked(double band) {
          final mask = lyricsEdgeMask(
            viewport,
            fadeTop: fadeTop,
            fadeBottom: fadeBottom,
            clearBottom: full ? 0 : LyricsPaneMetrics.windowFadeClear,
            easeBottom: !full,
            clearTop: band,
          );
          return ShaderMask(
            blendMode: BlendMode.dstIn,
            shaderCallback: (rect) => LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: mask.colors,
              stops: mask.stops,
            ).createShader(rect),
            child: list,
          );
        }

        final body = AnimatedBuilder(
          animation: _enter.animation,
          builder: (context, child) {
            final e = _enter.value;
            return Opacity(
              opacity: e.clamp(0.0, 1.0),
              child: Transform.translate(offset: Offset(0, (1 - e) * viewport / 12), child: child),
            );
          },
          // Select mode's label gets its own band: the list's window (and its
          // top fade) opens below it; the list keeps its size and place, so
          // no line moves (lyricsWindowBelowBand).
          child: full
              ? AnimatedBuilder(
                  animation: _captionBand.animation,
                  builder: (context, _) => masked(math.max(0.0, _captionBand.value)),
                )
              : masked(0),
        );
        if (!full) return body;
        return Stack(
          fit: StackFit.expand,
          children: [
            body,
            // The label sits in its band above the window, outside the fade.
            Positioned(
              left: NowPlayingTokens.columnPadding,
              right: NowPlayingTokens.columnPadding,
              top: 0,
              child: IgnorePointer(
                child: _SelectCaption(
                  visible: selecting,
                  label: lyricsSelectionLabel(selected.length, strings, limitNudge: _limitNudge),
                  nudging: _limitNudge,
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// Select mode's top label (Android's caption): "Select lines" / "N / 15";
/// the cap in primary while a refused tap nudges. It fades in and out with
/// the mode, and a new text cross-fades with the old one (AnimatedContent),
/// both on the standard effects spring.
class _SelectCaption extends StatefulWidget {
  const _SelectCaption({required this.visible, required this.label, required this.nudging});

  final bool visible;
  final String label;
  final bool nudging;

  @override
  State<_SelectCaption> createState() => _SelectCaptionState();
}

class _SelectCaptionState extends State<_SelectCaption> with TickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// The label's texts and tones still showing: the newest last, fading in; older ones fading out.
  final List<(String, bool, SpringValue)> _labels = [];

  @override
  void initState() {
    super.initState();
    _labels.add((
      widget.label,
      widget.nudging,
      SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects),
    ));
  }

  @override
  void didUpdateWidget(_SelectCaption oldWidget) {
    super.didUpdateWidget(oldWidget);
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (reduced) {
      _shown.snapTo(widget.visible ? 1 : 0);
    } else {
      _shown.animateTo(widget.visible ? 1 : 0);
    }
    final last = _labels.last;
    if (last.$1 == widget.label && last.$2 == widget.nudging) return;
    for (final (_, _, alpha) in _labels) {
      reduced ? alpha.snapTo(0) : alpha.animateTo(0);
    }
    final incoming = SpringValue(vsync: this, initial: reduced ? 1 : 0, spring: YoinSprings.defaultEffects);
    if (!reduced) incoming.animateTo(1);
    _labels.add((widget.label, widget.nudging, incoming));
    // Keep the outgoing one, drop anything older (already gone).
    while (_labels.length > 2) {
      _labels.removeAt(0).$3.dispose();
    }
  }

  @override
  void dispose() {
    _shown.dispose();
    for (final (_, _, alpha) in _labels) {
      alpha.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge!;
    return AnimatedBuilder(
      animation: Listenable.merge([_shown.animation, for (final l in _labels) l.$3.animation]),
      builder: (context, _) {
        final v = _shown.value.clamp(0.0, 1.0);
        if (v <= 0.001) return const SizedBox.shrink();
        return Opacity(
          opacity: v,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: LyricsPaneMetrics.captionPadV),
            child: Stack(
              alignment: AlignmentDirectional.centerStart,
              children: [
                for (final (text, nudging, alpha) in _labels)
                  Opacity(
                    opacity: alpha.value.clamp(0.0, 1.0),
                    child: Text(
                      text,
                      key: ValueKey('lyrics-select-caption-$text'),
                      maxLines: 1,
                      style: style.copyWith(color: nudging ? colors.primary : colors.onSurfaceVariant),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}

/// One line: active = rounded GSF SemiBold in primary at scale 1; inactive
/// = onSurfaceVariant at 0.94 (window, focus) / 0.96 (full) with alpha by
/// distance. Focus prints a size up ([NowPlayingFocusTokens.lyricScale]) and
/// fades the lines already sung. Scale on the Expressive default spatial
/// spring (left-centre origin), alpha and colour on the default effects
/// spring. The song's last timed line widens in X with the outro ([stretch],
/// I-2: slow spatial widening, default spatial release).
///
/// Full list rows (LyricsFullscreenPane LyricRow) also carry the select
/// mode: a picked line sits on primaryContainer with onPrimaryContainer text;
/// adjacent picks merge into one block (outer corners 16, 4 where two picks
/// meet, on the standard fast spatial spring) that reaches 12 dp past the
/// text into the page margin; the text never moves. Unpicked lines read 0.7,
/// 0.42 once the pick is full (or for the title card once a pick exists).
class _LyricLineView extends StatefulWidget {
  const _LyricLineView({
    super.key,
    required this.text,
    required this.active,
    required this.distance,
    required this.above,
    required this.mode,
    required this.synced,
    required this.stretch,
    this.readable = false,
    this.linesAbove = NowPlayingFocusTokens.linesAbove,
    this.secondary,
    this.showSecondary = true,
    this.selectMode = false,
    this.role = LyricSelectionRole.idle,
    this.runTop = true,
    this.runBottom = true,
    this.playingInSelect = false,
  });

  final String text;
  final bool active;
  final int distance;

  /// Sung already (above the current line): focus fades these out.
  final bool above;

  /// One of the next lines after the current one, or the line just sung:
  /// focus's dots stay readable (isReadableLyricLine).
  final bool readable;
  final int linesAbove;
  final LyricsListMode mode;
  final bool synced;
  final double stretch;

  /// Full list: a second line (the title card's artist, a lyric's translation).
  final String? secondary;

  /// The second line shows; it opens from the line's lower edge on the
  /// spatial spring and fades in (Android's translation expandVertically).
  final bool showSecondary;
  final bool selectMode;
  final LyricSelectionRole role;

  /// Picked rows only: this row opens / closes its run (full corner there).
  final bool runTop;
  final bool runBottom;

  /// The line being sung, while picking: lit in primary, nothing more.
  final bool playingInSelect;

  @override
  State<_LyricLineView> createState() => _LyricLineViewState();
}

class _LyricLineViewState extends State<_LyricLineView> with TickerProviderStateMixin {
  late final SpringValue _scale = SpringValue(vsync: this, initial: _targetScale, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: _targetAlpha, spring: YoinSprings.defaultEffects);
  late final SpringValue _on = SpringValue(vsync: this, initial: _targetOn, spring: YoinSprings.defaultEffects);
  late final SpringValue _held = SpringValue(vsync: this, initial: widget.stretch, spring: YoinSprings.slowSpatial);
  late final SpringValue _picked = SpringValue(
    vsync: this,
    initial: _isPicked ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _topCorner = SpringValue(
    vsync: this,
    initial: _targetTopCorner,
    spring: YoinSprings.standardFastSpatial,
  );
  late final SpringValue _bottomCorner = SpringValue(
    vsync: this,
    initial: _targetBottomCorner,
    spring: YoinSprings.standardFastSpatial,
  );
  late final SpringValue _secondaryOpen = SpringValue(
    vsync: this,
    initial: widget.showSecondary ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _secondaryAlpha = SpringValue(
    vsync: this,
    initial: widget.showSecondary ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  bool get _full => widget.mode == LyricsListMode.full;

  /// Focus's halftone: this line's own dot radius (LyricHalftone.kt). The
  /// current line develops dots → solid, a sung line breaks back; lines that
  /// are current when they first show start solid.
  late final AnimationController _dots = AnimationController.unbounded(
    vsync: this,
    value: widget.active ? NowPlayingEffectTokens.lyricDotsSolid : NowPlayingEffectTokens.lyricDotsRest,
  );
  double? _restDots;

  /// The focus stage's progress (the overlay's), read in build only.
  Animation<double>? _focusAnim;

  double get _restDotsNow => lyricDotsRestFor(YoinWindowInfo.of(context), readable: widget.readable);

  /// Develops the current line, breaks a sung one back, settles the rest.
  void _syncDots({required bool becameActive}) {
    final rest = _restDotsNow;
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (widget.active) {
      if (!becameActive) return;
      if (reduced) {
        _dots.value = NowPlayingEffectTokens.lyricDotsSolid;
        return;
      }
      _dots.value = math.min(_dots.value, NowPlayingEffectTokens.lyricDotsDevelopFrom);
      _dots.animateTo(
        NowPlayingEffectTokens.lyricDotsSolid,
        duration: const Duration(milliseconds: NowPlayingEffectTokens.lyricDotsDevelopMs),
        curve: Curves.easeOutCubic,
      );
    } else if (_dots.value != rest) {
      if (reduced) {
        _dots.value = rest;
        return;
      }
      _dots.animateTo(
        rest,
        duration: const Duration(milliseconds: NowPlayingEffectTokens.lyricDotsBreakMs),
        curve: Curves.easeOutCubic,
      );
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // The focus list, or a full list beside a focused cover (two columns), prints through the dots.
    _focusAnim = widget.mode == LyricsListMode.focus
        ? NowPlayingOverlayScope.read(context)?.focus.animation
        : LyricHalftoneScope.maybeOf(context);
    final rest = _restDotsNow;
    if (_restDots == null) {
      if (!widget.active) _dots.value = rest;
    } else if (rest != _restDots) {
      _syncDots(becameActive: false);
    }
    _restDots = rest;
  }

  bool get _isPicked => _full && widget.selectMode && widget.role == LyricSelectionRole.picked;

  bool get _emphasized => widget.active || _isPicked;

  double get _targetOn => widget.active || (_full && widget.playingInSelect) ? 1 : 0;

  double get _targetTopCorner =>
      _isPicked && !widget.runTop ? LyricsPaneMetrics.pickInnerCorner : LyricsPaneMetrics.pickCorner;

  double get _targetBottomCorner =>
      _isPicked && !widget.runBottom ? LyricsPaneMetrics.pickInnerCorner : LyricsPaneMetrics.pickCorner;

  double get _targetScale {
    if (_full) return _emphasized ? 1 : 0.96;
    return widget.active || !widget.synced ? 1 : 0.94;
  }

  double get _targetAlpha {
    if (_full) {
      if (_emphasized) return 1;
      if (widget.selectMode) {
        return widget.role == LyricSelectionRole.outside
            ? LyricsPaneMetrics.outsideLineAlpha
            : LyricsPaneMetrics.selectModeLineAlpha;
      }
      if (!widget.synced) return 0.62;
      final d = widget.distance;
      return d <= 1 ? 0.62 : (d == 2 ? 0.50 : 0.40);
    }
    if (!widget.synced) return 0.55;
    if (widget.active) return 1;
    final d = widget.distance;
    if (widget.mode == LyricsListMode.focus && widget.above) {
      if (d > widget.linesAbove) return NowPlayingFocusTokens.farAboveAlpha;
      // The kept lines fade with their distance (the nearest at aboveAlpha).
      return NowPlayingFocusTokens.aboveAlpha * (1 - (d - 1) / (widget.linesAbove + 1));
    }
    return d <= 1 ? 0.55 : (d == 2 ? 0.40 : 0.28);
  }

  @override
  void didUpdateWidget(_LyricLineView oldWidget) {
    super.didUpdateWidget(oldWidget);
    _scale.animateTo(_targetScale);
    _alpha.animateTo(_targetAlpha);
    _on.animateTo(_targetOn);
    _picked.animateTo(_isPicked ? 1 : 0);
    _topCorner.animateTo(_targetTopCorner);
    _bottomCorner.animateTo(_targetBottomCorner);
    if (MediaQuery.disableAnimationsOf(context)) {
      _secondaryOpen.snapTo(widget.showSecondary ? 1 : 0);
      _secondaryAlpha.snapTo(widget.showSecondary ? 1 : 0);
    } else {
      _secondaryOpen.animateTo(widget.showSecondary ? 1 : 0);
      _secondaryAlpha.animateTo(widget.showSecondary ? 1 : 0);
    }
    if (widget.active != oldWidget.active || widget.readable != oldWidget.readable) {
      _restDots = _restDotsNow;
      _syncDots(becameActive: widget.active && !oldWidget.active);
    }
    if (widget.stretch != oldWidget.stretch) {
      _held.spring = widget.stretch > 0 ? YoinSprings.slowSpatial : YoinSprings.defaultSpatial;
      if (MediaQuery.disableAnimationsOf(context)) {
        _held.snapTo(widget.stretch);
      } else {
        _held.animateTo(widget.stretch);
      }
    }
  }

  @override
  void dispose() {
    _dots.dispose();
    _scale.dispose();
    _alpha.dispose();
    _on.dispose();
    _held.dispose();
    _picked.dispose();
    _topCorner.dispose();
    _bottomCorner.dispose();
    _secondaryOpen.dispose();
    _secondaryAlpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final active = widget.active;
    final full = _full;
    final focus = widget.mode == LyricsListMode.focus;
    final k = focus ? NowPlayingFocusTokens.lyricScale : 1.0;
    final emphasized = full ? _emphasized : active;
    final raw = full ? text.headlineSmall! : (active ? text.bodyLarge! : text.bodyMedium!);
    final base = focus ? raw.copyWith(fontSize: raw.fontSize! * k) : raw;
    // Current line = SemiBold in the rounded cut (ROND 100); full list's others Medium.
    final style = emphasized
        ? base.merge(gsf(base.fontSize!, weight: 600, height: base.height, round: 100))
        : (full ? base.merge(gsf(base.fontSize!, weight: 500, height: base.height)) : base);
    final secondary = full && (widget.secondary?.trim().isNotEmpty ?? false) ? widget.secondary : null;
    final secondaryBase = text.bodyLarge!;
    final focusAnim = _focusAnim;
    final content = AnimatedBuilder(
      animation: Listenable.merge([
        if (focusAnim != null) ...[_dots, focusAnim],
        _scale.animation,
        _alpha.animation,
        _on.animation,
        _held.animation,
        _picked.animation,
        _secondaryOpen.animation,
        _secondaryAlpha.animation,
      ]),
      builder: (context, child) {
        final s = _scale.value;
        final lit = Color.lerp(colors.onSurfaceVariant, colors.primary, _on.value.clamp(0.0, 1.0))!;
        final color = Color.lerp(lit, colors.onPrimaryContainer, _picked.value.clamp(0.0, 1.0))!;
        final line = Text(widget.text, style: style.copyWith(color: color));
        // Focus prints the row through its dots, its translation too (inside
        // the row's own scale, as Android's layer).
        Widget dotted(Widget row) => focusAnim == null
            ? row
            : LyricHalftone(radius: lyricDotRadius(_dots.value, focusAnim.value), child: row);
        return Opacity(
          opacity: _alpha.value.clamp(0.0, 1.0),
          child: Transform(
            alignment: AlignmentDirectional.centerStart,
            transform: Matrix4.diagonal3Values(s, s, 1),
            child: dotted(() {
              final stretched = Transform(
                alignment: AlignmentDirectional.centerStart,
                transform: Matrix4.diagonal3Values(lastLineStretchScale(_held.value), 1, 1),
                child: line,
              );
              final open = _secondaryOpen.value.clamp(0.0, 1.0);
              if (secondary == null || open <= 0.001) return stretched;
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  stretched,
                  // The gap lives inside the opening block, so a closed row is one line tall.
                  ClipRect(
                    child: Align(
                      alignment: AlignmentDirectional.topStart,
                      heightFactor: open,
                      child: Opacity(
                        opacity: _secondaryAlpha.value.clamp(0.0, 1.0),
                        child: Padding(
                          padding: const EdgeInsets.only(top: LyricsPaneMetrics.secondaryGap),
                          child: Text(
                            secondary,
                            style: secondaryBase
                                .merge(
                                  gsf(
                                    secondaryBase.fontSize!,
                                    weight: emphasized ? 500 : 400,
                                    height: secondaryBase.height,
                                  ),
                                )
                                .copyWith(color: color.withValues(alpha: emphasized ? 0.82 : 0.7)),
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              );
            }()),
          ),
        );
      },
    );
    if (!full) {
      return Padding(
        padding: EdgeInsets.symmetric(vertical: NowPlayingTokens.lyricLinePads / 2 * k),
        child: content,
      );
    }
    return AnimatedBuilder(
      animation: Listenable.merge([_picked.animation, _topCorner.animation, _bottomCorner.animation]),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: LyricsPaneMetrics.rowInset,
          vertical: LyricsPaneMetrics.rowPadV,
        ),
        child: content,
      ),
      builder: (context, child) {
        final picked = _picked.value.clamp(0.0, 1.0);
        if (picked <= 0.001) return child!;
        final top = Radius.circular(math.max(0.0, _topCorner.value));
        final bottom = Radius.circular(math.max(0.0, _bottomCorner.value));
        return DecoratedBox(
          decoration: BoxDecoration(
            color: colors.primaryContainer.withValues(alpha: picked),
            borderRadius: BorderRadius.only(topLeft: top, topRight: top, bottomLeft: bottom, bottomRight: bottom),
          ),
          child: child,
        );
      },
    );
  }
}
