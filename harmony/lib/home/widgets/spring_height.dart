import 'dart:math' as math;

import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../theme/motion.dart';

/// Eases a section's HEIGHT across a re-composition (HomeFeedFrame.kt
/// springHeight): the content swaps in place, and only a change of
/// [heightKey] (the composition's identity — feed units, the stagger) opens a
/// gap between the shown height and the content's, which springs back to zero
/// on the spatial spring. Height that changes with the live width (a column
/// spring) passes through 1:1; the width always follows the constraints. The
/// content is clipped to the shown height while the gap closes, so a growing
/// section never draws over the next one. Reduced motion shows the content height.
class SpringHeight extends StatefulWidget {
  const SpringHeight({super.key, required this.heightKey, required this.child});

  final Object? heightKey;
  final Widget child;

  @override
  State<SpringHeight> createState() => _SpringHeightState();
}

class _SpringHeightState extends State<SpringHeight> with SingleTickerProviderStateMixin {
  late final SpringValue _gap = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _gap;
  }

  @override
  void dispose() {
    _gap.dispose();
    super.dispose();
  }

  void _seed(double gap) {
    if (!mounted) return;
    _gap.snapTo(gap);
    _gap.animateTo(0);
  }

  @override
  Widget build(BuildContext context) => _SpringHeightBox(
        heightKey: widget.heightKey,
        gap: _gap.animation,
        enabled: !MediaQuery.disableAnimationsOf(context),
        onSeed: _seed,
        child: widget.child,
      );
}

class _SpringHeightBox extends SingleChildRenderObjectWidget {
  const _SpringHeightBox({required this.heightKey, required this.gap, required this.enabled, required this.onSeed, super.child});

  final Object? heightKey;
  final Animation<double> gap;
  final bool enabled;
  final ValueChanged<double> onSeed;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderSpringHeight(heightKey: heightKey, gap: gap, enabled: enabled, onSeed: onSeed);

  @override
  void updateRenderObject(BuildContext context, _RenderSpringHeight renderObject) {
    renderObject
      ..heightKey = heightKey
      ..enabled = enabled
      ..onSeed = onSeed;
  }
}

class _RenderSpringHeight extends RenderProxyBox {
  _RenderSpringHeight({required Object? heightKey, required this.gap, required bool enabled, required this.onSeed})
      : _heightKey = heightKey,
        _enabled = enabled;

  final Animation<double> gap;
  ValueChanged<double> onSeed;

  Object? _heightKey;
  set heightKey(Object? value) {
    if (value == _heightKey) return;
    _heightKey = value;
    markNeedsLayout();
  }

  bool _enabled;
  set enabled(bool value) {
    if (value == _enabled) return;
    _enabled = value;
    markNeedsLayout();
  }

  Object? _measuredKey;
  bool _measured = false;
  double _lastShown = 0;

  /// A just-seeded gap, used until the post-frame callback has snapped the spring to it.
  double? _pending;
  final LayerHandle<ClipRectLayer> _clip = LayerHandle<ClipRectLayer>();

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    gap.addListener(markNeedsLayout);
  }

  @override
  void detach() {
    gap.removeListener(markNeedsLayout);
    super.detach();
  }

  @override
  void dispose() {
    _clip.layer = null;
    super.dispose();
  }

  @override
  void performLayout() {
    final child = this.child;
    if (child == null) {
      size = constraints.smallest;
      return;
    }
    child.layout(constraints, parentUsesSize: true);
    final content = child.size.height;
    if (_measured && _heightKey != _measuredKey && _enabled) {
      final seeded = _lastShown - content;
      _pending = seeded;
      SchedulerBinding.instance.addPostFrameCallback((_) {
        _pending = null;
        onSeed(seeded);
      });
    } else if (!_enabled) {
      _pending = null;
    }
    _measured = true;
    _measuredKey = _heightKey;
    final offset = _enabled ? (_pending ?? gap.value) : 0.0;
    final shown = math.max(0.0, content + offset);
    _lastShown = shown;
    size = constraints.constrain(Size(child.size.width, shown));
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    if (child.size.height > size.height) {
      _clip.layer = context.pushClipRect(needsCompositing, offset, Offset.zero & size, super.paint, oldLayer: _clip.layer);
    } else {
      _clip.layer = null;
      super.paint(context, offset);
    }
  }
}
