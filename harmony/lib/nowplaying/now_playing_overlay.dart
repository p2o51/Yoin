import 'package:flutter/material.dart';

import 'now_playing_presentation.dart';
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

  /// The player's own Navigator (the queue sheet opens above it).
  Widget _player(NowPlayingOverlayController controller) => Material(
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
  );

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
            if (controller.frame.panelFamily) return _panelFamily(controller);
            return _fullWindow(controller);
          },
        );
      },
    );
  }

  /// Panel ⇄ Full (Medium / Wide): one container at the end edge, sliding
  /// in from it; no scrim (the content beside stays usable), start corners
  /// and shadow fade out as it grows Full. The drag toward the end and the
  /// corner toggle / back feed the same controller.
  Widget _panelFamily(NowPlayingOverlayController controller) {
    final child = GestureDetector(
      behavior: HitTestBehavior.translucent,
      onHorizontalDragUpdate: controller.canDragPanel ? (d) => controller.panelDragUpdate(d.delta.dx) : null,
      onHorizontalDragEnd: controller.canDragPanel
          ? (d) => controller.panelDragEnd(d.velocity.pixelsPerSecond.dx)
          : null,
      onHorizontalDragCancel: controller.canDragPanel ? () => controller.panelDragEnd(0) : null,
      child: _player(controller),
    );
    return AnimatedBuilder(
      animation: controller.motion,
      builder: (context, child) {
        final colors = Theme.of(context).colorScheme;
        final f = controller.full.value.clamp(0.0, 1.0);
        final corner = Radius.circular(NowPlayingPanelTokens.corner * (1 - f));
        final shape = RoundedSuperellipseBorder(
          borderRadius: BorderRadiusDirectional.only(topStart: corner, bottomStart: corner),
        );
        return Align(
          alignment: AlignmentDirectional.centerEnd,
          child: IgnorePointer(
            ignoring: !controller.expanded,
            child: Transform.translate(
              offset: Offset(controller.offsetX, 0),
              child: SizedBox(
                width: controller.containerWidth,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    // Behind the panel's rounded start corners: the shell's
                    // page colour, not the window's black (Android fills
                    // them with the page's edge).
                    DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
                        ),
                      ),
                    ),
                    PhysicalShape(
                      clipper: ShapeBorderClipper(shape: shape, textDirection: Directionality.of(context)),
                      clipBehavior: Clip.antiAlias,
                      elevation: NowPlayingPanelTokens.shadowElevation * (1 - f),
                      // Opaque so the shadow shows only outside the panel.
                      color: Theme.of(context).colorScheme.surface,
                      shadowColor: Colors.black,
                      child: child,
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
      child: child,
    );
  }

  Widget _fullWindow(NowPlayingOverlayController controller) => Stack(
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
          // The clip is always in the tree and only switched off
          // at rest: wrapping the player on the first moving frame
          // and unwrapping it on the settle frame changed the
          // tree's shape, so the whole player subtree was
          // reparented (re-laid out, re-rastered) at both ends.
          final player = ClipRSuperellipse(
            borderRadius: BorderRadius.circular(screenCornerFallback),
            clipBehavior: controller.isMoving ? Clip.antiAlias : Clip.none,
            child: child,
          );
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
          onVerticalDragEnd: controller.canDrag ? (d) => controller.dragEnd(d.velocity.pixelsPerSecond.dy) : null,
          onVerticalDragCancel: controller.canDrag ? () => controller.dragEnd(0) : null,
          child: _player(controller),
        ),
      ),
    ],
  );
}
