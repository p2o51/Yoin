/// The tip page's demo phone (Android ui/tips/TipDemoPhone.kt). Its screen is
/// laid out at a real phone's size ([TipTokens.screenWidth] wide) out of the
/// app's own components, then drawn scaled down to the 168 × 300 frame — so
/// every component is the one in the app, at its true proportions. The scale
/// is applied to painting and hit testing alike, so a press lands on
/// whatever is drawn under it.
///
/// A touch dot acts the gesture out on a loop — tap (a ripple), hold (its
/// ring fills), drag — and the phone answers the way the app does. The
/// user's first touch takes the phone over; left alone for
/// [TipTokens.takeoverIdleMs], the demo starts again. It runs only while
/// `playing` (its page is the settled one); reduced motion keeps the phone
/// still but still pressable.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/corner_mask.dart';

/// Where the scene's named parts are, for the demo touch to go to.
class DemoTargets {
  final GlobalKey _root = GlobalKey(debugLabel: 'demo-screen');
  final Map<String, GlobalKey> _parts = {};

  GlobalKey _key(String name) => _parts.putIfAbsent(name, () => GlobalKey(debugLabel: 'demo:$name'));

  /// The point ([fx], [fy]) of part [name] (0..1 across it), moved by
  /// ([dx], [dy]) of the screen's own units, as fractions of the screen.
  /// Null until laid out.
  Offset? fraction(String name, {double fx = 0.5, double fy = 0.5, double dx = 0, double dy = 0}) {
    final screen = _root.currentContext?.findRenderObject();
    final part = _parts[name]?.currentContext?.findRenderObject();
    if (screen is! RenderBox || part is! RenderBox || !screen.attached || !part.attached) return null;
    if (!screen.hasSize || !part.hasSize) return null;
    final origin = part.localToGlobal(Offset.zero, ancestor: screen);
    final w = math.max(screen.size.width, 1.0);
    final h = math.max(screen.size.height, 1.0);
    return Offset((origin.dx + part.size.width * fx + dx) / w, (origin.dy + part.size.height * fy + dy) / h);
  }

  /// As [fraction], for the first element under the screen that [test]
  /// picks (a real component's own key, or a line of text by its words):
  /// the scene needs no hook inside a shared component.
  Offset? fractionWhere(bool Function(Element element) test, {double fx = 0.5, double fy = 0.5}) {
    final screenElement = _root.currentContext;
    final screen = screenElement?.findRenderObject();
    if (screenElement is! Element || screen is! RenderBox || !screen.attached || !screen.hasSize) return null;
    RenderBox? found;
    void visit(Element element) {
      if (found != null) return;
      if (test(element)) {
        final box = element.findRenderObject();
        if (box is RenderBox && box.attached && box.hasSize) {
          found = box;
          return;
        }
      }
      element.visitChildElements(visit);
    }

    screenElement.visitChildElements(visit);
    final part = found;
    if (part == null) return null;
    final origin = part.localToGlobal(Offset.zero, ancestor: screen);
    return Offset(
      (origin.dx + part.size.width * fx) / math.max(screen.size.width, 1.0),
      (origin.dy + part.size.height * fy) / math.max(screen.size.height, 1.0),
    );
  }
}

/// The element of a widget keyed [key].
bool demoKeyed(Element element, Key key) => element.widget.key == key;

/// The element of a line of text reading [words] (Text or RichText).
bool demoText(Element element, String words) {
  final widget = element.widget;
  if (widget is Text) return (widget.data ?? widget.textSpan?.toPlainText()) == words;
  if (widget is RichText) return widget.text.toPlainText() == words;
  return false;
}

class _DemoTargetsScope extends InheritedWidget {
  const _DemoTargetsScope({required this.targets, required super.child});

  final DemoTargets targets;

  @override
  bool updateShouldNotify(_DemoTargetsScope old) => old.targets != targets;
}

/// Names [child] [name] for the demo touch. Outside a demo phone it is just [child].
class DemoTarget extends StatelessWidget {
  const DemoTarget(this.name, {super.key, required this.child});

  final String name;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final targets = context.dependOnInheritedWidgetOfExactType<_DemoTargetsScope>()?.targets;
    if (targets == null) return child;
    return KeyedSubtree(key: targets._key(name), child: child);
  }
}

/// Thrown into a script when the demo stops (the page moved on, the user
/// took the phone, the page closed); the loop swallows it.
class DemoStopped implements Exception {
  const DemoStopped();
}

/// The demo's touch: where it is (fractions of the phone), whether it is
/// down, its ripple and its hold ring. Every step of a script awaits it, and
/// a stopped demo throws [DemoStopped] out of the next step.
class DemoFinger {
  DemoFinger._(TickerProvider vsync, this._targets)
    : x = AnimationController.unbounded(vsync: vsync, value: 0.5),
      y = AnimationController.unbounded(vsync: vsync, value: 0.5),
      alpha = AnimationController(vsync: vsync),
      press = AnimationController.unbounded(vsync: vsync),
      ring = AnimationController(vsync: vsync),
      ripple = AnimationController(vsync: vsync, value: 1),
      _dragClock = AnimationController(vsync: vsync);

  final DemoTargets _targets;
  final AnimationController x;
  final AnimationController y;
  final AnimationController alpha;
  final AnimationController press;
  final AnimationController ring;
  final AnimationController ripple;

  /// A drag's own clock (not painted).
  final AnimationController _dragClock;

  int _run = 0;
  bool _stopped = true;

  Listenable get listenable => Listenable.merge([x, y, alpha, press, ring, ripple]);

  void _start(int run) {
    _run = run;
    _stopped = false;
  }

  void _stop() {
    _stopped = true;
    _timer?.cancel();
    final waiting = _waiting;
    _waiting = null;
    if (waiting != null && !waiting.isCompleted) waiting.completeError(const DemoStopped());
    for (final c in [x, y, alpha, press, ring, ripple, _dragClock]) {
      c.stop(canceled: true);
    }
    alpha.value = 0;
    press.value = 0;
    ring.value = 0;
    ripple.value = 1;
  }

  void _dispose() {
    for (final c in [x, y, alpha, press, ring, ripple, _dragClock]) {
      c.dispose();
    }
  }

  void _check(int run) {
    if (_stopped || run != _run) throw const DemoStopped();
  }

  Future<void> _await(TickerFuture future) async {
    final run = _run;
    try {
      await future.orCancel;
    } on TickerCanceled {
      throw const DemoStopped();
    }
    _check(run);
  }

  Future<void> _spring(AnimationController c, double target) =>
      _await(c.animateWith(SpringSimulation(YoinSprings.defaultSpatial, c.value, target, c.velocity)));

  Future<void> _tween(AnimationController c, double target, int ms, {Curve curve = Curves.easeOut}) => _await(
    c.animateTo(
      target,
      duration: Duration(milliseconds: ms),
      curve: curve,
    ),
  );

  Timer? _timer;
  Completer<void>? _waiting;

  /// Waits [ms]; a stopped demo ends the wait at once (no timer outlives it).
  Future<void> wait(int ms) async {
    final run = _run;
    _check(run);
    final waiting = Completer<void>();
    _waiting = waiting;
    _timer = Timer(Duration(milliseconds: ms), () {
      if (identical(_waiting, waiting)) _waiting = null;
      waiting.complete();
    });
    await waiting.future;
    _check(run);
  }

  /// Where part [name] is (see [DemoTargets.fraction]); the phone's middle until it is laid out.
  Offset at(String name, {double fx = 0.5, double fy = 0.5, double dx = 0, double dy = 0}) =>
      _found(_targets.fraction(name, fx: fx, fy: fy, dx: dx, dy: dy), name);

  /// A part the script names is on the screen (a scene that lost one fails its test, not the user's eye).
  Offset _found(Offset? p, Object what) {
    assert(p != null || _stopped, 'The demo phone has no $what on screen');
    return p ?? const Offset(0.5, 0.5);
  }

  /// Goes to part [name] (the dot appears there when it was gone).
  Future<void> moveTo(String name, {double fx = 0.5, double fy = 0.5, double dx = 0, double dy = 0}) {
    final p = at(name, fx: fx, fy: fy, dx: dx, dy: dy);
    return moveToPoint(p.dx, p.dy);
  }

  /// Goes to the widget keyed [key] (a real component's own key).
  Future<void> moveToKey(Key key, {double fx = 0.5, double fy = 0.5}) {
    final p = _found(_targets.fractionWhere((e) => demoKeyed(e, key), fx: fx, fy: fy), key);
    return moveToPoint(p.dx, p.dy);
  }

  /// Goes to the first widget [test] picks (a real component by its type).
  Future<void> moveToWhere(bool Function(Widget widget) test, {double fx = 0.5, double fy = 0.5}) {
    final p = _found(_targets.fractionWhere((e) => test(e.widget), fx: fx, fy: fy), 'widget it looks for');
    return moveToPoint(p.dx, p.dy);
  }

  /// The state of the first [T] on the screen (a real component the demo acts on, as a hold would).
  T? stateOf<T extends State>() {
    T? found;
    void visit(Element element) {
      if (found != null) return;
      if (element is StatefulElement && element.state is T) {
        found = element.state as T;
        return;
      }
      element.visitChildElements(visit);
    }

    final root = _targets._root.currentContext;
    if (root is Element) root.visitChildElements(visit);
    return found;
  }

  /// Goes to the line of text reading [words].
  Future<void> moveToText(String words, {double fx = 0.5, double fy = 0.5}) {
    final p = _found(_targets.fractionWhere((e) => demoText(e, words), fx: fx, fy: fy), '"$words"');
    return moveToPoint(p.dx, p.dy);
  }

  /// Goes to ([fx], [fy]) of the phone, on the spatial spring.
  Future<void> moveToPoint(double fx, double fy) async {
    _check(_run);
    if (alpha.value < 0.05) {
      x.value = fx;
      y.value = fy;
      await _tween(alpha, 1, TipTokens.fingerAppearMs);
    } else {
      await Future.wait([_spring(x, fx), _spring(y, fy)]);
    }
  }

  /// A tap: [action] when the dot is down, then a ripple as it comes up.
  Future<void> tap([VoidCallback? action]) async {
    await _tween(press, 1, TipTokens.fingerTapMs);
    action?.call();
    ripple.value = 0;
    await Future.wait([_tween(ripple, 1, TipTokens.rippleMs), _spring(press, 0)]);
  }

  /// Holds for the long-press timeout (the ring fills — a clock, so linear),
  /// then [action]; [pressed] is told down and up.
  Future<void> hold(VoidCallback action, {ValueChanged<bool>? pressed}) async {
    await _tween(press, 1, TipTokens.fingerHoldPressMs);
    pressed?.call(true);
    ring.value = 0;
    await _tween(ring, 1, TipTokens.holdMs, curve: Curves.linear);
    action();
    ring.value = 0;
    pressed?.call(false);
    await _spring(press, 0);
  }

  /// Down where it is, slides to ([fx], [fy]) over [ms]; [onProgress] gets 0..1 every frame.
  Future<void> drag(double fx, double fy, int ms, {ValueChanged<double>? onProgress, bool release = true}) async {
    await _tween(press, 1, TipTokens.fingerDragPressMs);
    final fromX = x.value;
    final fromY = y.value;
    void step() {
      final t = _dragClock.value;
      x.value = fromX + (fx - fromX) * t;
      y.value = fromY + (fy - fromY) * t;
      onProgress?.call(t);
    }

    _dragClock.value = 0;
    _dragClock.addListener(step);
    try {
      await _tween(_dragClock, 1, ms, curve: Curves.easeInOut);
    } finally {
      _dragClock.removeListener(step);
    }
    if (release) await _spring(press, 0);
  }

  /// Comes up after a [drag] with `release: false`.
  Future<void> release() => _spring(press, 0);

  /// Fades away.
  Future<void> lift() => _tween(alpha, 0, TipTokens.liftMs);

  /// Gone at once (it would sit on a sheet that just came up).
  void hide() {
    _check(_run);
    alpha.value = 0;
    press.value = 0;
    ring.value = 0;
    ripple.value = 1;
  }
}

/// A script: plays the gesture once, putting the scene back at rest first.
typedef DemoScript = Future<void> Function(DemoFinger finger);

/// How much bigger than 168 × 300 the phone is drawn in a [window] this size:
/// a tall window (a tablet held upright) gets a bigger phone, the demo being
/// the page's subject; width keeps it to half the window.
double demoPhoneGrow(Size window, {bool tall = false}) {
  final phoneWidth = tall ? TipTokens.phoneHeight * TipTokens.tallAspect : TipTokens.phoneWidth;
  return math
      .min(
        window.height * TipTokens.phoneHeightShare / TipTokens.phoneHeight,
        window.width * TipTokens.phoneWidthShare / phoneWidth,
      )
      .clamp(1.0, TipTokens.phoneMaxGrow);
}

/// The phone frame's corner in its screen's own units ([TipTokens.screenWidth]
/// wide, drawn [demoPhoneGrow] times 168 wide): what a screen-corner setting
/// would match on this phone.
double demoPhoneScreenCorner(Size window) =>
    TipTokens.phoneCorner * TipTokens.screenWidth / (TipTokens.phoneWidth * demoPhoneGrow(window));

/// A 168 × 300 phone (larger in a tall window) that plays [script] on a loop
/// while [playing] and the user isn't touching it.
class DemoPhone extends StatefulWidget {
  const DemoPhone({
    super.key,
    required this.playing,
    required this.script,
    required this.child,
    this.tall = false,
    this.screenWidth,
  });

  final bool playing;
  final DemoScript script;

  /// A 20:9 phone as tall as the others (narrower), for a scene whose layout
  /// differs on a 16:9 screen (Now Playing's focus).
  final bool tall;

  /// The screen, laid out [TipTokens.screenWidth] wide.
  final Widget child;

  /// Lays the screen out this wide instead (Now Playing's widths come out as
  /// they do on a 20:9 phone, [TipTokens.tallScreenWidth]); the frame keeps its size.
  final double? screenWidth;

  @override
  State<DemoPhone> createState() => _DemoPhoneState();
}

class _DemoPhoneState extends State<DemoPhone> with TickerProviderStateMixin {
  final DemoTargets _targets = DemoTargets();
  late final DemoFinger _finger = DemoFinger._(this, _targets);
  int _run = 0;
  bool _userHas = false;
  bool _reduced = false;
  Timer? _takeover;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (reduced != _reduced) {
      _reduced = reduced;
      _sync();
    }
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _sync();
    });
  }

  @override
  void didUpdateWidget(DemoPhone old) {
    super.didUpdateWidget(old);
    if (old.playing != widget.playing) _sync();
  }

  @override
  void dispose() {
    _takeover?.cancel();
    _run++;
    _finger._stop();
    _finger._dispose();
    super.dispose();
  }

  bool get _shouldPlay => widget.playing && !_userHas && !_reduced;

  void _sync() {
    final run = ++_run;
    _finger._stop();
    if (_shouldPlay) _loop(run);
  }

  Future<void> _loop(int run) async {
    _finger._start(run);
    try {
      await _finger.wait(TipTokens.startDelayMs);
      while (mounted && run == _run) {
        await widget.script(_finger);
        await _finger.lift();
        await _finger.wait(TipTokens.loopGapMs);
      }
    } on DemoStopped {
      // The demo stepped aside.
    }
  }

  void _onUserDown(PointerDownEvent _) {
    if (!_userHas) {
      _userHas = true;
      _sync();
    }
    _takeover?.cancel();
    _takeover = Timer(const Duration(milliseconds: TipTokens.takeoverIdleMs), () {
      if (!mounted) return;
      _userHas = false;
      _sync();
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final phoneWidth = widget.tall ? TipTokens.phoneHeight * TipTokens.tallAspect : TipTokens.phoneWidth;
    final screenWidth = widget.screenWidth ?? (widget.tall ? TipTokens.tallScreenWidth : TipTokens.screenWidth);
    final screenHeight = screenWidth * TipTokens.phoneHeight / phoneWidth;
    final grow = demoPhoneGrow(MediaQuery.sizeOf(context), tall: widget.tall);
    final size = Size(phoneWidth * grow, TipTokens.phoneHeight * grow);
    final corner = BorderRadius.circular(TipTokens.phoneCorner);
    final screen = MediaQuery(
      data: MediaQuery.of(context).copyWith(
        size: Size(screenWidth, screenHeight),
        padding: EdgeInsets.zero,
        viewPadding: EdgeInsets.zero,
        viewInsets: EdgeInsets.zero,
      ),
      child: YoinWindowScope(
        info: YoinWindowInfo(screenWidth, screenHeight),
        child: _DemoTargetsScope(
          targets: _targets,
          child: KeyedSubtree(
            key: _targets._root,
            child: ColoredBox(
              color: colors.surface,
              child: SizedBox(width: screenWidth, height: screenHeight, child: widget.child),
            ),
          ),
        ),
      ),
    );
    return Listener(
      // Watch (never consume) the user's touches: the phone becomes theirs.
      onPointerDown: _onUserDown,
      child: SizedBox.fromSize(
        size: size,
        child: Stack(
          children: [
            // The rounded frame is painted over the square screen, never clipped (CornerMask).
            Positioned.fill(
              child: CornerMask(
                radius: TipTokens.phoneCorner,
                topStart: true,
                topEnd: true,
                bottomStart: true,
                bottomEnd: true,
                fill: colors.surfaceContainerLow,
                child: ColoredBox(
                  color: colors.surface,
                  child: ClipRect(
                    child: FittedBox(fit: BoxFit.fill, alignment: Alignment.topLeft, child: screen),
                  ),
                ),
              ),
            ),
            Positioned.fill(
              child: IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: corner,
                    border: Border.all(color: colors.outlineVariant, width: TipTokens.phoneBorder),
                  ),
                ),
              ),
            ),
            Positioned.fill(
              child: IgnorePointer(child: CustomPaint(painter: _FingerPainter(_finger, colors.primary, grow))),
            ),
          ],
        ),
      ),
    );
  }
}

class _FingerPainter extends CustomPainter {
  _FingerPainter(this.finger, this.color, this.grow) : super(repaint: finger.listenable);

  final DemoFinger finger;
  final Color color;
  final double grow;

  @override
  void paint(Canvas canvas, Size size) {
    final a = finger.alpha.value.clamp(0.0, 1.0);
    if (a <= 0) return;
    final c = Offset(size.width * finger.x.value, size.height * finger.y.value);
    final r = TipTokens.fingerRadius * grow;
    final down = finger.press.value;
    final shrink = 1 - TipTokens.fingerPressShrink * down;
    canvas.drawCircle(c, r * shrink, Paint()..color = color.withValues(alpha: 0.22 * a));
    canvas.drawCircle(c, r * TipTokens.fingerDot * shrink, Paint()..color = color.withValues(alpha: 0.9 * a));
    final ripple = finger.ripple.value;
    if (ripple < 1) {
      canvas.drawCircle(
        c,
        r * (1 + ripple * TipTokens.rippleSpread),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = TipTokens.rippleStroke
          ..color = color.withValues(alpha: (1 - ripple) * 0.5 * a),
      );
    }
    final ring = finger.ring.value;
    if (ring > 0) {
      canvas.drawArc(
        Rect.fromCircle(center: c, radius: r * TipTokens.ringRadius),
        -math.pi / 2,
        2 * math.pi * ring,
        false,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = TipTokens.ringStroke
          ..strokeCap = StrokeCap.round
          ..color = color.withValues(alpha: a),
      );
    }
  }

  @override
  bool shouldRepaint(_FingerPainter old) => old.finger != finger || old.color != color || old.grow != grow;
}
