import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../home/home_section.dart';
import '../home/home_strings.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'landing_motion.dart';
import 'landing_scenes.dart';
import 'landing_strings.dart';

/// The Home step (LandingHomeStep.kt): every section Home can draw, on/off and
/// in order. Drag a row by its handle (or long-press it) to move it. The same
/// haptics as Home's edit mode: lift, a tick for every slot passed, a confirm
/// for a drop that changed the order; the switches tick. Row counts stay with
/// Home's edit mode — one line below says so.
class HomeSectionsScene extends StatelessWidget {
  const HomeSectionsScene({super.key, required this.sections, required this.onEnabled, required this.onMove});

  /// The rows, in order (the layout's sections this build can draw).
  final List<HomeSectionState> sections;
  final void Function(HomeSection section, bool enabled) onEnabled;

  /// Moves a row: [from] and [to] index [sections].
  final void Function(int from, int to) onMove;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return LandingSceneColumn(
      children: [
        LandingSectionList(sections: sections, onEnabled: onEnabled, onMove: onMove),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 10, 8, 0),
          child: Text(
            LandingStrings.of(context).homeRowsNote,
            style: theme.textTheme.bodySmall!.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
      ],
    );
  }
}

/// The reorderable rows. The row under the finger follows it 1:1; the others
/// spring to their new slots as it passes them (a column has no item
/// animation of its own); the released row springs into its slot.
class LandingSectionList extends StatefulWidget {
  const LandingSectionList({super.key, required this.sections, required this.onEnabled, required this.onMove});

  final List<HomeSectionState> sections;
  final void Function(HomeSection section, bool enabled) onEnabled;
  final void Function(int from, int to) onMove;

  @override
  State<LandingSectionList> createState() => _LandingSectionListState();
}

class _LandingSectionListState extends State<LandingSectionList> with TickerProviderStateMixin {
  final _heights = <String, double>{};
  final _slide = <String, SpringValue>{};

  /// The row being dragged, its offset from its slot, and the order at the lift.
  String? _dragging;
  late final LandingAnim _dragOffset = LandingAnim(this, 0);
  List<String> _orderAtLift = const [];

  /// The order as the drag sees it: moved here at once, so two moves inside
  /// one frame never read a list that hasn't rebuilt yet.
  List<String> _liveOrder = const [];

  List<String> get _order => [for (final p in widget.sections) p.id];

  SpringValue _slideOf(String id) =>
      _slide.putIfAbsent(id, () => SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial));

  @override
  void didUpdateWidget(LandingSectionList old) {
    super.didUpdateWidget(old);
    final before = [for (final p in old.sections) p.id];
    final after = _order;
    if (listEquals(before, after)) return;
    // FLIP: every row that moved starts where it was and springs to its new slot.
    final oldTops = _tops(before);
    final newTops = _tops(after);
    final reduce = MediaQuery.disableAnimationsOf(context);
    for (final id in after) {
      if (id == _dragging) continue;
      final was = oldTops[id];
      final now = newTops[id];
      if (was == null || now == null || was == now) continue;
      final slide = _slideOf(id);
      slide.snapTo(slide.value + was - now);
      reduce ? slide.snapTo(0) : slide.animateTo(0);
    }
  }

  Map<String, double> _tops(List<String> order) {
    final tops = <String, double>{};
    var y = 0.0;
    for (final id in order) {
      tops[id] = y;
      y += (_heights[id] ?? 0) + LandingTokens.rowGap;
    }
    return tops;
  }

  @override
  void dispose() {
    for (final s in _slide.values) {
      s.dispose();
    }
    _dragOffset.dispose();
    super.dispose();
  }

  void _lift(String id) {
    setState(() => _dragging = id);
    _dragOffset.snapTo(0);
    _orderAtLift = _order;
    _liveOrder = [..._orderAtLift];
    YoinHaptics.dragStart();
  }

  void _move(int from, int to) {
    _liveOrder.insert(to, _liveOrder.removeAt(from));
    widget.onMove(from, to);
    YoinHaptics.segmentTick();
  }

  void _dragBy(double dy) {
    final id = _dragging;
    if (id == null) return;
    var offset = _dragOffset.value + dy;
    final index = _liveOrder.indexOf(id);
    if (index < 0) return;
    // Pass a neighbour once the dragged row's centre crosses the neighbour's centre.
    if (offset > 0 && index < _liveOrder.length - 1) {
      final step = (_heights[_liveOrder[index + 1]] ?? 0) + LandingTokens.rowGap;
      if (offset > step / 2) {
        _move(index, index + 1);
        offset -= step;
      }
    } else if (offset < 0 && index > 0) {
      final step = (_heights[_liveOrder[index - 1]] ?? 0) + LandingTokens.rowGap;
      if (-offset > step / 2) {
        _move(index, index - 1);
        offset += step;
      }
    }
    _dragOffset.snapTo(offset);
  }

  Future<void> _drop() async {
    final id = _dragging;
    if (id == null) return;
    if (!listEquals(_liveOrder, _orderAtLift)) YoinHaptics.confirm();
    // The released row settles into its slot on the spatial spring, then lands.
    if (MediaQuery.disableAnimationsOf(context)) {
      _dragOffset.snapTo(0);
    } else {
      await _dragOffset.animateTo(0, LandingSprings.spatial);
    }
    if (mounted && _dragging == id) setState(() => _dragging = null);
  }

  @override
  Widget build(BuildContext context) {
    final home = HomeStrings.of(context);
    final strings = LandingStrings.of(context);
    final sections = widget.sections;
    final lifted = _dragging == null ? -1 : sections.indexWhere((p) => p.id == _dragging);
    return _LiftColumn(
      lifted: lifted,
      gap: LandingTokens.rowGap,
      children: [
        for (var i = 0; i < sections.length; i++) _buildRow(context, sections[i], i, sections.length, home, strings),
      ],
    );
  }

  Widget _buildRow(
    BuildContext context,
    HomeSectionState pref,
    int index,
    int count,
    HomeStrings home,
    LandingStrings strings,
  ) {
    final section = pref.section;
    final id = pref.id;
    final lifted = _dragging == id;
    final title = home.sectionTitle(section);
    final slide = _slideOf(id);
    return KeyedSubtree(
      key: ValueKey(id),
      child: _MeasureHeight(
        onHeight: (h) => _heights[id] = h,
        child: AnimatedBuilder(
          animation: Listenable.merge([slide.animation, _dragOffset.animation]),
          builder: (context, child) =>
              Transform.translate(offset: Offset(0, lifted ? _dragOffset.value : slide.value), child: child),
          child: Semantics(
            customSemanticsActions: {
              if (index > 0)
                CustomSemanticsAction(label: home.editMoveUp): () {
                  widget.onMove(index, index - 1);
                  YoinHaptics.segmentTick();
                },
              if (index < count - 1)
                CustomSemanticsAction(label: home.editMoveDown): () {
                  widget.onMove(index, index + 1);
                  YoinHaptics.segmentTick();
                },
            },
            child: GestureDetector(
              onLongPressStart: (_) {
                _longPressLast = 0;
                _lift(id);
              },
              onLongPressMoveUpdate: (d) {
                _dragBy(d.offsetFromOrigin.dy - _longPressLast);
                _longPressLast = d.offsetFromOrigin.dy;
              },
              onLongPressEnd: (_) {
                _longPressLast = 0;
                _drop();
              },
              onLongPressCancel: () {
                _longPressLast = 0;
                _drop();
              },
              child: _SectionRow(
                pref: pref,
                title: title,
                supporting: home.sectionSupporting(section),
                handleLabel: strings.homeDrag(title),
                first: index == 0,
                last: index == count - 1,
                lifted: lifted,
                onHandleDragStart: () => _lift(id),
                onHandleDrag: _dragBy,
                onHandleDragEnd: _drop,
                onEnabled: (on) {
                  YoinHaptics.toggle(on: on);
                  widget.onEnabled(section, on);
                },
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// The long press reports its offset from the press; this is the part already applied.
  double _longPressLast = 0;
}

/// One section: handle, sketch, name and line, switch (SectionRow).
class _SectionRow extends StatefulWidget {
  const _SectionRow({
    required this.pref,
    required this.title,
    required this.supporting,
    required this.handleLabel,
    required this.first,
    required this.last,
    required this.lifted,
    required this.onHandleDragStart,
    required this.onHandleDrag,
    required this.onHandleDragEnd,
    required this.onEnabled,
  });

  final HomeSectionState pref;
  final String title;
  final String supporting;
  final String handleLabel;
  final bool first;
  final bool last;
  final bool lifted;
  final VoidCallback onHandleDragStart;
  final ValueChanged<double> onHandleDrag;
  final VoidCallback onHandleDragEnd;
  final ValueChanged<bool> onEnabled;

  @override
  State<_SectionRow> createState() => _SectionRowState();
}

class _SectionRowState extends State<_SectionRow> with TickerProviderStateMixin {
  late final SpringValue _top = SpringValue(vsync: this, initial: _topTarget, spring: YoinSprings.defaultSpatial);
  late final SpringValue _bottom = SpringValue(vsync: this, initial: _bottomTarget, spring: YoinSprings.defaultSpatial);
  late final SpringValue _lift = SpringValue(
    vsync: this,
    initial: widget.lifted ? 1 : 0,
    spring: YoinSprings.fastSpatial,
  );
  late final SpringValue _liftColor = SpringValue(
    vsync: this,
    initial: widget.lifted ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );
  late final SpringValue _dim = SpringValue(
    vsync: this,
    initial: widget.pref.enabled ? 1 : 0.5,
    spring: YoinSprings.defaultEffects,
  );

  double get _topTarget => (widget.first || widget.lifted) ? LandingTokens.rowOuter : LandingTokens.rowInner;
  double get _bottomTarget => (widget.last || widget.lifted) ? LandingTokens.rowOuter : LandingTokens.rowInner;

  @override
  void didUpdateWidget(_SectionRow old) {
    super.didUpdateWidget(old);
    final reduce = MediaQuery.disableAnimationsOf(context);
    void go(SpringValue v, double target) => reduce ? v.snapTo(target) : v.animateTo(target);
    go(_top, _topTarget);
    go(_bottom, _bottomTarget);
    go(_lift, widget.lifted ? 1 : 0);
    go(_liftColor, widget.lifted ? 1 : 0);
    go(_dim, widget.pref.enabled ? 1 : 0.5);
  }

  @override
  void dispose() {
    _top.dispose();
    _bottom.dispose();
    _lift.dispose();
    _liftColor.dispose();
    _dim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([
        _top.animation,
        _bottom.animation,
        _lift.animation,
        _liftColor.animation,
        _dim.animation,
      ]),
      builder: (context, _) {
        final dim = _dim.value.clamp(0.0, 1.0);
        final scale = 1 + (LandingTokens.liftScale - 1) * _lift.value;
        final top = Radius.circular(math.max(0.0, _top.value));
        final bottom = Radius.circular(math.max(0.0, _bottom.value));
        return Transform.scale(
          scale: scale,
          child: DecoratedBox(
            decoration: ShapeDecoration(
              color: Color.lerp(colors.surfaceBright, colors.surfaceContainerHighest, _liftColor.value.clamp(0.0, 1.0)),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.vertical(top: top, bottom: bottom),
              ),
            ),
            child: Padding(
              padding: const EdgeInsetsDirectional.fromSTEB(2, 10, 12, 10),
              child: Row(
                children: [
                  Semantics(
                    container: true,
                    label: widget.handleLabel,
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onVerticalDragStart: (_) => widget.onHandleDragStart(),
                      onVerticalDragUpdate: (d) => widget.onHandleDrag(d.delta.dy),
                      onVerticalDragEnd: (_) => widget.onHandleDragEnd(),
                      onVerticalDragCancel: widget.onHandleDragEnd,
                      child: SizedBox(
                        width: LandingTokens.handleWidth,
                        height: LandingTokens.handleHeight,
                        child: Icon(YoinSymbols.dragHandle, size: 20, color: colors.onSurfaceVariant),
                      ),
                    ),
                  ),
                  Opacity(
                    opacity: dim,
                    child: SectionSketch(section: widget.pref.section, color: colors.primary),
                  ),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsetsDirectional.only(start: 12, end: 10),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.title,
                            style: landingWeight(
                              theme.textTheme.titleMedium!,
                              600,
                            ).copyWith(color: colors.onSurface.withValues(alpha: dim)),
                          ),
                          Text(
                            widget.supporting,
                            style: theme.textTheme.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ],
                      ),
                    ),
                  ),
                  Switch(value: widget.pref.enabled, onChanged: widget.onEnabled),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// A 36dp sketch of the section's layout on Home, in the primary colour
/// (SectionGlyph): a picture of the layout, not an icon.
class SectionSketch extends StatelessWidget {
  const SectionSketch({super.key, required this.section, required this.color});

  final HomeSection section;
  final Color color;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: CustomPaint(
      painter: _SketchPainter(section, color),
      child: const SizedBox.square(dimension: LandingTokens.sectionGlyph),
    ),
  );
}

class _SketchPainter extends CustomPainter {
  _SketchPainter(this.section, this.color);

  final HomeSection section;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final u = size.width / 36;
    void box(double x, double y, double w, double h, double a, [double r = 2.5]) => canvas.drawRRect(
      RRect.fromRectAndRadius(Rect.fromLTWH(x * u, y * u, w * u, h * u), Radius.circular(r * u)),
      Paint()..color = color.withValues(alpha: color.a * a),
    );
    void dot(double x, double y, double r, double a) =>
        canvas.drawCircle(Offset(x * u, y * u), r * u, Paint()..color = color.withValues(alpha: color.a * a));
    switch (section) {
      case HomeSection.activities:
        box(4, 6, 16, 24, 1, 3);
        box(22, 6, 10, 11, 0.55, 3);
        box(22, 19, 10, 11, 0.3, 3);
      case HomeSection.jumpBackIn:
        for (var c = 0; c <= 2; c++) {
          for (var r = 0; r <= 1; r++) {
            box(4 + c * 10, 8 + r * 10, 8, 8, r == 0 ? 0.9 : 0.45, 2);
          }
        }
      case HomeSection.recentlyAdded:
        for (var c = 0; c <= 2; c++) {
          box(3 + c * 10.5, 12, 9, 9, 0.9 - c * 0.25, 2);
        }
        box(3, 24, 14, 2.5, 0.35, 1.2);
      case HomeSection.rediscover:
        box(5, 10, 15, 15, 0.45, 3);
        box(14, 12, 15, 15, 1, 3);
      case HomeSection.recentlyPlayed:
        for (var c = 0; c <= 2; c++) {
          dot(8.5 + c * 9.5, 17, 4.3, 0.9 - c * 0.25);
        }
        box(6, 25, 22, 2.4, 0.35, 1.2);
      case HomeSection.yourPlaylists:
        for (var r = 0; r <= 2; r++) {
          box(5, 8 + r * 7.5, 6, 6, 0.9 - r * 0.2, 1.5);
          box(14, 9.8 + r * 7.5, 16 - r * 3, 2.4, 0.4, 1.2);
        }
      case HomeSection.otherProfiles:
        box(4, 10, 12, 12, 0.9, 3);
        box(19, 10, 12, 12, 0.55, 3);
        box(4, 25, 8, 2.4, 0.35, 1.2);
        box(19, 25, 8, 2.4, 0.35, 1.2);
      case HomeSection.lyricHighlight:
        dot(10, 17, 6, 0.9);
        box(19, 11, 12, 2.6, 0.55, 1.2);
        box(19, 16, 10, 2.6, 0.55, 1.2);
        box(19, 22, 7, 2.2, 0.3, 1.2);
      case HomeSection.memoryGrid:
        for (var c = 0; c < 4; c++) {
          for (var r = 0; r < 3; r++) {
            box(4 + c * 7.4, 8 + r * 7.4, 5.6, 5.6, 0.9 - (c + r) * 0.1, 1.4);
          }
        }
      case HomeSection.madeForYou:
        for (var c = 0; c <= 2; c++) {
          box(3 + c * 10.5, 10, 9, 9, 0.9 - c * 0.25, 2);
          box(3 + c * 10.5, 22, 7, 2.4, 0.35, 1.2);
        }
    }
  }

  @override
  bool shouldRepaint(_SketchPainter old) => old.section != section || old.color != color;
}

/// Reports its child's height after layout.
class _MeasureHeight extends SingleChildRenderObjectWidget {
  const _MeasureHeight({required this.onHeight, required super.child});

  final ValueChanged<double> onHeight;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderMeasureHeight(onHeight);

  @override
  void updateRenderObject(BuildContext context, _RenderMeasureHeight renderObject) => renderObject.onHeight = onHeight;
}

class _RenderMeasureHeight extends RenderProxyBox {
  _RenderMeasureHeight(this.onHeight);

  ValueChanged<double> onHeight;

  @override
  void performLayout() {
    super.performLayout();
    onHeight(size.height);
  }
}

/// A column that paints (and hit-tests) its [lifted] child above the others
/// (Compose zIndex), so the dragged row passes over its neighbours.
class _LiftColumn extends MultiChildRenderObjectWidget {
  const _LiftColumn({required this.lifted, required this.gap, required super.children});

  final int lifted;
  final double gap;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderLiftColumn(lifted, gap);

  @override
  void updateRenderObject(BuildContext context, _RenderLiftColumn renderObject) {
    renderObject
      ..lifted = lifted
      ..gap = gap;
  }
}

class _LiftParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderLiftColumn extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _LiftParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _LiftParentData> {
  _RenderLiftColumn(this._lifted, this._gap);

  int _lifted;
  set lifted(int v) {
    if (v == _lifted) return;
    _lifted = v;
    markNeedsPaint();
  }

  double _gap;
  set gap(double v) {
    if (v == _gap) return;
    _gap = v;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _LiftParentData) child.parentData = _LiftParentData();
  }

  @override
  void performLayout() {
    var y = 0.0;
    var child = firstChild;
    final inner = BoxConstraints(minWidth: constraints.maxWidth, maxWidth: constraints.maxWidth);
    while (child != null) {
      child.layout(inner, parentUsesSize: true);
      final data = child.parentData! as _LiftParentData;
      data.offset = Offset(0, y);
      y += child.size.height;
      child = data.nextSibling;
      if (child != null) y += _gap;
    }
    size = constraints.constrain(Size(constraints.maxWidth, y));
  }

  RenderBox? _childAt(int index) {
    var i = 0;
    var child = firstChild;
    while (child != null) {
      if (i == index) return child;
      child = childAfter(child);
      i++;
    }
    return null;
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final top = _childAt(_lifted);
    var child = firstChild;
    while (child != null) {
      if (child != top) context.paintChild(child, offset + (child.parentData! as _LiftParentData).offset);
      child = childAfter(child);
    }
    if (top != null) context.paintChild(top, offset + (top.parentData! as _LiftParentData).offset);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    final top = _childAt(_lifted);
    if (top != null) {
      final hit = result.addWithPaintOffset(
        offset: (top.parentData! as _LiftParentData).offset,
        position: position,
        hitTest: (result, transformed) => top.hitTest(result, position: transformed),
      );
      if (hit) return true;
    }
    return defaultHitTestChildren(result, position: position);
  }
}
