/// Two columns' resize (parity p4 C2; Android HomeSpanResize.kt and HomeGridUnits.kt HomeUnitDrag; iOS
/// HomeGridResize.swift; design.md §主页「两栏」). Every section's ⌟ drags both ways in two columns, and the first
/// direction a drag takes locks it ([homeHandleAxis]). Sideways is the span: a full row dragged left past the
/// midpoint becomes a half and pulls the row after it up beside it; a left half dragged right (a right half
/// left) past it becomes full, its partner on its own row after it. While the finger is down the block's width
/// follows it 1:1 (a rubber band a little under a half) and a dashed outline shows the width it will snap to;
/// on release it springs there and the change is written (one Undo step), or springs back when there is
/// nothing to pair with. Down and up is the units: the row's height follows the finger over the pair's overlap
/// range (24dp of rubber band past either end), ticking at each count, and springs to the nearest on release.
/// ← / → and ↑ / ↓ on a focused handle do the same at once; the reader gets Half width / Full width and an
/// adjustable count on the handle.
library;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart' show CustomSemanticsAction;
import 'package:flutter/services.dart';

import '../data/home/home_edit_rules.dart';
import '../data/home/home_grid_rules.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import 'home_metrics.dart';
import 'home_rows_handle.dart' show HomeRowsGlyph, HomeRowsGrabRecognizer;
import 'home_section.dart';
import 'home_strings.dart';

/// The handle's key in tests.
Key homeGridHandleKey(HomeSection section) => ValueKey('home-grid-handle-${section.id}');

/// Where a section sits in two columns, for its ⌟ ([HomeSpanInfo] and the row's units).
@immutable
final class HomeGridPlace {
  const HomeGridPlace({
    required this.section,
    required this.row,
    required this.span,
    required this.hasNext,
    required this.units,
    required this.range,
    required this.fullWidth,
    required this.halfWidth,
  });

  final HomeSection section;
  final HomeFeedRow row;
  final HomeSpan span;

  /// A full row has a row after it to pull up beside it.
  final bool hasNext;

  /// The row's units now, and the range a unit drag moves over (the pair's overlap).
  final int units;
  final HomeUnitRange range;

  /// The content's width, and a half's.
  final double fullWidth;
  final double halfWidth;

  double get width => span == HomeSpan.full ? fullWidth : halfWidth;

  /// [section]'s place in [rows] (the feed's rows now) in [layout]; [enabledOrder] the sections drawn.
  static HomeGridPlace? of(
    HomeSection section, {
    required List<HomeFeedRow> rows,
    required List<HomeSection> enabledOrder,
    required double fullWidth,
    required HomeLayout layout,
  }) {
    final info = homeSpanInfoOf(section, rows, enabledOrder, fullWidth, HomeSpanRules.columnGap);
    if (info == null) return null;
    final row = rows.firstWhere((r) => r.sections.contains(section));
    final (left, right) = switch (row) {
      HomeFeedRowFull(:final section) => (section, null),
      HomeFeedRowHalves(:final left, :final right) => (left, right),
    };
    return HomeGridPlace(
      section: section,
      row: row,
      span: info.span,
      hasNext: info.hasNext,
      units: layout.unitsOfRow(row),
      range: homeRowUnitRange(left, right),
      fullWidth: fullWidth,
      halfWidth: info.halfPx,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is HomeGridPlace &&
      other.section == section &&
      other.row == row &&
      other.span == span &&
      other.hasNext == hasNext &&
      other.units == units &&
      other.range == range &&
      other.fullWidth == fullWidth &&
      other.halfWidth == halfWidth;

  @override
  int get hashCode => Object.hash(section, row, span, hasNext, units, range, fullWidth, halfWidth);
}

/// The one two-column resize in flight (one finger on one handle): a span drag's live width and the span it will
/// snap to, or a unit drag's live height and the count it will settle on. The feed's rows read them in build.
class HomeGridResize extends ChangeNotifier {
  HomeGridResize({
    required TickerProvider vsync,
    required this.commitSpan,
    required this.commitUnits,
    required this.reduced,
    required this.slop,
  }) : _spring = AnimationController.unbounded(vsync: vsync) {
    _spring.addListener(_onSpring);
  }

  /// Writes a span change (one Undo step).
  final void Function(HomeSection section, HomeSpanCommit commit) commitSpan;

  /// Writes a row's unit count (one Undo step, both halves of a pair).
  final void Function(HomeSection section, int units) commitUnits;

  final bool Function() reduced;

  /// The touch slop the axis locks past (px).
  final double Function() slop;

  final AnimationController _spring;
  void Function(double)? _apply;
  VoidCallback? _done;

  // ── Span (horizontal) ──

  /// The section whose width is dragged (or springing to where it lands); null: none.
  HomeSection? get spanSection => _spanSection;
  HomeSection? _spanSection;

  /// Its live width (dp).
  double get spanWidth => _spanWidth;
  double _spanWidth = 0;

  /// What the outline shows and a release springs to: half or full.
  HomeSpan get spanTarget => _spanTarget;
  HomeSpan _spanTarget = HomeSpan.full;

  /// The finger is down (the outline at full strength), not the release spring.
  bool get spanDragging => _spanDragging;
  bool _spanDragging = false;

  // ── Units (vertical) ──

  /// The row whose height is dragged (or springing); null: none.
  HomeFeedRow? get unitRow => _unitRow;
  HomeFeedRow? _unitRow;

  /// Its live height (dp).
  double get unitHeight => _unitHeight;
  double _unitHeight = 0;

  /// The count it settles on.
  int get unitTarget => _unitTarget;
  int _unitTarget = 1;

  HomeGridPlace? _place;
  Offset _start = Offset.zero;

  /// A finger is on a handle, or a release is still springing.
  bool get active => _place != null || _spanSection != null || _unitRow != null;

  /// A finger is on a handle.
  bool get dragging => _place != null;

  /// The live height for [row], null when it isn't the one dragged.
  double? liveHeight(HomeFeedRow row) => _unitRow != null && _unitRow == row ? _unitHeight : null;

  /// The span drag for [row]: its section and live width; null when none of its sections is dragged.
  (HomeSection, double)? liveSpan(HomeFeedRow row) {
    final s = _spanSection;
    if (s == null || !row.sections.contains(s)) return null;
    return (s, _spanWidth);
  }

  /// The finger came down on [place]'s handle at global [p].
  void grab(HomeGridPlace place, Offset p) {
    _stopSpring();
    _place = place;
    _start = p;
  }

  /// The finger moved to global [p]: the first direction past the slop locks the axis.
  void drag(Offset p) {
    final place = _place;
    if (place == null) return;
    final dx = p.dx - _start.dx, dy = p.dy - _start.dy;
    if (_spanSection == null && _unitRow == null) {
      final axis = homeHandleAxis(dx, dy, slop(), true);
      if (axis == null) return;
      if (axis == HomeHandleAxis.horizontal) {
        _spanSection = place.section;
        _spanWidth = place.width;
        _spanTarget = place.span == HomeSpan.full ? HomeSpan.full : HomeSpan.leftHalf;
        _spanDragging = true;
      } else {
        _unitRow = place.row;
        _unitHeight = homeGridHeightDp(place.units);
        _unitTarget = place.units;
      }
    }
    if (_spanSection != null) {
      _spanWidth = homeSpanDragWidth(place.span, place.width, dx, place.halfWidth, place.fullWidth);
      final target = homeSpanTarget(_spanWidth, place.halfWidth, place.fullWidth);
      if (target != _spanTarget) {
        _spanTarget = target;
        YoinHaptics.segmentTick();
      }
    } else if (_unitRow != null) {
      final lo = homeGridHeightDp(place.range.first) - HomeGridTokens.overdrag;
      final hi = homeGridHeightDp(place.range.last) + HomeGridTokens.overdrag;
      _unitHeight = (homeGridHeightDp(place.units) + dy).clamp(lo, hi);
      final next = homeUnitsNearest(_unitHeight, place.range, 1);
      if (next != _unitTarget) {
        _unitTarget = next;
        YoinHaptics.segmentTick();
      }
    }
    notifyListeners();
  }

  /// The finger lifted ([cancelled]: the system took it): springs to where it lands, then writes it.
  void release({bool cancelled = false}) {
    final place = _place;
    if (place == null) return;
    _place = null;
    final section = _spanSection;
    if (section != null) {
      _spanDragging = false;
      final write = cancelled
          ? HomeSpanCommit.none
          : homeSpanCommit(place.span, _spanTarget == HomeSpan.full ? HomeSpan.full : HomeSpan.leftHalf, place.hasNext);
      final landing = switch (write) {
        HomeSpanCommit.toHalf => place.halfWidth,
        HomeSpanCommit.toFull => place.fullWidth,
        HomeSpanCommit.none => place.width,
      };
      _run(_spanWidth, landing, (v) => _spanWidth = v, () {
        if (_spanSection != section) return;
        _spanSection = null;
        if (write != HomeSpanCommit.none) {
          YoinHaptics.confirm();
          commitSpan(section, write);
        }
      });
    } else if (_unitRow != null) {
      final row = _unitRow;
      final units = cancelled ? place.units : _unitTarget;
      _run(_unitHeight, homeGridHeightDp(units), (v) => _unitHeight = v, () {
        if (_unitRow != row) return;
        _unitRow = null;
        if (units != place.units) {
          YoinHaptics.confirm();
          commitUnits(place.section, units);
        }
      });
    }
    notifyListeners();
  }

  /// Drops any resize at once (edit mode left, a drag started, the width changed).
  void snap() {
    _stopSpring();
    final was = active;
    _place = null;
    _spanSection = null;
    _spanDragging = false;
    _unitRow = null;
    if (was) notifyListeners();
  }

  void _run(double from, double to, void Function(double) apply, VoidCallback done) {
    _stopSpring();
    if (reduced() || (from - to).abs() < .5) {
      apply(to);
      done();
      notifyListeners();
      return;
    }
    _apply = apply;
    _done = done;
    _spring.value = from;
    _spring
        .animateWith(SpringSimulation(YoinSprings.defaultSpatial, from, to, 0, tolerance: const Tolerance(distance: .5)))
        .then((_) => _finishSpring());
  }

  void _onSpring() {
    final apply = _apply;
    if (apply == null) return;
    apply(_spring.value);
    notifyListeners();
  }

  void _finishSpring() {
    final done = _done;
    _apply = null;
    _done = null;
    if (done == null) return;
    done();
    notifyListeners();
  }

  void _stopSpring() {
    if (_spring.isAnimating) _spring.stop();
    // A spring cut short lands where it was going.
    _finishSpring();
  }

  @override
  void dispose() {
    _spring
      ..removeListener(_onSpring)
      ..dispose();
    super.dispose();
  }

  /// ← / → on a focused handle ([homeSpanKey]): what it writes, null when it does nothing here.
  static HomeSpanCommit? spanKey(HomeGridPlace place, {required bool left}) =>
      homeSpanKey(place.span, left, place.hasNext);
}

/// The two-column ⌟ on a block's plate corner: drags sideways for the span and down / up for the units, steps
/// with the arrow keys when focused (a PC keyboard), shows a move cursor under a pointer, and is one reader
/// element whose actions are Half width / Full width and whose increase / decrease step the units.
class HomeGridHandle extends StatefulWidget {
  const HomeGridHandle({
    super.key,
    required this.place,
    required this.resize,
    required this.progress,
    required this.onSpan,
    required this.onUnits,
    required this.enabled,
  });

  final HomeGridPlace place;
  final HomeGridResize resize;
  final double progress;

  /// A key or the reader's action wrote a span change.
  final void Function(HomeSpanCommit commit) onSpan;

  /// A key or the reader stepped the units.
  final void Function(int units) onUnits;

  /// Drags start only while true (edit mode, nothing lifted).
  final bool Function() enabled;

  @override
  State<HomeGridHandle> createState() => _HomeGridHandleState();
}

class _HomeGridHandleState extends State<HomeGridHandle> with TickerProviderStateMixin {
  final FocusNode _focus = FocusNode(debugLabel: 'home-grid-handle');
  late final AnimationController _hot = AnimationController(vsync: this, duration: const Duration(milliseconds: 160));
  late final AnimationController _pulse = AnimationController(vsync: this, value: 1);

  @override
  void dispose() {
    _focus.dispose();
    _hot.dispose();
    _pulse.dispose();
    super.dispose();
  }

  bool get _live => widget.progress > 0.5;

  KeyEventResult _onKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return KeyEventResult.ignored;
    final place = widget.place;
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.arrowLeft || key == LogicalKeyboardKey.arrowRight) {
      final write = HomeGridResize.spanKey(place, left: key == LogicalKeyboardKey.arrowLeft);
      if (write == null) return KeyEventResult.ignored;
      widget.onSpan(write);
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.arrowUp || key == LogicalKeyboardKey.arrowDown) {
      final next = place.units + (key == LogicalKeyboardKey.arrowDown ? 1 : -1);
      if (!place.range.contains(next)) return KeyEventResult.ignored;
      widget.onUnits(next);
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final e = widget.progress.clamp(0.0, 1.0);
    final colors = Theme.of(context).colorScheme;
    final strings = HomeStrings.of(context);
    final place = widget.place;
    final resize = widget.resize;
    final half = place.span != HomeSpan.full;
    return Semantics(
      container: true,
      // The span in the label; the units are the adjustable value (increase and decrease step them).
      label: '${strings.sectionTitle(place.section)}, ${half ? strings.editHalfWidth : strings.editFullWidth}',
      value: '${place.units}',
      increasedValue: place.range.contains(place.units + 1) ? '${place.units + 1}' : null,
      decreasedValue: place.range.contains(place.units - 1) ? '${place.units - 1}' : null,
      customSemanticsActions: _live
          ? {
              if (!half && place.hasNext)
                CustomSemanticsAction(label: strings.editHalfWidth): () => widget.onSpan(HomeSpanCommit.toHalf),
              if (half) CustomSemanticsAction(label: strings.editFullWidth): () => widget.onSpan(HomeSpanCommit.toFull),
            }
          : null,
      onIncrease: _live && place.range.contains(place.units + 1) ? () => widget.onUnits(place.units + 1) : null,
      onDecrease: _live && place.range.contains(place.units - 1) ? () => widget.onUnits(place.units - 1) : null,
      excludeSemantics: true,
      child: IgnorePointer(
        ignoring: !_live,
        child: Opacity(
          opacity: e,
          child: Focus(
            focusNode: _focus,
            canRequestFocus: _live,
            onKeyEvent: _onKey,
            child: MouseRegion(
              cursor: SystemMouseCursors.move,
              onEnter: (_) => _hot.animateTo(HomeRowsMetrics.hoverHot),
              onExit: (_) {
                if (!resize.dragging) _hot.animateTo(0);
              },
              child: RawGestureDetector(
                key: homeGridHandleKey(place.section),
                behavior: HitTestBehavior.opaque,
                gestures: {
                  HomeRowsGrabRecognizer: GestureRecognizerFactoryWithHandlers<HomeRowsGrabRecognizer>(
                    () => HomeRowsGrabRecognizer(debugOwner: this),
                    (r) => r
                      ..onDown = ((position, _) {
                        if (!widget.enabled()) return false;
                        resize.grab(place, position);
                        _hot.animateTo(1);
                        return true;
                      })
                      ..onMoveAt = ((position, _) => resize.drag(position))
                      ..onUp = ((_) {
                        resize.release();
                        _hot.animateTo(0);
                      })
                      ..onCancel = () {
                        resize.release(cancelled: true);
                        _hot.animateTo(0);
                      },
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
                      child: HomeRowsGlyph(rest: colors.onSurfaceVariant, held: colors.primary, hot: _hot, pulse: _pulse),
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
