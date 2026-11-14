/// Home's Memories bubble (Android ui/home/HomeMemoryBubble.kt, the
/// Recommended entry): a ⌄ rests at the page's top centre; when there is
/// something to say, a bubble grows out of it over the header's title — a
/// round block on the left (a memory's cover, a tip's symbol) and one or two
/// lines on the right — holds, and flies back into the arrow. A memory hangs
/// from the arrow by a tail; a tip is a plain capsule. When it speaks and
/// when it goes: [MemoryBubbleDirector] / [MemoryBubbleTurn.after].
///
/// Taps: the arrow opens Memories; a memory opens Memories on it; the
/// "pull down Home" tip opens Memories; any other tip just flies away. Any
/// other press anywhere in the window (the bar too), a wheel or trackpad
/// scroll, a pull toward Memories, Now Playing, a pushed page or the app
/// going inactive tucks it away and starts the idle clock over. A finger held
/// on it keeps it. Going to the background puts it away unseen.
///
/// Not ported: Android's Yoin mark that blooms beside the title (Harmony has
/// no YoinMark yet), the camera-cutout placement (Flutter can't read the
/// cutout here: the arrow takes the page's centre), and the accessibility
/// "time to take action" timeout (no Flutter / HarmonyOS API for it).
library;

import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../memories/home_memory_pill.dart';
import '../memories/memories_motion.dart';
import '../memories/memories_reveal_scope.dart';
import '../nowplaying/haptics.dart';
import '../nowplaying/overlay_controller.dart';
import '../shell/shell_state.dart';
import '../shell/yoin_section.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'home_bubble_tips.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_view_model.dart';
import 'home_ui_state.dart' show HomeCardKind;
import 'widgets/cover_palette.dart';
import 'widgets/home_artwork.dart';
import 'memory_bubble_director.dart';
import 'memory_bubble_turn.dart';

double _smoothstep(double e0, double e1, double x) {
  final t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// Flying away, it fades from this far into the flight and is gone by this far.
const double _flyFadeFrom = 0.35;
const double _flyGoneAt = 0.9;

/// Tucked from this far unrolled it flies away; less, it just folds back.
const double _flyAwayFrom = 0.5;

/// Presence below this is tucked away: not drawn.
const double _goneBelow = 0.01;

/// The tail rises over the last stretch of the unroll.
const double _tailRisesFrom = 0.82;

/// How much of the bubble is still seen [fly] into its flight.
double memoryBubbleFlightAlpha(double fly) => 1 - _smoothstep(_flyFadeFrom, _flyGoneAt, fly);

/// How far the chevron's ink is on its way back to rest [fly] into the flight.
double memoryArrowHomecoming(double fly) => _smoothstep(_flyGoneAt, 1, fly);

/// Where the speaking bubble lands, in the header's space.
@immutable
final class MemoryBubbleGeometry {
  const MemoryBubbleGeometry({required this.body, required this.arrow, required this.tailTop});

  final Rect body;

  /// The chevron's centre; the tail's sharp tip is above it at [tailTop].
  final Offset arrow;
  final double tailTop;

  Offset get tip => Offset(arrow.dx, tailTop);

  /// The body at unroll [s]: the chevron's own box at 0, grown out to [body] at 1 (past 1 on the overshoot).
  Rect bodyAt(double s) {
    final start = Rect.fromCenter(center: arrow, width: HomeBubbleTokens.arrowIcon, height: HomeBubbleTokens.arrowIcon);
    return Rect.fromLTRB(
      start.left + (body.left - start.left) * s,
      start.top + (body.top - start.top) * s,
      start.right + (body.right - start.right) * s,
      start.bottom + (body.bottom - start.bottom) * s,
    );
  }
}

/// The header's geometry: the arrow at the page's top centre (or as far left
/// as the bubble allows), the body from the title's place to [HomeBubbleTokens.reach]
/// past the arrow, centred on the header row or below the arrow's tail room.
MemoryBubbleGeometry memoryBubbleGeometry({
  required Size size,
  required double statusTop,
  required double rowTop,
  required double trailingReserve,
}) {
  final arrowY = statusTop + HomeBubbleTokens.arrowBelowTop + HomeBubbleTokens.arrowIcon / 2;
  final (arrowX, _) = memoryArrowCenterX(
    pageCentre: size.width / 2,
    titleLeft: 0,
    freeEnd: size.width - trailingReserve,
    minLeft: HomeBubbleTokens.minLeft,
    reach: HomeBubbleTokens.reach,
  );
  final right = arrowX + HomeBubbleTokens.reach;
  final left = math.max(0.0, right - HomeBubbleTokens.maxWidth);
  final rowCentre = rowTop + (size.height - rowTop) / 2;
  final top = math.max(rowCentre - HomeBubbleTokens.bodyHeight / 2, arrowY + HomeBubbleTokens.chevronAboveBody);
  return MemoryBubbleGeometry(
    body: Rect.fromLTWH(left, top, right - left, HomeBubbleTokens.bodyHeight),
    arrow: Offset(arrowX, arrowY),
    tailTop: arrowY - HomeBubbleTokens.tipAboveChevron,
  );
}

// A quarter circle's cubic handle, as a fraction of its radius.
const double _arcHandle = 0.5523;

// How far along its base each flank keeps level before it rises.
const double _flankSettle = 0.62;

/// The bubble's outline (Android memoryBubbleOutline): a rounded body; with
/// a [tailX], a tail rising from its top to a sharp tip at ([tailX], [tailTop])
/// — two mirrored concave flanks from [flank] either side of it, leaving the
/// tip [lean] out and [drop] down and levelling off into the top edge. A tail
/// with no room (into either corner, or its tip not above the body) is left off.
Path memoryBubbleOutline(
  Rect body,
  double radius, {
  double? tailX,
  double tailTop = 0,
  double flank = HomeBubbleTokens.tailFlank,
  double lean = HomeBubbleTokens.tipLean,
  double drop = HomeBubbleTokens.tipDrop,
}) {
  final path = Path();
  final l = body.left;
  final t = body.top;
  final r = body.right;
  final b = body.bottom;
  final rr = radius.clamp(0.0, math.max(0.0, math.min(body.width, body.height) / 2));
  final h = rr * _arcHandle;
  final flankStart = (tailX ?? 0) - flank;
  final hasTail = tailX != null && flankStart >= l + rr && tailX + flank <= r - rr && tailTop < t;
  path.moveTo(l, t + rr);
  path.cubicTo(l, t + rr - h, l + rr - h, t, l + rr, t);
  if (hasTail) {
    path.lineTo(flankStart, t);
    path.cubicTo(flankStart + flank * _flankSettle, t, tailX - lean, tailTop + drop, tailX, tailTop);
    path.cubicTo(tailX + lean, tailTop + drop, tailX + flank * (1 - _flankSettle), t, tailX + flank, t);
  }
  path.lineTo(r - rr, t);
  path.cubicTo(r - rr + h, t, r, t + rr - h, r, t + rr);
  path.lineTo(r, b - rr);
  path.cubicTo(r, b - rr + h, r - rr + h, b, r - rr, b);
  path.lineTo(l + rr, b);
  path.cubicTo(l + rr - h, b, l, b - rr + h, l, b - rr);
  path.close();
  return path;
}

/// Put around the header's title: it gives way while the bubble speaks over it.
class MemoryBubbleTitle extends StatelessWidget {
  const MemoryBubbleTitle({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<_MemoryBubbleScope>();
    if (scope == null) return child;
    return AnimatedBuilder(
      animation: scope.motion,
      child: child,
      builder: (context, child) => Opacity(opacity: scope.titleAlpha().clamp(0.0, 1.0), child: child),
    );
  }
}

class _MemoryBubbleScope extends InheritedWidget {
  const _MemoryBubbleScope({required this.motion, required this.titleAlpha, required super.child});

  final Listenable motion;
  final double Function() titleAlpha;

  @override
  bool updateShouldNotify(_MemoryBubbleScope oldWidget) => !identical(oldWidget.motion, motion);
}

/// Wraps Home's header ([child]); lays the arrow and the bubble over it.
/// [rowTop] is where the header's row starts (below the status bar);
/// [trailingReserve] the account control's width at the row's end.
class HomeMemoryBubble extends ConsumerStatefulWidget {
  const HomeMemoryBubble({
    super.key,
    required this.rowTop,
    required this.trailingReserve,
    required this.child,
    this.timings = const MemoryBubbleTimings(),
    this.tips = HomeBubbleTip.values,
    this.random,
  });

  final double rowTop;
  final double trailingReserve;
  final Widget child;
  final MemoryBubbleTimings timings;
  final List<HomeBubbleTip> tips;
  final math.Random? random;

  @override
  ConsumerState<HomeMemoryBubble> createState() => _HomeMemoryBubbleState();
}

class _HomeMemoryBubbleState extends ConsumerState<HomeMemoryBubble> with TickerProviderStateMixin {
  late final MemoryBubbleDirector _director;

  /// 0 → 1: the bubble grows out of the arrow (Expressive spatial out, Standard back).
  late final SpringValue _unroll = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// 0 → 1: a spoken bubble flies back into the arrow (slow Standard spatial), then everything resets.
  late final SpringValue _fly = SpringValue(
    vsync: this,
    initial: 0,
    spring: YoinSprings.of(MotionTokens.standardSlowSpatial),
  );

  /// Reduced motion: it fades in place (effects); 1 otherwise.
  late final SpringValue _fade = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  /// 0 = the speech bubble with its tail, 1 = a tip's plain capsule.
  late final SpringValue _capsule = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// A line turning into the next in place: the old rises out, the new rises in (spatial), each fading (effects).
  late final SpringValue _swapMove = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);
  late final SpringValue _swapFade = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  /// The body's colour moving to the new line's cover wash (effects).
  late final SpringValue _fillT = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  Color? _fillFrom;
  Color? _fillTo;
  String? _paletteKey;
  BackdropColors? _palette;

  late final Listenable _springs = Listenable.merge([
    _unroll.animation,
    _fly.animation,
    _fade.animation,
    _capsule.animation,
    _swapMove.animation,
    _swapFade.animation,
    _fillT.animation,
  ]);

  BubbleSay? _shown;
  BubbleSay? _previous;
  int _shownCount = 0;
  bool _wasSpeaking = false;
  bool _reduced = false;

  // What Home's readability is made of.
  bool _lifecycleResumed = true;
  bool _routeCurrent = true;
  bool _homeShown = true;
  NowPlayingOverlayController? _nowPlaying;
  MemoriesRevealController? _reveal;
  ScrollPosition? _scroll;
  AppLifecycleListener? _lifecycle;

  // Presses on the bubble or the arrow: theirs, not a tuck.
  final Map<int, Offset> _ownPointers = {};

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    for (final s in [_unroll, _fly, _fade, _capsule, _swapMove, _swapFade, _fillT]) {
      s.animation.addListener(_onSprings);
      s.animation.addStatusListener(_onSpringStatus);
    }
    _director = MemoryBubbleDirector(
      store: ref.read(memoryBubbleSeenStoreProvider),
      tips: widget.tips,
      timings: widget.timings,
      random: widget.random,
    );
    ref.listenManual(homeMemoryPillProvider, (_, next) => _director.setPill(next), fireImmediately: true);
    _director.addListener(_onDirector);
    final state = WidgetsBinding.instance.lifecycleState;
    _lifecycleResumed = state == null || state == AppLifecycleState.resumed;
    _lifecycle = AppLifecycleListener(onStateChange: _onLifecycle);
    GestureBinding.instance.pointerRouter.addGlobalRoute(_onAnyPointer);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncReadable());
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final nowPlaying = NowPlayingOverlayScope.read(context);
    if (!identical(nowPlaying, _nowPlaying)) {
      _nowPlaying?.removeListener(_syncReadable);
      _nowPlaying = nowPlaying?..addListener(_syncReadable);
    }
    final scroll = Scrollable.maybeOf(context)?.position;
    if (!identical(scroll, _scroll)) {
      _scroll?.removeListener(_syncReadable);
      _scroll = scroll?..addListener(_syncReadable);
    }
  }

  @override
  void dispose() {
    GestureBinding.instance.pointerRouter.removeGlobalRoute(_onAnyPointer);
    _lifecycle?.dispose();
    _nowPlaying?.removeListener(_syncReadable);
    _reveal?.removeListener(_syncReadable);
    _scroll?.removeListener(_syncReadable);
    _director.removeListener(_onDirector);
    _director.dispose();
    for (final s in [_unroll, _fly, _fade, _capsule, _swapMove, _swapFade, _fillT]) {
      s.dispose();
    }
    super.dispose();
  }

  static bool _moving(SpringValue s) => (s.animation as AnimationController).isAnimating;

  // ── Inputs ──

  void _onAnyPointer(PointerEvent event) {
    if (!mounted) return;
    if (event is PointerDownEvent) {
      // The bubble's and the arrow's own Listeners saw it first: theirs.
      if (_ownPointers.containsKey(event.pointer)) return;
      _director.touch();
    } else if (event is PointerScrollEvent || event is PointerPanZoomStartEvent) {
      _director.touch();
    }
  }

  void _onOwnDown(PointerDownEvent event) {
    _ownPointers[event.pointer] = event.position;
    _director.setHeld(true);
  }

  void _onOwnMove(PointerMoveEvent event) {
    final down = _ownPointers[event.pointer];
    if (down == null || (event.position - down).distance <= kTouchSlop) return;
    // A drag that leaves the slop is the feed's (scroll, the pull): it tucks like any other.
    _ownPointers.remove(event.pointer);
    _director.setHeld(_ownPointers.isNotEmpty);
    _director.touch();
  }

  void _onOwnUp(PointerEvent event) {
    _ownPointers.remove(event.pointer);
    _director.setHeld(_ownPointers.isNotEmpty);
  }

  void _onLifecycle(AppLifecycleState state) {
    _lifecycleResumed = state == AppLifecycleState.resumed;
    if (state == AppLifecycleState.hidden || state == AppLifecycleState.paused || state == AppLifecycleState.detached) {
      // Can't be seen: put away at once, no spring left for later.
      _director.backgrounded();
      _snapAway();
    }
    _syncReadable();
  }

  void _syncReadable() {
    if (!mounted) return;
    final revealClosed = (_reveal?.fraction ?? 1) >= 1 - _goneBelow;
    final scrolled = _scroll?.hasPixels ?? false ? _scroll!.pixels : 0.0;
    final readable =
        _lifecycleResumed &&
        _routeCurrent &&
        _homeShown &&
        !(_nowPlaying?.isMounted ?? false) &&
        revealClosed &&
        scrolled <= HomeBubbleTokens.arrowScrollFade;
    _director.setReadable(readable);
  }

  // ── The director's turns ──

  void _onDirector() {
    if (!mounted) return;
    final speaking = _director.speaking;
    final say = _director.say;
    if (_director.sayCount != _shownCount) {
      _shownCount = _director.sayCount;
      final swapping = _wasSpeaking && speaking && _shown != null && _unroll.value > _goneBelow;
      _previous = swapping ? _shown : null;
      _shown = say;
      if (swapping && !_reduced) {
        _swapMove.snapTo(0);
        _swapFade.snapTo(0);
        _swapMove.animateTo(1);
        _swapFade.animateTo(1);
      } else {
        _swapMove.snapTo(1);
        _swapFade.snapTo(1);
      }
      final capsule = say is BubbleSayTip ? 1.0 : 0.0;
      if (_reduced || _unroll.value < _goneBelow) {
        _capsule.snapTo(capsule);
      } else {
        _capsule.animateTo(capsule);
      }
    }
    if (speaking != _wasSpeaking) {
      _wasSpeaking = speaking;
      _settle(speaking);
    }
    _safeSetState();
  }

  void _safeSetState() {
    if (SchedulerBinding.instance.schedulerPhase == SchedulerPhase.persistentCallbacks) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() {});
      });
    } else {
      setState(() {});
    }
  }

  /// Moves the bubble to where [speaking] puts it: out of the arrow, or —
  /// spoken — back into it, or folded back when it had barely begun.
  void _settle(bool speaking) {
    if (_reduced) {
      _fly.snapTo(0);
      if (speaking) {
        if (_unroll.value < 1) {
          _fade.snapTo(0);
          _unroll.snapTo(1);
        }
        _fade.animateTo(1);
      } else {
        _fade.animateTo(0);
      }
      return;
    }
    _fade.snapTo(1);
    if (speaking) {
      // Caught flying away: start over from the arrow.
      if (_fly.value > 0) {
        _fly.snapTo(0);
        _unroll.snapTo(0);
      }
      _unroll.spring = YoinSprings.defaultSpatial;
      _unroll.animateTo(1);
    } else if (_unroll.value >= _flyAwayFrom) {
      _fly.animateTo(1);
    } else {
      _unroll.spring = YoinSprings.standardDefaultSpatial;
      _unroll.animateTo(0);
    }
  }

  void _snapAway() {
    _unroll.snapTo(0);
    _fly.snapTo(0);
    _fade.snapTo(1);
    _swapMove.snapTo(1);
    _swapFade.snapTo(1);
    _previous = null;
    _wasSpeaking = false;
  }

  void _onSprings() => _director.setFullySaid(_fullySaid());

  void _onSpringStatus(AnimationStatus _) {
    if (!_director.speaking) {
      // Landed: exactly at rest, no ghost left at 0.004.
      if (_fly.value >= 1 - 1e-3 && !_moving(_fly)) {
        _unroll.snapTo(0);
        _fly.snapTo(0);
      } else if (_fade.value <= 1e-3 && !_moving(_fade)) {
        _unroll.snapTo(0);
        _fade.snapTo(1);
      } else if (_unroll.value.abs() < 1e-3 && !_moving(_unroll)) {
        _unroll.snapTo(0);
      }
    }
    _director.setFullySaid(_fullySaid());
  }

  bool _fullySaid() {
    final said = memoryBubbleFullySaid(
      unroll: _unroll.value,
      unrolling: _moving(_unroll),
      flyAway: _fly.value,
      flyingAway: _moving(_fly),
    );
    return said && _fade.value >= 1 - 1e-3 && !_moving(_fade);
  }

  // ── Taps ──

  void _open({int? focus}) => ref.read(memoriesRevealHandleProvider).open(focus: focus);

  void _tapArrow() {
    YoinHaptics.click();
    _director.touch();
    _open();
  }

  void _tapBubble() {
    YoinHaptics.click();
    final said = _shown;
    final latest = ref.read(homeMemoryPillProvider).latest;
    _director.touch();
    switch (said) {
      case BubbleSayTip(tip: HomeBubbleTip.pullForMemories):
        _open();
      case BubbleSayTip():
        break; // It just flies away.
      case BubbleSayMemory(latest: final memory):
        _open(focus: (memory ?? latest)?.sessionId);
      case null:
        _open(focus: latest?.sessionId);
    }
  }

  // ── Build ──

  Color _fillTarget(ColorScheme colors) {
    final raised = colors.surfaceContainerHigh;
    final said = _shown;
    if (said is! BubbleSayMemory || said.latest == null) return raised;
    final image = resolveHomeCover(
      ref,
      context,
      said.latest!.coverArt,
      requestSize: HomeCoverRequest.standard,
      side: HomeBubbleTokens.coverSize,
    );
    final key = image?.url;
    if (key != _paletteKey) {
      _paletteKey = key;
      _palette = key == null ? null : CoverPaletteCache.instance.peek(key);
      if (key != null && image != null && _palette == null) {
        CoverPaletteCache.instance.colorsFor(key, image.image).then((colors) {
          if (mounted && _paletteKey == key && colors != null) setState(() => _palette = colors);
        });
      }
    }
    return Color.lerp(raised, _palette?.base ?? colors.secondary, HomeBubbleTokens.coverWash)!;
  }

  @override
  Widget build(BuildContext context) {
    _reduced = MediaQuery.disableAnimationsOf(context);
    final colors = Theme.of(context).colorScheme;
    final homeShown = ref.watch(shellSectionProvider) == YoinSection.home;
    final routeCurrent = ModalRoute.isCurrentOf(context) ?? true;
    final reveal = ref.watch(memoriesRevealHandleProvider).controller;
    if (!identical(reveal, _reveal)) {
      _reveal?.removeListener(_syncReadable);
      _reveal = reveal?..addListener(_syncReadable);
    }
    if (homeShown != _homeShown || routeCurrent != _routeCurrent) {
      // Library in front: the bubble can't be seen there (its ticker is muted), put it away unseen.
      if (!homeShown) _snapAway();
      _homeShown = homeShown;
      _routeCurrent = routeCurrent;
      WidgetsBinding.instance.addPostFrameCallback((_) => _syncReadable());
    }

    final target = _fillTarget(colors);
    if (target != _fillTo) {
      final from = _fillTo == null ? null : Color.lerp(_fillFrom, _fillTo, _fillT.value.clamp(0.0, 1.0));
      _fillFrom = from ?? target;
      _fillTo = target;
      if (from == null || _reduced) {
        _fillT.snapTo(1);
      } else {
        _fillT.snapTo(0);
        _fillT.animateTo(1);
      }
    }

    final statusTop = MediaQuery.viewPaddingOf(context).top;
    final motion = Listenable.merge([_springs, ?reveal, ?_scroll]);
    return _MemoryBubbleScope(
      motion: _springs,
      titleAlpha: () {
        final s = _unroll.value.clamp(0.0, 1.0);
        return 1 - _smoothstep(0, 0.45, s) * (1 - _fly.value.clamp(0.0, 1.0)) * _fade.value.clamp(0.0, 1.0);
      },
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          widget.child,
          Positioned.fill(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final geometry = memoryBubbleGeometry(
                  size: constraints.biggest,
                  statusTop: statusTop,
                  rowTop: widget.rowTop,
                  trailingReserve: widget.trailingReserve,
                );
                return AnimatedBuilder(animation: motion, builder: (context, _) => _layers(context, geometry, colors));
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _layers(BuildContext context, MemoryBubbleGeometry g, ColorScheme colors) {
    final strings = MemoryBubbleStrings.of(context);
    final s = _unroll.value;
    final fly = _fly.value.clamp(0.0, 1.0);
    final fade = _fade.value.clamp(0.0, 1.0);
    final speaking = _director.speaking;
    final composed = speaking || s > _goneBelow;
    final scrolled = _scroll?.hasPixels ?? false ? _scroll!.pixels : 0.0;
    final scrolledAway = scrolled > HomeBubbleTokens.arrowScrollFade;
    final hint = (1 - (_reveal?.fraction ?? 1)).clamp(0.0, 1.0);
    final scrollFade = (1 - scrolled / HomeBubbleTokens.arrowScrollFade).clamp(0.0, 1.0);
    final present = (s.clamp(0.0, 1.0) * (1 - memoryArrowHomecoming(fly)) * fade).clamp(0.0, 1.0);
    final arrowInk = HomeBubbleTokens.arrowRestAlpha + (1 - HomeBubbleTokens.arrowRestAlpha) * math.max(hint, present);
    final fill = Color.lerp(_fillFrom, _fillTo, _fillT.value.clamp(0.0, 1.0)) ?? colors.surfaceContainerHigh;
    final flight = memoryBubbleFlightAlpha(fly);
    final scale = 1 - HomeBubbleTokens.flyShrink * fly;

    return Stack(
      clipBehavior: Clip.none,
      children: [
        if (composed)
          Positioned.fill(
            child: Transform.translate(
              offset: Offset(0, -HomeBubbleTokens.flyLift * fly),
              child: Transform.scale(
                scale: scale,
                alignment: Alignment.topLeft,
                origin: g.tip,
                child: Stack(
                  clipBehavior: Clip.none,
                  children: [
                    Positioned.fill(
                      child: IgnorePointer(
                        child: Opacity(
                          opacity: (_smoothstep(0.04, 0.3, s) * flight * fade).clamp(0.0, 1.0),
                          child: CustomPaint(
                            painter: _BubblePainter(
                              geometry: g,
                              unroll: s,
                              capsule: _capsule.value.clamp(0.0, 1.0),
                              fill: fill,
                            ),
                          ),
                        ),
                      ),
                    ),
                    Positioned.fill(
                      child: IgnorePointer(
                        child: ExcludeSemantics(
                          child: Opacity(
                            opacity: (_smoothstep(0.55, 0.95, s) * flight * fade).clamp(0.0, 1.0),
                            child: ClipPath(
                              clipper: _BodyClip(g.bodyAt(s)),
                              child: Stack(
                                clipBehavior: Clip.none,
                                children: [
                                  Positioned(
                                    left: g.body.left + HomeBubbleTokens.coverInset,
                                    top: g.body.top,
                                    width: math.max(
                                      0,
                                      g.body.width - HomeBubbleTokens.coverInset - HomeBubbleTokens.contentTrail,
                                    ),
                                    height: g.body.height,
                                    child: _lines(context),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        if (speaking)
          Positioned.fromRect(
            rect: g.body,
            child: Semantics(
              button: true,
              label: _description(strings),
              onTap: _tapBubble,
              excludeSemantics: true,
              child: _own(GestureDetector(behavior: HitTestBehavior.opaque, onTap: _tapBubble)),
            ),
          ),
        Positioned(
          left: g.arrow.dx - HomeBubbleTokens.arrowTouch / 2,
          top: g.arrow.dy - HomeBubbleTokens.arrowTouch / 2,
          width: HomeBubbleTokens.arrowTouch,
          height: HomeBubbleTokens.arrowTouch,
          child: IgnorePointer(
            ignoring: scrolledAway,
            child: Semantics(
              button: !scrolledAway,
              label: scrolledAway ? null : strings.arrowCd,
              onTap: scrolledAway ? null : _tapArrow,
              excludeSemantics: true,
              child: _own(
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: _tapArrow,
                  child: Center(
                    child: Transform.translate(
                      offset: Offset(0, hint * HomeBubbleTokens.arrowPullNudge),
                      child: Opacity(
                        opacity: (arrowInk * scrollFade).clamp(0.0, 1.0),
                        child: Icon(
                          YoinSymbols.chevronDown,
                          size: HomeBubbleTokens.arrowIcon,
                          color: colors.onSurfaceVariant,
                        ),
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
  }

  Widget _own(Widget child) => Listener(
    onPointerDown: _onOwnDown,
    onPointerMove: _onOwnMove,
    onPointerUp: _onOwnUp,
    onPointerCancel: _onOwnUp,
    child: child,
  );

  String _description(MemoryBubbleStrings strings) {
    final said = _shown;
    if (said is BubbleSayTip) return said.tip.text(zh: strings.zh);
    final latest = (said as BubbleSayMemory?)?.latest ?? ref.read(homeMemoryPillProvider).latest;
    if (latest == null) return strings.cdMemories;
    final artist = latest.artistName;
    return strings.cdLatest(latest.albumName, artist == null || artist.trim().isEmpty ? null : artist);
  }

  Widget _lines(BuildContext context) {
    final current = _shown;
    if (current == null) return const SizedBox.shrink();
    final previous = _previous;
    final rise = HomeBubbleTokens.bodyHeight / 2;
    final move = _swapMove.value;
    final alpha = _swapFade.value.clamp(0.0, 1.0);
    return Stack(
      alignment: AlignmentDirectional.centerStart,
      children: [
        if (previous != null && alpha < 1)
          Transform.translate(
            offset: Offset(0, -rise * move),
            child: Opacity(
              opacity: 1 - alpha,
              child: _BubbleLine(say: previous),
            ),
          ),
        Transform.translate(
          offset: Offset(0, rise * (1 - move)),
          child: Opacity(
            opacity: alpha,
            child: _BubbleLine(say: current),
          ),
        ),
      ],
    );
  }
}

/// One thing said: a tip (its symbol in a round tile, its title) or a memory
/// (its cover, its album, and — with room — when it was written).
class _BubbleLine extends ConsumerWidget {
  const _BubbleLine({required this.say});

  final BubbleSay say;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = MemoryBubbleStrings.of(context);
    final say = this.say;
    if (say is BubbleSayTip) {
      return Row(
        children: [
          Container(
            width: HomeBubbleTokens.coverSize,
            height: HomeBubbleTokens.coverSize,
            decoration: BoxDecoration(color: colors.secondaryContainer, shape: BoxShape.circle),
            alignment: Alignment.center,
            child: Icon(say.tip.icon, size: HomeBubbleTokens.tipIconSize, color: colors.onSecondaryContainer),
          ),
          const SizedBox(width: HomeBubbleTokens.coverTitleGap),
          Flexible(
            child: Text(
              say.tip.title(zh: strings.zh),
              style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w500, color: colors.onSurface),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      );
    }
    final latest = (say as BubbleSayMemory).latest ?? ref.watch(homeMemoryPillProvider).latest;
    final headline = text.titleMedium?.copyWith(fontWeight: FontWeight.w600, color: colors.onSurface);
    if (latest == null) {
      return Padding(
        padding: const EdgeInsetsDirectional.only(start: HomeBubbleTokens.coverTitleGap),
        child: Text(strings.title, style: headline?.copyWith(color: colors.onSurfaceVariant), maxLines: 1),
      );
    }
    final image = resolveHomeCover(
      ref,
      context,
      latest.coverArt,
      requestSize: HomeCoverRequest.standard,
      side: HomeBubbleTokens.coverSize,
    );
    final written = latest.lastWrittenAt;
    final ago = written == null
        ? null
        : HomeStrings.of(
            context,
          ).timeAgo(timeAgoOf(nowMs: ref.read(homeClockProvider)().millisecondsSinceEpoch, timestampMs: written));
    return LayoutBuilder(
      builder: (context, constraints) {
        final room = constraints.maxWidth - HomeBubbleTokens.coverSize - HomeBubbleTokens.coverTitleGap;
        final wide = room >= HomeBubbleTokens.saysMoreFrom && ago != null;
        return Row(
          children: [
            HomeArtwork(image: image, kind: HomeCardKind.album, size: HomeBubbleTokens.coverSize),
            const SizedBox(width: HomeBubbleTokens.coverTitleGap),
            Flexible(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(latest.albumName, style: headline, maxLines: 1, overflow: TextOverflow.ellipsis),
                  if (wide)
                    Text(
                      ago,
                      style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                ],
              ),
            ),
          ],
        );
      },
    );
  }
}

class _BodyClip extends CustomClipper<Path> {
  _BodyClip(this.body);

  final Rect body;

  @override
  Path getClip(Size size) =>
      Path()..addRRect(RRect.fromRectAndRadius(body, const Radius.circular(HomeBubbleTokens.corner)));

  @override
  bool shouldReclip(_BodyClip oldClipper) => oldClipper.body != body;
}

class _BubblePainter extends CustomPainter {
  _BubblePainter({required this.geometry, required this.unroll, required this.capsule, required this.fill});

  final MemoryBubbleGeometry geometry;
  final double unroll;
  final double capsule;
  final Color fill;

  @override
  void paint(Canvas canvas, Size size) {
    if (unroll < _goneBelow) return;
    final body = geometry.bodyAt(unroll);
    final paint = Paint()..color = fill;
    const corner = HomeBubbleTokens.corner;
    final round = corner + math.max(0.0, body.height / 2 - corner) * capsule;
    canvas.drawPath(memoryBubbleOutline(body, round), paint);
    // The tail rises last, out of the body's top, its point over the chevron —
    // pulled along if the body hasn't reached it yet. A tip has none.
    final rise = _smoothstep(_tailRisesFrom, 1, unroll) * (1 - capsule);
    if (rise <= 0) return;
    final tailX = math.min(geometry.arrow.dx, body.right - HomeBubbleTokens.reach);
    final tail = memoryBubbleOutline(body, corner, tailX: tailX, tailTop: geometry.tailTop);
    canvas.save();
    canvas.clipRect(Rect.fromLTRB(-size.width, -size.height, size.width * 2, body.top + corner));
    canvas.translate(0, (1 - rise) * (body.top - geometry.tailTop));
    canvas.drawPath(tail, paint);
    canvas.restore();
  }

  @override
  bool shouldRepaint(_BubblePainter old) =>
      old.geometry != geometry || old.unroll != unroll || old.capsule != capsule || old.fill != fill;
}
