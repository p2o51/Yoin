/// The row resize handle (HomeRowsHandle.kt; D1 ②, owner H5): a rounded ⌟
/// hugging the plate's bottom-end corner, concentric with it, in
/// onSurfaceVariant. It fades in with edit mode, takes input only while
/// editing, and wins the finger at once, so a press on it never scrolls the
/// feed or lifts the section. Held it turns primary and grows a little; every
/// stop it crosses pulses it (the tick's visual twin). ↑ / ↓ step a stop for a
/// keyboard. Not shown with a single stop. The reader uses the plate's own
/// actions ("More rows", "Fewer rows").
///
/// The ⌟ is the plate's own corner drawn as a stroke (its arc is the plate
/// radius less the inset), the same geometry as Android's HomeRowsGlyph — a
/// piece of the plate, not a symbol.
library;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'home_metrics.dart';
import 'home_rows_resize.dart';
import 'home_section.dart';

/// The handle's key in tests.
Key homeRowsHandleKey(HomeSection section) => ValueKey('home-rows-handle-${section.id}');

/// [section]'s resize handle, a [HomeRowsMetrics.handleTouch] box whose
/// bottom-end corner is the plate's. [progress] is edit mode's (0..1).
class HomeRowsHandle extends StatefulWidget {
  const HomeRowsHandle({
    super.key,
    required this.section,
    required this.engine,
    required this.track,
    required this.progress,
  });

  final HomeSection section;
  final HomeRowsEngine engine;
  final HomeRowsTrack track;
  final double progress;

  @override
  State<HomeRowsHandle> createState() => _HomeRowsHandleState();
}

class _HomeRowsHandleState extends State<HomeRowsHandle> {
  final FocusNode _focus = FocusNode(debugLabel: 'home-rows-handle');

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  bool get _live => widget.progress > 0.5;

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    if (event.logicalKey == LogicalKeyboardKey.arrowUp) {
      return widget.engine.step(widget.section, -1) ? KeyEventResult.handled : KeyEventResult.ignored;
    }
    if (event.logicalKey == LogicalKeyboardKey.arrowDown) {
      return widget.engine.step(widget.section, 1) ? KeyEventResult.handled : KeyEventResult.ignored;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final e = widget.progress.clamp(0.0, 1.0);
    final colors = Theme.of(context).colorScheme;
    final engine = widget.engine;
    final section = widget.section;
    return ExcludeSemantics(
      child: IgnorePointer(
        ignoring: !_live,
        child: Opacity(
          opacity: e,
          child: Focus(
            focusNode: _focus,
            canRequestFocus: _live,
            onKeyEvent: _onKey,
            child: MouseRegion(
              cursor: SystemMouseCursors.resizeUpDown,
              onEnter: (_) => engine.hover(section, HomeRowsMetrics.hoverHot),
              onExit: (_) => engine.hover(section, 0),
              child: RawGestureDetector(
                key: homeRowsHandleKey(section),
                behavior: HitTestBehavior.opaque,
                gestures: {
                  _EagerDragRecognizer: GestureRecognizerFactoryWithHandlers<_EagerDragRecognizer>(
                    () => _EagerDragRecognizer(debugOwner: this),
                    (r) => r
                      ..onDown = ((y, t) => engine.grab(section, y, t))
                      ..onMove = ((y, t) => engine.dragTo(y, t))
                      ..onUp = ((t) => engine.release(timeMs: t))
                      ..onCancel = () => engine.release(cancelled: true),
                  ),
                },
                child: SizedBox.square(
                  dimension: HomeRowsMetrics.handleTouch,
                  child: Padding(
                    padding: const EdgeInsetsDirectional.only(
                      start: HomeRowsMetrics.handleCornerInTouch - HomeRowsMetrics.handleGlyph,
                      top: HomeRowsMetrics.handleCornerInTouch - HomeRowsMetrics.handleGlyph,
                    ),
                    child: Align(
                      alignment: AlignmentDirectional.topStart,
                      child: HomeRowsGlyph(
                        rest: colors.onSurfaceVariant,
                        held: colors.primary,
                        hot: widget.track.hot,
                        pulse: widget.track.pulse,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The ⌟: two strokes meeting at a rounded corner, the corner's arc
/// concentric with the plate's (radius = plate radius − inset, on the
/// stroke's outer edge), round caps. Its outer corner is the box's bottom-end
/// corner. Colour and scale follow [hot] and [pulse] in paint.
class HomeRowsGlyph extends StatelessWidget {
  const HomeRowsGlyph({super.key, required this.rest, required this.held, required this.hot, required this.pulse});

  final Color rest;
  final Color held;
  final Animation<double> hot;
  final Animation<double> pulse;

  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: HomeRowsMetrics.handleGlyph,
    child: CustomPaint(
      painter: _GlyphPainter(
        rest: rest,
        held: held,
        hot: hot,
        pulse: pulse,
        rtl: Directionality.of(context) == TextDirection.rtl,
      ),
    ),
  );
}

class _GlyphPainter extends CustomPainter {
  _GlyphPainter({required this.rest, required this.held, required this.hot, required this.pulse, required this.rtl})
    : super(repaint: Listenable.merge([hot, pulse]));

  final Color rest;
  final Color held;
  final Animation<double> hot;
  final Animation<double> pulse;
  final bool rtl;

  @override
  void paint(Canvas canvas, Size size) {
    final h = hot.value.clamp(0.0, 1.0);
    final scale = (1 + HomeRowsMetrics.heldScale * h) * pulse.value;
    const stroke = HomeRowsMetrics.handleStroke;
    const half = stroke / 2;
    final corner = size.width - half;
    final radius = ((HomeEditMetrics.plateCorner - HomeRowsMetrics.handleInset) - half).clamp(0.0, corner - half);
    canvas.save();
    // Grows from the plate's corner (bottom-end).
    final pivotX = rtl ? 0.0 : size.width;
    canvas.translate(pivotX, size.height);
    canvas.scale(scale);
    canvas.translate(-pivotX, -size.height);
    if (rtl) {
      canvas.translate(size.width, 0);
      canvas.scale(-1, 1);
    }
    final path = Path()
      ..moveTo(corner, half)
      ..lineTo(corner, corner - radius)
      ..arcTo(Rect.fromLTRB(corner - 2 * radius, corner - 2 * radius, corner, corner), 0, 1.5707963267948966, false)
      ..lineTo(half, corner);
    canvas.drawPath(
      path,
      Paint()
        ..color = Color.lerp(rest, held, h)!
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(_GlyphPainter old) =>
      old.rest != rest || old.held != held || old.rtl != rtl || old.hot != hot || old.pulse != pulse;
}

/// A drag that wins the arena the moment the finger lands (when [onDown]
/// takes it): the handle answers 1:1 from the first pixel, and the feed's
/// scroll, the long-press entry and the page tap never see the finger.
class _EagerDragRecognizer extends OneSequenceGestureRecognizer {
  _EagerDragRecognizer({super.debugOwner});

  /// The finger landed at global y [y] (ms [t]); true takes it.
  bool Function(double y, int t)? onDown;
  void Function(double y, int t)? onMove;
  void Function(int t)? onUp;
  VoidCallback? onCancel;

  int? _pointer;
  bool _accepted = false;

  static int _ms(Duration d) => d.inMilliseconds;

  @override
  void addAllowedPointer(PointerDownEvent event) {
    if (_pointer != null) return;
    if (!(onDown?.call(event.position.dy, _ms(event.timeStamp)) ?? false)) return;
    _pointer = event.pointer;
    _accepted = true;
    startTrackingPointer(event.pointer, event.transform);
    resolvePointer(event.pointer, GestureDisposition.accepted);
  }

  @override
  void handleEvent(PointerEvent event) {
    if (event.pointer != _pointer) return;
    if (event is PointerMoveEvent) {
      onMove?.call(event.position.dy, _ms(event.timeStamp));
    } else if (event is PointerUpEvent) {
      _finish(() => onUp?.call(_ms(event.timeStamp)));
      stopTrackingPointer(event.pointer);
    } else if (event is PointerCancelEvent) {
      _finish(() => onCancel?.call());
      stopTrackingPointer(event.pointer);
    }
  }

  void _finish(VoidCallback callback) {
    if (!_accepted) return;
    _accepted = false;
    _pointer = null;
    callback();
  }

  @override
  void rejectGesture(int pointer) {
    // Lost to another recognizer (it never should): let go where it is.
    if (pointer == _pointer) _finish(() => onCancel?.call());
    stopTrackingPointer(pointer);
  }

  @override
  void didStopTrackingLastPointer(int pointer) {
    if (pointer == _pointer) _finish(() => onCancel?.call());
  }

  @override
  void dispose() {
    if (_pointer != null) _finish(() => onCancel?.call());
    super.dispose();
  }

  @override
  String get debugDescription => 'home rows handle drag';
}
