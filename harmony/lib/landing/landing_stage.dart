import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';
import 'landing_bubble.dart';
import 'landing_mascot.dart';
import 'landing_models.dart';
import 'landing_motion.dart';

// The landing stage (LandingStage.kt): one progress t (from step → to step)
// drives the floating window, the mascot, the speech bubble and the pill; one
// more (the hand-off) turns the pill into the shell's bar. Every per-frame
// value is read in layout or paint — a step change never rebuilds the stage.

/// The rects the stage interpolates between, for one window size.
@immutable
final class LandingFrame {
  const LandingFrame({
    required this.width,
    required this.height,
    required this.top,
    required this.wide,
    required this.medium,
    required this.colX,
    required this.colW,
    required this.pillH,
    required this.pillTop,
    required this.helloPillX,
    required this.helloPillW,
    required this.barX,
    required this.barW,
  });

  final double width;
  final double height;

  /// The status bar's bottom.
  final double top;

  /// Two columns (a wide or short window): Yoin on the left, the window on the right.
  final bool wide;

  /// One column under the centred bar (a medium window).
  final bool medium;
  final double colX;
  final double colW;
  final double pillH;
  final double pillTop;
  final double helloPillX;
  final double helloPillW;

  /// The shell's bar, where the pill ends up in the hand-off (same top and height as the pill).
  final double barX;
  final double barW;

  double pillX(LandingStep step) => step == LandingStep.hello ? helloPillX : colX;
  double pillW(LandingStep step) => step == LandingStep.hello ? helloPillW : colW;
}

/// The frame for a [width] × [height] window. [centered]: the shell's bar is
/// the centred one (ShellChromeForm.centeredBar), [twoColumns]: Wide or a
/// short window, [wideWindow]: LayoutMode.wide (the centred bar sits higher).
LandingFrame landingFrame({
  required double width,
  required double height,
  required double topInset,
  required double bottomInset,
  required bool centered,
  required bool twoColumns,
  bool wideWindow = false,
}) {
  final double colW;
  final double colX;
  if (twoColumns) {
    colW = math.min(LandingTokens.twoColumnMax, width * LandingTokens.twoColumnFraction);
    colX = width - colW - math.max(LandingTokens.twoColumnEndMin, width * LandingTokens.twoColumnEndFraction);
  } else {
    colW = math.min(width - 2 * LandingTokens.columnMargin, LandingTokens.columnMax);
    colX = (width - colW) / 2;
  }
  final pillH = centered ? BarTokens.centeredHeight : BarTokens.height;
  final bottomMargin = centered
      ? (wideWindow ? BarTokens.centeredBottomMarginWide : BarTokens.centeredBottomMargin)
      : BarTokens.portraitVerticalMargin;
  final pillTop = height - bottomInset - bottomMargin - pillH;
  final helloPillW = (centered || twoColumns)
      ? math.min(LandingTokens.helloPillMax, width - 2 * LandingTokens.columnMargin)
      : width - 2 * LandingTokens.columnMargin;
  final barW = centered
      ? math.min(width - 2 * BarTokens.centeredHorizontalMargin, BarTokens.centeredMaxWidth)
      : width - 2 * BarTokens.horizontalMargin;
  return LandingFrame(
    width: width,
    height: height,
    top: topInset,
    wide: twoColumns,
    medium: !twoColumns && centered,
    colX: colX,
    colW: colW,
    pillH: pillH,
    pillTop: pillTop,
    helloPillX: (width - helloPillW) / 2,
    helloPillW: helloPillW,
    barX: (width - barW) / 2,
    barW: barW,
  );
}

/// Layout inputs the stage hands the window right before laying it out.
class LandingWindowSpec {
  double sceneWidth = 0;
  double minHeight = 0;
  double helloHeight = 0;
}

enum LandingStageSlot { window, mascot, bubble, pill }

/// Lays the landing out: [window] (the floating window with the step's
/// scene), [mascot], [bubble] and [pill].
class LandingStage extends SlottedMultiChildRenderObjectWidget<LandingStageSlot, RenderBox> {
  const LandingStage({
    super.key,
    required this.motion,
    required this.windowSpec,
    required this.tail,
    required this.topInset,
    required this.bottomInset,
    required this.imeBottom,
    required this.centered,
    required this.twoColumns,
    required this.wideWindow,
    required this.window,
    required this.mascot,
    required this.bubble,
    required this.pill,
  });

  final LandingMotion motion;
  final LandingWindowSpec windowSpec;
  final BubbleTailState tail;
  final double topInset;
  final double bottomInset;
  final double imeBottom;
  final bool centered;
  final bool twoColumns;
  final bool wideWindow;
  final Widget window;
  final Widget mascot;
  final Widget bubble;
  final Widget pill;

  @override
  Iterable<LandingStageSlot> get slots => LandingStageSlot.values;

  @override
  Widget? childForSlot(LandingStageSlot slot) => switch (slot) {
    LandingStageSlot.window => window,
    LandingStageSlot.mascot => mascot,
    LandingStageSlot.bubble => bubble,
    LandingStageSlot.pill => pill,
  };

  @override
  RenderLandingStage createRenderObject(BuildContext context) => RenderLandingStage(
    motion: motion,
    windowSpec: windowSpec,
    tail: tail,
    topInset: topInset,
    bottomInset: bottomInset,
    imeBottom: imeBottom,
    centered: centered,
    twoColumns: twoColumns,
    wideWindow: wideWindow,
  );

  @override
  void updateRenderObject(BuildContext context, RenderLandingStage renderObject) {
    renderObject
      ..motion = motion
      ..windowSpec = windowSpec
      ..tail = tail
      ..topInset = topInset
      ..bottomInset = bottomInset
      ..imeBottom = imeBottom
      ..centered = centered
      ..twoColumns = twoColumns
      ..wideWindow = wideWindow;
  }
}

final class _Pose {
  const _Pose(this.cx, this.cy, this.size, this.tail);
  final double cx;
  final double cy;
  final double size;
  final BubbleTail tail;
}

double _lerp(double a, double b, double t) => lerpDouble(a, b, t)!;

class RenderLandingStage extends RenderBox with SlottedContainerRenderObjectMixin<LandingStageSlot, RenderBox> {
  RenderLandingStage({
    required LandingMotion motion,
    required this.windowSpec,
    required this.tail,
    required double topInset,
    required double bottomInset,
    required double imeBottom,
    required bool centered,
    required bool twoColumns,
    required bool wideWindow,
  }) : _motion = motion,
       _topInset = topInset,
       _bottomInset = bottomInset,
       _imeBottom = imeBottom,
       _centered = centered,
       _twoColumns = twoColumns,
       _wideWindow = wideWindow;

  LandingMotion _motion;
  set motion(LandingMotion value) {
    if (identical(value, _motion)) return;
    if (attached) _motion.frame.removeListener(markNeedsLayout);
    _motion = value;
    if (attached) _motion.frame.addListener(markNeedsLayout);
    markNeedsLayout();
  }

  LandingWindowSpec windowSpec;
  BubbleTailState tail;

  double _topInset;
  set topInset(double v) => _set(_topInset, v, () => _topInset = v);
  double _bottomInset;
  set bottomInset(double v) => _set(_bottomInset, v, () => _bottomInset = v);
  double _imeBottom;
  set imeBottom(double v) => _set(_imeBottom, v, () => _imeBottom = v);
  bool _centered;
  set centered(bool v) => _set(_centered, v, () => _centered = v);
  bool _twoColumns;
  set twoColumns(bool v) => _set(_twoColumns, v, () => _twoColumns = v);
  bool _wideWindow;
  set wideWindow(bool v) => _set(_wideWindow, v, () => _wideWindow = v);

  void _set(Object old, Object value, VoidCallback assign) {
    if (old == value) return;
    assign();
    markNeedsLayout();
  }

  // Measured heights the next pass lays out with: a bubble that changes
  // height re-lays the stage out even when nothing animates.
  double _helloBubbleHeight = 0;
  double _flowBubbleHeight = 0;
  bool _relayoutQueued = false;

  // Per-frame paint values.
  double _windowAlpha = 1;
  double _bubbleScale = 1;
  double _bubbleAlpha = 1;
  Offset _bubbleOrigin = Offset.zero;
  double _pillAlpha = 1;

  final _windowLayer = LayerHandle<OpacityLayer>();
  final _bubbleLayer = LayerHandle<TransformLayer>();
  final _bubbleAlphaLayer = LayerHandle<OpacityLayer>();
  final _pillLayer = LayerHandle<OpacityLayer>();

  RenderBox? get _window => childForSlot(LandingStageSlot.window);
  RenderBox? get _mascot => childForSlot(LandingStageSlot.mascot);
  RenderBox? get _bubble => childForSlot(LandingStageSlot.bubble);
  RenderBox? get _pill => childForSlot(LandingStageSlot.pill);

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _motion.frame.addListener(markNeedsLayout);
  }

  @override
  void detach() {
    _motion.frame.removeListener(markNeedsLayout);
    super.detach();
  }

  @override
  void dispose() {
    _windowLayer.layer = null;
    _bubbleLayer.layer = null;
    _bubbleAlphaLayer.layer = null;
    _pillLayer.layer = null;
    super.dispose();
  }

  @override
  bool get sizedByParent => true;

  @override
  Size computeDryLayout(BoxConstraints constraints) => constraints.biggest;

  static Offset _offsetOf(RenderBox child) => (child.parentData! as BoxParentData).offset;
  static void _place(RenderBox child, double x, double y) => (child.parentData! as BoxParentData).offset = Offset(x, y);

  @override
  void performLayout() {
    final window = _window!;
    final mascot = _mascot!;
    final bubble = _bubble!;
    final pill = _pill!;
    final f = landingFrame(
      width: size.width,
      height: size.height,
      topInset: _topInset,
      bottomInset: _bottomInset,
      centered: _centered,
      twoColumns: _twoColumns,
      wideWindow: _wideWindow,
    );
    final a = _motion.from;
    final b = _motion.to;
    final t = _motion.t.value;
    final hv = _motion.handoff.value.clamp(0.0, 1.0);
    const gap = LandingTokens.windowGap;
    // The window stands on the pill, or on the keyboard when one is up (the pill stays behind it).
    final floor = math.min(f.pillTop - gap, f.height - _imeBottom - gap);

    // ---- window ----
    final baseSize = f.medium ? LandingTokens.mascotBaseMedium : LandingTokens.mascotBase;
    final rowHeight = math.max(baseSize * 0.8, _flowBubbleHeight);
    final maxH = math.max(
      f.pillH,
      f.wide
          ? floor - (f.top + LandingTokens.topGapWide)
          : floor - (f.top + LandingTokens.topGap + rowHeight + LandingTokens.mascotRowGap),
    );
    final minH = f.wide
        ? math.min(maxH, math.max(LandingTokens.minHeightWide, maxH * LandingTokens.minHeightWideFraction))
        : f.medium
        ? math.min(maxH, math.max(LandingTokens.minHeightMedium, maxH * LandingTokens.minHeightMediumFraction))
        : 0.0;
    windowSpec
      ..sceneWidth = f.colW
      ..minHeight = minH
      ..helloHeight = f.pillH;
    var winW = _lerp(f.pillW(a), f.pillW(b), t);
    var winX = _lerp(f.pillX(a), f.pillX(b), t);
    window.layout(BoxConstraints(minWidth: winW, maxWidth: winW, maxHeight: maxH), parentUsesSize: true);
    final winH = window.size.height;
    double winTop(LandingStep step) => step == LandingStep.hello ? f.pillTop : floor - winH;
    var winY = _lerp(winTop(a), winTop(b), t);
    var winAlpha = switch ((a, b)) {
      (LandingStep.hello, LandingStep.hello) => 0.0,
      (LandingStep.hello, _) => (t * 6).clamp(0.0, 1.0),
      (_, LandingStep.hello) => ((1 - t) * 6).clamp(0.0, 1.0),
      _ => 1.0,
    };
    if (hv > 0) {
      winX = _lerp(winX, f.barX, hv);
      winY = _lerp(winY, f.pillTop, hv);
      winAlpha *= (1 - hv * 1.6).clamp(0.0, 1.0);
    }
    // The window's own width follows winX's lerp; the hand-off narrows it into the bar.
    if (hv > 0) {
      winW = _lerp(winW, f.barW, hv);
      window.layout(BoxConstraints(minWidth: winW, maxWidth: winW, maxHeight: maxH), parentUsesSize: true);
    }
    _place(window, winX, winY);
    _windowAlpha = winAlpha;

    // ---- bubble width; an up-tail bubble is laid out first ----
    // Its width doesn't depend on the mascot, and the hello pose centres the
    // mascot + bubble group on the bubble's height: laying it out first means
    // the very first frame is already in place.
    final shown = _motion.bubbleStep;
    bool upTail(LandingStep step) => step == LandingStep.hello || f.wide;
    void layoutBubble(double mascotSize) {
      final double bubbleMax;
      if (!upTail(shown)) {
        bubbleMax = f.colW - mascotSize - 6;
      } else if (f.wide && shown != LandingStep.hello) {
        bubbleMax = math.min(LandingTokens.bubbleMaxWide, f.width * LandingTokens.bubbleMaxWideFraction);
      } else {
        bubbleMax = math.min(
          (f.wide || f.medium) ? LandingTokens.bubbleMaxMedium : LandingTokens.bubbleMax,
          f.width - 48,
        );
      }
      bubble.layout(BoxConstraints(maxWidth: math.max(1, bubbleMax)), parentUsesSize: true);
      final height = bubble.size.height;
      if (shown == LandingStep.hello) {
        if (_helloBubbleHeight != height) {
          _helloBubbleHeight = height;
          _queueRelayout();
        }
      } else if (_flowBubbleHeight != height) {
        _flowBubbleHeight = height;
        _queueRelayout();
      }
    }

    final early = upTail(shown);
    if (early) layoutBubble(0);

    // ---- mascot ----
    final helloSize = (f.wide || f.medium)
        ? LandingTokens.mascotHello
        : math.min(f.width * LandingTokens.mascotHelloPhoneFraction, LandingTokens.mascotHelloPhone);
    final helloGroup = helloSize * 0.8 + 15 + _helloBubbleHeight;
    final helloCy = (f.top + f.pillTop) / 2 * 0.94 - helloGroup / 2 + helloSize * 0.4;
    _Pose pose(LandingStep step) {
      if (step == LandingStep.hello) return _Pose(f.width / 2, helloCy, helloSize, BubbleTail.up);
      if (f.wide) {
        // Left column. A keyboard lifts the floor: Yoin and its bubble shrink
        // into the room above it (ratio 1 = no keyboard, the resting pose).
        final ratio = ((floor - f.top) / (f.pillTop - gap - f.top)).clamp(0.0, 1.0);
        return _Pose(
          f.colX / 2,
          f.top + (f.height * 0.40 - f.top) * ratio,
          LandingTokens.mascotColumn * math.max(ratio, 0.6),
          BubbleTail.up,
        );
      }
      final top = floor - winH;
      final room = top - LandingTokens.mascotRowGap - (f.top + LandingTokens.topGap);
      final grown = (room * 0.42).clamp(
        baseSize,
        f.medium ? LandingTokens.mascotGrownMedium : LandingTokens.mascotGrown,
      );
      final size = (grown - baseSize > 4 && _flowBubbleHeight <= room) ? grown : baseSize;
      final base = top - LandingTokens.mascotRowGap;
      return _Pose(f.colX + size / 2 - 6, base - size * 0.4, size, BubbleTail.left);
    }

    final pa = pose(a);
    final pb = pose(b);
    var cx = _lerp(pa.cx, pb.cx, t);
    var cy = _lerp(pa.cy, pb.cy, t);
    var mascotSize = _lerp(pa.size, pb.size, t);
    if (hv > 0) {
      final fly = _motion.fly.value;
      cx = _lerp(cx, LandingTokens.mascotCornerX, fly);
      cy = _lerp(cy, f.top + LandingTokens.mascotCornerY, fly);
      mascotSize = _lerp(mascotSize, LandingTokens.mascotCorner, fly);
    }
    final mw = math.max(1.0, mascotSize);
    final mh = math.max(1.0, mascotSize / mascotAspect);
    mascot.layout(BoxConstraints.tight(Size(mw, mh)));
    _place(mascot, cx - mw / 2, cy - mh / 2);

    // ---- bubble ----
    final shownPose = shown == b ? pb : pa;
    if (!early) layoutBubble(shownPose.size);
    final bw = bubble.size.width;
    final bh = bubble.size.height;
    Offset anchor(_Pose p) => p.tail == BubbleTail.up
        ? Offset(p.cx, p.cy + p.size * 0.42 + 4)
        : Offset(p.cx + p.size * 0.5 + 2, p.cy - p.size * 0.05);
    final anchorA = anchor(pa);
    final anchorB = anchor(pb);
    final ax = _lerp(anchorA.dx, anchorB.dx, t);
    var ay = _lerp(anchorA.dy, anchorB.dy, t);
    const tailH = LandingTokens.bubbleTail;
    const sideY = LandingTokens.bubbleSideTailY;
    final double bubbleX;
    final double bubbleY;
    if (shownPose.tail == BubbleTail.up) {
      bubbleX = (ax - bw / 2).clamp(16.0, math.max(16.0, f.width - 16 - bw));
      bubbleY = ay + tailH;
      tail.set(BubbleTail.up, ax - bubbleX);
      _bubbleOrigin = Offset(ax - bubbleX, -tailH);
    } else {
      final base = floor - winH - LandingTokens.mascotRowGap;
      ay = math.min(ay, base - bh + sideY);
      bubbleX = ax + tailH;
      bubbleY = ay - sideY;
      tail.set(BubbleTail.left, sideY);
      _bubbleOrigin = const Offset(-tailH, sideY);
    }
    _place(bubble, bubbleX, bubbleY);
    final tc = t.clamp(0.0, 1.0);
    final dip = _motion.settled ? 1.0 : 1 - 0.55 * math.sin(math.pi * tc);
    _bubbleScale = math.max(0.0, _motion.bubblePop.value * dip * (1 - hv * 2.2).clamp(0.0, 1.0));
    _bubbleAlpha = (_bubbleScale * 2.5).clamp(0.0, 1.0);

    // ---- pill ----
    var pillX = _lerp(f.pillX(a), f.pillX(b), t);
    var pillW = _lerp(f.pillW(a), f.pillW(b), t);
    if (hv > 0) {
      pillX = _lerp(pillX, f.barX, hv);
      pillW = _lerp(pillW, f.barW, hv);
    }
    pill.layout(BoxConstraints.tight(Size(math.max(0.0, pillW), f.pillH)));
    final rise = (1 - _motion.pillIn.value) * LandingTokens.pillRise;
    _place(pill, pillX, f.pillTop + rise);
    _pillAlpha = (_motion.pillIn.value * 1.4).clamp(0.0, 1.0);
  }

  void _queueRelayout() {
    if (_relayoutQueued) return;
    _relayoutQueued = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _relayoutQueued = false;
      if (attached) markNeedsLayout();
    });
  }

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final window = _window!;
    final mascot = _mascot!;
    final bubble = _bubble!;
    final pill = _pill!;
    final windowAlpha = (_windowAlpha * 255).round();
    if (windowAlpha > 0) {
      _windowLayer.layer = context.pushOpacity(
        offset + _offsetOf(window),
        windowAlpha,
        (context, o) => context.paintChild(window, o),
        oldLayer: _windowLayer.layer,
      );
    } else {
      _windowLayer.layer = null;
    }
    context.paintChild(mascot, offset + _offsetOf(mascot));
    final bubbleAlpha = (_bubbleAlpha * 255).round();
    if (bubbleAlpha > 0 && _bubbleScale > 0) {
      final origin = offset + _offsetOf(bubble) + _bubbleOrigin;
      final transform = Matrix4.identity()
        ..translateByDouble(origin.dx, origin.dy, 0, 1)
        ..scaleByDouble(_bubbleScale, _bubbleScale, 1, 1)
        ..translateByDouble(-origin.dx, -origin.dy, 0, 1);
      _bubbleLayer.layer = context.pushTransform(needsCompositing, Offset.zero, transform, (context, o) {
        _bubbleAlphaLayer.layer = context.pushOpacity(
          o + offset + _offsetOf(bubble),
          bubbleAlpha,
          (context, o) => context.paintChild(bubble, o),
          oldLayer: _bubbleAlphaLayer.layer,
        );
      }, oldLayer: _bubbleLayer.layer);
    } else {
      _bubbleLayer.layer = null;
      _bubbleAlphaLayer.layer = null;
    }
    final pillAlpha = (_pillAlpha * 255).round();
    if (pillAlpha > 0) {
      _pillLayer.layer = context.pushOpacity(
        offset + _offsetOf(pill),
        pillAlpha,
        (context, o) => context.paintChild(pill, o),
        oldLayer: _pillLayer.layer,
      );
    } else {
      _pillLayer.layer = null;
    }
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    // Top to bottom: the pill, the mascot, the window. The bubble never takes a touch.
    for (final child in [_pill, _mascot, _window]) {
      if (child == null) continue;
      if (child == _window && _windowAlpha <= 0) continue;
      final hit = result.addWithPaintOffset(
        offset: _offsetOf(child),
        position: position,
        hitTest: (result, transformed) => child.hitTest(result, position: transformed),
      );
      if (hit) return true;
    }
    return false;
  }

  @override
  bool hitTestSelf(Offset position) => true;
}

/// The floating window (LandingWindow): the current step's scene, and the
/// previous one while they cross. Its height follows the two scenes' heights
/// by t; the scenes slide along the axis of travel and cross-fade. Painted as
/// a rounded surface with a rectangular clip — no rounded clip layer (they
/// rasterise black wedges on HarmonyOS); the scenes keep their content clear
/// of the corners.
class LandingWindow extends MultiChildRenderObjectWidget {
  const LandingWindow({
    super.key,
    required this.motion,
    required this.spec,
    required this.color,
    required this.leaving,
    required super.children,
  });

  final LandingMotion motion;
  final LandingWindowSpec spec;
  final Color color;

  /// Whether the first child is the leaving scene (two children while crossing).
  final bool leaving;

  @override
  RenderLandingWindow createRenderObject(BuildContext context) =>
      RenderLandingWindow(motion: motion, spec: spec, color: color, leaving: leaving);

  @override
  void updateRenderObject(BuildContext context, RenderLandingWindow renderObject) {
    renderObject
      ..motion = motion
      ..spec = spec
      ..color = color
      ..leaving = leaving;
  }
}

class _WindowParentData extends ContainerBoxParentData<RenderBox> {}

class RenderLandingWindow extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _WindowParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _WindowParentData> {
  RenderLandingWindow({required LandingMotion motion, required this.spec, required Color color, required bool leaving})
    : _motion = motion,
      _color = color,
      _leaving = leaving;

  LandingMotion _motion;
  set motion(LandingMotion value) {
    if (identical(value, _motion)) return;
    if (attached) _motion.t.animation.removeListener(markNeedsLayout);
    _motion = value;
    if (attached) _motion.t.animation.addListener(markNeedsLayout);
    markNeedsLayout();
  }

  LandingWindowSpec spec;

  Color _color;
  set color(Color value) {
    if (value == _color) return;
    _color = value;
    markNeedsPaint();
  }

  bool _leaving;
  set leaving(bool value) {
    if (value == _leaving) return;
    _leaving = value;
    markNeedsLayout();
  }

  final _clip = LayerHandle<ClipRectLayer>();
  final _alphas = <LayerHandle<OpacityLayer>>[LayerHandle(), LayerHandle()];

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _WindowParentData) child.parentData = _WindowParentData();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _motion.t.animation.addListener(markNeedsLayout);
  }

  @override
  void detach() {
    _motion.t.animation.removeListener(markNeedsLayout);
    super.detach();
  }

  @override
  void dispose() {
    _clip.layer = null;
    for (final h in _alphas) {
      h.layer = null;
    }
    super.dispose();
  }

  List<RenderBox> get _children {
    final list = <RenderBox>[];
    var child = firstChild;
    while (child != null) {
      list.add(child);
      child = childAfter(child);
    }
    return list;
  }

  bool get _crossing => _leaving && childCount == 2;

  double get _tt => _motion.t.value.clamp(0.0, 1.0);

  bool get _forward => _motion.to.index >= _motion.from.index;

  @override
  void performLayout() {
    final maxH = constraints.maxHeight;
    final sceneW = math.max(1.0, spec.sceneWidth);
    final minH = math.min(spec.minHeight, maxH);
    final children = _children;
    final heights = <double>[];
    final steps = _crossing ? [_motion.from, _motion.to] : [_motion.to];
    for (var i = 0; i < children.length; i++) {
      final child = children[i];
      child.layout(
        BoxConstraints(minWidth: sceneW, maxWidth: sceneW, minHeight: minH, maxHeight: maxH),
        parentUsesSize: true,
      );
      final step = i < steps.length ? steps[i] : _motion.to;
      heights.add(step == LandingStep.hello ? spec.helloHeight : child.size.height);
      (child.parentData! as _WindowParentData).offset = Offset.zero;
    }
    final h = heights.isEmpty
        ? spec.helloHeight
        : heights.length == 1
        ? heights.first
        : _lerp(heights[0], heights[1], _motion.t.value);
    size = constraints.constrain(Size(constraints.maxWidth, h.clamp(0.0, maxH)));
  }

  @override
  bool get alwaysNeedsCompositing => true;

  @override
  void paint(PaintingContext context, Offset offset) {
    final rect = offset & size;
    context.canvas.drawRRect(
      RRect.fromRectAndRadius(rect, const Radius.circular(LandingTokens.windowCorner)),
      Paint()..color = _color,
    );
    _clip.layer = context.pushClipRect(needsCompositing, offset, Offset.zero & size, (context, o) {
      final children = _children;
      final sign = _forward ? 1.0 : -1.0;
      for (var i = 0; i < children.length; i++) {
        final child = children[i];
        if (!_crossing) {
          context.paintChild(child, o);
          _alphas[i.clamp(0, 1)].layer = null;
          continue;
        }
        final leaving = i == 0;
        final alpha = leaving ? (1 - _tt * 1.8).clamp(0.0, 1.0) : ((_tt - 0.3) / 0.7).clamp(0.0, 1.0);
        final dx = leaving ? -sign * LandingTokens.sceneSlide * _tt : sign * LandingTokens.sceneSlide * (1 - _tt);
        final a = (alpha * 255).round();
        final handle = _alphas[i.clamp(0, 1)];
        if (a <= 0) {
          handle.layer = null;
          continue;
        }
        handle.layer = context.pushOpacity(
          o + Offset(dx, 0),
          a,
          (context, o) => context.paintChild(child, o),
          oldLayer: handle.layer,
        );
      }
    }, oldLayer: _clip.layer);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) {
    // The leaving scene takes no input while it fades.
    final child = lastChild;
    if (child == null) return false;
    final dx = _crossing ? (_forward ? 1.0 : -1.0) * LandingTokens.sceneSlide * (1 - _tt) : 0.0;
    return result.addWithPaintOffset(
      offset: Offset(dx, 0),
      position: position,
      hitTest: (result, transformed) => child.hitTest(result, position: transformed),
    );
  }

  @override
  bool hitTestSelf(Offset position) => size.contains(position);

  @override
  void visitChildrenForSemantics(RenderObjectVisitor visitor) {
    // Only the arriving scene is read out.
    final child = lastChild;
    if (child != null) visitor(child);
  }
}
