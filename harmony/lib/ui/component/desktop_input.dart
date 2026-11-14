/// Mouse, trackpad and keyboard on PC windows (MateBook 2in1 today, the
/// Windows build later): the M3 hover / focus state layer, the click cursor,
/// a visible focus ring in keyboard mode, Enter to activate and the
/// secondary click (right button, two-finger tap) as the long-press. Touch
/// never sees any of it: hover needs a mouse-like pointer and the ring only
/// shows in keyboard highlight mode.
///
/// Material's own InkWell / buttons already draw hover and focus; this is for
/// the custom pressables built on GestureDetector ([ElasticPress], the Home
/// cards, the pills).
library;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';

/// Wraps a tappable [child] for desktop input. [onTap] is what Enter (or the
/// platform's activate key) does while the target has keyboard focus;
/// [onSecondaryTap] is the right click — pass the long-press action there
/// when long-press opens a menu.
///
/// The state layer tints only the pixels [child] paints (srcATop), so it
/// follows any shape — covers, backdrop shapes, pills — without a clip.
class YoinHover extends StatefulWidget {
  const YoinHover({
    super.key,
    required this.child,
    this.onTap,
    this.onSecondaryTap,
    this.onHoverChanged,
    this.layerColor,
    this.focusRadius,
    this.focusStadium = false,
    this.cursor,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onSecondaryTap;

  /// A mouse-like pointer entered (true) or left (false) — for hover-only
  /// affordances (the Home cards' lift).
  final ValueChanged<bool>? onHoverChanged;

  /// The state layer's colour (M3: the content colour). Default onSurface.
  final Color? layerColor;

  /// The focus ring's corner radius; default [DesktopInputTokens.focusRingCorner].
  final BorderRadius? focusRadius;

  /// The ring is a stadium / circle (pills, avatars); wins over [focusRadius].
  final bool focusStadium;

  /// Default: the click cursor when there is something to click.
  final MouseCursor? cursor;

  @override
  State<YoinHover> createState() => _YoinHoverState();
}

class _YoinHoverState extends State<YoinHover> with SingleTickerProviderStateMixin {
  late final SpringValue _layer = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _hovered = false;
  bool _focused = false;

  bool get _interactive => widget.onTap != null || widget.onSecondaryTap != null;

  @override
  void initState() {
    super.initState();
    _layer; // eager: a lazy field first read in dispose() would build a ticker on a dead element
  }

  @override
  void dispose() {
    _layer.dispose();
    super.dispose();
  }

  void _retarget() {
    final target = _focused
        ? DesktopInputTokens.focusStateLayerAlpha
        : (_hovered ? DesktopInputTokens.hoverStateLayerAlpha : 0.0);
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _layer.snapTo(target);
    } else {
      _layer.animateTo(target);
    }
  }

  void _onHover(bool value) {
    if (_hovered == value) return;
    _hovered = value;
    widget.onHoverChanged?.call(value);
    _retarget();
  }

  void _onFocus(bool value) {
    if (_focused == value) return;
    setState(() => _focused = value);
    _retarget();
  }

  @override
  Widget build(BuildContext context) {
    final onTap = widget.onTap;
    final onSecondary = widget.onSecondaryTap;
    final colors = Theme.of(context).colorScheme;
    Widget child = AnimatedBuilder(
      animation: _layer.animation,
      builder: (context, child) => _StateLayer(
        color: (widget.layerColor ?? colors.onSurface).withValues(alpha: _layer.value.clamp(0.0, 1.0)),
        ring: _focused ? colors.secondary : null,
        ringRadius: widget.focusStadium
            ? null
            : (widget.focusRadius ?? BorderRadius.circular(DesktopInputTokens.focusRingCorner)),
        child: child,
      ),
      child: widget.child,
    );
    if (onSecondary != null) {
      // Only the secondary button: primary taps stay with the child's own detector.
      child = GestureDetector(behavior: HitTestBehavior.translucent, onSecondaryTap: onSecondary, child: child);
    }
    return FocusableActionDetector(
      enabled: _interactive,
      mouseCursor: widget.cursor ?? (_interactive ? SystemMouseCursors.click : MouseCursor.defer),
      includeFocusSemantics: false,
      // Hover is read from the pointer itself: the detector's own hover
      // highlight waits for keyboard highlight mode, which a touch-first
      // platform (HarmonyOS) never enters from mouse movement alone.
      onShowFocusHighlight: _onFocus,
      actions: {
        if (onTap != null) ActivateIntent: CallbackAction<ActivateIntent>(onInvoke: (_) => onTap()),
        if (onSecondary != null)
          ContextMenuIntent: CallbackAction<ContextMenuIntent>(onInvoke: (_) => onSecondary()),
      },
      child: MouseRegion(
        opaque: false,
        onEnter: _interactive ? (_) => _onHover(true) : null,
        onExit: (_) => _onHover(false),
        child: child,
      ),
    );
  }
}

/// Shift+F10 / the context-menu key on a focused target: its menu (the
/// right click's action), like Android's and Windows' keyboard context menu.
class ContextMenuIntent extends Intent {
  const ContextMenuIntent();
}

/// The keys that open a focused target's menu.
const Map<ShortcutActivator, Intent> contextMenuShortcuts = {
  SingleActivator(LogicalKeyboardKey.contextMenu): ContextMenuIntent(),
  SingleActivator(LogicalKeyboardKey.f10, shift: true): ContextMenuIntent(),
};

/// Paints [child], tinted by [color] (srcATop: only where the child paints)
/// and ringed by [ring] when set. Structure-stable: no layer while clear.
class _StateLayer extends SingleChildRenderObjectWidget {
  const _StateLayer({required this.color, required this.ring, required this.ringRadius, super.child});

  final Color color;
  final Color? ring;

  /// null = a stadium.
  final BorderRadius? ringRadius;

  @override
  RenderObject createRenderObject(BuildContext context) =>
      _RenderStateLayer(color: color, ring: ring, ringRadius: ringRadius);

  @override
  void updateRenderObject(BuildContext context, _RenderStateLayer renderObject) {
    renderObject
      ..color = color
      ..ring = ring
      ..ringRadius = ringRadius;
  }
}

class _RenderStateLayer extends RenderProxyBox {
  _RenderStateLayer({required Color color, required Color? ring, required BorderRadius? ringRadius})
    : _color = color,
      _ring = ring,
      _ringRadius = ringRadius;

  Color _color;
  set color(Color value) {
    if (value == _color) return;
    final layered = _visible(_color) != _visible(value);
    _color = value;
    if (layered) markNeedsCompositingBitsUpdate();
    markNeedsPaint();
  }

  Color? _ring;
  set ring(Color? value) {
    if (value == _ring) return;
    _ring = value;
    markNeedsPaint();
  }

  BorderRadius? _ringRadius;
  set ringRadius(BorderRadius? value) {
    if (value == _ringRadius) return;
    _ringRadius = value;
    markNeedsPaint();
  }

  // Painting outside our bounds (the ring) is fine; the layer is ours only while tinted.
  @override
  bool get alwaysNeedsCompositing => child != null && _visible(_color);

  /// Below one 8-bit step a spring's settling tail paints nothing: no layer.
  static bool _visible(Color c) => c.a >= 1 / 255;

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    if (_visible(_color)) {
      layer = context.pushColorFilter(
        offset,
        ColorFilter.mode(_color, BlendMode.srcATop),
        (context, offset) => context.paintChild(child, offset),
        oldLayer: layer is ColorFilterLayer ? layer! as ColorFilterLayer : null,
      );
    } else {
      layer = null;
      context.paintChild(child, offset);
    }
    final ring = _ring;
    if (ring != null) {
      const inset = DesktopInputTokens.focusRingOffset + DesktopInputTokens.focusRingWidth / 2;
      final rect = (offset & size).inflate(inset);
      final rrect = (_ringRadius ?? BorderRadius.circular(rect.shortestSide / 2)).toRRect(rect);
      context.canvas.drawRRect(
        rrect,
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = DesktopInputTokens.focusRingWidth
          ..color = ring,
      );
    }
  }
}

/// A visible keyboard focus ring around a Material control (IconButton,
/// InkWell surfaces) whose own focus overlay is too faint on a cover-tinted
/// page. Shows only in keyboard highlight mode, while focus is inside.
class YoinFocusRing extends StatefulWidget {
  const YoinFocusRing({super.key, required this.child, this.radius});

  final Widget child;

  /// Default: a stadium (half the shorter side).
  final BorderRadius? radius;

  @override
  State<YoinFocusRing> createState() => _YoinFocusRingState();
}

class _YoinFocusRingState extends State<YoinFocusRing> {
  bool _inside = false;
  late bool _keyboard = FocusManager.instance.highlightMode == FocusHighlightMode.traditional;

  @override
  void initState() {
    super.initState();
    FocusManager.instance.addHighlightModeListener(_onMode);
  }

  @override
  void dispose() {
    FocusManager.instance.removeHighlightModeListener(_onMode);
    super.dispose();
  }

  void _onMode(FocusHighlightMode mode) {
    final keyboard = mode == FocusHighlightMode.traditional;
    if (keyboard != _keyboard && mounted) setState(() => _keyboard = keyboard);
  }

  @override
  Widget build(BuildContext context) => Focus(
    canRequestFocus: false,
    skipTraversal: true,
    includeSemantics: false,
    onFocusChange: (inside) {
      if (inside != _inside) setState(() => _inside = inside);
    },
    child: _StateLayer(
      color: const Color(0x00000000),
      ring: _inside && _keyboard ? Theme.of(context).colorScheme.secondary : null,
      ringRadius: widget.radius,
      child: widget.child,
    ),
  );
}
