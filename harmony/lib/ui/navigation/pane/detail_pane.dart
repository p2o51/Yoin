import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../../nowplaying/overlay_controller.dart'
    show NowPlayingHostWindowScope, NowPlayingOverlayScope, OverlaySpring;
import '../../../shell/chrome_back.dart';
import '../../../theme/motion.dart';
import '../../adaptive/yoin_window_info.dart';
import '../yoin_push_route.dart';
import 'pane_split.dart';

/// The Wide window's detail column (DetailPaneHost / DetailPaneState,
/// adaptive-principles §3): on a Wide, tall window a detail page opens as a
/// column beside the shell — one window, one bar, one Now Playing chain —
/// instead of covering it. Opening from the shell replaces the column's
/// stack; opening from a page in the column pushes onto it. The column's
/// open fraction is the one settle owner of its motion; the shell gives
/// way on the same spring, and both sides read their own width as the
/// window from the moment the column is requested (a width class never
/// changes mid-spring).
class DetailPaneController extends ChangeNotifier {
  DetailPaneController({required TickerProvider vsync}) : open = OverlaySpring(vsync: vsync, initial: 0) {
    open.animation.addStatusListener(_onSettle);
  }

  /// The window's column host (one per window). Read by [DetailPane.open]
  /// for callers above the shell (Now Playing's "go to album").
  static DetailPaneController? active;

  /// The column is open (or opening): the window's bar takes the merged pose.
  static final ValueNotifier<bool> columnOpen = ValueNotifier(false);

  @override
  void notifyListeners() {
    if (identical(active, this)) columnOpen.value = hasEntries;
    super.notifyListeners();
  }

  /// 0 = no column, 1 = the column fully in.
  final OverlaySpring open;

  /// The column's own navigator (its sub-stack).
  final GlobalKey<NavigatorState> navigator = GlobalKey(debugLabel: 'detailColumn');

  /// The user's split (null = the window's default); edited 1:1 by the handle.
  double? shellFraction;

  /// The handle is held.
  bool dragging = false;

  /// Builders of the column's pages, root first (to hand them to the window
  /// when it narrows out of Wide).
  final List<WidgetBuilder> _stack = [];

  /// A new column (not a replacement) mounts a fresh navigator.
  int generation = 0;

  bool _closing = false;
  bool _reduceMotion = false;

  /// A Wide, tall window (set by the host every build).
  bool enabled = false;

  /// The shell's route is the app's top route: pages opened from it go to
  /// the column (set by the shell every build).
  bool shellIsTop = true;

  late final ChromeBackEntry _rootBack = ChromeBackEntry(close);

  /// The column is in the tree: open, or still leaving.
  bool get holdsColumn => _stack.isNotEmpty;

  /// The column is (or is becoming) open: back belongs to it.
  bool get hasEntries => _stack.isNotEmpty && !_closing;

  set reduceMotion(bool value) => _reduceMotion = value;

  /// Opens [builder] as the column's only page (from the shell or above it).
  void openRoot(WidgetBuilder builder) {
    final mounted = holdsColumn && navigator.currentState != null;
    _closing = false;
    if (mounted) {
      _stack
        ..clear()
        ..add(builder);
      _rootBack.detach();
      navigator.currentState!.pushAndRemoveUntil(YoinPushRoute<void>(builder: builder), (_) => false);
      _attachRootBack();
    } else {
      _stack
        ..clear()
        ..add(builder);
      generation++;
      _attachRootBack();
    }
    _animate(1);
    notifyListeners();
  }

  /// Pushes [builder] onto the column (from a page already in it).
  void push(WidgetBuilder builder) {
    final nav = navigator.currentState;
    if (nav == null) return openRoot(builder);
    _stack.add(builder);
    nav.push(YoinPushRoute<void>(builder: builder)).whenComplete(() {
      if (_stack.isNotEmpty && identical(_stack.last, builder)) _stack.removeLast();
    });
  }

  /// The root page's back: the column leaves; its pages stay until it has.
  void close() {
    if (!hasEntries) return;
    _closing = true;
    // Not synchronously: the close can come from the route's own pop-entry loop.
    scheduleMicrotask(() {
      if (_closing) _rootBack.detach();
    });
    _animate(0);
    notifyListeners();
  }

  /// System back while the column owns it: the column's top page, then the column.
  bool handleBack() {
    if (!hasEntries) return false;
    final nav = navigator.currentState;
    if (nav != null && nav.canPop()) {
      nav.pop();
    } else {
      close();
    }
    return true;
  }

  /// The window left Wide with the column open: its pages become ordinary
  /// pushed pages on [app], top last (Android: Wide → narrow turns the
  /// column's pages into pushed Activities).
  void handOver(NavigatorState app) {
    if (!holdsColumn) return;
    final pages = _closing ? const <WidgetBuilder>[] : List.of(_stack);
    _stack.clear();
    _closing = false;
    _rootBack.detach();
    open.snapTo(0);
    notifyListeners();
    for (final page in pages) {
      app.push(YoinPushRoute<void>(builder: page));
    }
  }

  void dragHandle(double delta, double windowWidth) {
    shellFraction = dragShellFraction(windowWidth, shellFraction, delta);
    notifyListeners();
  }

  void _attachRootBack() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      final nav = navigator.currentState;
      if (nav != null && hasEntries) _rootBack.attach(nav);
    });
  }

  void _animate(double target) {
    if (_reduceMotion) {
      open.snapTo(target);
      _onSettle(AnimationStatus.completed);
    } else {
      open.animateTo(target, YoinSprings.standardDefaultSpatial);
    }
  }

  void _onSettle(AnimationStatus _) {
    if (_closing && !open.isAnimating && open.value <= 0.001) {
      _closing = false;
      _stack.clear();
      notifyListeners();
    }
  }

  @override
  void dispose() {
    if (identical(active, this)) active = null;
    open.animation.removeStatusListener(_onSettle);
    _rootBack.dispose();
    open.dispose();
    super.dispose();
  }
}

/// A transient surface pushed over the shell (Library's full-screen search)
/// that steps aside when one of its results opens in the detail column: the
/// page goes where the grid's would, not on top as a pushed page
/// (adaptive-principles §5: search collapses first, keeping its query).
mixin DetailPaneYieldingRoute<T> on ModalRoute<T> {
  /// The surface left for the column (not dismissed by the user).
  bool handedToColumn = false;

  /// Collapses the surface (its own pop motion) for the column.
  void yieldToDetailColumn() {
    handedToColumn = true;
    final nav = navigator;
    if (nav == null) return;
    if (isCurrent) {
      nav.pop();
    } else {
      nav.removeRoute(this);
    }
  }
}

/// Opens detail pages where they belong (used by DetailRoutes only).
abstract final class DetailPane {
  /// True when [builder] went to the column (a Wide, tall window, opened
  /// from the shell, from a page in the column, from a surface that yields
  /// to the column, or from above the shell).
  static bool open(BuildContext context, WidgetBuilder builder) {
    final scope = context.getInheritedWidgetOfExactType<_DetailPaneScope>();
    final pane = scope?.controller ?? DetailPaneController.active;
    if (pane == null || !pane.enabled) return false;
    if (scope?.inColumn ?? false) {
      pane.push(builder);
      return true;
    }
    final route = ModalRoute.of(context);
    if (scope == null && route != null) {
      // Library search over the shell: it collapses and the column opens.
      if (route is DetailPaneYieldingRoute && route.isActive) {
        route.yieldToDetailColumn();
        _openRoot(context, pane, builder);
        return true;
      }
      // A page pushed over the shell (an earlier narrow window's) keeps pushing.
      return false;
    }
    if (!pane.shellIsTop) return false;
    _openRoot(context, pane, builder);
    return true;
  }

  static void _openRoot(BuildContext context, DetailPaneController pane, WidgetBuilder builder) {
    // The page asked for must be visible: Now Playing steps back to the
    // panel where the panel fits beside two columns, else it closes.
    NowPlayingOverlayScope.read(context)?.yieldToColumn(PaneTokens.twoColumnsMinWidth);
    pane.openRoot(builder);
  }
}

class _DetailPaneScope extends InheritedWidget {
  const _DetailPaneScope({required this.controller, required this.inColumn, required super.child});

  final DetailPaneController controller;
  final bool inColumn;

  @override
  bool updateShouldNotify(_DetailPaneScope oldWidget) =>
      oldWidget.controller != controller || oldWidget.inColumn != inColumn;
}

/// Lays the shell and the column side by side: the shell narrows from the
/// window to its share while the column slides in from the end, both on the
/// column's one spring; the gutter carries the M3 drag handle (1:1, clamp
/// only). Each side reads its own width as the window.
class DetailPaneHost extends StatelessWidget {
  const DetailPaneHost({super.key, required this.controller, required this.shell});

  final DetailPaneController controller;
  final Widget shell;

  @override
  Widget build(BuildContext context) {
    controller.reduceMotion = MediaQuery.disableAnimationsOf(context);
    // The width tiers read the content's own (rest) width: beside a Now
    // Playing side panel that is the narrowed window.
    final window = YoinWindowInfo.of(context);
    // Whether the column exists at all belongs to the window: the side
    // panel narrowing the content never turns the column off.
    final host = NowPlayingHostWindowScope.of(context);
    controller.enabled = host.layoutMode == LayoutMode.wide && !host.isCompactHeight;
    final colors = Theme.of(context).colorScheme;
    // Persistent shell: same widget, same place, so it keeps its state.
    final shellSide = _DetailPaneScope(controller: controller, inColumn: false, child: shell);
    return ListenableBuilder(
      listenable: controller,
      builder: (context, _) {
        final holds = controller.holdsColumn;
        final rest = resolvePaneBudget(window.width, controller.shellFraction);
        final shellInfo = holds ? window.forPaneWidth(rest.shellWidth) : window;
        final columnInfo = window.forPaneWidth(rest.paneWidth);
        // Positions read the live width this layout gets: while the side
        // panel is dragged away the content beside it widens 1:1 and both
        // columns follow, so no window background ever shows between them
        // and the panel (principle 5). The tiers above stay at rest.
        return LayoutBuilder(
          builder: (context, constraints) {
            final w = constraints.hasBoundedWidth ? constraints.maxWidth : window.width;
            final budget = resolvePaneBudget(w, controller.shellFraction);
            return AnimatedBuilder(
              animation: controller.open.animation,
              builder: (context, _) {
                final o = controller.open.value.clamp(0.0, 1.0);
                final shellW = holds ? w - (w - budget.shellWidth) * o : w;
                final columnLeft = shellW + PaneTokens.gutter * o;
                return Stack(
                  children: [
                    Positioned(
                      key: const ValueKey('shell'),
                      left: 0,
                      top: 0,
                      bottom: 0,
                      width: shellW,
                      child: YoinWindowScope(info: shellInfo, child: shellSide),
                    ),
                    if (holds) ...[
                      // The gutter: the shell's own page colour, and the handle.
                      Positioned(
                        key: const ValueKey('gutter'),
                        left: shellW,
                        top: 0,
                        bottom: 0,
                        width: PaneTokens.gutter * o,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
                            ),
                          ),
                          child: Opacity(
                            opacity: o,
                            child: _PaneHandle(controller: controller, windowWidth: w),
                          ),
                        ),
                      ),
                      Positioned(
                        key: ValueKey('column${controller.generation}'),
                        left: columnLeft,
                        top: 0,
                        bottom: 0,
                        width: budget.paneWidth,
                        child: IgnorePointer(
                          ignoring: !controller.hasEntries,
                          child: YoinWindowScope(
                            info: columnInfo,
                            child: _DetailPaneScope(
                              controller: controller,
                              inColumn: true,
                              child: ClipRect(child: _ColumnNavigator(controller: controller)),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ],
                );
              },
            );
          },
        );
      },
    );
  }
}

class _ColumnNavigator extends StatelessWidget {
  const _ColumnNavigator({required this.controller});

  final DetailPaneController controller;

  @override
  Widget build(BuildContext context) => HeroControllerScope.none(
    child: Navigator(
      key: controller.navigator,
      // The first page is built with the column and slides in with it: no
      // route animation of its own.
      onGenerateInitialRoutes: (_, _) => [
        if (controller._stack.isNotEmpty) YoinPushRoute<void>(builder: controller._stack.first),
      ],
    ),
  );
}

/// The M3 vertical drag handle in the gutter: 1:1, clamped, no spring.
class _PaneHandle extends StatefulWidget {
  const _PaneHandle({required this.controller, required this.windowWidth});

  final DetailPaneController controller;
  final double windowWidth;

  @override
  State<_PaneHandle> createState() => _PaneHandleState();
}

class _PaneHandleState extends State<_PaneHandle> with SingleTickerProviderStateMixin {
  late final SpringValue _held = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);

  @override
  void dispose() {
    _held.dispose();
    super.dispose();
  }

  void _hold(bool down) {
    widget.controller.dragging = down;
    _held.animateTo(down ? 1 : 0);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return MouseRegion(
      cursor: SystemMouseCursors.resizeColumn,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onHorizontalDragStart: (_) => _hold(true),
        onHorizontalDragUpdate: (d) => widget.controller.dragHandle(d.delta.dx, widget.windowWidth),
        onHorizontalDragEnd: (_) => _hold(false),
        onHorizontalDragCancel: () => _hold(false),
        child: Center(
          child: AnimatedBuilder(
            animation: _held.animation,
            builder: (context, _) {
              final h = _held.value.clamp(0.0, 1.0);
              final width = PaneTokens.handleWidth + (PaneTokens.handleHeldWidth - PaneTokens.handleWidth) * h;
              final height = PaneTokens.handleHeight + (PaneTokens.handleHeldHeight - PaneTokens.handleHeight) * h;
              return Container(
                width: math.max(0, width),
                height: height,
                decoration: ShapeDecoration(
                  color: Color.lerp(colors.outline, colors.onSurface, h),
                  shape: const StadiumBorder(),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
