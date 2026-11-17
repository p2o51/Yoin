/// The seams' bottom field around the floating bar (SeamBarField.kt,
/// seamDissolveViewport's remainingPx / background, dissolve-final §1.2):
/// graphics near the bar break into a halftone print anchored to the item —
/// they start to open 20 above the bar, finish the approach behind it and
/// run on under and beside it as a still lattice to the screen's bottom
/// edge; dots whose lightness is close to the bar's give way next to it.
/// Text passes under the bar untouched (it is never inside a [SeamDissolve]).
///
/// Three parts:
///  - [SeamBarField], one per window (AppChrome): where the bar is, its
///    corner and colour, and the field's presence, which fades with the bar
///    (Now Playing rising over it, a bar-less page sending it away).
///  - [SeamBarSource]: the bar's surface reports itself, only inside the
///    window chrome's [SeamBarSlot] (a demo bar in Tips never does).
///  - [SeamFieldViewport]: a scroll container that takes the field (Home,
///    Library, the detail pages): its followers, its page colour and how far
///    it can still scroll, read by every [SeamDissolve] below it.
///
/// The field looks the same in every Scroll edge style; it composes with the
/// style's top seam (the Dots print, the Cookie lip, or under the tide).
library;

import 'dart:math' as math;

import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../../home/widgets/seam.dart' show SeamFlow;
import '../../../theme/motion.dart';
import '../../../theme/tokens.dart';
import 'chrome_seam.dart' show SeamBackground;
import 'seam_halftone.dart' show seamDisorder;

/// Where this window's floating bar sits, for the seams' bottom field. The
/// bar reports its rounded rectangle (window coordinates) and its container
/// colour; changes reach listeners after the frame that made them, so a
/// report from paint never repaints anything mid-paint.
class SeamBarField extends ChangeNotifier {
  SeamBarField({required TickerProvider vsync})
    : _reveal = SpringValue(vsync: vsync, initial: 0, spring: YoinSprings.defaultEffects) {
    _reveal.animation.addListener(notifyListeners);
  }

  final SpringValue _reveal;

  /// The bar's rounded rectangle in the window, unclipped.
  Rect get bounds => _bounds;
  Rect _bounds = Rect.zero;

  double get cornerRadius => _cornerRadius;
  double _cornerRadius = 0;

  /// The bar's container colour; null until it reports one.
  Color? get containerColor => _containerColor;
  Color? _containerColor;

  /// The window's height: the screen's bottom edge for the field.
  double get rootHeight => _rootHeight;
  double _rootHeight = 0;

  /// A bar is laid out and painted in this window.
  bool get attached => _owner != null;
  Object? _owner;

  /// The window's chrome wants the bar on screen (false while Now Playing covers it).
  bool get shown => _shown;
  bool _shown = true;
  set shown(bool value) {
    if (value == _shown) return;
    _shown = value;
    _changed();
  }

  /// Reduced motion: the presence snaps instead of fading.
  bool reduced = false;

  /// Field presence, 0..1: follows [attached] and [shown] on the effects spring
  /// (whole or none within the spring's settling tolerance).
  double get reveal {
    final v = _reveal.value;
    return v >= 0.999 ? 1 : (v <= 0.001 ? 0 : v);
  }

  bool _pending = false;
  bool _disposed = false;

  /// [token]'s bar sits at [bounds] (window) with [cornerRadius] in [color], in a window [rootHeight] tall.
  void place(
    Object token, {
    required Rect bounds,
    required double cornerRadius,
    required Color color,
    required double rootHeight,
  }) {
    if (_owner != null && !identical(_owner, token)) return;
    final changed =
        _owner == null ||
        bounds != _bounds ||
        cornerRadius != _cornerRadius ||
        color != _containerColor ||
        rootHeight != _rootHeight;
    _owner = token;
    _bounds = bounds;
    _cornerRadius = cornerRadius;
    _containerColor = color;
    _rootHeight = rootHeight;
    if (changed) _changed();
  }

  /// [token]'s bar left the window.
  void release(Object token) {
    if (!identical(_owner, token)) return;
    _owner = null;
    _changed();
  }

  void _changed() {
    if (_pending || _disposed) return;
    _pending = true;
    SchedulerBinding.instance
      ..addPostFrameCallback((_) => _flush())
      ..ensureVisualUpdate();
  }

  void _flush() {
    _pending = false;
    if (_disposed) return;
    final target = attached && _shown ? 1.0 : 0.0;
    if (reduced) {
      _reveal.snapTo(target);
    } else {
      _reveal.animateTo(target);
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _reveal.animation.removeListener(notifyListeners);
    _reveal.dispose();
    super.dispose();
  }
}

/// Provides the window's [SeamBarField] to the viewports below.
class SeamBarFieldScope extends InheritedWidget {
  const SeamBarFieldScope({super.key, required this.field, required super.child});

  final SeamBarField field;

  static SeamBarField? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SeamBarFieldScope>()?.field;

  @override
  bool updateShouldNotify(SeamBarFieldScope old) => old.field != field;
}

/// Marks the window chrome's bar: a [SeamBarSource] below reports to [field].
class SeamBarSlot extends InheritedWidget {
  const SeamBarSlot({super.key, required this.field, required super.child});

  final SeamBarField field;

  static SeamBarField? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SeamBarSlot>()?.field;

  @override
  bool updateShouldNotify(SeamBarSlot old) => old.field != field;
}

/// The bar's opaque surface: reports its box, [cornerRadius] and [color] as
/// the window's bar, when it sits in the chrome's [SeamBarSlot].
class SeamBarSource extends SingleChildRenderObjectWidget {
  const SeamBarSource({super.key, required this.cornerRadius, required this.color, super.child});

  final double cornerRadius;
  final Color color;

  @override
  RenderSeamBarSource createRenderObject(BuildContext context) =>
      RenderSeamBarSource(field: SeamBarSlot.maybeOf(context), cornerRadius: cornerRadius, color: color);

  @override
  void updateRenderObject(BuildContext context, RenderSeamBarSource renderObject) {
    renderObject
      ..field = SeamBarSlot.maybeOf(context)
      ..cornerRadius = cornerRadius
      ..color = color;
  }
}

class RenderSeamBarSource extends RenderProxyBox {
  RenderSeamBarSource({SeamBarField? field, required double cornerRadius, required Color color})
    : _field = field,
      _cornerRadius = cornerRadius,
      _color = color;

  final Object _token = Object();

  SeamBarField? _field;
  set field(SeamBarField? value) {
    if (identical(value, _field)) return;
    _field?.release(_token);
    _field = value;
    markNeedsPaint();
  }

  double _cornerRadius;
  set cornerRadius(double value) {
    if (value == _cornerRadius) return;
    _cornerRadius = value;
    markNeedsPaint();
  }

  Color _color;
  set color(Color value) {
    if (value == _color) return;
    _color = value;
    markNeedsPaint();
  }

  @override
  void detach() {
    _field?.release(_token);
    super.detach();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    super.paint(context, offset);
    final field = _field;
    if (field == null || !hasSize) return;
    RenderObject root = this;
    while (root.parent != null) {
      root = root.parent!;
    }
    final rootHeight = root is RenderView ? root.size.height : (root as RenderBox).size.height;
    field.place(
      _token,
      bounds: localToGlobal(Offset.zero) & size,
      cornerRadius: math.min(_cornerRadius, size.shortestSide / 2),
      color: _color,
      rootHeight: rootHeight,
    );
  }
}

/// One viewport's side of the field: its followers, page colour and how far
/// it can still scroll, plus the window's bar. Read at paint time by the
/// [SeamDissolve]s below; notifies when any of them moves.
class SeamFieldController extends ChangeNotifier {
  SeamFieldController();

  SeamFlow? _flow;
  SeamFlow? get flow => _flow;
  set flow(SeamFlow? value) {
    if (identical(value, _flow)) return;
    _flow?.removeListener(notifyListeners);
    _flow = value;
    _flow?.addListener(notifyListeners);
  }

  SeamBarField? _bar;
  SeamBarField? get bar => _bar;
  set bar(SeamBarField? value) {
    if (identical(value, _bar)) return;
    _bar?.removeListener(notifyListeners);
    _bar = value;
    _bar?.addListener(notifyListeners);
  }

  /// The page colour the field eases toward (退色), as a gradient over the window.
  SeamBackground? background;

  /// How far the content can still scroll toward its end (the approach shrinks over the last 40).
  double Function() remaining = _endless;

  /// Reduced motion: no stretch, no disorder, no afterglow.
  bool reduced = false;

  static double _endless() => double.infinity;

  double get stretch => reduced ? 0 : (_flow?.stretch ?? 0);
  double get disorder => reduced ? 0 : seamDisorder(_flow?.speedDp ?? 0);
  double get lagPx => reduced ? 0 : (_flow?.lagPx ?? 0);

  /// The field may touch anything now: a bar is (partly) in and the page colour is known.
  bool get active {
    final bar = _bar;
    return bar != null && background != null && bar.reveal > 0.001 && !bar.bounds.isEmpty && bar.rootHeight > 0;
  }

  /// Whether an element whose bottom is at [bottom] (window) is close enough to the window's bottom to watch.
  bool watches(double bottom) => active && bottom > _bar!.rootHeight - SeamTokens.fieldWatch;

  @override
  void dispose() {
    _flow?.removeListener(notifyListeners);
    _bar?.removeListener(notifyListeners);
    super.dispose();
  }
}

/// Provides a viewport's [SeamFieldController] to the [SeamDissolve]s below.
class SeamFieldScope extends InheritedWidget {
  const SeamFieldScope({super.key, required this.controller, required super.child});

  final SeamFieldController controller;

  static SeamFieldController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<SeamFieldScope>()?.controller;

  @override
  bool updateShouldNotify(SeamFieldScope old) => old.controller != controller;
}

/// Marks a scroll container as taking the bottom field (Android
/// seamDissolveViewport with remainingPx and background): graphics below
/// it ([SeamDissolve]) break into the field near the window's bar. [flow]
/// is the viewport's followers, [background] its page colour and
/// [remaining] how far it can still scroll. Without a bar in the window
/// (or with the bar in its edge capsules) nothing changes.
class SeamFieldViewport extends StatefulWidget {
  const SeamFieldViewport({
    super.key,
    required this.flow,
    required this.background,
    required this.remaining,
    required this.child,
  });

  final SeamFlow flow;
  final SeamBackground background;
  final double Function() remaining;
  final Widget child;

  @override
  State<SeamFieldViewport> createState() => _SeamFieldViewportState();
}

class _SeamFieldViewportState extends State<SeamFieldViewport> {
  final SeamFieldController _controller = SeamFieldController();

  @override
  void dispose() {
    _controller
      ..flow = null
      ..bar = null
      ..dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bar = SeamBarFieldScope.maybeOf(context);
    final reduced = MediaQuery.disableAnimationsOf(context);
    bar?.reduced = reduced;
    _controller
      ..flow = widget.flow
      ..bar = bar
      ..background = widget.background
      ..remaining = widget.remaining
      ..reduced = reduced;
    return SeamFieldScope(controller: _controller, child: widget.child);
  }
}
