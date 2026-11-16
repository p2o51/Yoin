import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../settings/appearance/screen_corners.dart';
import '../ui/component/corner_mask.dart';
import 'now_playing_presentation.dart';
import 'now_playing_screen.dart';
import 'overlay_controller.dart';

/// The side panel's shadow at [strength] (1 = the panel, 0 = Full): the
/// M3 level-5-ish key + ambient pair for [NowPlayingPanelTokens.shadowElevation],
/// as blurred shape shadows (never black where the panel's corner is).
List<BoxShadow> nowPlayingPanelShadows(double strength) {
  final s = strength.clamp(0.0, 1.0);
  if (s == 0) return const [];
  const e = NowPlayingPanelTokens.shadowElevation;
  return [
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.20 * s),
      blurRadius: e,
      offset: const Offset(0, e / 4),
    ),
    BoxShadow(
      color: Colors.black.withValues(alpha: 0.12 * s),
      blurRadius: e * 1.5,
    ),
  ];
}

/// The Now Playing layer (ShellOverlayDown): scrim + the player, displaced
/// by the controller's ONE offset. Composed only while open or leaving; the
/// vertical drag on the whole player feeds the same controller as the
/// chevron and system back. Hosts its own Navigator so the queue sheet
/// opens above the player.
///
/// While it moves the player wears the screen's corner (ScreenCorners:
/// Settings › Appearance › Screen corners, or the display's own), as do the
/// side panel's start corners.
class NowPlayingLayer extends ConsumerStatefulWidget {
  const NowPlayingLayer({super.key, required this.controller});

  final NowPlayingOverlayController controller;

  @override
  ConsumerState<NowPlayingLayer> createState() => _NowPlayingLayerState();
}

class _NowPlayingLayerState extends ConsumerState<NowPlayingLayer> {
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
    final corner = ref.watch(screenCornerRadiusProvider);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        if (!controller.isMounted) return const SizedBox.shrink();
        return LayoutBuilder(
          builder: (context, constraints) {
            controller.height = constraints.maxHeight;
            controller.reduceMotion = MediaQuery.disableAnimationsOf(context);
            if (controller.frame.panelFamily) return _panelFamily(controller, corner);
            return _fullWindow(controller, corner);
          },
        );
      },
    );
  }

  /// Panel ⇄ Full (Medium / Wide): one container at the end edge, sliding
  /// in from it; no scrim (the content beside stays usable), start corners
  /// and shadow fade out as it grows Full. The drag toward the end and the
  /// corner toggle / back feed the same controller.
  Widget _panelFamily(NowPlayingOverlayController controller, double corner) {
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
                  clipBehavior: Clip.none,
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
                    // The panel's shadow is a blurred shape shadow, not
                    // PhysicalShape's drawShadow: on device that rasterised
                    // a solid black wedge in the bottom-start corner once a
                    // sheet composited over the panel (QA r2_33 / r2_34).
                    // Its own surface and an explicit anti-aliased clip keep
                    // every layer inside — sheets and their scrims included.
                    // The shadow is a plain gradient strip beside the panel's
                    // start edge: blurred shadows (drawShadow and BoxShadow
                    // alike) rasterised as a solid black wedge in the
                    // bottom-start corner on HarmonyOS once a sheet
                    // composited over the panel (QA r2_33, r3_03).
                    PositionedDirectional(
                      start: -NowPlayingPanelTokens.shadowElevation,
                      top: 0,
                      bottom: 0,
                      width: NowPlayingPanelTokens.shadowElevation,
                      child: IgnorePointer(
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              begin: AlignmentDirectional.centerStart,
                              end: AlignmentDirectional.centerEnd,
                              colors: [
                                Colors.black.withValues(alpha: 0),
                                Colors.black.withValues(alpha: 0.10 * (1 - f)),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                    // The corners are painted, not clipped: an anti-aliased
                    // clip over the player's navigator rasterised a black
                    // wedge once a sheet composited over it (QA r3_03).
                    ColoredBox(
                      color: colors.surface,
                      child: CornerMask(
                        radius: corner * (1 - f),
                        topStart: true,
                        bottomStart: true,
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
                        ),
                        child: child!,
                      ),
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

  Widget _fullWindow(NowPlayingOverlayController controller, double corner) => Stack(
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
            borderRadius: BorderRadius.circular(corner),
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
