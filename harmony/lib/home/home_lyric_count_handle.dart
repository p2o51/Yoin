/// Lyric Cards' count handle (Android ui/home/edit/HomeLyricCountHandle.kt,
/// owner 2026-11-08): the same ⌟ as the row resize, on the plate's
/// bottom-end corner, but held and pulled down it opens a column of dots
/// beside the corner, like a thinking-effort picker. Still held, sliding down
/// adds a card and up takes one away, two to four (a tick at each); letting
/// go keeps it as one Undo step (a confirm when it changed). ↑ / ↓ step it
/// for a keyboard. Hidden from the reader, as on Android.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'home_metrics.dart';
import 'home_rows_handle.dart';
import 'home_section.dart';
import 'home_strings.dart';
import 'lyric_highlight_view_model.dart';

/// The handle's key in tests.
const Key homeLyricCountHandleKey = ValueKey('home-lyric-count-handle');

/// The count under the finger, [dy] below where it landed (the dots open over
/// the first [HomeLyricCardTokens.countReveal]); [anchor] is the travel at which
/// they finished opening: from there whole steps of [HomeLyricCardTokens.countStep]
/// (toward zero) add or take a card, so the pull that opens them never counts.
int lyricCountAt({required int start, required double dy, required double anchor}) =>
    (start + ((dy - anchor) / HomeLyricCardTokens.countStep).truncate()).clamp(
      lyricHighlightMinCards,
      lyricHighlightMaxCards,
    );

/// The haptics the handle plays (tests count them).
abstract interface class HomeLyricCountFeedback {
  /// The dots crossing to another count (the resize's tick).
  void segmentTick();

  /// A drop that changed the count.
  void confirm();
}

final class _SystemFeedback implements HomeLyricCountFeedback {
  const _SystemFeedback();
  @override
  void segmentTick() => YoinHaptics.segmentTick();
  @override
  void confirm() => YoinHaptics.confirm();
}

/// The handle's drag, owned by Home: the handle's own recognizer grabs a
/// finger on its box, and Home's feed recognizer the strip of it below the
/// section's box ([grabAt]), where the section can't hit-test it.
class HomeLyricCountEngine extends ChangeNotifier {
  HomeLyricCountEngine({
    required TickerProvider vsync,
    required this.editing,
    required this.blocked,
    required this.saved,
    required this.commit,
    required this.reduced,
    this.feedback = const _SystemFeedback(),
  }) : reveal = SpringValue(vsync: vsync, initial: 0, spring: YoinSprings.defaultSpatial),
       hot = SpringValue(vsync: vsync, initial: 0, spring: YoinSprings.defaultEffects);

  /// Edit mode is on (input is live).
  final bool Function() editing;

  /// Something else holds the page (a section carried, a row resize).
  final bool Function() blocked;

  /// The count saved in the layout now.
  final int Function() saved;

  /// Saves [preset]; true when it changed the layout.
  final bool Function(HomeRowPreset preset) commit;
  final bool Function() reduced;
  final HomeLyricCountFeedback feedback;

  /// 0 closed, 1 open; follows the finger down, springs shut on release.
  final SpringValue reveal;

  /// The ⌟'s held look (primary, grown).
  final SpringValue hot;

  bool _held = false;
  bool get held => _held;

  int _count = lyricHighlightMaxCards;

  /// The count under the finger while held.
  int get count => _count;

  int _start = lyricHighlightMaxCards;
  double _downY = 0;
  double? _anchor;
  bool Function(Offset globalPosition)? _hit;

  void attachHandle(bool Function(Offset globalPosition) hit) => _hit = hit;

  void detachHandle(bool Function(Offset globalPosition) hit) {
    if (_hit == hit) _hit = null;
  }

  /// A finger landed at global [position] on the feed: takes it when it is on the handle.
  bool grabAt(Offset position) => !_held && (_hit?.call(position) ?? false) && grab(position.dy);

  /// The handle is pressed at global y [fingerY]; false when edit mode can't take it.
  bool grab(double fingerY) {
    if (_held || !editing() || blocked()) return false;
    _start = saved().clamp(lyricHighlightMinCards, lyricHighlightMaxCards);
    _count = _start;
    _downY = fingerY;
    _anchor = null;
    _held = true;
    _spring(hot, 1);
    notifyListeners();
    return true;
  }

  void dragTo(double fingerY) {
    if (!_held) return;
    final dy = fingerY - _downY;
    final open = (dy / HomeLyricCardTokens.countReveal).clamp(0.0, 1.0);
    reveal.snapTo(math.max(reveal.value, open));
    if (reveal.value >= 1 || open >= 1) {
      final anchor = _anchor ??= dy;
      final next = lyricCountAt(start: _start, dy: dy, anchor: anchor);
      if (next != _count) {
        _count = next;
        feedback.segmentTick();
      }
    }
    notifyListeners();
  }

  /// The finger left; [cancelled] (another gesture took it) keeps the count as it was, and so
  /// does edit mode having closed under the finger (the app went to the background).
  void release({bool cancelled = false}) {
    if (!_held) return;
    final opened = reveal.value >= 1;
    _held = false;
    if (!cancelled && opened && editing() && commit(lyricPresetFor(_count))) feedback.confirm();
    _spring(hot, 0);
    _spring(reveal, 0);
    notifyListeners();
  }

  /// ↑ / ↓ from a keyboard: one card fewer or more, saved at once.
  bool step(int delta) {
    if (!editing() || _held) return false;
    final next = (saved() + delta).clamp(lyricHighlightMinCards, lyricHighlightMaxCards);
    final changed = commit(lyricPresetFor(next));
    if (changed) feedback.confirm();
    return changed;
  }

  void _spring(SpringValue value, double target) => reduced() ? value.snapTo(target) : value.animateTo(target);

  @override
  void dispose() {
    reveal.dispose();
    hot.dispose();
    super.dispose();
  }
}

/// The handle: a [HomeRowsMetrics.handleTouch] box whose bottom-end corner is
/// the plate's, and the dots' picker beside it while held. [progress] is edit
/// mode's (0..1).
class HomeLyricCountHandle extends StatefulWidget {
  const HomeLyricCountHandle({super.key, required this.engine, required this.progress});

  final HomeLyricCountEngine engine;
  final double progress;

  @override
  State<HomeLyricCountHandle> createState() => _HomeLyricCountHandleState();
}

class _HomeLyricCountHandleState extends State<HomeLyricCountHandle> {
  final FocusNode _focus = FocusNode(debugLabel: 'home-lyric-count-handle');
  final GlobalKey _box = GlobalKey(debugLabel: 'home-lyric-count-box');

  @override
  void initState() {
    super.initState();
    widget.engine.attachHandle(_hit);
  }

  @override
  void didUpdateWidget(HomeLyricCountHandle oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.engine != widget.engine) {
      oldWidget.engine.detachHandle(_hit);
      widget.engine.attachHandle(_hit);
    }
  }

  @override
  void dispose() {
    widget.engine.detachHandle(_hit);
    _focus.dispose();
    super.dispose();
  }

  bool get _live => widget.progress > 0.5;

  bool _hit(Offset position) {
    if (!mounted || !_live) return false;
    final box = _box.currentContext?.findRenderObject();
    if (box is! RenderBox || !box.attached || !box.hasSize) return false;
    return (Offset.zero & box.size).contains(box.globalToLocal(position));
  }

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    final delta = switch (event.logicalKey) {
      LogicalKeyboardKey.arrowUp => -1,
      LogicalKeyboardKey.arrowDown => 1,
      _ => 0,
    };
    if (delta == 0) return KeyEventResult.ignored;
    return widget.engine.step(delta) ? KeyEventResult.handled : KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final e = widget.progress.clamp(0.0, 1.0);
    final colors = Theme.of(context).colorScheme;
    final engine = widget.engine;
    return ExcludeSemantics(
      child: IgnorePointer(
        ignoring: !_live,
        child: Opacity(
          opacity: e,
          child: Stack(
            clipBehavior: Clip.none,
            children: [
              Focus(
                focusNode: _focus,
                canRequestFocus: _live,
                onKeyEvent: _onKey,
                child: MouseRegion(
                  cursor: SystemMouseCursors.resizeUpDown,
                  child: RawGestureDetector(
                    key: homeLyricCountHandleKey,
                    behavior: HitTestBehavior.opaque,
                    gestures: {
                      HomeRowsGrabRecognizer: GestureRecognizerFactoryWithHandlers<HomeRowsGrabRecognizer>(
                        () => HomeRowsGrabRecognizer(debugOwner: this),
                        (r) => r
                          ..onDown = ((position, _) => engine.grab(position.dy))
                          ..onMove = ((y, _) => engine.dragTo(y))
                          ..onUp = ((_) => engine.release())
                          ..onCancel = () => engine.release(cancelled: true),
                      ),
                    },
                    child: SizedBox.square(
                      key: _box,
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
                            hot: engine.hot.animation,
                            pulse: const AlwaysStoppedAnimation(1),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              // A tall pill just before the corner, its bottom level with the handle's, so it
              // stands over this section's own cards and never covers the next section.
              PositionedDirectional(
                end: HomeRowsMetrics.handleTouch + HomeLyricCardTokens.countPickerGap,
                bottom: 0,
                child: ListenableBuilder(
                  listenable: Listenable.merge([engine, engine.reveal.animation]),
                  builder: (context, _) {
                    final r = engine.reveal.value.clamp(0.0, 1.0);
                    // Shut (a settled spring stops within its tolerance of 0).
                    if (!engine.held && r < 0.001) return const SizedBox.shrink();
                    return Opacity(
                      opacity: r,
                      child: Transform.scale(
                        scale:
                            HomeLyricCardTokens.countPickerScaleFrom +
                            (1 - HomeLyricCardTokens.countPickerScaleFrom) * r,
                        alignment: AlignmentDirectional.bottomEnd.resolve(Directionality.of(context)),
                        child: HomeLyricCountPicker(count: engine.count),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The dots, top to bottom from two to four cards to match the finger: every
/// dot up to the count filled, the count's own one larger, its label at the
/// top, in a rounded bar taller than it is wide (LyricCountPicker).
class HomeLyricCountPicker extends StatelessWidget {
  const HomeLyricCountPicker({super.key, required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    // A painted shadow, not Material's elevation: PhysicalShape leaves dark wedges at rounded corners on HarmonyOS.
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHighest,
        shadows: kElevationToShadow[HomeLyricCardTokens.countPickerElevation],
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(HomeLyricCardTokens.countPickerRadius)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: HomeLyricCardTokens.countPickerPaddingH,
          vertical: HomeLyricCardTokens.countPickerPaddingV,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              HomeStrings.of(context).editLyricCards(count),
              maxLines: 1,
              style: Theme.of(context).textTheme.labelMedium!.copyWith(color: colors.onSurface),
            ),
            const SizedBox(height: HomeLyricCardTokens.countPickerSpacing),
            for (var n = lyricHighlightMinCards; n <= lyricHighlightMaxCards; n++)
              SizedBox(
                height: HomeLyricCardTokens.countDotPitch,
                child: Center(
                  child: _CountDot(current: n == count, on: n <= count),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _CountDot extends StatefulWidget {
  const _CountDot({required this.current, required this.on});

  final bool current;
  final bool on;

  @override
  State<_CountDot> createState() => _CountDotState();
}

class _CountDotState extends State<_CountDot> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.current ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _tone = SpringValue(
    vsync: this,
    initial: widget.on ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _size;
    _tone;
  }

  @override
  void didUpdateWidget(_CountDot oldWidget) {
    super.didUpdateWidget(oldWidget);
    final reduced = MediaQuery.disableAnimationsOf(context);
    for (final (value, target) in [(_size, widget.current ? 1.0 : 0.0), (_tone, widget.on ? 1.0 : 0.0)]) {
      reduced ? value.snapTo(target) : value.animateTo(target);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _tone.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _tone.animation]),
      builder: (context, _) {
        final side =
            HomeLyricCardTokens.countDot +
            (HomeLyricCardTokens.countDotCurrent - HomeLyricCardTokens.countDot) * _size.value;
        return Container(
          width: side,
          height: side,
          decoration: ShapeDecoration(
            color: Color.lerp(colors.outlineVariant, colors.primary, _tone.value.clamp(0.0, 1.0)),
            shape: const CircleBorder(),
          ),
        );
      },
    );
  }
}

/// For tests: a feedback that only counts.
@visibleForTesting
final class CountingLyricCountFeedback implements HomeLyricCountFeedback {
  int ticks = 0;
  int confirms = 0;
  @override
  void segmentTick() => ticks++;
  @override
  void confirm() => confirms++;
}
