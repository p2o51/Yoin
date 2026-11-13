import 'package:flutter/material.dart';

import 'now_playing_screen.dart';
import 'overlay_controller.dart';

/// Device corner radius fallback while the player moves (ScreenCorners.kt;
/// HarmonyOS exposes no corner value to Flutter yet).
const double screenCornerFallback = 28;

/// The Now Playing layer (ShellOverlayDown): scrim + the player, displaced
/// by the controller's ONE offset. Composed only while open or leaving; the
/// vertical drag on the whole player feeds the same controller as the
/// chevron and system back. Hosts its own Navigator so the queue sheet
/// opens above the player.
class NowPlayingLayer extends StatefulWidget {
  const NowPlayingLayer({super.key, required this.controller});

  final NowPlayingOverlayController controller;

  @override
  State<NowPlayingLayer> createState() => _NowPlayingLayerState();
}

class _NowPlayingLayerState extends State<NowPlayingLayer> {
  final GlobalKey<NavigatorState> _navigator = GlobalKey(debugLabel: 'nowPlaying');

  @override
  void initState() {
    super.initState();
    widget.controller.handleInnerBack = _popSheet;
  }

  @override
  void dispose() {
    if (widget.controller.handleInnerBack == _popSheet) widget.controller.handleInnerBack = null;
    super.dispose();
  }

  /// The queue sheet is the innermost back level.
  bool _popSheet() {
    final nav = _navigator.currentState;
    if (nav == null || !nav.canPop()) return false;
    nav.pop();
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        if (!controller.isMounted) return const SizedBox.shrink();
        return LayoutBuilder(
          builder: (context, constraints) {
            controller.height = constraints.maxHeight;
            controller.reduceMotion = MediaQuery.disableAnimationsOf(context);
            return Stack(
              fit: StackFit.expand,
              children: [
                // Scrim: black at 0.5 while expanded, on the effects spring.
                IgnorePointer(
                  child: AnimatedBuilder(
                    animation: controller.fade.animation,
                    builder: (context, _) =>
                        ColoredBox(color: Colors.black.withValues(alpha: 0.5 * controller.fade.value.clamp(0.0, 1.0))),
                  ),
                ),
                AnimatedBuilder(
                  animation: controller.motion,
                  builder: (context, child) {
                    final moving = controller.isMoving;
                    Widget player = child!;
                    if (moving) {
                      player = ClipRSuperellipse(
                        borderRadius: BorderRadius.circular(screenCornerFallback),
                        child: player,
                      );
                    }
                    return IgnorePointer(
                      ignoring: !controller.expanded,
                      child: Opacity(
                        opacity: controller.fade.value.clamp(0.0, 1.0),
                        child: Transform.translate(offset: Offset(0, controller.offsetY), child: player),
                      ),
                    );
                  },
                  child: GestureDetector(
                    behavior: HitTestBehavior.translucent,
                    // Off in Expanded: the lyric list owns vertical drags there.
                    onVerticalDragUpdate: controller.canDrag ? (d) => controller.dragUpdate(d.delta.dy) : null,
                    onVerticalDragEnd: controller.canDrag
                        ? (d) => controller.dragEnd(d.velocity.pixelsPerSecond.dy)
                        : null,
                    onVerticalDragCancel: controller.canDrag ? () => controller.dragEnd(0) : null,
                    child: Material(
                      type: MaterialType.transparency,
                      // MaterialApp's HeroController belongs to the app Navigator.
                      child: HeroControllerScope.none(
                        child: Navigator(
                          key: _navigator,
                          onGenerateRoute: (settings) => PageRouteBuilder<void>(
                            settings: settings,
                            transitionDuration: Duration.zero,
                            reverseTransitionDuration: Duration.zero,
                            pageBuilder: (_, _, _) => NowPlayingScreen(controller: controller),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            );
          },
        );
      },
    );
  }
}
