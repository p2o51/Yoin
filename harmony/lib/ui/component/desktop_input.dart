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

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
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

  void _onHover(bool value) {
    if (_hovered == value) return;
    _hovered = value;
    widget.onHoverChanged?.call(value);
    final target = value ? DesktopInputTokens.hoverStateLayerAlpha : 0.0;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      _layer.snapTo(target);
    } else {
      _layer.animateTo(target);
    }
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
        child: child,
      ),
      child: widget.child,
    );
    if (onSecondary != null) {
      // Only the secondary button: primary taps stay with the child's own detector.
      child = GestureDetector(behavior: HitTestBehavior.translucent, onSecondaryTap: onSecondary, child: child);
    }
    // Keyboard focus shows as the ring ([YoinFocusRingLayer]) in this shape.
    return YoinFocusRing(
      radius: widget.focusStadium
          ? null
          : (widget.focusRadius ?? BorderRadius.circular(DesktopInputTokens.focusRingCorner)),
      child: FocusableActionDetector(
        enabled: _interactive,
        mouseCursor: widget.cursor ?? (_interactive ? SystemMouseCursors.click : MouseCursor.defer),
        includeFocusSemantics: false,
        // Hover is read from the pointer itself: the detector's own hover
        // highlight waits for keyboard highlight mode, which a touch-first
        // platform (HarmonyOS) never enters from mouse movement alone.
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

/// Paints [child] tinted by [color] (srcATop: only where the child paints).
/// Structure-stable: no layer while clear.
class _StateLayer extends SingleChildRenderObjectWidget {
  const _StateLayer({required this.color, super.child});

  final Color color;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderStateLayer(color);

  @override
  void updateRenderObject(BuildContext context, _RenderStateLayer renderObject) => renderObject.color = color;
}

class _RenderStateLayer extends RenderProxyBox {
  _RenderStateLayer(this._color);

  Color _color;
  set color(Color value) {
    if (value == _color) return;
    final layered = _visible(_color) != _visible(value);
    _color = value;
    if (layered) markNeedsCompositingBitsUpdate();
    markNeedsPaint();
  }

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
  }
}

/// The focus ring's shape for the focusable inside [child] (the ring itself
/// is drawn once, by [YoinFocusRingLayer]). Without a marker the ring guesses:
/// a circle / stadium for small controls (icon buttons), the token corner for
/// rows and cards.
class YoinFocusRing extends InheritedWidget {
  const YoinFocusRing({super.key, this.radius, required super.child});

  /// null = a stadium (half the shorter side).
  final BorderRadius? radius;

  @override
  bool updateShouldNotify(YoinFocusRing oldWidget) => oldWidget.radius != radius;
}

/// Draws the keyboard focus ring (M3 focus indicator: 3 dp in `secondary`,
/// 2 dp outside the target) around whatever has primary focus, over [child],
/// only in keyboard highlight mode. One ring for every focusable — Material
/// buttons and InkWells included — so the back buttons and rows show it too.
/// Follows the target while it moves (scroll, springs).
class YoinFocusRingLayer extends StatefulWidget {
  const YoinFocusRingLayer({super.key, required this.child, this.ignore});

  final Widget child;

  /// A node that never gets a ring (the shortcuts' focus anchor).
  final FocusNode? ignore;

  @override
  State<YoinFocusRingLayer> createState() => _YoinFocusRingLayerState();
}

class _YoinFocusRingLayerState extends State<YoinFocusRingLayer> {
  final GlobalKey _box = GlobalKey();
  final ValueNotifier<RRect?> _ring = ValueNotifier(null);
  bool _following = false;

  @override
  void initState() {
    super.initState();
    FocusManager.instance.addListener(_sync);
    FocusManager.instance.addHighlightModeListener(_onMode);
  }

  @override
  void dispose() {
    FocusManager.instance.removeListener(_sync);
    FocusManager.instance.removeHighlightModeListener(_onMode);
    _ring.dispose();
    super.dispose();
  }

  void _onMode(FocusHighlightMode _) => _sync();

  FocusNode? get _target {
    if (FocusManager.instance.highlightMode != FocusHighlightMode.traditional) return null;
    final node = FocusManager.instance.primaryFocus;
    if (node == null || node is FocusScopeNode || node == widget.ignore) return null;
    final context = node.context;
    if (context == null || !context.mounted) return null;
    // Text fields show their caret; no ring.
    if (context.widget is EditableText || context.findAncestorStateOfType<EditableTextState>() != null) return null;
    return node;
  }

  void _sync() {
    if (!mounted) return;
    if (_target == null) {
      _ring.value = null;
    } else {
      _measure();
      _follow();
    }
  }

  /// After every frame the app draws anyway (scroll, springs) while a ring
  /// shows: re-measure. Never asks for frames of its own.
  void _follow() {
    if (_following) return;
    _following = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _following = false;
      if (!mounted || _target == null) return;
      _measure();
      _follow();
    });
  }

  void _measure() {
    final node = _target;
    final host = _box.currentContext?.findRenderObject() as RenderBox?;
    final box = node?.context?.findRenderObject();
    if (node == null || host == null || !host.attached || box is! RenderBox || !box.attached || !box.hasSize) {
      _ring.value = null;
      return;
    }
    final topLeft = box.localToGlobal(Offset.zero, ancestor: host);
    const inset = DesktopInputTokens.focusRingOffset + DesktopInputTokens.focusRingWidth / 2;
    final rect = (topLeft & box.size).inflate(inset);
    final marker = node.context!.getInheritedWidgetOfExactType<YoinFocusRing>();
    final BorderRadius radius;
    if (marker != null) {
      final own = marker.radius;
      radius = own == null ? BorderRadius.circular(rect.shortestSide / 2) : own + BorderRadius.circular(inset);
    } else {
      // Small square-ish controls (icon buttons, back) are round; the rest
      // take the token corner.
      final small = box.size.shortestSide <= 64 && box.size.longestSide <= 64 * 1.5;
      radius = small
          ? BorderRadius.circular(rect.shortestSide / 2)
          : BorderRadius.circular(DesktopInputTokens.focusRingCorner + inset);
    }
    final rrect = radius.toRRect(rect);
    if (_ring.value != rrect) _ring.value = rrect;
  }

  @override
  Widget build(BuildContext context) => Stack(
    key: _box,
    fit: StackFit.passthrough,
    children: [
      widget.child,
      Positioned.fill(
        child: IgnorePointer(
          child: CustomPaint(painter: _RingPainter(_ring, Theme.of(context).colorScheme.secondary)),
        ),
      ),
    ],
  );
}

class _RingPainter extends CustomPainter {
  _RingPainter(this.ring, this.color) : super(repaint: ring);

  final ValueListenable<RRect?> ring;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final r = ring.value;
    if (r == null) return;
    canvas.drawRRect(
      r,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = DesktopInputTokens.focusRingWidth
        ..color = color,
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) => old.ring != ring || old.color != color;
}
