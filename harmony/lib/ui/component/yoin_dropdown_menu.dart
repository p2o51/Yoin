import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../theme/motion.dart';

/// One row of a [showYoinDropdownMenu].
@immutable
final class YoinMenuItem<T> {
  const YoinMenuItem({required this.value, required this.label, this.icon, this.checked = false});

  final T value;
  final String label;
  final IconData? icon;

  /// Shows a trailing check (the active choice).
  final bool checked;
}

/// Yoin's dropdown menu (component/YoinDropdownMenu.kt): an M3 Expressive
/// menu surface opening from [anchor] (global rect) — below it, or above
/// when there is no room — scaling from its top edge and fading in on a
/// spring; tapping outside or system back closes it (Navigator.pop).
/// Resolves with the chosen value, or null.
Future<T?> showYoinDropdownMenu<T>({
  required BuildContext context,
  required Rect anchor,
  required List<YoinMenuItem<T>> items,
  String? semanticLabel,
}) => Navigator.of(context).push(
  _MenuRoute<T>(
    anchor: anchor,
    items: items,
    semanticLabel: semanticLabel,
    capturedThemes: InheritedTheme.capture(from: context, to: Navigator.of(context).context),
  ),
);

/// The global rect of the widget [context] belongs to.
Rect globalRectOf(BuildContext context) {
  final box = context.findRenderObject()! as RenderBox;
  return box.localToGlobal(Offset.zero) & box.size;
}

class _MenuRoute<T> extends PopupRoute<T> {
  _MenuRoute({required this.anchor, required this.items, required this.capturedThemes, this.semanticLabel});

  final Rect anchor;
  final List<YoinMenuItem<T>> items;
  final CapturedThemes capturedThemes;
  final String? semanticLabel;

  @override
  Color? get barrierColor => null;
  @override
  bool get barrierDismissible => true;
  @override
  String? get barrierLabel => semanticLabel ?? 'Dismiss';
  @override
  Duration get transitionDuration => const Duration(milliseconds: 300);

  @override
  Simulation? createSimulation({required bool forward}) => SpringSimulation(
    YoinSprings.standardDefaultSpatial,
    controller?.value ?? (forward ? 0 : 1),
    forward ? 1 : 0,
    controller?.velocity ?? 0,
    tolerance: const Tolerance(distance: 0.001, velocity: 0.01),
  );

  @override
  Widget buildPage(BuildContext context, Animation<double> animation, Animation<double> secondaryAnimation) {
    final media = MediaQuery.of(context);
    return capturedThemes.wrap(
      CustomSingleChildLayout(
        delegate: _MenuLayout(anchor, media.padding),
        child: AnimatedBuilder(
          animation: animation,
          builder: (context, child) {
            final t = animation.value;
            return Opacity(
              opacity: (t * 1.6).clamp(0.0, 1.0),
              child: Transform.scale(
                alignment: Alignment.topCenter,
                scaleY: 0.6 + 0.4 * t.clamp(0.0, 1.2),
                scaleX: 0.9 + 0.1 * t.clamp(0.0, 1.0),
                child: child,
              ),
            );
          },
          child: _MenuSurface<T>(items: items),
        ),
      ),
    );
  }
}

class _MenuLayout extends SingleChildLayoutDelegate {
  _MenuLayout(this.anchor, this.padding);

  final Rect anchor;
  final EdgeInsets padding;

  @override
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) => BoxConstraints(
    minWidth: 112,
    maxWidth: math.min(280, constraints.maxWidth - 32),
    maxHeight: constraints.maxHeight - padding.vertical - 32,
  );

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final double left = anchor.left.clamp(16.0, math.max(16.0, size.width - childSize.width - 16));
    final below = anchor.bottom + 4;
    final double top = below + childSize.height <= size.height - padding.bottom - 16
        ? below
        : math.max(padding.top + 16, anchor.top - 4 - childSize.height);
    return Offset(left, top);
  }

  @override
  bool shouldRelayout(_MenuLayout old) => old.anchor != anchor || old.padding != padding;
}

class _MenuSurface<T> extends StatelessWidget {
  const _MenuSurface({required this.items});

  final List<YoinMenuItem<T>> items;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Material(
      color: colors.surfaceContainer,
      elevation: 3,
      shadowColor: colors.shadow,
      shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(16)),
      clipBehavior: Clip.antiAlias,
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: IntrinsicWidth(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final item in items)
                InkWell(
                  onTap: () => Navigator.of(context).pop(item.value),
                  child: Semantics(
                    selected: item.checked,
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
                      child: Row(
                        children: [
                          if (item.icon != null) ...[
                            Icon(item.icon, size: 22, color: colors.onSurfaceVariant),
                            const SizedBox(width: 12),
                          ],
                          Expanded(
                            child: Text(item.label, style: text.titleMedium?.copyWith(color: colors.onSurface)),
                          ),
                          if (item.checked) ...[
                            const SizedBox(width: 12),
                            Icon(YoinSymbols.check, size: 22, color: colors.primary),
                          ],
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
