import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'lyrics_logic.dart';
import 'now_playing_view_model.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';
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
  });

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
  });

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

  /// Select mode (LyricsSelection): a long-press picks a line, taps toggle
  /// lines, Copy puts them on the clipboard with the song's credit.
  bool _selecting = false;
  final Set<int> _selected = {};

  /// "Copied 3 lines", shown in the select bar for a moment after Copy.
  String? _copied;
  Timer? _copiedTimer;

  void _startSelecting(int index) {
    YoinHaptics.contextClick();
    widget.autoFollow?.value = false;
    setState(() {
      _selecting = true;
      _copied = null;
      _selected
        ..clear()
        ..add(index);
    });
  }

  void _toggle(int index) {
    YoinHaptics.lightTick();
    setState(() {
      if (!_selected.remove(index)) _selected.add(index);
      if (_selected.isEmpty) _selecting = false;
    });
  }

  void _endSelecting() {
    _copiedTimer?.cancel();
    setState(() {
      _selecting = false;
      _selected.clear();
      _copied = null;
    });
    widget.autoFollow?.value = true;
  }

  Future<void> _copy() async {
    final rows = widget.sheet.rows;
    final picked = (_selected.toList()..sort()).where((i) => i >= 0 && i < rows.length).toList();
    if (picked.isEmpty) return;
    final strings = NowPlayingStrings.of(context);
    final body = picked.map((i) => rows[i].text).join('\n');
    await Clipboard.setData(ClipboardData(text: strings.lyricsClipboard(body, widget.title, widget.artist)));
    if (!mounted) return;
    YoinHaptics.click();
    setState(() => _copied = strings.lyricsCopied(picked.length));
    _copiedTimer?.cancel();
    _copiedTimer = Timer(const Duration(milliseconds: 1500), () {
      if (mounted) _endSelecting();
    });
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
    _copiedTimer?.cancel();
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
    return changed;
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
    return top - rowTop;
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
      return above == null ? current : above - NowPlayingTokens.lyricsListPadding;
    }
    final top = _rowTop(focus);
    if (top == null) return current;
    if (!_full) return top - _viewport * NowPlayingTokens.lyricsAnchorFraction;
    return _seatFull(focus, top);
  }

  void _seat({required bool animate}) {
    if (!mounted || !_scroll.hasClients) return;
    if (!widget.sheet.isSynced) return;
    if (_full && !(widget.autoFollow?.value ?? true)) return;
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

  bool _onScrollNotification(ScrollNotification n) {
    if (_full && n is ScrollStartNotification && n.dragDetails != null) {
      _glide.stop();
      widget.autoFollow?.value = false;
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

    Widget row(int index, LyricRow line) {
      final key = _rowKeys.putIfAbsent(index, GlobalKey.new);
      final int distance = focus == -2 ? 99 : (index - focus).abs();
      final active = index == _active;
      final child = _LyricLineView(
        key: key,
        text: line.text,
        active: active,
        distance: synced ? distance : 0,
        above: synced && focus >= 0 && index < focus,
        linesAbove: widget.linesAbove,
        mode: widget.mode,
        synced: synced,
        stretch: index == _lastTimed ? _stretch : 0,
      );
      if (!full) return child;
      final picked = _selected.contains(index);
      final shown = _PickTint(picked: picked, selecting: _selecting, child: child);
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onLongPress: _selecting ? null : () => _startSelecting(index),
        onTap: _selecting
            ? () => _toggle(index)
            : line.startMs == null
            ? null
            : () {
                widget.autoFollow?.value = true;
                widget.onSeek(line.startMs!);
              },
        child: shown,
      );
    }

    List<Widget> spaced(List<Widget> items) => [
      for (var i = 0; i < items.length; i++) ...[if (i > 0) SizedBox(height: gap), items[i]],
    ];

    final current = <Widget>[
      if (full && (widget.title != null))
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: synced
              ? () {
                  widget.autoFollow?.value = true;
                  widget.onSeek(0);
                }
              : null,
          child: KeyedSubtree(
            key: _rowKeys.putIfAbsent(_header, GlobalKey.new),
            child: _titleRow(context, title: widget.title!, artist: widget.artist, lit: focus == _header),
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
              _titleRow(context, title: upNext.title, artist: upNext.artist, lit: _handingOver && full),
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
    final padding = full
        ? const EdgeInsets.fromLTRB(
            NowPlayingTokens.columnPadding,
            LyricsPaneMetrics.contentPad,
            NowPlayingTokens.columnPadding,
            LyricsPaneMetrics.contentPad + LyricsPaneMetrics.tail,
          )
        : EdgeInsets.symmetric(horizontal: side, vertical: NowPlayingTokens.lyricsListPadding);

    return LayoutBuilder(
      builder: (context, constraints) {
        final viewport = constraints.maxHeight;
        if (viewport > 0 && viewport != _viewport) {
          _viewport = viewport;
          // The window is resized in place by the stage reshape: re-anchor instantly.
          WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: false));
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
        );
        final body = AnimatedBuilder(
          animation: _enter.animation,
          builder: (context, child) {
            final e = _enter.value;
            return Opacity(
              opacity: e.clamp(0.0, 1.0),
              child: Transform.translate(offset: Offset(0, (1 - e) * viewport / 12), child: child),
            );
          },
          child: ShaderMask(
            blendMode: BlendMode.dstIn,
            shaderCallback: (rect) => LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: const [Color(0x00000000), Color(0xFF000000), Color(0xFF000000), Color(0x00000000)],
              stops: [0, (fadeTop / rect.height).clamp(0.0, 0.5), 1 - (fadeBottom / rect.height).clamp(0.0, 0.5), 1],
            ).createShader(rect),
            child: list,
          ),
        );
        if (!full) return body;
        return Stack(
          fit: StackFit.expand,
          children: [
            body,
            Positioned(
              left: 0,
              right: 0,
              bottom: 8,
              child: Center(
                child: _SelectBar(
                  visible: _selecting,
                  label: _copied ?? NowPlayingStrings.of(context).lyricsSelectCount(_selected.length, rows.length),
                  onCopy: _copied == null ? _copy : null,
                  onDone: _endSelecting,
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// The select bar over the full list: the count (or "Copied N lines"),
/// Copy and Done. Slides up and fades in on the Standard springs.
class _SelectBar extends StatefulWidget {
  const _SelectBar({required this.visible, required this.label, required this.onCopy, required this.onDone});

  final bool visible;
  final String label;
  final VoidCallback? onCopy;
  final VoidCallback onDone;

  @override
  State<_SelectBar> createState() => _SelectBarState();
}

class _SelectBarState extends State<_SelectBar> with TickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  @override
  void didUpdateWidget(_SelectBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (MediaQuery.disableAnimationsOf(context)) {
      _shown.snapTo(widget.visible ? 1 : 0);
    } else {
      _shown.animateTo(widget.visible ? 1 : 0);
    }
  }

  @override
  void dispose() {
    _shown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    return AnimatedBuilder(
      animation: _shown.animation,
      builder: (context, child) {
        final v = _shown.value.clamp(0.0, 1.0);
        if (v <= 0.001) return const SizedBox.shrink();
        return IgnorePointer(
          ignoring: !widget.visible,
          child: Opacity(
            opacity: v,
            child: Transform.translate(offset: Offset(0, 24 * (1 - v)), child: child),
          ),
        );
      },
      child: Material(
        color: colors.inverseSurface,
        shape: const StadiumBorder(),
        elevation: 3,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 4, 4, 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                widget.label,
                style: text.labelLarge!.copyWith(
                  color: colors.onInverseSurface,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
              const SizedBox(width: 8),
              IconButton(
                tooltip: strings.cdCopyLyrics,
                onPressed: widget.onCopy,
                icon: Icon(YoinSymbols.copy, color: colors.onInverseSurface),
              ),
              IconButton(
                tooltip: strings.cdDoneSelecting,
                onPressed: widget.onDone,
                icon: Icon(YoinSymbols.check, color: colors.onInverseSurface),
              ),
            ],
          ),
        ),
      ),
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
    this.linesAbove = NowPlayingFocusTokens.linesAbove,
  });

  final String text;
  final bool active;
  final int distance;

  /// Sung already (above the current line): focus fades these out.
  final bool above;
  final int linesAbove;
  final LyricsListMode mode;
  final bool synced;
  final double stretch;

  @override
  State<_LyricLineView> createState() => _LyricLineViewState();
}

class _LyricLineViewState extends State<_LyricLineView> with TickerProviderStateMixin {
  late final SpringValue _scale = SpringValue(vsync: this, initial: _targetScale, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: _targetAlpha, spring: YoinSprings.defaultEffects);
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.active ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _held = SpringValue(vsync: this, initial: widget.stretch, spring: YoinSprings.slowSpatial);

  bool get _full => widget.mode == LyricsListMode.full;

  double get _targetScale => widget.active || !widget.synced ? 1 : (_full ? 0.96 : 0.94);

  double get _targetAlpha {
    if (!widget.synced) return _full ? 0.62 : 0.55;
    if (widget.active) return 1;
    final d = widget.distance;
    if (_full) return d <= 1 ? 0.62 : (d == 2 ? 0.50 : 0.40);
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
    _on.animateTo(widget.active ? 1 : 0);
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
    _scale.dispose();
    _alpha.dispose();
    _on.dispose();
    _held.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final active = widget.active;
    final focus = widget.mode == LyricsListMode.focus;
    final k = focus ? NowPlayingFocusTokens.lyricScale : 1.0;
    final raw = _full ? text.headlineSmall! : (active ? text.bodyLarge! : text.bodyMedium!);
    final base = focus ? raw.copyWith(fontSize: raw.fontSize! * k) : raw;
    final style = active
        ? base.merge(gsf(base.fontSize!, weight: 600, height: base.height, round: 100))
        : (_full ? base.merge(gsf(base.fontSize!, weight: 500, height: base.height)) : base);
    return Padding(
      padding: EdgeInsets.symmetric(
        vertical: _full ? LyricsPaneMetrics.rowPadV : NowPlayingTokens.lyricLinePads / 2 * k,
      ),
      child: AnimatedBuilder(
        animation: Listenable.merge([_scale.animation, _alpha.animation, _on.animation, _held.animation]),
        builder: (context, child) {
          final s = _scale.value;
          return Opacity(
            opacity: _alpha.value.clamp(0.0, 1.0),
            child: Transform(
              alignment: AlignmentDirectional.centerStart,
              transform: Matrix4.diagonal3Values(s * lastLineStretchScale(_held.value), s, 1),
              child: Text(
                widget.text,
                style: style.copyWith(
                  color: Color.lerp(colors.onSurfaceVariant, colors.primary, _on.value.clamp(0.0, 1.0)),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// A picked line's tint (no stroke) on the effects spring; the inset that
/// makes room for it on the spatial spring.
class _PickTint extends StatefulWidget {
  const _PickTint({required this.picked, required this.selecting, required this.child});

  final bool picked;
  final bool selecting;
  final Widget child;

  @override
  State<_PickTint> createState() => _PickTintState();
}

class _PickTintState extends State<_PickTint> with TickerProviderStateMixin {
  late final SpringValue _tint = SpringValue(
    vsync: this,
    initial: widget.picked ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _inset = SpringValue(
    vsync: this,
    initial: widget.selecting ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );

  @override
  void didUpdateWidget(_PickTint oldWidget) {
    super.didUpdateWidget(oldWidget);
    _tint.animateTo(widget.picked ? 1 : 0);
    _inset.animateTo(widget.selecting ? 1 : 0);
  }

  @override
  void dispose() {
    _tint.dispose();
    _inset.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_tint.animation, _inset.animation]),
      builder: (context, child) => DecoratedBox(
        decoration: BoxDecoration(
          color: colors.primaryContainer.withValues(alpha: _tint.value.clamp(0.0, 1.0)),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: 8 * _inset.value.clamp(0.0, 1.0)),
          child: child,
        ),
      ),
      child: widget.child,
    );
  }
}
