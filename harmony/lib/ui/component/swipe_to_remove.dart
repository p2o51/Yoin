import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';

/// A row that leaves when swiped toward the start (Compose SwipeToDismissBox,
/// end-to-start only). The row follows the finger 1:1 over an
/// errorContainer backdrop with a delete symbol; let go past
/// [SheetTokens.swipeRemoveFraction] of its width (or flung fast enough) and
/// it springs out, folds its height away, then [onRemove] runs. Short of
/// that it springs back. While [enabled] is false it takes no swipe.
class SwipeToRemove extends StatefulWidget {
  const SwipeToRemove({
    super.key,
    required this.enabled,
    required this.onRemove,
    required this.semanticLabel,
    required this.child,
  });

  final bool enabled;
  final VoidCallback onRemove;

  /// The backdrop symbol's description (and the row's accessibility action).
  final String semanticLabel;
  final Widget child;

  @override
  State<SwipeToRemove> createState() => _SwipeToRemoveState();
}

class _SwipeToRemoveState extends State<SwipeToRemove> with TickerProviderStateMixin {
  late final SpringValue _dx = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);
  late final SpringValue _height = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultSpatial);
  double _width = 0;
  bool _leaving = false;

  @override
  void dispose() {
    _dx.dispose();
    _height.dispose();
    super.dispose();
  }

  void _onUpdate(DragUpdateDetails d) {
    if (!widget.enabled || _leaving) return;
    final next = (_dx.value + d.delta.dx).clamp(-_width, 0.0);
    _dx.snapTo(next);
  }

  void _onEnd(DragEndDetails d) {
    if (!widget.enabled || _leaving) return;
    final velocity = d.primaryVelocity ?? 0;
    if (_dx.value < -_width * SheetTokens.swipeRemoveFraction || velocity < -SheetTokens.swipeRemoveVelocity) {
      _leave();
    } else {
      _dx.animateTo(0);
    }
  }

  void _leave() {
    _leaving = true;
    if (MediaQuery.disableAnimationsOf(context)) {
      widget.onRemove();
      return;
    }
    void folded(AnimationStatus status) {
      if (status != AnimationStatus.completed) return;
      _height.animation.removeStatusListener(folded);
      if (mounted) widget.onRemove();
    }

    void slid(AnimationStatus status) {
      if (status != AnimationStatus.completed) return;
      _dx.animation.removeStatusListener(slid);
      _height.animation.addStatusListener(folded);
      _height.animateTo(0);
    }

    _dx.animation.addStatusListener(slid);
    _dx.animateTo(-_width);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, constraints) {
        _width = constraints.maxWidth;
        return Semantics(
          customSemanticsActions: widget.enabled ? {CustomSemanticsAction(label: widget.semanticLabel): _leave} : null,
          child: GestureDetector(
            behavior: HitTestBehavior.translucent,
            onHorizontalDragUpdate: widget.enabled ? _onUpdate : null,
            onHorizontalDragEnd: widget.enabled ? _onEnd : null,
            onHorizontalDragCancel: widget.enabled
                ? () {
                    if (!_leaving) _dx.animateTo(0);
                  }
                : null,
            child: AnimatedBuilder(
              animation: Listenable.merge([_dx.animation, _height.animation]),
              builder: (context, child) {
                final dx = _dx.value;
                return ClipRect(
                  child: Align(
                    alignment: Alignment.topCenter,
                    heightFactor: _height.value.clamp(0.0, 1.0),
                    child: Stack(
                      children: [
                        if (dx < 0)
                          Positioned.fill(
                            child: ColoredBox(
                              color: colors.errorContainer,
                              child: Align(
                                alignment: AlignmentDirectional.centerEnd,
                                child: Padding(
                                  padding: const EdgeInsetsDirectional.only(end: SheetTokens.queueRowPaddingH),
                                  child: Icon(
                                    YoinSymbols.delete,
                                    color: colors.onErrorContainer,
                                    semanticLabel: widget.semanticLabel,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        Transform.translate(offset: Offset(dx, 0), child: child),
                      ],
                    ),
                  ),
                );
              },
              child: widget.child,
            ),
          ),
        );
      },
    );
  }
}
