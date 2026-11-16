/// Home's cold-start intro (Android ui/home/HomeGreetingTitle.kt, owner
/// 2026-10-09, prototype artifact GWEBwdavGiwS1ngqmcqTyF): on a cold start the
/// Yoin mark blooms where the title sits — each arm springing open a beat
/// after the last, with a little overshoot — then spins away while the
/// greeting and "Home" rise in behind it. Returning to Home, a rotation or a
/// rebuilt shell never replays it (once per process). Reduced motion: the two
/// lines are simply there.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/yoin_mark.dart';

/// Plays once per process; [running] is true while the bloom plays (the
/// Memories bubble, which speaks from the same place, waits for it).
abstract final class HomeIntroLatch {
  static bool played = false;
  static final ValueNotifier<bool> running = ValueNotifier(false);

  /// Tests start each case as a cold start.
  @visibleForTesting
  static void reset({bool played = false}) {
    HomeIntroLatch.played = played;
    running.value = false;
  }
}

/// [greeting] over [title], with the cold-start bloom. Only the intro's own
/// alpha and rise are applied here; the header keeps its edit crossfade.
class HomeIntroTitle extends StatefulWidget {
  const HomeIntroTitle({super.key, required this.greeting, required this.title});

  final Widget greeting;
  final Widget title;

  @override
  State<HomeIntroTitle> createState() => _HomeIntroTitleState();
}

class _HomeIntroTitleState extends State<HomeIntroTitle> with TickerProviderStateMixin {
  late final bool _plays = !HomeIntroLatch.played;
  late final List<SpringValue> _arms;
  late final SpringValue _exit;
  late final SpringValue _fade;
  late final SpringValue _greeting;
  late final SpringValue _title;
  late bool _markShown = _plays;
  Ticker? _clock;
  int _stage = 0;
  bool _started = false;

  @override
  void initState() {
    super.initState();
    final rest = _plays ? 0.0 : 1.0;
    final bloom = YoinSprings.of(HomeIntroTokens.bloom);
    _arms = [for (var i = 0; i < 3; i++) SpringValue(vsync: this, initial: rest, spring: bloom)];
    _exit = SpringValue(vsync: this, initial: rest, spring: YoinSprings.defaultSpatial);
    _fade = SpringValue(vsync: this, initial: rest, spring: YoinSprings.defaultEffects);
    _greeting = SpringValue(vsync: this, initial: rest, spring: YoinSprings.defaultSpatial);
    _title = SpringValue(vsync: this, initial: rest, spring: YoinSprings.defaultSpatial);
    _title.animation.addListener(_onTitle);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started || !_plays) return;
    _started = true;
    HomeIntroLatch.played = true;
    if (MediaQuery.disableAnimationsOf(context)) {
      // Reduce motion: the two lines are simply there.
      for (final v in [..._arms, _exit, _fade, _greeting, _title]) {
        v.snapTo(1);
      }
      _markShown = false;
      return;
    }
    HomeIntroLatch.running.value = true;
    _clock = createTicker(_onClock)..start();
  }

  /// The schedule, on frames (no timers): the arms' stagger, the exit, the two lines.
  void _onClock(Duration elapsed) {
    final ms = elapsed.inMilliseconds;
    const stagger = HomeIntroTokens.armStaggerMs;
    const exitAt = HomeIntroTokens.exitAtMs;
    const greetingAt = exitAt + HomeIntroTokens.greetingAfterExitMs;
    const titleAt = greetingAt + HomeIntroTokens.titleAfterGreetingMs;
    while (_stage < 3 && ms >= _stage * stagger) {
      _arms[_stage].animateTo(1);
      _stage++;
    }
    if (_stage == 3 && ms >= exitAt) {
      _exit.animateTo(1);
      _fade.animateTo(1);
      _stage++;
    }
    if (_stage == 4 && ms >= greetingAt) {
      _greeting.animateTo(1);
      _stage++;
    }
    if (_stage == 5 && ms >= titleAt) {
      _title.animateTo(1);
      _stage++;
      _clock?.stop();
    }
  }

  /// The title has risen: the mark is gone, the intro is over.
  void _onTitle() {
    if (!_markShown || _stage < 6 || (_title.value - 1).abs() > 0.002) return;
    setState(() => _markShown = false);
    HomeIntroLatch.running.value = false;
  }

  @override
  void dispose() {
    if (_markShown && _started) HomeIntroLatch.running.value = false;
    _clock?.dispose();
    _title.animation.removeListener(_onTitle);
    for (final v in [..._arms, _exit, _fade, _greeting, _title]) {
      v.dispose();
    }
    super.dispose();
  }

  Widget _rise(SpringValue v, double distance, Widget child) {
    if (!_plays) return child;
    return AnimatedBuilder(
      animation: v.animation,
      builder: (context, child) {
        final p = v.value;
        return Opacity(
          opacity: p.clamp(0.0, 1.0),
          child: Transform.translate(offset: Offset(0, (1 - p) * distance), child: child),
        );
      },
      child: child,
    );
  }

  @override
  Widget build(BuildContext context) {
    final lines = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        _rise(_greeting, HomeIntroTokens.greetingRise, widget.greeting),
        _rise(_title, HomeIntroTokens.titleRise, widget.title),
      ],
    );
    if (!_markShown) return lines;
    final scheme = Theme.of(context).colorScheme;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return Stack(
      alignment: AlignmentDirectional.centerStart,
      clipBehavior: Clip.none,
      children: [
        lines,
        PositionedDirectional(
          start: HomeIntroTokens.markOffsetX,
          child: IgnorePointer(
            child: SizedBox.square(
              dimension: HomeIntroTokens.markSize,
              child: AnimatedBuilder(
                animation: Listenable.merge([for (final a in _arms) a.animation, _exit.animation, _fade.animation]),
                builder: (context, _) {
                  final e = _exit.value;
                  final s = 1 - HomeIntroTokens.exitShrink * e;
                  return Opacity(
                    opacity: (1 - _fade.value).clamp(0.0, 1.0),
                    child: Transform(
                      alignment: Alignment.center,
                      transform: Matrix4.identity()
                        ..translateByDouble((rtl ? 1 : -1) * HomeIntroTokens.exitShift * e, 0, 0, 1)
                        ..rotateZ(HomeIntroTokens.exitTurn * e * math.pi / 180)
                        ..scaleByDouble(s, s, 1, 1),
                      child: YoinMark(
                        transforms: [
                          for (final arm in _arms)
                            YoinArmTransform(
                              scale: math.max(0, arm.value),
                              rotationDeg: (1 - arm.value) * HomeIntroTokens.armStartTurn,
                            ),
                        ],
                        // Arm roles as in the launcher mark: tertiary, primary, secondary.
                        colors: [scheme.tertiary, scheme.primary, scheme.secondary],
                        lineColor: Colors.white,
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ),
      ],
    );
  }
}
