import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../nowplaying/haptics.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../settings_rows.dart' show SpringFade;
import 'appearance_strings.dart';
import 'screen_corners.dart';

/// The corner bar's value at [x] across a bar [width] × [height]: whole dp,
/// 0–64; the ends sit half a bar-height in (the fill's round start).
double screenCornerValueAt(double x, double width, double height) {
  final f = ((x - height / 2) / math.max(1, width - height)).clamp(0.0, 1.0);
  return (ScreenCornerTokens.min + f * (ScreenCornerTokens.max - ScreenCornerTokens.min)).roundToDouble();
}

/// Which haptic a step onto [value] gets (ScreenCornerBar): a click landing on
/// the display's own value, a threshold at either end, a firmer tick every
/// 8dp, a light tick otherwise.
enum ScreenCornerStep { reported, end, segment, tick }

ScreenCornerStep screenCornerStepFor(double value, double? reported) {
  if (reported != null && value == reported.roundToDouble()) return ScreenCornerStep.reported;
  if (value == ScreenCornerTokens.min || value == ScreenCornerTokens.max) return ScreenCornerStep.end;
  if (value.round() % ScreenCornerTokens.segmentTick == 0) return ScreenCornerStep.segment;
  return ScreenCornerStep.tick;
}

void _stepHaptic(ScreenCornerStep step) => switch (step) {
  ScreenCornerStep.reported => YoinHaptics.contextClick(),
  ScreenCornerStep.end => YoinHaptics.confirm(),
  ScreenCornerStep.segment => YoinHaptics.tick(),
  ScreenCornerStep.tick => YoinHaptics.lightTick(),
};

/// The screen-corner control as the landing's step shows it (ScreenCornerControl.kt;
/// Settings has a page of its own): the value, "Match screen", the corner bar
/// and its hint. While the bar moves, a line traces the window's edge with
/// the chosen corner, so it can be matched to the glass by eye; it fades a
/// moment after the finger lifts.
class ScreenCornerControl extends ConsumerStatefulWidget {
  const ScreenCornerControl({super.key});

  @override
  ConsumerState<ScreenCornerControl> createState() => _ScreenCornerControlState();
}

class _ScreenCornerControlState extends ConsumerState<ScreenCornerControl> {
  final _trace = ValueNotifier<bool>(false);
  bool _dragging = false;
  Timer? _linger;
  OverlayEntry? _entry;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // The nearest overlay spanning the window (the landing's own).
      final overlay = Overlay.maybeOf(context);
      if (overlay == null) return;
      _entry = OverlayEntry(builder: (_) => ScreenCornerTrace(visible: _trace));
      overlay.insert(_entry!);
    });
  }

  @override
  void dispose() {
    _linger?.cancel();
    _entry?.remove();
    _entry?.dispose();
    _trace.dispose();
    super.dispose();
  }

  /// The trace stays up while the finger moves and for a moment after; each change restarts that moment.
  void _show() {
    _trace.value = true;
    _linger?.cancel();
    if (!_dragging) _linger = Timer(ScreenCornerTokens.traceLinger, () => _trace.value = false);
  }

  @override
  Widget build(BuildContext context) {
    final corners = ref.watch(screenCornersProvider);
    final notifier = ref.read(screenCornersProvider.notifier);
    return ScreenCornerSlider(
      value: corners.radius,
      following: corners.following,
      reported: corners.device,
      onValueChange: (v) {
        notifier.select(v, persist: false);
        _show();
      },
      onInteractionChange: (active) {
        _dragging = active;
        if (!active) notifier.commit();
        _show();
      },
      onFollow: () {
        notifier.select(null);
        _show();
      },
    );
  }
}

/// What [ScreenCornerControl] shows, without its state: the value (or "Match
/// screen · N dp"), the "Match screen" reset, the corner bar and its hint.
class ScreenCornerSlider extends StatelessWidget {
  const ScreenCornerSlider({
    super.key,
    required this.value,
    required this.following,
    required this.reported,
    required this.onValueChange,
    required this.onInteractionChange,
    required this.onFollow,
    this.showHint = true,
  });

  final double value;
  final bool following;
  final double? reported;
  final ValueChanged<double> onValueChange;
  final ValueChanged<bool> onInteractionChange;
  final VoidCallback onFollow;
  final bool showHint;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        ScreenCornerValueRow(value: value, following: following, onFollow: onFollow),
        const SizedBox(height: ScreenCornerTokens.sliderGap),
        ScreenCornerBar(
          value: value,
          reported: reported,
          onValueChange: onValueChange,
          onInteractionChange: onInteractionChange,
        ),
        if (showHint)
          Padding(
            padding: const EdgeInsets.only(top: ScreenCornerTokens.sliderGap + ScreenCornerTokens.hintTop),
            child: Text(
              AppearanceStrings.of(context).screenCornersHint,
              style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
      ],
    );
  }
}

/// "Match screen · N dp" or "Screen corners", and the reset while the user's
/// own value is in use. Fixed height: "Match screen" (a 48dp target)
/// appearing mid-drag must not move the bar.
class ScreenCornerValueRow extends StatelessWidget {
  const ScreenCornerValueRow({super.key, required this.value, required this.following, required this.onFollow});

  final double value;
  final bool following;
  final VoidCallback onFollow;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final strings = AppearanceStrings.of(context);
    return SizedBox(
      height: ScreenCornerTokens.valueRowHeight,
      child: Row(
        children: [
          Expanded(
            child: Padding(
              padding: const EdgeInsetsDirectional.only(start: ScreenCornerTokens.valueRowStart),
              child: Text(
                following ? strings.screenCornersSummaryFollow(value.round()) : strings.screenCorners,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium!.copyWith(
                  color: theme.colorScheme.onSurface,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
          ),
          IgnorePointer(
            ignoring: following,
            child: ExcludeSemantics(
              excluding: following,
              child: SpringFade(
                visible: !following,
                child: TextButton(
                  onPressed: () {
                    YoinHaptics.confirm();
                    onFollow();
                  },
                  child: Text(strings.screenCornersFollow),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// The corner bar, drawn like the rating bar (a full-round track, the primary
/// fill from the start, the value inside it) but in whole dp, 0–64. The
/// fill's head is a round knob at 0 and squares off as the value grows; a dot
/// marks what the display reports. A tap sets the value too; a vertical
/// swipe passes to the page.
class ScreenCornerBar extends StatefulWidget {
  const ScreenCornerBar({
    super.key,
    required this.value,
    required this.reported,
    required this.onValueChange,
    required this.onInteractionChange,
  });

  final double value;
  final double? reported;
  final ValueChanged<double> onValueChange;
  final ValueChanged<bool> onInteractionChange;

  @override
  State<ScreenCornerBar> createState() => _ScreenCornerBarState();
}

class _ScreenCornerBarState extends State<ScreenCornerBar> with SingleTickerProviderStateMixin {
  late final SpringValue _shown = SpringValue(vsync: this, initial: _fraction, spring: YoinSprings.fastSpatial);
  double? _last;

  double get _fraction =>
      ((widget.value - ScreenCornerTokens.min) / (ScreenCornerTokens.max - ScreenCornerTokens.min)).clamp(0.0, 1.0);

  @override
  void didUpdateWidget(ScreenCornerBar old) {
    super.didUpdateWidget(old);
    if (old.value == widget.value) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _shown.snapTo(_fraction);
    } else {
      _shown.animateTo(_fraction);
    }
  }

  @override
  void dispose() {
    _shown.dispose();
    super.dispose();
  }

  void _apply(double x, Size size) {
    final v = screenCornerValueAt(x, size.width, size.height);
    if (v == _last) return;
    if (_last != null) _stepHaptic(screenCornerStepFor(v, widget.reported));
    _last = v;
    widget.onValueChange(v);
  }

  void _nudge(double by) {
    final v = (widget.value.roundToDouble() + by).clamp(ScreenCornerTokens.min, ScreenCornerTokens.max);
    widget.onInteractionChange(true);
    widget.onValueChange(v);
    widget.onInteractionChange(false);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = AppearanceStrings.of(context);
    final value = widget.value.round();
    return Semantics(
      slider: true,
      label: strings.screenCornersSliderCd,
      value: strings.screenCornersValue(value),
      increasedValue: strings.screenCornersValue(math.min(value + 1, ScreenCornerTokens.max.round())),
      decreasedValue: strings.screenCornersValue(math.max(value - 1, ScreenCornerTokens.min.round())),
      onIncrease: () => _nudge(1),
      onDecrease: () => _nudge(-1),
      child: SizedBox(
        height: ScreenCornerTokens.barHeight,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final size = Size(constraints.maxWidth, ScreenCornerTokens.barHeight);
            return GestureDetector(
              behavior: HitTestBehavior.opaque,
              // A tap (not a swipe the page took): set the value there.
              onTapUp: (d) {
                widget.onInteractionChange(true);
                YoinHaptics.click();
                _last = null;
                _apply(d.localPosition.dx, size);
                widget.onInteractionChange(false);
              },
              onHorizontalDragStart: (d) {
                _last = null;
                widget.onInteractionChange(true);
                _apply(d.localPosition.dx, size);
              },
              onHorizontalDragUpdate: (d) => _apply(d.localPosition.dx, size),
              onHorizontalDragEnd: (_) => widget.onInteractionChange(false),
              onHorizontalDragCancel: () => widget.onInteractionChange(false),
              child: AnimatedBuilder(
                animation: _shown.animation,
                builder: (context, _) {
                  final f = _shown.value.clamp(0.0, 1.0);
                  // The value turns onPrimary as the fill reaches it.
                  final cross =
                      ((f - ScreenCornerTokens.barValueCrossFrom) / (1 - ScreenCornerTokens.barValueCrossFrom)).clamp(
                        0.0,
                        1.0,
                      );
                  return CustomPaint(
                    painter: ScreenCornerBarPainter(
                      fraction: f,
                      reported: widget.reported,
                      track: colors.surfaceContainerHighest,
                      fill: colors.primary,
                      dotCovered: colors.onPrimary.withValues(alpha: 0.7),
                      dot: colors.onSurfaceVariant.withValues(alpha: 0.6),
                    ),
                    child: Align(
                      alignment: AlignmentDirectional.centerEnd,
                      child: Padding(
                        padding: const EdgeInsetsDirectional.only(end: ScreenCornerTokens.barValueEnd),
                        child: ExcludeSemantics(
                          child: Text(
                            strings.screenCornersValue(value),
                            maxLines: 1,
                            style: theme.textTheme.titleMedium!.copyWith(
                              color: Color.lerp(colors.onSurfaceVariant, colors.onPrimary, cross),
                              fontFeatures: const [FontFeature.tabularFigures()],
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            );
          },
        ),
      ),
    );
  }
}

/// The bar's track, its fill (round at the start, its head squaring off as
/// the corner grows) and the display's own value as a dot low in the bar.
class ScreenCornerBarPainter extends CustomPainter {
  const ScreenCornerBarPainter({
    required this.fraction,
    required this.reported,
    required this.track,
    required this.fill,
    required this.dotCovered,
    required this.dot,
  });

  final double fraction;
  final double? reported;
  final Color track;
  final Color fill;
  final Color dotCovered;
  final Color dot;

  /// The fill's right edge for [fraction] on a bar [size].
  static double fillWidth(Size size, double fraction) => size.height + (size.width - size.height) * fraction;

  @override
  void paint(Canvas canvas, Size size) {
    final h = size.height;
    final full = Radius.circular(h / 2);
    canvas.drawRRect(RRect.fromRectAndRadius(Offset.zero & size, full), Paint()..color = track);
    final width = fillWidth(size, fraction);
    final head = Radius.circular((h / 2) * (1 - ScreenCornerTokens.barHeadSquare * fraction));
    canvas.drawRRect(
      RRect.fromLTRBAndCorners(0, 0, width, h, topLeft: full, bottomLeft: full, topRight: head, bottomRight: head),
      Paint()..color = fill,
    );
    final r = reported;
    if (r == null) return;
    final rf = ((r - ScreenCornerTokens.min) / (ScreenCornerTokens.max - ScreenCornerTokens.min)).clamp(0.0, 1.0);
    final x = h / 2 + (size.width - h) * rf;
    final covered = x < width - h * ScreenCornerTokens.reportedDotCover;
    canvas.drawCircle(
      // Low in the bar, under the value's text when the two meet.
      Offset(x, h - ScreenCornerTokens.reportedDotLift),
      ScreenCornerTokens.reportedDot,
      Paint()..color = covered ? dotCovered : dot,
    );
  }

  @override
  bool shouldRepaint(ScreenCornerBarPainter old) =>
      old.fraction != fraction ||
      old.reported != reported ||
      old.track != track ||
      old.fill != fill ||
      old.dotCovered != dotCovered ||
      old.dot != dot;
}

/// A line around the whole window at the screen corner, on the root overlay
/// (above every page, never clipped by a scrolling list, never taking a
/// touch), fading on the effects spring as [visible] flips.
class ScreenCornerTrace extends ConsumerStatefulWidget {
  const ScreenCornerTrace({super.key, required this.visible});

  final ValueListenable<bool> visible;

  @override
  ConsumerState<ScreenCornerTrace> createState() => _ScreenCornerTraceState();
}

class _ScreenCornerTraceState extends ConsumerState<ScreenCornerTrace> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    widget.visible.addListener(_sync);
  }

  @override
  void dispose() {
    widget.visible.removeListener(_sync);
    _alpha.dispose();
    super.dispose();
  }

  void _sync() {
    final target = widget.visible.value ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _alpha.snapTo(target);
    } else {
      _alpha.animateTo(target);
    }
  }

  @override
  Widget build(BuildContext context) {
    final radius = ref.watch(screenCornerRadiusProvider);
    final color = Theme.of(context).colorScheme.primary;
    return IgnorePointer(
      child: ExcludeSemantics(
        child: AnimatedBuilder(
          animation: _alpha.animation,
          builder: (context, _) => CustomPaint(
            size: Size.infinite,
            painter: ScreenCornerTracePainter(radius: radius, color: color, alpha: _alpha.value.clamp(0.0, 1.0)),
          ),
        ),
      ),
    );
  }
}

/// The trace: its stroke inside the edge, so its outer side follows the corner exactly.
class ScreenCornerTracePainter extends CustomPainter {
  const ScreenCornerTracePainter({required this.radius, required this.color, required this.alpha});

  final double radius;
  final Color color;
  final double alpha;

  @override
  void paint(Canvas canvas, Size size) {
    if (alpha <= 0) return;
    const half = ScreenCornerTokens.traceStroke / 2;
    final rect = (Offset.zero & size).deflate(half);
    final r = math.max(0.0, radius - half);
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, Radius.circular(r)),
      Paint()
        ..isAntiAlias = true
        ..style = PaintingStyle.stroke
        ..strokeWidth = ScreenCornerTokens.traceStroke
        ..color = color.withValues(alpha: color.a * alpha),
    );
  }

  @override
  bool shouldRepaint(ScreenCornerTracePainter old) => old.radius != radius || old.color != color || old.alpha != alpha;
}
