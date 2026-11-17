import 'package:flutter/material.dart';

import '../../theme/motion.dart';
import 'yoin_loading_indicator.dart';

/// Pull to refresh (component/YoinPullToRefresh.kt): pulling down at the
/// top of any scrollable inside [child] brings a contained loading
/// indicator down from under the box's top edge — the content itself never
/// moves. Past [triggerDistance] a release calls [onRefresh]; the indicator
/// then rests until [refreshing] turns false. The pulled distance settles on
/// the standard default spatial spring.
///
/// Scrollables inside should use clamping physics that are always
/// scrollable so the pull arrives as overscroll even on short content.
class YoinPullToRefresh extends StatefulWidget {
  const YoinPullToRefresh({
    super.key,
    required this.child,
    required this.onRefresh,
    required this.refreshing,
    this.triggerDistance = 80,
    this.restDistance = 64,
    this.indicatorSize = 40,
  });

  final Widget child;
  final VoidCallback onRefresh;
  final bool refreshing;
  final double triggerDistance;
  final double restDistance;
  final double indicatorSize;

  @override
  State<YoinPullToRefresh> createState() => _YoinPullToRefreshState();
}

class _YoinPullToRefreshState extends State<YoinPullToRefresh> with TickerProviderStateMixin {
  late final SpringValue _pull = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardDefaultSpatial);
  bool _dragging = false;
  bool _waiting = false;

  @override
  void didUpdateWidget(YoinPullToRefresh old) {
    super.didUpdateWidget(old);
    if (old.refreshing && !widget.refreshing) {
      _waiting = false;
      if (!_dragging) _pull.animateTo(0);
    } else if (widget.refreshing && !_dragging) {
      _pull.animateTo(widget.restDistance);
    }
  }

  @override
  void dispose() {
    _pull.dispose();
    super.dispose();
  }

  bool _onScroll(ScrollNotification n) {
    if (n.metrics.axis != Axis.vertical || n.depth != 0 && n is! OverscrollNotification) return false;
    switch (n) {
      case OverscrollNotification(:final overscroll, :final dragDetails) when dragDetails != null && overscroll < 0:
        _dragging = true;
        // Resistance grows as it comes down.
        final current = _pull.value;
        final resistance = 0.5 * (1 - (current / (widget.triggerDistance * 2.5)).clamp(0.0, 0.8));
        _pull.snapTo(current - overscroll * resistance);
      case ScrollUpdateNotification(:final scrollDelta?, :final dragDetails) when dragDetails != null && _dragging:
        if (scrollDelta > 0 && _pull.value > 0) _pull.snapTo((_pull.value - scrollDelta).clamp(0.0, double.infinity));
      case ScrollEndNotification():
        if (_dragging) _release();
      default:
        break;
    }
    return false;
  }

  void _release() {
    _dragging = false;
    if (_pull.value >= widget.triggerDistance && !widget.refreshing && !_waiting) {
      _waiting = true;
      _pull.animateTo(widget.restDistance);
      widget.onRefresh();
    } else if (widget.refreshing || _waiting) {
      _pull.animateTo(widget.restDistance);
    } else {
      _pull.animateTo(0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return NotificationListener<ScrollNotification>(
      onNotification: _onScroll,
      child: Listener(
        // A finger lifted without a ScrollEnd (overscroll at rest) still releases.
        onPointerUp: (_) {
          if (_dragging) _release();
        },
        onPointerCancel: (_) {
          if (_dragging) _release();
        },
        child: Stack(
          children: [
            widget.child,
            Positioned(
              left: 0,
              right: 0,
              top: 0,
              child: IgnorePointer(
                child: AnimatedBuilder(
                  animation: _pull.animation,
                  builder: (context, _) {
                    final d = _pull.value;
                    if (d <= 0.5) return const SizedBox.shrink();
                    final progress = (d / widget.triggerDistance).clamp(0.0, 1.0);
                    return Transform.translate(
                      offset: Offset(0, d - widget.indicatorSize),
                      child: Center(
                        child: Opacity(
                          opacity: progress,
                          child: Transform.scale(
                            scale: 0.6 + 0.4 * progress,
                            child: YoinLoadingIndicator(
                              size: widget.indicatorSize,
                              containerColor: colors.secondaryContainer,
                              color: colors.onSecondaryContainer,
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
