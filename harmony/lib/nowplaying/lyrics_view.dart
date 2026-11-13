import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'lyrics_logic.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';

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

/// Which list: the Compact window (read-only, anchored at 22 %) or the
/// Expanded stage's full list (tap a line to seek, scroll frees it).
enum LyricsListMode { window, full }

/// Lyrics for the stage: loading indicator, "No lyrics available", or the
/// list. The active line follows the shared clock; the widget rebuilds only
/// when the active line changes.
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

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final full = mode == LyricsListMode.full;
    return switch (lyrics) {
      LyricsLoading() => Align(
        alignment: full ? Alignment.center : AlignmentDirectional.centerStart,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: full ? 0 : NowPlayingTokens.columnPadding),
          child: SizedBox.square(
            dimension: full ? 36 : 32,
            child: CircularProgressIndicator(color: colors.primary, strokeWidth: 3),
          ),
        ),
      ),
      LyricsReady(:final sheet) when sheet.isEmpty => Align(
        alignment: full ? Alignment.center : AlignmentDirectional.topStart,
        child: Padding(
          padding: EdgeInsets.symmetric(horizontal: full ? 0 : NowPlayingTokens.columnPadding),
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
        sheet: sheet,
        clock: clock,
        onSeek: onSeek,
        title: title,
        artist: artist,
        autoFollow: autoFollow,
        windowTop: windowTop,
      ),
    };
  }
}

class _LyricsList extends StatefulWidget {
  const _LyricsList({
    super.key,
    required this.mode,
    required this.sheet,
    required this.clock,
    required this.onSeek,
    required this.title,
    required this.artist,
    required this.autoFollow,
    required this.windowTop,
  });

  final LyricsListMode mode;
  final LyricSheet sheet;
  final PlayheadClock clock;
  final ValueChanged<int> onSeek;
  final String? title;
  final String? artist;
  final ValueNotifier<bool>? autoFollow;
  final double windowTop;

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

  bool get _full => widget.mode == LyricsListMode.full;

  /// The full list's header row (title / artist) sits at index -1 → key 'header'.
  static const int _header = -1;

  @override
  void initState() {
    super.initState();
    _active = _activeNow();
    widget.clock.addListener(_onTick);
    widget.autoFollow?.addListener(_onAutoFollow);
    _enter.animateTo(1);
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
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    widget.autoFollow?.removeListener(_onAutoFollow);
    _glide.dispose();
    _enter.dispose();
    _scroll.dispose();
    super.dispose();
  }

  int _activeNow() => activeLyricIndex(widget.sheet.rows, widget.clock.positionMs());

  void _onTick() {
    final next = _activeNow();
    if (next == _active) return;
    setState(() => _active = next);
    WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: true));
  }

  void _onAutoFollow() {
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

  double _targetOffset() {
    final focus = _focus;
    if (focus == -2) return 0;
    final top = _rowTop(focus);
    if (top == null) return _scroll.hasClients ? _scroll.offset : 0;
    final viewport = _viewport;
    if (!_full) return top - viewport * NowPlayingTokens.lyricsAnchorFraction;
    // Full list: the row's centre in the clean band between the fades, pulled toward 40 % of the screen.
    final fade = math.min(LyricsPaneMetrics.fade, viewport * LyricsPaneMetrics.fadeMaxShare);
    final band = math.max(1.0, viewport - 2 * fade);
    final screenH = MediaQuery.sizeOf(context).height;
    var fraction = ((screenH * LyricsPaneMetrics.screenPull - widget.windowTop - fade) / band).clamp(0.2, 0.5);
    fraction = (fraction * 50).round() / 50;
    final rowH = _rowHeight(focus) ?? 0;
    final rowTop = (fade + band * fraction - rowH / 2).clamp(fade, math.max(fade, fade + band - rowH));
    return top - rowTop;
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

  @override
  Widget build(BuildContext context) {
    final rows = widget.sheet.rows;
    final synced = widget.sheet.isSynced;
    final full = _full;
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final focus = _focus;

    Widget row(int index, LyricRow line) {
      final key = _rowKeys.putIfAbsent(index, GlobalKey.new);
      final int distance = focus == -2 ? 99 : (index - focus).abs();
      final active = index == _active;
      final child = _LyricLineView(
        key: key,
        text: line.text,
        active: active,
        distance: synced ? distance : 0,
        full: full,
        synced: synced,
      );
      if (!full || line.startMs == null) return child;
      return GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () {
          widget.autoFollow?.value = true;
          widget.onSeek(line.startMs!);
        },
        child: child,
      );
    }

    final children = <Widget>[
      if (full && (widget.title != null))
        GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: synced
              ? () {
                  widget.autoFollow?.value = true;
                  widget.onSeek(0);
                }
              : null,
          child: Padding(
            key: _rowKeys.putIfAbsent(_header, GlobalKey.new),
            padding: const EdgeInsets.symmetric(vertical: LyricsPaneMetrics.rowPadV),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.title!,
                  style: text.headlineSmall!.copyWith(color: focus == _header ? colors.primary : colors.onSurface),
                ),
                if (widget.artist != null)
                  Text(widget.artist!, style: text.titleMedium!.copyWith(color: colors.onSurfaceVariant)),
              ],
            ),
          ),
        ),
      for (var i = 0; i < rows.length; i++) row(i, rows[i]),
    ];

    final padding = full
        ? const EdgeInsets.fromLTRB(
            NowPlayingTokens.columnPadding,
            LyricsPaneMetrics.contentPad,
            NowPlayingTokens.columnPadding,
            LyricsPaneMetrics.contentPad + LyricsPaneMetrics.tail,
          )
        : const EdgeInsets.symmetric(
            horizontal: NowPlayingTokens.columnPadding,
            vertical: NowPlayingTokens.lyricsListPadding,
          );

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
            : LyricsPaneMetrics.windowFadeTop;
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
                for (var i = 0; i < children.length; i++) ...[
                  if (i > 0) SizedBox(height: full ? LyricsPaneMetrics.spacing : NowPlayingTokens.lyricsListLineGap),
                  children[i],
                ],
              ],
            ),
          ),
        );
        return AnimatedBuilder(
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
      },
    );
  }
}

/// One line: active = rounded GSF SemiBold in primary at scale 1; inactive
/// = onSurfaceVariant at 0.94 (window) / 0.96 (full) with alpha by distance.
/// Scale on the Expressive default spatial spring (left-centre origin),
/// alpha and colour on the default effects spring.
class _LyricLineView extends StatefulWidget {
  const _LyricLineView({
    super.key,
    required this.text,
    required this.active,
    required this.distance,
    required this.full,
    required this.synced,
  });

  final String text;
  final bool active;
  final int distance;
  final bool full;
  final bool synced;

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

  double get _targetScale => widget.active || !widget.synced ? 1 : (widget.full ? 0.96 : 0.94);

  double get _targetAlpha {
    if (!widget.synced) return widget.full ? 0.62 : 0.55;
    if (widget.active) return 1;
    final d = widget.distance;
    if (widget.full) return d <= 1 ? 0.62 : (d == 2 ? 0.50 : 0.40);
    return d <= 1 ? 0.55 : (d == 2 ? 0.40 : 0.28);
  }

  @override
  void didUpdateWidget(_LyricLineView oldWidget) {
    super.didUpdateWidget(oldWidget);
    _scale.animateTo(_targetScale);
    _alpha.animateTo(_targetAlpha);
    _on.animateTo(widget.active ? 1 : 0);
  }

  @override
  void dispose() {
    _scale.dispose();
    _alpha.dispose();
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final active = widget.active;
    final base = widget.full ? text.headlineSmall! : (active ? text.bodyLarge! : text.bodyMedium!);
    final style = active
        ? base.merge(gsf(base.fontSize!, weight: 600, height: base.height, round: 100))
        : (widget.full ? base.merge(gsf(base.fontSize!, weight: 500, height: base.height)) : base);
    return Padding(
      padding: EdgeInsets.symmetric(
        vertical: widget.full ? LyricsPaneMetrics.rowPadV : NowPlayingTokens.lyricLinePads / 2,
      ),
      child: AnimatedBuilder(
        animation: Listenable.merge([_scale.animation, _alpha.animation, _on.animation]),
        builder: (context, child) => Opacity(
          opacity: _alpha.value.clamp(0.0, 1.0),
          child: Transform.scale(
            scale: _scale.value,
            alignment: AlignmentDirectional.centerStart,
            child: Text(
              widget.text,
              style: style.copyWith(
                color: Color.lerp(colors.onSurfaceVariant, colors.primary, _on.value.clamp(0.0, 1.0)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
