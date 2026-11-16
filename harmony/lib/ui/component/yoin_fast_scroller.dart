import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import 'seam/chrome_seam.dart';

/// One labelled run of a [YoinFastScroller]'s list.
@immutable
final class FastScrollMark {
  const FastScrollMark({required this.bubble, required this.tick, required this.startIndex});

  /// The bubble's text ("Mar 2024", "A").
  final String bubble;

  /// The tick column's text ("2024", "Mar", "A").
  final String tick;

  /// First item (in the sectioned items, not counting leading full-span items).
  final int startIndex;
}

/// Handle geometry (component/YoinFastScroller.kt).
abstract final class FastScrollerGeometry {
  static const double thumbWidth = 24;
  static const double thumbHeight = 48;
  static const double touchWidth = 32;
  static const double touchHeight = 64;
  static const double minTickGap = 18;

  /// The handle's top on a track [trackLength] long (thumb top travels
  /// 0..trackLength - thumbHeight) at scroll [fraction].
  static double thumbTop(double fraction, double trackLength) =>
      fraction.clamp(0.0, 1.0) * math.max(0, trackLength - thumbHeight);

  /// The scroll fraction for a finger at [y] on the track (thumb centred under it).
  static double fractionAt(double y, double trackLength) {
    final travel = trackLength - thumbHeight;
    if (travel <= 0) return 0;
    return ((y - thumbHeight / 2) / travel).clamp(0.0, 1.0);
  }

  /// The line (row) a fraction points at, of [lineCount] lines.
  static int lineAt(double fraction, int lineCount) => lineCount <= 1 ? 0 : (fraction * (lineCount - 1)).round();

  /// Index of the mark covering [itemIndex] (marks sorted by startIndex), or -1.
  static int markAt(List<FastScrollMark> marks, int itemIndex) {
    var found = -1;
    for (final (i, m) in marks.indexed) {
      if (m.startIndex <= itemIndex) {
        found = i;
      } else {
        break;
      }
    }
    return found;
  }
}

/// A fast-scroll handle at the end edge of a long grid or list (Library
/// Artists / Albums / All). It slides in while the list scrolls or is held
/// and sleeps [MotionTokens.fastScrollerHideDelayMs] after both stop.
/// Pressing anywhere on it follows the finger 1:1 from the first pixel and
/// jumps the list; while held, a tick column of [marks] and a bubble with the
/// current mark unfold beside it. Shown only for content longer than three
/// screens.
///
/// The jump maps the handle's track to the scroll extent proportionally
/// (rows are uniform in these grids), so a line's position on the track is
/// line / lastLine.
class YoinFastScroller extends StatefulWidget {
  const YoinFastScroller({
    super.key,
    required this.controller,
    required this.itemCount,
    required this.columns,
    required this.child,
    this.marks = const [],
    this.topInset = LibraryTokens.scrollerTopInset,
    this.bottomInset = 0,
    this.onJumped,
  });

  final ScrollController controller;
  final int itemCount;
  final int columns;
  final List<FastScrollMark> marks;
  final double topInset;
  final double bottomInset;
  final Widget child;

  /// After each jump, the list already moved: a grid moves its column-change
  /// anchor along with it (a jump is no scroll; Android onJumped).
  final VoidCallback? onJumped;

  @override
  State<YoinFastScroller> createState() => _YoinFastScrollerState();
}

class _YoinFastScrollerState extends State<YoinFastScroller> with TickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _slide = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultSpatial);
  late final SpringValue _held = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _expand = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastEffects);
  late final SpringValue _pulse = SpringValue(vsync: this, initial: 1, spring: YoinSprings.fastSpatial);
  final _fraction = ValueNotifier<double>(0);
  Timer? _hide;
  bool _holding = false;
  int _markIndex = -1;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onScroll);
  }

  @override
  void didUpdateWidget(YoinFastScroller old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_onScroll);
      widget.controller.addListener(_onScroll);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onScroll);
    _hide?.cancel();
    for (final s in [_shown, _slide, _held, _expand, _pulse]) {
      s.dispose();
    }
    _fraction.dispose();
    super.dispose();
  }

  bool get _longEnough {
    if (!widget.controller.hasClients) return false;
    final p = widget.controller.position;
    return p.hasContentDimensions && p.maxScrollExtent > p.viewportDimension * 2;
  }

  void _onScroll() {
    if (!widget.controller.hasClients) return;
    final p = widget.controller.position;
    if (!_holding && p.maxScrollExtent > 0) _fraction.value = (p.pixels / p.maxScrollExtent).clamp(0.0, 1.0);
    if (!_longEnough) return;
    _wake();
  }

  void _wake() {
    _shown.animateTo(1);
    _slide.animateTo(0);
    _hide?.cancel();
    if (!_holding) _hide = Timer(const Duration(milliseconds: MotionTokens.fastScrollerHideDelayMs), _sleep);
  }

  void _sleep() {
    if (!mounted || _holding) return;
    _shown.animateTo(0);
    _slide.animateTo(1);
  }

  void _press(double y, double track) {
    _holding = true;
    _held.animateTo(1);
    if (widget.marks.isNotEmpty) _expand.animateTo(1);
    _wake();
    _drag(y, track);
  }

  void _drag(double y, double track) {
    final f = FastScrollerGeometry.fractionAt(y, track);
    _fraction.value = f;
    final p = widget.controller.position;
    // A jump, not a scroll: the chrome seam above stays still.
    ChromeSeam.jump(context, () => widget.controller.jumpTo(f * p.maxScrollExtent));
    widget.onJumped?.call();
    final lines = (widget.itemCount / math.max(1, widget.columns)).ceil();
    final line = FastScrollerGeometry.lineAt(f, lines);
    final mark = FastScrollerGeometry.markAt(widget.marks, line * widget.columns);
    if (mark != _markIndex) {
      _markIndex = mark;
      if (mark >= 0) {
        _pulse.snapTo(1.03);
        _pulse.animateTo(1);
      }
      setState(() {});
    }
  }

  void _release() {
    _holding = false;
    _held.animateTo(0);
    _expand.animateTo(0);
    _wake();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return LayoutBuilder(
      builder: (context, constraints) {
        final track = math.max(0.0, constraints.maxHeight - widget.topInset - widget.bottomInset);
        return Stack(
          clipBehavior: Clip.none,
          children: [
            // A layout-time move (a grid keeping its place as its columns
            // change) is no scroll: the handle follows it here.
            NotificationListener<ScrollMetricsNotification>(
              onNotification: (n) {
                if (n.depth == 0 && !_holding && n.metrics.maxScrollExtent > 0) {
                  _fraction.value = (n.metrics.pixels / n.metrics.maxScrollExtent).clamp(0.0, 1.0);
                }
                return false;
              },
              child: widget.child,
            ),
            Positioned(
              top: widget.topInset,
              bottom: widget.bottomInset,
              right: 0,
              width: 240,
              child: AnimatedBuilder(
                animation: Listenable.merge([
                  _shown.animation,
                  _slide.animation,
                  _held.animation,
                  _expand.animation,
                  _pulse.animation,
                  _fraction,
                ]),
                builder: (context, _) {
                  final shown = _shown.value.clamp(0.0, 1.0);
                  if (shown <= 0.001 && !_holding) return const SizedBox.shrink();
                  final top = FastScrollerGeometry.thumbTop(_fraction.value, track);
                  final held = _held.value.clamp(0.0, 1.0);
                  final expand = _expand.value.clamp(0.0, 1.0);
                  final mark = _markIndex >= 0 && _markIndex < widget.marks.length ? widget.marks[_markIndex] : null;
                  return Stack(
                    clipBehavior: Clip.none,
                    children: [
                      if (expand > 0.001 && widget.marks.isNotEmpty)
                        Positioned(
                          right: FastScrollerGeometry.thumbWidth + 4,
                          top: 0,
                          bottom: 0,
                          child: Opacity(
                            opacity: expand,
                            child: _TickColumn(
                              marks: widget.marks,
                              itemCount: widget.itemCount,
                              columns: widget.columns,
                              track: track,
                            ),
                          ),
                        ),
                      if (expand > 0.001 && mark != null)
                        Positioned(
                          right: FastScrollerGeometry.thumbWidth + 4 + 44 + 8,
                          top: top + FastScrollerGeometry.thumbHeight / 2 - 28,
                          child: Opacity(
                            opacity: expand,
                            child: Transform.scale(
                              scale: (0.6 + 0.4 * expand) * _pulse.value,
                              alignment: Alignment.centerRight,
                              child: Container(
                                constraints: const BoxConstraints(minWidth: 56, minHeight: 56),
                                padding: const EdgeInsets.symmetric(horizontal: 16),
                                alignment: Alignment.center,
                                decoration: ShapeDecoration(
                                  color: colors.primaryContainer,
                                  shape: const StadiumBorder(),
                                ),
                                child: Text(
                                  mark.bubble,
                                  style: text.titleMedium?.copyWith(color: colors.onPrimaryContainer),
                                ),
                              ),
                            ),
                          ),
                        ),
                      Positioned(
                        right: 0,
                        top: top - (FastScrollerGeometry.touchHeight - FastScrollerGeometry.thumbHeight) / 2,
                        child: Transform.translate(
                          offset: Offset(MotionTokens.fastScrollerHideSlide * _slide.value, 0),
                          child: Opacity(
                            opacity: shown,
                            child: Listener(
                              behavior: HitTestBehavior.opaque,
                              onPointerDown: (e) => _press(e.localPosition.dy + top - 8, track),
                              onPointerMove: (e) => _drag(e.position.dy - _trackTopGlobal(context), track),
                              onPointerUp: (_) => _release(),
                              onPointerCancel: (_) => _release(),
                              child: SizedBox(
                                width: FastScrollerGeometry.touchWidth,
                                height: FastScrollerGeometry.touchHeight,
                                child: Align(
                                  alignment: Alignment.centerRight,
                                  child: CustomPaint(
                                    size: const Size(FastScrollerGeometry.thumbWidth, FastScrollerGeometry.thumbHeight),
                                    painter: _ThumbPainter(
                                      Color.lerp(colors.secondaryContainer, colors.primary, held)!,
                                      Color.lerp(colors.onSecondaryContainer, colors.onPrimary, held)!,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }

  double _trackTopGlobal(BuildContext context) {
    final box = context.findRenderObject() as RenderBox?;
    return (box?.localToGlobal(Offset.zero).dy ?? 0) + widget.topInset;
  }
}

class _TickColumn extends StatelessWidget {
  const _TickColumn({required this.marks, required this.itemCount, required this.columns, required this.track});

  final List<FastScrollMark> marks;
  final int itemCount;
  final int columns;
  final double track;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelSmall?.copyWith(color: colors.onSurfaceVariant);
    final lines = (itemCount / math.max(1, columns)).ceil();
    final lastLine = math.max(1, lines - 1);
    final ticks = <(double, String)>[];
    String? lastLabel;
    for (final m in marks) {
      final line = m.startIndex ~/ math.max(1, columns);
      final y = FastScrollerGeometry.thumbTop(line / lastLine, track) + FastScrollerGeometry.thumbHeight / 2;
      if (m.tick == lastLabel) continue; // merged runs share one tick
      if (ticks.isNotEmpty && y - ticks.last.$1 < FastScrollerGeometry.minTickGap) continue;
      ticks.add((y, m.tick));
      lastLabel = m.tick;
    }
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHigh,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
      ),
      child: SizedBox(
        width: 44,
        child: Stack(
          children: [
            for (final (y, label) in ticks)
              Positioned(
                left: 0,
                right: 0,
                top: y - 8,
                child: Text(label, textAlign: TextAlign.center, maxLines: 1, style: style),
              ),
          ],
        ),
      ),
    );
  }
}

class _ThumbPainter extends CustomPainter {
  _ThumbPainter(this.fill, this.ink);

  final Color fill;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    final r = Radius.circular(size.width / 2);
    canvas.drawRRect(RRect.fromRectAndCorners(Offset.zero & size, topLeft: r, bottomLeft: r), Paint()..color = fill);
    final stroke = Paint()
      ..color = ink
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    final cx = size.width / 2 + 1;
    final cy = size.height / 2;
    // Two small chevrons: half-width 4, height 3, 5 from the centre.
    canvas.drawPath(
      Path()
        ..moveTo(cx - 4, cy - 5 + 1.5)
        ..lineTo(cx, cy - 5 - 1.5)
        ..lineTo(cx + 4, cy - 5 + 1.5)
        ..moveTo(cx - 4, cy + 5 - 1.5)
        ..lineTo(cx, cy + 5 + 1.5)
        ..lineTo(cx + 4, cy + 5 - 1.5),
      stroke,
    );
  }

  @override
  bool shouldRepaint(_ThumbPainter old) => old.fill != fill || old.ink != ink;
}
