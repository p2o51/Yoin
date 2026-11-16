import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../home/home_edit.dart';
import '../home/home_section.dart';
import '../memories/memories_reveal_scope.dart';
import '../nowplaying/flight.dart';
import '../nowplaying/now_playing_overlay.dart';
import '../nowplaying/now_playing_pill.dart';
import '../nowplaying/now_playing_presentation.dart' show NowPlayingPanelTokens;
import '../nowplaying/now_playing_screen.dart' show heroRestScale, showNowPlayingTrackMenu;
import '../nowplaying/now_playing_view_model.dart' show isPlaybackUnavailable;
import '../nowplaying/overlay_controller.dart';
import '../nowplaying/player_snapshot.dart';
import '../playback/keep_screen_on.dart';
import '../playback/local_ratings.dart';
import '../playback/playback_controller.dart';
import '../playback/playback_strings.dart';
import '../playback/system_requests.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/seam/seam_field.dart' show SeamBarField, SeamBarFieldScope, SeamBarSlot;
import '../ui/navigation/pane/detail_pane.dart';
import '../ui/navigation/pane/pane_split.dart';
import 'app_messages.dart';
import 'bar_edit_pose.dart';
import 'bar_suppression.dart';
import 'chrome_back.dart';
import 'desktop_shortcuts.dart';
import 'detail_chrome.dart';
import 'shell_back_order.dart';
import 'shell_state.dart';
import 'yoin_section.dart';
import 'yoin_bottom_bar.dart';
import 'yoin_edge_split_group.dart';

/// The window's chrome, above the Navigator (adaptive-principles §2: one
/// window, one bar, one Now Playing chain): every route — shell or detail —
/// sits under the same bar, and the Now Playing overlay sits above all.
///
/// Layers, bottom to top: the Navigator [child], the floating bar, the Now
/// Playing overlay (scrim + player), the playback snackbar (above the bar,
/// or at the bottom while the player is up), the pill ⇄ player flight. Owns the one
/// [NowPlayingOverlayController]: the pill, the chevron, the drag and system
/// back (through [ChromeBackEntry] while open) all feed it. Its own
/// [Overlay] gives bar widgets tooltips/menus.
class AppChrome extends ConsumerStatefulWidget {
  const AppChrome({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<AppChrome> createState() => _AppChromeState();
}

class _AppChromeState extends ConsumerState<AppChrome> with TickerProviderStateMixin {
  late final NowPlayingOverlayController _controller = NowPlayingOverlayController(vsync: this);

  /// The window's back order (principle 8): the full-window player takes
  /// system back first; the side panel takes it while it is the newest of
  /// the surfaces beside it (the detail column, Memories), else it waits.
  late final ShellBackLedger _backOrder = ref.read(shellBackLedgerProvider);
  late final ChromeBackEntry _back = ChromeBackEntry(
    () => _controller.handleBack(),
    owns: () => _backOrder.owns(ShellBackOwner.nowPlaying),
    priority: ChromeBackPriority.nowPlaying,
  );

  /// The open state and stage last seen (the back order is noted on changes).
  bool _openNoted = false;
  NowPlayingStage _stageSeen = NowPlayingStage.compact;
  late final OverlayEntry _entry = OverlayEntry(builder: _buildLayers);

  /// 0 = the bar may show, 1 = a bar-less page (setup, search) sent it away.
  late final OverlaySpring _barAway = OverlaySpring(vsync: this, initial: 0);

  /// Where the bar is, for the seams' bottom field (one per window, ProvideSeamBarField).
  late final SeamBarField _seamBar = SeamBarField(vsync: this);

  @override
  void initState() {
    super.initState();
    _backOrder.bindNowPlaying(open: () => _controller.expanded, isPanel: () => _controller.panelOpen);
    _controller.addListener(_syncBack);
    DetailPaneController.columnOpen.addListener(_syncPanelFloor);
    _syncPanelFloor();
  }

  @override
  void didUpdateWidget(AppChrome oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.child != widget.child) _entry.markNeedsBuild();
  }

  @override
  void dispose() {
    _backOrder.unbindNowPlaying();
    _controller.removeListener(_syncBack);
    DetailPaneController.columnOpen.removeListener(_syncPanelFloor);
    _back.dispose();
    _barAway.dispose();
    _seamBar.dispose();
    _controller.dispose();
    // _entry lives and dies with this chrome's own Overlay (already unmounted here).
    super.dispose();
  }

  /// Beside the side panel the content keeps a phone column — two columns
  /// and the gutter while the detail column is open (adaptive-principles §4).
  void _syncPanelFloor() => _controller.minContentWidth = DetailPaneController.columnOpen.value
      ? PaneTokens.twoColumnsMinWidth
      : NowPlayingPanelTokens.minContentWidth;

  /// Now Playing open asks the window to stay awake (while playing and when
  /// the user chose it; NowPlayingKeepScreenOn decides).
  bool _keepAwakeAsked = false;

  void _syncKeepAwake() {
    final ask = _controller.expanded;
    if (ask == _keepAwakeAsked) return;
    _keepAwakeAsked = ask;
    ref.read(nowPlayingKeepScreenOnProvider.notifier).setKeepScreenOn(ask);
  }

  /// The field fades as Now Playing starts to rise and grows back once it has
  /// collapsed (OverlayChromeVisibility), and leaves with the bar when a
  /// bar-less page sends it away (its bounds stop following it while it travels).
  void _syncSeamBar() => _seamBar.shown = !_controller.expanded && !ref.read(barSuppressedProvider);

  /// Opening or closing the player moves it in the back order, and so does
  /// going further into it: the stage leaving Compact (lyrics, focus) makes
  /// it the newest again, so back steps that back before it closes a
  /// surface opened in between.
  void _noteBackOrder() {
    final open = _controller.expanded;
    if (open != _openNoted) {
      _openNoted = open;
      _backOrder.note(ShellBackOwner.nowPlaying, open: open);
    }
    final stage = _controller.stage;
    if (stage == _stageSeen) return;
    _stageSeen = stage;
    if (open && stage != NowPlayingStage.compact) _backOrder.note(ShellBackOwner.nowPlaying, open: true);
  }

  /// Back is the overlay's to take while it is open (the back order decides when).
  void _syncBack() {
    _noteBackOrder();
    _syncKeepAwake();
    _syncSeamBar();
    final navigator = appNavigatorKey.currentState;
    if (_controller.expanded && navigator != null) {
      _back.attach(navigator);
    } else if (!_controller.expanded) {
      // Not synchronously: a close can come from inside the route's own
      // pop-entry loop (system back), which must not change under it.
      scheduleMicrotask(() {
        if (!_controller.expanded) _back.detach();
      });
    }
  }

  /// An open request that came before the queue (card tap while the queue
  /// is still being rebuilt from the native host) waits until then.
  DateTime? _pendingOpenUntil;

  void _open() {
    if (ref.read(playerSnapshotProvider).hasTrack) {
      _pendingOpenUntil = null;
      _controller.openPlayer();
    } else {
      _pendingOpenUntil = DateTime.now().add(nowPlayingOpenRequestTtl);
    }
  }

  void _onHasTrack(bool has) {
    if (!has) {
      // Queue emptied while open (explicit clear): close (spec item 18).
      _controller.close();
      return;
    }
    final until = _pendingOpenUntil;
    _pendingOpenUntil = null;
    if (until != null && DateTime.now().isBefore(until)) _controller.openPlayer();
  }

  Widget _buildLayers(BuildContext context) => NowPlayingOverlayScope(
    controller: _controller,
    // The keyboard (PC windows): one shortcut layer over every page and Now Playing.
    child: YoinShortcuts(
      controller: _controller,
      openNowPlaying: _open,
      navigator: appNavigatorKey,
      child: Stack(
        fit: StackFit.expand,
        children: [
          BesideNowPlayingPanel(controller: _controller, child: widget.child),
          // The bar belongs to the window, but sits centred in the content
          // beside the side panel (it folds to the two destinations there).
          BesideNowPlayingPanel(
            controller: _controller,
            providesWindow: false,
            // The window's bar reports itself to the seams' bottom field.
            child: SeamBarSlot(
              field: _seamBar,
              child: _ChromeBar(controller: _controller, away: _barAway),
            ),
          ),
          // The player's own Navigator blocks the semantics painted before
          // it (its route's barrier). Full, that is right: nothing under it
          // is visible. As the side panel, the bar and the content beside it
          // stay on screen and usable, so they must stay in the a11y tree:
          // the panel is then its own semantics container.
          ListenableBuilder(
            listenable: _controller,
            builder: (context, child) => Semantics(container: _controller.panelOpen, child: child),
            child: NowPlayingLayer(controller: _controller),
          ),
          PlaybackSnackbarHost(
            barAway: Listenable.merge([_controller.bar.animation, _barAway.animation]),
            barAwayValue: () => _barAway.value,
            playerUpValue: () => _controller.bar.value,
          ),
          _Flight(controller: _controller),
        ],
      ),
    ),
  );

  @override
  Widget build(BuildContext context) {
    _controller.window = YoinWindowInfo.of(context);
    // Local ratings live from app start, not from the first player open:
    // pending ratings are resent when an account becomes active and on
    // resume whether or not the player was ever opened (Android keeps this
    // retry in the repository), and a rated track's bar is filled before it
    // first shows.
    ref.listen(localRatingsProvider, (_, _) {});
    ref.listen(nowPlayingOpenRequestsProvider, (_, _) => _open());
    ref.listen(playerSnapshotProvider.select((s) => s.hasTrack), (_, has) => _onHasTrack(has));
    ref.listen(barSuppressedProvider, (_, away) {
      _syncSeamBar();
      if (MediaQuery.disableAnimationsOf(context)) {
        _barAway.snapTo(away ? 1 : 0);
      } else {
        _barAway.animateTo(away ? 1 : 0, YoinSprings.standardDefaultSpatial);
      }
    });
    // Every page under this chrome reads the window's bar for the seams' bottom field.
    return SeamBarFieldScope(
      field: _seamBar,
      child: Overlay(initialEntries: [_entry]),
    );
  }
}

class _ChromeBar extends ConsumerWidget {
  const _ChromeBar({required this.controller, required this.away});

  final NowPlayingOverlayController controller;

  /// Bar-less pages push the bar out on their own spring; the bar leaves by
  /// whichever of the two is further out.
  final OverlaySpring away;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hasTrack = ref.watch(playerSnapshotProvider.select((s) => s.hasTrack));
    final unavailable = ref.watch(playerSnapshotProvider.select(isPlaybackUnavailable));
    final actions = ref.watch(topDetailActionsProvider);
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final memoriesHide = ref.watch(memoriesRevealHandleProvider).barHide;
    final selected = ref.watch(shellSectionProvider);
    final onSelect = ref.read(shellSectionProvider.notifier).select;
    // Home being edited: [Undo|Add] [Done] where the destinations were (BarEditPose).
    final edit = ref.watch(homeEditProvider);
    final hidden = ref.watch(homeLayoutProvider.select((l) => hiddenSections(l).length));
    final editController = ref.read(homeEditProvider.notifier);
    final editPose = selected == YoinSection.home && edit.editing
        ? BarEditPose(
            editing: true,
            leftSlot: resolveEditLeftSlot(edit.undo.length, hidden),
            onLeftSlot: editController.barLeftSlot,
            onDone: editController.done,
          )
        : null;
    // Long-press Library: its search, as Ctrl+K opens it (YoinNavHost onLibraryLongClick).
    void librarySearch() => openLibrarySearchShortcut(ProviderScope.containerOf(context, listen: false));
    void songMenu(Track track) {
      final nav = appNavigatorKey.currentContext;
      if (nav != null) showNowPlayingTrackMenu(nav, controller, track);
    }

    // One Button Group, three forms (ShellChromeForm); a form change (rotate,
    // fold) cross-fades on the effects spring, each form keeping its own pose.
    return _ChromeFormCrossfade(
      form: YoinWindowInfo.of(context).chromeForm,
      builder: (context, form) {
        final edge = form == ShellChromeForm.edgeSplit;
        // Edge capsules leave over their own edge — the cutout's.
        final edgeOut = DisplayPostureScope.cutoutsOf(context).side == EdgeSplitSide.right ? 1.0 : -1.0;
        return AnimatedBuilder(
          // The controller too: the flight landing releases the bar (barComposed).
          animation: Listenable.merge([
            controller,
            controller.bar.animation,
            away.animation,
            memoriesHide,
            DetailPaneController.columnOpen,
          ]),
          builder: (context, child) {
            // Not composed while the player is fully up (its wave stops ticking)
            // or while a bar-less page has sent it away.
            if (!controller.barComposed || (away.value >= 0.999 && !away.isAnimating)) return const SizedBox.shrink();
            final b = math.max(controller.bar.value, away.value).clamp(0.0, 1.0);
            // Memories takes the bar with its reveal: it slides and fades with q (YoinNavHost).
            // With the detail column open the bar carries the column page's
            // Play: Memories (over the shell column only) doesn't take it.
            final hide = DetailPaneController.columnOpen.value ? 0.0 : memoriesHide.value;
            if (hide >= 0.999) return const SizedBox.shrink();
            final hideAlpha = (1 - hide * MemoriesTokens.barHideAlphaGain).clamp(0.0, 1.0);
            // Down below the navigation inset for the bar forms; over their
            // edge for the capsules (the content band × 1.15).
            final offset = edge
                ? Offset(
                    edgeOut *
                        (EdgeSplitTokens.contentStart * EdgeSplitTokens.exitTravelFactor * b +
                            hide * MemoriesTokens.barHideTravel),
                    0,
                  )
                : Offset(
                    0,
                    (BarTokens.height + 2 * BarTokens.portraitVerticalMargin + navBottom) * b +
                        hide * MemoriesTokens.barHideTravel,
                  );
            return IgnorePointer(
              ignoring: b > 0.01 || hide > 0.01,
              child: Opacity(
                opacity: (1 - b) * hideAlpha,
                child: Transform.translate(offset: offset, child: child),
              ),
            );
          },
          // Each capsule is its own Material: nothing here may take the page's taps.
          child: edge
              ? YoinEdgeSplitGroup(
                  selected: selected,
                  onSelect: onSelect,
                  idle: !hasTrack && !unavailable,
                  detailActions: actions,
                  onLibraryLongPress: librarySearch,
                  onPillLongPress: songMenu,
                  editPose: editPose,
                )
              : Align(
                  alignment: Alignment.bottomCenter,
                  child: SafeArea(
                    top: false,
                    child: Material(
                      type: MaterialType.transparency,
                      child: ListenableBuilder(
                        // The side panel folds the pill; the detail column merges the pose.
                        listenable: Listenable.merge([controller, DetailPaneController.columnOpen]),
                        builder: (context, _) => LayoutBuilder(
                          builder: (context, constraints) => YoinBottomBar(
                            form: form,
                            availableWidth: constraints.maxWidth,
                            selected: selected,
                            onSelect: onSelect,
                            hasTrack: hasTrack,
                            detailActions: actions,
                            // The pill became the side panel: only the two destinations stay.
                            navOnly: controller.panelOpen,
                            paneOpen: DetailPaneController.columnOpen.value,
                            onLibraryLongPress: librarySearch,
                            editPose: editPose,
                            pillBuilder: (context, onPress) =>
                                NowPlayingPill(onPressChanged: onPress, onLongPress: songMenu),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
        );
      },
    );
  }
}

/// Cross-fades the Button Group between its forms on the effects spring
/// (YoinNavHost Crossfade(chromeForm, effectsSpring)): the outgoing form
/// keeps laying itself out in its own form while it fades.
class _ChromeFormCrossfade extends StatefulWidget {
  const _ChromeFormCrossfade({required this.form, required this.builder});

  final ShellChromeForm form;
  final Widget Function(BuildContext context, ShellChromeForm form) builder;

  @override
  State<_ChromeFormCrossfade> createState() => _ChromeFormCrossfadeState();
}

class _ChromeFormCrossfadeState extends State<_ChromeFormCrossfade> with TickerProviderStateMixin {
  /// 0 = the previous form, 1 = the current one.
  late final SpringValue _fade = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  ShellChromeForm? _previous;

  @override
  void initState() {
    super.initState();
    _fade.animation.addStatusListener(_onStatus);
  }

  @override
  void didUpdateWidget(_ChromeFormCrossfade oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.form == widget.form) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _previous = null;
      _fade.snapTo(1);
      return;
    }
    _previous = oldWidget.form;
    _fade.snapTo(0);
    _fade.animateTo(1);
  }

  void _onStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed && _previous != null && _fade.value >= 0.999) {
      setState(() => _previous = null);
    }
  }

  @override
  void dispose() {
    _fade.animation.removeStatusListener(_onStatus);
    _fade.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final previous = _previous;
    return AnimatedBuilder(
      animation: _fade.animation,
      builder: (context, _) {
        final t = _fade.value.clamp(0.0, 1.0);
        return Stack(
          fit: StackFit.expand,
          children: [
            // Keyed by form, the same wrapper either way: a form keeps its state from current to outgoing.
            if (previous != null && previous != widget.form && t < 0.999)
              KeyedSubtree(
                key: ValueKey(previous),
                child: IgnorePointer(
                  // The incoming pill owns the flight's slot keys.
                  child: FlightSlotsOff(
                    off: true,
                    child: Opacity(opacity: 1 - t, child: widget.builder(context, previous)),
                  ),
                ),
              ),
            KeyedSubtree(
              key: ValueKey(widget.form),
              child: IgnorePointer(
                ignoring: false,
                child: FlightSlotsOff(
                  off: false,
                  child: Opacity(opacity: t, child: widget.builder(context, widget.form)),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// Content beside the Now Playing side panel (besideNowPlayingPanel +
/// ProvideBesidePanelWindowInfo): narrowed from the end by the panel's live
/// inset, read only in layout so a drag frame re-lays the column without
/// rebuilding it. With [providesWindow] the subtree reads its own remaining
/// width as the window from the moment the panel starts opening (a width
/// class never changes mid-spring); the scope is always in the tree and
/// only its value changes, so pages keep their state.
class BesideNowPlayingPanel extends StatelessWidget {
  const BesideNowPlayingPanel({super.key, required this.controller, required this.child, this.providesWindow = true});

  final NowPlayingOverlayController controller;
  final Widget child;
  final bool providesWindow;

  @override
  Widget build(BuildContext context) {
    final laid = _BesideInset(controller: controller, child: child);
    if (!providesWindow) return laid;
    return ListenableBuilder(
      listenable: controller,
      builder: (context, child) {
        final window = YoinWindowInfo.of(context);
        final info = controller.panelOpen ? window.forPaneWidth(window.width - controller.frame.panelWidth) : window;
        // The window itself stays readable: the detail column belongs to it,
        // not to the width the panel leaves.
        return NowPlayingHostWindowScope(
          window: window,
          child: YoinWindowScope(info: info, child: child!),
        );
      },
      child: laid,
    );
  }
}

class _BesideInset extends SingleChildRenderObjectWidget {
  const _BesideInset({required this.controller, super.child});

  final NowPlayingOverlayController controller;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderBesideInset(controller);

  @override
  void updateRenderObject(BuildContext context, _RenderBesideInset renderObject) =>
      renderObject.controller = controller;
}

class _RenderBesideInset extends RenderProxyBox {
  _RenderBesideInset(this._controller);

  NowPlayingOverlayController _controller;
  set controller(NowPlayingOverlayController value) {
    if (identical(value, _controller)) return;
    if (attached) _controller.besideMotion.removeListener(markNeedsLayout);
    _controller = value;
    if (attached) _controller.besideMotion.addListener(markNeedsLayout);
    markNeedsLayout();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _controller.besideMotion.addListener(markNeedsLayout);
  }

  @override
  void detach() {
    _controller.besideMotion.removeListener(markNeedsLayout);
    super.detach();
  }

  @override
  void performLayout() {
    final inset = _controller.besideInset.clamp(0.0, constraints.maxWidth);
    final child = this.child;
    if (child == null || !constraints.hasBoundedWidth) {
      super.performLayout();
      return;
    }
    child.layout(
      constraints.copyWith(
        minWidth: math.max(0.0, constraints.minWidth - inset),
        maxWidth: constraints.maxWidth - inset,
      ),
      parentUsesSize: true,
    );
    size = constraints.constrain(Size(child.size.width + inset, child.size.height));
  }
}

/// The shared flight's content, from the playing track and the live theme.
class _Flight extends ConsumerWidget {
  const _Flight({required this.controller});

  final NowPlayingOverlayController controller;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final track = ref.watch(playerSnapshotProvider.select((s) => s.track));
    final playing = ref.watch(playerSnapshotProvider.select((s) => s.playWhenReady));
    final source = ref.watch(activeSourceProvider).value;
    if (track == null) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final cover = track.coverArt;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final url = cover == null ? null : source?.resolveCoverUrl(cover, size: (pillCoverRequestDp * dpr).round());
    controller.coverCanFly = url != null;
    final artistBase = text.titleMedium!;
    final rest = heroRestScale(playing: playing, reduced: MediaQuery.disableAnimationsOf(context));
    return FlightLayer(
      controller: controller,
      content: FlightContent(
        title: track.title ?? '',
        artist: track.artist ?? '',
        coverUrl: url,
        pillTitleStyle: text.labelLarge!.copyWith(color: colors.onPrimaryContainer),
        pillArtistStyle: text.labelSmall!.copyWith(
          color: colors.onPrimaryContainer.withValues(alpha: PillMetrics.artistAlpha),
        ),
        heroTitleStyle: text.displaySmall!.copyWith(color: colors.onSurface),
        heroArtistStyle: artistBase.copyWith(
          fontSize: artistBase.fontSize! * 0.9,
          height: (artistBase.height ?? 1.5) / 0.9,
          color: colors.onSurfaceVariant,
        ),
        heroTitleScaleX: rest.title,
        heroArtistScaleX: rest.artist,
      ),
    );
  }
}

/// The shell's snackbar for [PlaybackController.notices] (Android
/// YoinSnackbarHost at the shell, YoinNavHost): one at a time, Short (4 s)
/// or Long (10 s) each, queued; a notice with an action carries its text
/// button. It sits clear of the window's bar — the bar's reserve for the
/// window's chrome form ([snackbarBottomReserve], Android
/// LocalShellChromeInsets) — and keeps that reserve while Now Playing is up
/// (the player's tools row sits inside it, under the snackbar, as on
/// Android). On a bar-less page it follows the bar down to the navigation
/// inset. Notices with no copy show nothing.
class PlaybackSnackbarHost extends ConsumerStatefulWidget {
  const PlaybackSnackbarHost({
    super.key,
    required this.barAway,
    required this.barAwayValue,
    this.playerUpValue = _none,
  });

  static double _none() => 0;

  /// Ticks while the bar moves; [barAwayValue] reads 0 (bar shown) .. 1 (a bar-less page).
  final Listenable barAway;
  final double Function() barAwayValue;

  /// Now Playing up (0 .. 1): the reserve stays however the bar went.
  final double Function() playerUpValue;

  @override
  ConsumerState<PlaybackSnackbarHost> createState() => _PlaybackSnackbarHostState();
}

/// M3's Short snackbar duration (YoinSnackbar SHORT_MS).
const Duration snackbarShortDuration = Duration(milliseconds: 4000);

/// M3's Long snackbar duration (YoinSnackbar LONG_MS), for one with an action.
const Duration snackbarLongDuration = Duration(milliseconds: 10000);

/// How far above the navigation inset the snackbar sits: the bar's reserve
/// for [window]'s chrome form (Android rememberShellChromeInsets' bottom) —
/// the portrait bar's band, the centred bar with its margin and the content
/// gap, nothing beside the edge-split capsules (the snackbar keeps out of
/// their side band instead, [shellChromeInsetsOf]).
double snackbarBottomReserve(YoinWindowInfo window) => switch (window.chromeForm) {
  ShellChromeForm.edgeSplit => 0,
  ShellChromeForm.centeredBar =>
    BarTokens.centeredHeight +
        (window.layoutMode == LayoutMode.wide ? BarTokens.centeredBottomMarginWide : BarTokens.centeredBottomMargin) +
        BarTokens.floatingContentGap,
  ShellChromeForm.portraitBar => BarTokens.height + 2 * BarTokens.portraitVerticalMargin,
};

class _PlaybackSnackbarHostState extends ConsumerState<PlaybackSnackbarHost> {
  StreamSubscription<PlaybackNotice>? _sub;
  final List<PlaybackNotice> _queue = [];
  final List<_ShownSnackbar> _shown = [];
  Timer? _timer;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    _sub = ref.read(playbackControllerProvider.notifier).notices.listen(_onNotice);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _timer?.cancel();
    super.dispose();
  }

  void _onNotice(PlaybackNotice notice) {
    if (!mounted) return;
    if (PlaybackStrings.of(context).noticeMessage(notice) == null) return;
    _queue.add(notice);
    if (_current == null) _showNext();
  }

  void _act(_ShownSnackbar s) {
    if (s.leaving) return;
    final action = s.onAction;
    _dismissCurrent();
    action?.call();
  }

  _ShownSnackbar? get _current => _shown.where((s) => !s.leaving).firstOrNull;

  void _showNext() {
    _timer?.cancel();
    if (_queue.isEmpty) return;
    final notice = _queue.removeAt(0);
    final message = PlaybackStrings.of(context).noticeMessage(notice);
    if (message == null) return _showNext();
    final text = notice is TextNotice ? notice : null;
    setState(
      () => _shown.add(_ShownSnackbar(++_seq, message, actionLabel: text?.actionLabel, onAction: text?.onAction)),
    );
    _timer = Timer((text?.long ?? false) ? snackbarLongDuration : snackbarShortDuration, _dismissCurrent);
  }

  void _dismissCurrent() {
    _timer?.cancel();
    final current = _current;
    if (!mounted || current == null) return;
    setState(() => current.leaving = true);
    // The next one rises while this one sinks (AnimatedContent togetherWith).
    _showNext();
  }

  void _gone(_ShownSnackbar s) {
    if (!mounted) return;
    setState(() => _shown.remove(s));
    if (_shown.isEmpty) _lift(0);
  }

  /// Library's FAB rises over the snackbar showing now (snackbarLiftProvider).
  void _lift(double height) {
    if (mounted) ref.read(snackbarLiftProvider.notifier).set(height);
  }

  @override
  Widget build(BuildContext context) {
    if (_shown.isEmpty) return const Positioned(left: 0, bottom: 0, child: SizedBox.shrink());
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final window = YoinWindowInfo.of(context);
    final reserve = snackbarBottomReserve(window);
    // Beside the edge capsules the snackbar keeps out of their band (and a cutout on the other edge).
    final side = window.chromeForm == ShellChromeForm.edgeSplit ? shellChromeInsetsOf(context) : EdgeInsets.zero;
    return AnimatedBuilder(
      animation: widget.barAway,
      builder: (context, child) {
        final playerUp = widget.playerUpValue().clamp(0.0, 1.0);
        final away = widget.barAwayValue().clamp(0.0, 1.0) * (1 - playerUp);
        return Positioned(
          left: SnackbarTokens.hostSideInset + side.left,
          right: SnackbarTokens.hostSideInset + side.right,
          bottom: navBottom + reserve * (1 - away),
          child: child!,
        );
      },
      child: SnackbarLiftReporter(
        active: _current != null,
        onLift: _lift,
        child: Stack(
          alignment: Alignment.bottomCenter,
          children: [
            for (final s in _shown)
              YoinSnackbar(
                key: ValueKey(s.id),
                message: s.message,
                actionLabel: s.actionLabel,
                onAction: s.onAction == null || s.leaving ? null : () => _act(s),
                leaving: s.leaving,
                dismissLabel: PlaybackStrings.of(context).snackbarDismiss,
                onDismiss: s.leaving ? null : _dismissCurrent,
                onGone: () => _gone(s),
              ),
          ],
        ),
      ),
    );
  }
}

class _ShownSnackbar {
  _ShownSnackbar(this.id, this.message, {this.actionLabel, this.onAction});
  final int id;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;
  bool leaving = false;
}

/// YoinSnackbar.kt numbers (Dart-only; Android keeps them private there).
abstract final class SnackbarTokens {
  /// The host's start / end padding (YoinNavHost).
  static const double hostSideInset = 12;

  /// The pill's outer padding, horizontal and vertical.
  static const double outerHorizontal = 16;
  static const double outerVertical = 12;
  static const double maxWidth = 560;
  static const double corner = 28; // YoinShapeTokens.ExtraLarge
  static const double elevation = 6;
  static const double minHeight = 56;
  static const double paddingStart = 24;
  static const double paddingEnd = 24;

  /// The end padding when an action button ends the row.
  static const double paddingEndAction = 8;

  /// Between the message and the action.
  static const double actionGap = 8;
  static const double paddingVertical = 6;
  static const double textVertical = 10;

  /// Enter scales from / exit scales to this, about the bottom centre.
  static const double scaleFrom = 0.9;
}

/// One Yoin snackbar (Android ui/component/YoinSnackbar.kt): a floating pill
/// on the inverse surface with the message. It rises in on the Expressive
/// spatial spring (slide up its own height, scale 0.9 → 1 from the bottom
/// centre, fade in) and, when [leaving], sinks out on the Standard spring
/// (half its height, scale → 0.9, fade out), then calls [onGone].
class YoinSnackbar extends StatefulWidget {
  const YoinSnackbar({
    super.key,
    required this.message,
    this.actionLabel,
    this.onAction,
    this.leaving = false,
    required this.dismissLabel,
    this.onDismiss,
    this.onGone,
  });

  final String message;

  /// The text button at the row's end (inversePrimary, bold label), and what it does.
  final String? actionLabel;
  final VoidCallback? onAction;
  final bool leaving;
  final String dismissLabel;
  final VoidCallback? onDismiss;
  final VoidCallback? onGone;

  @override
  State<YoinSnackbar> createState() => _YoinSnackbarState();
}

class _YoinSnackbarState extends State<YoinSnackbar> with TickerProviderStateMixin {
  late final SpringValue _move = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _goneSent = false;

  @override
  void initState() {
    super.initState();
    _alpha.animation.addStatusListener(_onStatus);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.disableAnimationsOf(context)) {
        _move.snapTo(1);
        _alpha.animateTo(1);
      } else {
        _move.animateTo(1);
        _alpha.animateTo(1);
      }
      if (widget.leaving) _leave();
    });
  }

  @override
  void didUpdateWidget(YoinSnackbar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.leaving && !oldWidget.leaving) _leave();
  }

  void _leave() {
    if (MediaQuery.disableAnimationsOf(context)) {
      _move.snapTo(0);
    } else {
      _move.spring = YoinSprings.standardDefaultSpatial;
      _move.animateTo(0);
    }
    _alpha.animateTo(0);
  }

  /// The fade-out settled: the card is gone.
  void _onStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed || !widget.leaving || _goneSent) return;
    if (_alpha.value > 0.01) return;
    _goneSent = true;
    widget.onGone?.call();
  }

  @override
  void dispose() {
    _alpha.animation.removeStatusListener(_onStatus);
    _move.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final pill = Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: SnackbarTokens.outerHorizontal,
        vertical: SnackbarTokens.outerVertical,
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: SnackbarTokens.maxWidth),
        child: Material(
          color: scheme.inverseSurface,
          elevation: SnackbarTokens.elevation,
          shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(SnackbarTokens.corner))),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: SnackbarTokens.minHeight),
            child: Padding(
              padding: EdgeInsetsDirectional.only(
                start: SnackbarTokens.paddingStart,
                end: widget.actionLabel != null ? SnackbarTokens.paddingEndAction : SnackbarTokens.paddingEnd,
                top: SnackbarTokens.paddingVertical,
                bottom: SnackbarTokens.paddingVertical,
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Flexible(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: SnackbarTokens.textVertical),
                      child: Text(
                        widget.message,
                        maxLines: 3,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyLarge!.copyWith(
                          color: scheme.onInverseSurface,
                          fontWeight: FontWeight.w500,
                          fontVariations: const [FontVariation.weight(500)],
                        ),
                      ),
                    ),
                  ),
                  if (widget.actionLabel case final label?) ...[
                    const SizedBox(width: SnackbarTokens.actionGap),
                    TextButton(
                      onPressed: widget.onAction,
                      style: TextButton.styleFrom(foregroundColor: scheme.inversePrimary, shape: const StadiumBorder()),
                      child: Text(
                        label,
                        style: text.labelLarge!.copyWith(
                          fontWeight: FontWeight.w700,
                          fontVariations: const [FontVariation.weight(700)],
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
    return Semantics(
      liveRegion: true,
      customSemanticsActions: widget.onDismiss == null
          ? null
          : {CustomSemanticsAction(label: widget.dismissLabel): widget.onDismiss!},
      child: AnimatedBuilder(
        animation: Listenable.merge([_move.animation, _alpha.animation]),
        builder: (context, child) {
          final m = _move.value;
          final entering = !widget.leaving;
          // Rise from its own height below; sink by half of it.
          final travel = entering ? 1.0 : 0.5;
          final scale = SnackbarTokens.scaleFrom + (1 - SnackbarTokens.scaleFrom) * m;
          return Opacity(
            opacity: _alpha.value.clamp(0.0, 1.0),
            child: FractionalTranslation(
              translation: Offset(0, travel * (1 - m)),
              child: Transform.scale(scale: scale, alignment: Alignment.bottomCenter, child: child),
            ),
          );
        },
        child: pill,
      ),
    );
  }
}
