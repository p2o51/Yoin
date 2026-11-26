import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/gestures.dart' show PointerScaleEvent, PointerSignalEvent, kTouchSlop;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show SchedulerBinding, SchedulerPhase;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../memories/ledger/memory_ledger_home_card.dart';
import '../memories/ledger/memory_ledger_view_model.dart';
import '../data/home/home_drag_core.dart';
import '../data/home/home_drag_rules.dart' show DragArrangement, dragRows;
import '../data/home/home_edit_rules.dart';
import '../data/home/home_grid_rules.dart';
import '../data/model/model.dart';
import '../memories/memories_pull.dart';
import '../nowplaying/haptics.dart';
import '../shell/bar_scroll_hide.dart';
import '../shell/chrome_back.dart';
import '../shell/shell_back_order.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/expressive/loading_indicator.dart';
import '../ui/component/seam/chrome_seam.dart' show SeamBackground;
import '../ui/component/seam/seam_field.dart' show SeamFieldViewport;
import '../ui/component/user_message.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import 'activity_bento.dart';
import 'home_drag_engine.dart';
import 'home_edit.dart';
import 'home_edit_gestures.dart';
import 'home_edit_lift.dart';
import 'home_edit_list.dart';
import 'home_edit_wiggle.dart';
import 'home_feed_density.dart';
import 'home_feed_rows.dart';
import 'home_grid_resize.dart';
import 'home_grid_sections.dart';
import 'home_jbi_hidden.dart';
import 'home_lyric_count_handle.dart';
import 'home_header.dart';
import 'home_made_for_you.dart';
import 'home_memory_signals.dart';
import 'home_metrics.dart';
import 'home_rows_handle.dart';
import 'home_rows_resize.dart';
import 'home_other_profiles.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'rediscover_selection.dart';
import 'home_shelves.dart';
import 'jump_back_in.dart';
import 'lyric_highlight_section.dart';
import 'lyric_highlight_view_model.dart';
import 'rediscover_section.dart';
import 'widgets/cover_palette.dart';
import 'widgets/home_artwork.dart' show HomeCoverRequest;
import 'widgets/home_motion.dart';
import 'widgets/seam.dart';

/// Home (RootSection; root back is never intercepted). One scrolling list
/// on the page gradient: header, then one item per visible section in
/// layout order. The status-bar tide sits above the list; text fades into
/// it. The feed enters once (fade + 16 dp settle), the sections play the
/// staged reveal once per process, and the list keeps clear of the bar.
class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  /// Tests start each case with a fresh process.
  @visibleForTesting
  static void resetProcessState() {
    _HomeScreenState._entrancePlayed = false;
    StagedReveal.resetLatch();
  }

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> with TickerProviderStateMixin {
  /// hasPlayedInitialContentEntrance, process-wide.
  static bool _entrancePlayed = false;

  final ScrollController _scroll = ScrollController();
  late final SeamFlow _flow = SeamFlow(vsync: this);
  late final StagedReveal _reveal = StagedReveal(vsync: this);
  late final SpringValue _feedAlpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  /// 1 = settled 16 dp low (entering / leaving), 0 = in place.
  late final SpringValue _feedRise = SpringValue(vsync: this, initial: _entrancePlayed ? 0 : 1, spring: YoinSprings.defaultSpatial);
  final ValueNotifier<bool> _paletteGate = ValueNotifier(false);

  /// The window's bar scroll-hide this feed drives.
  late final ShellBarScrollHide _barScrollHide = ref.read(shellBarScrollHideProvider);
  Timer? _gateTimer;
  bool _warmedUp = false;
  bool _scrolling = false;
  bool _reduced = false;
  HomeContent? _lastContent;
  String? _scope;
  final Set<HomeSection> _shownSections = {};

  /// False until the first Content frame of a scope is up: sections in that frame don't grow in.
  bool _sectionsSeeded = false;

  /// The feed's resting shape (frame class, N, cover columns) from YoinWindowInfo.
  HomeFeedDensity? _density;

  /// A frame-class change eases the margins from the old formula to the new
  /// one (HomeFeedFrame.kt): 0 = [_blendFrom], 1 = the current class's insets.
  late final SpringValue _frameBlend = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultSpatial);
  FeedFrameInsets? _blendFrom;

  /// The margins last laid out, so an interrupted blend leaves from where it stood.
  FeedFrameInsets? _lastInsets;

  /// Edit Home's progress: 0 = the feed, 1 = editing (plates, spacing, header pose).
  late final SpringValue _edit = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// System back leaves edit mode (= Done) while it is on — after a
  /// full-window or side-panel player, before the column and Memories
  /// (the window's back order).
  late final ShellBackLedger _backOrder = ref.read(shellBackLedgerProvider);
  late final ChromeBackEntry _editBack = ChromeBackEntry(
    () => scheduleMicrotask(() {
      if (mounted) ref.read(homeEditProvider.notifier).exit();
    }),
    owns: () => _backOrder.owns(ShellBackOwner.homeEdit),
    priority: ChromeBackPriority.homeEdit,
  );
  final GlobalKey _headerKey = GlobalKey();
  final Map<HomeSection, GlobalKey> _itemKeys = {};

  /// The section painted in the overlay, above its neighbours: lifted (the
  /// lift's scale and shadow), until the lift has fallen back.
  HomeSection? _lifted;
  Size _liftSize = Size.zero;

  /// Edit mode's wiggle: one clock for the page, read by every card in paint.
  late final HomeEditWiggle _wiggle = HomeEditWiggle(vsync: this);

  /// The edit controller this page defers exits through while it carries.
  late final HomeEditController _editController = ref.read(homeEditProvider.notifier);

  /// Pick up and drag in place (HomeEditGestures, HomeDragEngine): the springs, the normal-mode press
  /// charge, the lift, the drag and the compact list.
  HomeEditSpecs _specs = HomeEditSpecs(reduced: false);
  late final HomeEditPressState _press = HomeEditPressState(this);
  late final HomeDragEngine _drag = HomeDragEngine(
    vsync: this,
    specs: () => _specs,
    wiggle: _wiggle,
    commitArrangement: (arrangement, {required twoColumn}) => ref
        .read(homeEditProvider.notifier)
        .commitArrangement(arrangement.ids, arrangement.pairs, twoColumn: twoColumn),
    finishDeferredExit: (reason) {
      if (mounted) ref.read(homeEditProvider.notifier).finishExit(done: reason == HomeEditExitReason.done);
    },
  )..host = _FeedDragHost(this);

  /// The page's origin, which the dragged block follows the finger from.
  final LayerLink _pageLink = LayerLink();

  /// The entry plate growing from the long-press's square (port sheet §2.3).
  PlateFrom? _plateFrom;

  /// The page stack every edit position is measured in (the feed sits at its origin).
  final GlobalKey _pageKey = GlobalKey();
  final GlobalKey _trayKey = GlobalKey();
  final Map<HomeSection, GlobalKey> _hideKeys = {};
  final Map<HomeSection, GlobalKey> _handleKeys = {};

  /// The ⌟ (or the Lyric Cards count) took the press being dispatched: the edit gestures leave it.
  bool _handleGrabbed = false;

  /// A block is lifted or dragged (the row resizes were put at rest as it began).
  bool _dragHolding = false;

  /// The feed's frame and section spacing last laid out (the drag reads them between frames).
  HomeFeedFrame? _frame;
  double _spacing = 0;
  double? _pageHeight;

  /// The geometry a drag was laid out in (width, frame class, tabletop, two columns): a change mid-drag rolls it
  /// back (and drops a two-column resize).
  (double, FeedFrameClass, bool, bool)? _dragGeometry;

  /// The feed lays out in two columns now (homeFeedTwoColumn: a Wide frame ≥ 840 × 480, never tabletop).
  bool _twoColumn = false;

  /// Two columns: each displayed section's cell frame (its own margins and width), as last built.
  final Map<HomeSection, HomeFeedFrame> _cellFrames = {};

  /// Two columns' ⌟: the span and units resize in flight.
  late final HomeGridResize _grid = HomeGridResize(
    vsync: this,
    commitSpan: (section, commit) => ref.read(homeEditProvider.notifier).commitSpan(section, commit),
    commitUnits: (section, units) => ref.read(homeEditProvider.notifier).setUnits(section, units),
    reduced: () => _reduced,
    slop: () => kTouchSlop,
  );

  /// What the feed shows at its width (HomeLayout.shownAt): the large-screen default for a never-customized
  /// layout in two columns.
  HomeLayout get _shownLayout => ref.read(homeLayoutProvider).shownAt(_twoColumn);

  /// When the feed last scrolled (a blank tap this soon after only stops the fling).
  final Stopwatch _clock = Stopwatch()..start();
  int _lastScrollMs = -100000;

  /// The bar's Add scrolls the "Hidden" tray into view on this spring.
  late final SpringValue _trayScroll = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  bool _trayScrolling = false;
  final Map<HomeSection, GlobalKey> _bodyKeys = {};
  final Map<HomeSection, LayerLink> _links = {};
  final Map<HomeSection, OverlayPortalController> _portals = {};
  final GlobalKey _listKey = GlobalKey();

  /// The feed's bottom padding (the bar's room): auto-scroll's lower edge sits above it.
  double _feedBottomInset = 0;

  /// The app going to the background folds edit mode away.
  AppLifecycleListener? _lifecycle;

  /// While Jump Back In's "not now" menu is up: the route it stands over (Home's).
  ModalRoute<Object?>? _hideMenuBase;

  /// Edit mode's row resize (Activities, Jump Back In): the ⌟ handles' engine.
  late final HomeRowsEngine _rows = HomeRowsEngine(
    vsync: this,
    editing: () => mounted && ref.read(homeEditProvider).editing,
    carrying: () => _drag.isCarrying || _drag.liftSection != null,
    currentPreset: (section) => ref.read(homeLayoutProvider).rowsOf(section),
    commit: (section, preset) => ref.read(homeEditProvider.notifier).setRows(section, preset),
    reduced: () => _reduced,
  )..autoScroll = _RowsAutoScroll(this);

  /// Edit mode's Lyric Cards count: its ⌟ handle's drag.
  late final HomeLyricCountEngine _lyricCount = HomeLyricCountEngine(
    vsync: this,
    editing: () => mounted && ref.read(homeEditProvider).editing,
    blocked: () => _drag.isCarrying || _drag.liftSection != null || _rows.dragging,
    saved: () => lyricCardsFor(ref.read(homeLayoutProvider).rowsOf(HomeSection.lyricHighlight)),
    commit: (preset) => ref.read(homeEditProvider.notifier).setRows(HomeSection.lyricHighlight, preset),
    reduced: () => _reduced,
  );

  GlobalKey _keyFor(HomeSection s) => _itemKeys.putIfAbsent(s, GlobalKey.new);
  GlobalKey _bodyKeyFor(HomeSection s) => _bodyKeys.putIfAbsent(s, GlobalKey.new);
  LayerLink _linkFor(HomeSection s) => _links.putIfAbsent(s, LayerLink.new);
  OverlayPortalController _portalFor(HomeSection s) => _portals.putIfAbsent(s, OverlayPortalController.new);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _flow;
    _reveal;
    _feedAlpha;
    _feedRise;
    _frameBlend;
    _edit;
    _rows;
    _lyricCount;
    _grid;
    _wiggle;
    _press;
    _drag.addListener(_onDrag);
    _drag.resizeActive = () => _rows.dragging || _grid.dragging;
    _edit.animation.addListener(_onEditProgress);
    _editController.deferExit = _deferExit;
    _backOrder.bindHomeEdit(() => ref.read(homeEditProvider).editing);
    _trayScroll.animation.addListener(_onTrayScroll);
    _lifecycle = AppLifecycleListener(
      onHide: () {
        if (mounted) ref.read(homeEditProvider.notifier).exit();
      },
      // Each return to Home (after a while) brings other lyric cards, and reads Made For You again.
      onShow: _homeShown,
    );
    _scroll.addListener(() => _flow.onScroll(_scroll.position.pixels));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _onState(ref.read(homeViewModelProvider));
      _homeShown();
    });
  }

  /// Home came back into view (Android LifecycleStartEffect → onHomeShown): from the
  /// background, from Library, or from a page pushed over it.
  void _homeShown() {
    if (!mounted) return;
    ref.read(lyricHighlightCardsProvider.notifier).onHomeShown();
    ref.read(madeForYouProvider.notifier).onHomeShown();
  }

  @override
  void dispose() {
    _gateTimer?.cancel();
    _scroll.dispose();
    _flow.dispose();
    _reveal.dispose();
    _feedAlpha.dispose();
    _feedRise.dispose();
    _frameBlend.dispose();
    _lifecycle?.dispose();
    _edit.dispose();
    _backOrder.unbindHomeEdit();
    _editBack.dispose();
    _wiggle.dispose();
    _trayScroll.dispose();
    if (_editController.deferExit == _deferExit) _editController.deferExit = null;
    _drag.removeListener(_onDrag);
    _drag.dispose();
    _press.dispose();
    _rows.dispose();
    _lyricCount.dispose();
    _grid.dispose();
    _paletteGate.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final next = HomeFeedDensity.of(YoinWindowInfo.of(context));
    final previous = _density;
    _density = next;
    if (previous == null || previous.frameClass == next.frameClass) return;
    // Only a resting-width threshold changes the class; the margins blend
    // from where they stand (mid-blend too) to the new formula.
    if (_lastInsets == null || MediaQuery.disableAnimationsOf(context)) {
      _blendFrom = null;
      _frameBlend.snapTo(1);
      return;
    }
    _blendFrom = _lastInsets;
    _frameBlend.snapTo(0);
    _frameBlend.animateTo(1);
  }

  void _onState(HomeUiState state) {
    if (!mounted) return;
    switch (state) {
      case HomeContent():
        if (_scope != null && state.scopeKey != _scope) {
          // Another account starts at the top.
          if (_scroll.hasClients) _scroll.jumpTo(0);
          _shownSections.clear();
          _sectionsSeeded = false;
        }
        _scope = state.scopeKey;
        _lastContent = state;
        if (_entrancePlayed && _feedAlpha.value == 0 && _feedRise.value == 0) {
          _feedAlpha.snapTo(1); // A re-created Home paints at once.
        } else {
          _feedAlpha.animateTo(1);
        }
        _reduced ? _feedRise.snapTo(0) : _feedRise.animateTo(0);
        _entrancePlayed = true;
        if (state.hasSomethingToReveal) _reveal.start(reducedMotion: _reduced);
        _armGate();
      case HomeLoading() || HomeError():
        if (_lastContent != null) {
          _feedAlpha.animateTo(0);
          _reduced ? _feedRise.snapTo(0) : _feedRise.animateTo(1);
        }
    }
  }

  void _armGate() {
    if (_warmedUp || _gateTimer != null || _scrolling) return;
    _gateTimer = Timer(HomeTimings.paletteWarmup, () {
      _gateTimer = null;
      _warmedUp = true;
      if (!_scrolling) _paletteGate.value = true;
    });
  }

  /// How far the feed can still scroll toward its end (the bottom field's approach shrinks over the last 40).
  double _remainingScroll() {
    if (!_scroll.hasClients) return double.infinity;
    final position = _scroll.positions.last;
    if (!position.hasContentDimensions) return double.infinity;
    return (position.maxScrollExtent - position.pixels).clamp(0.0, double.infinity);
  }

  bool _onScrollNotification(ScrollNotification n) {
    // Scrolling the feed down takes the bar out, up brings it back (ShellBarScrollHide) — not a drag's
    // edge scroll.
    if (!_drag.isCarrying) {
      _barScrollHide.onScrollNotification(n, devicePixelRatio: MediaQuery.devicePixelRatioOf(context));
    }
    if (n.depth != 0) return false;
    if (n is ScrollUpdateNotification) _lastScrollMs = _clock.elapsedMilliseconds;
    if (n is ScrollStartNotification) {
      _scrolling = true;
      _paletteGate.value = false;
      _gateTimer?.cancel();
      _gateTimer = null;
    } else if (n is ScrollEndNotification) {
      _scrolling = false;
      if (_warmedUp) {
        _openPaletteGate();
      } else {
        _armGate();
      }
    }
    return false;
  }

  /// A scroll can end inside a layout (a trackpad's pan-zoom drag cancelled as a pinch stills the feed): the
  /// palette gate then opens after the frame.
  void _openPaletteGate() {
    if (SchedulerBinding.instance.schedulerPhase != SchedulerPhase.persistentCallbacks) {
      _paletteGate.value = true;
      return;
    }
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (mounted && !_scrolling) _paletteGate.value = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    _reduced = MediaQuery.disableAnimationsOf(context);
    _flow.reduced = _reduced;
    if (_specs.reduced != _reduced) _specs = HomeEditSpecs(reduced: _reduced);
    ref.listen(homeViewModelProvider, (_, next) => _onState(next));
    final state = ref.watch(homeViewModelProvider);
    final info = YoinWindowInfo.of(context);
    final twoColumn = homeFeedTwoColumn(info);
    _twoColumn = twoColumn;
    // Edits are made on what the feed shows at its width (the draft follows a width change: homeDraftAtWidth).
    ref.read(homeEditProvider.notifier).twoColumn = twoColumn;
    final layout = ref.watch(homeLayoutProvider).shownAt(twoColumn);
    final signals = ref.watch(homeMemorySignalsProvider);
    final editing = ref.watch(homeEditProvider).editing;
    ref.listen(homeEditProvider.select((e) => e.editing), (_, on) => _onEditing(on));
    ref.listen(homeEditProvider.select((e) => e.trayRequests), (_, _) => _scrollToTray());
    // A changed preset (a step, Undo, Reset) springs its section to the new stop.
    ref.listen(homeLayoutProvider, (previous, next) {
      if (previous != null) _rows.onLayoutChange(previous, next);
    });
    final colors = Theme.of(context).colorScheme;
    final density = _density ?? HomeFeedDensity.of(YoinWindowInfo.of(context));
    final padding = MediaQuery.viewPaddingOf(context);
    final content = state is HomeContent ? state : _lastContent;

    final feed = AnimatedBuilder(
      animation: Listenable.merge([_frameBlend.animation, _edit.animation, _grid]),
      builder: (context, _) => LayoutBuilder(
        builder: (context, constraints) {
          // Margins and widths follow the LIVE width; N and the cover columns the resting one.
          final width = constraints.maxWidth;
          // A resize, a new width class or tabletop mid-drag takes the drag's geometry away: it rolls back
          // to the order before the lift, nothing written (Android HomeDragEngine.cancel).
          final geometry = (width, density.frameClass, info.isTabletop, twoColumn);
          if (_dragGeometry != null && _dragGeometry != geometry) {
            if (_drag.isCarrying) {
              scheduleMicrotask(() {
                if (mounted) _drag.cancel();
              });
            }
            if (_grid.active) scheduleMicrotask(_grid.snap);
          }
          _dragGeometry = geometry;
          final target = feedFrameInsets(width, density.frameClass);
          final from = _blendFrom;
          final blend = _frameBlend.value;
          final insets = from == null || blend >= 1 ? target : FeedFrameInsets.lerp(from, target, blend);
          _lastInsets = insets;
          final frame = homeFeedFrame(width, density, insets: insets);
          final bottom = (frame.landscape ? HomeFeedMetrics.landscapeBottomPadding : LibraryTokens.bottomGroupPadding) + padding.bottom;
          _feedBottomInset = bottom;
          final e = _edit.value;
          final spacing = frame.itemSpacing + (HomeEditMetrics.editItemSpacing - frame.itemSpacing) * homeEditSmoothstep(e);
          _frame = frame;
          _spacing = spacing;
          _pageHeight = constraints.maxHeight.isFinite ? constraints.maxHeight : null;
          final list = CustomScrollView(
            key: _listKey,
            controller: _scroll,
            // A pinch holds the feed still (the scroll it may have started from included).
            physics: _drag.list.pinching ? const NeverScrollableScrollPhysics() : null,
            // While a block is lifted or dragged every section stays laid out: the drag reads every row's
            // place, and the edge scroll brings rows in that are already measured.
            cacheExtent: _drag.liftSection == null && !_drag.isCarrying ? null : HomeEditMetrics.dragCacheExtent,
            slivers: [
              SliverPadding(
                padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
                sliver: SliverToBoxAdapter(
                  child: HomeHeader(
                    key: _headerKey,
                    accountTitle: content?.accountTitle,
                    compactHeight: frame.landscape,
                    onEditHome: ref.read(homeEditProvider.notifier).enter,
                    editProgress: _edit.animation,
                    // Edit mode's one-hand switch to the compact list, where the account control was.
                    editAction: HomeEditViewToggle(engine: _drag, enabled: editing && !_drag.list.on),
                  ),
                ),
              ),
              if (content != null)
                if (!twoColumn)
                  SliverList.list(children: _sections(content, layout, frame, signals, e: e, spacing: spacing))
                else
                  // Two columns: one box for every row. A section moving to another row (a pair forming or
                  // splitting, a drop beside) takes its state with it, which a lazy list's separate items
                  // can't hand over mid-layout.
                  SliverToBoxAdapter(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: _sections(content, layout, frame, signals, e: e, spacing: spacing),
                    ),
                  ),
              if (content != null && content.hasAccount)
                SliverPadding(
                    padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end, top: spacing),
                    sliver: SliverToBoxAdapter(
                      child: KeyedSubtree(
                        key: _trayKey,
                        child: e > 0.001
                            ? Opacity(opacity: e.clamp(0.0, 1.0), child: const HomeEditTray())
                            : (layout.visibleV1Sections.isEmpty ? const HomeEmptyLayoutCard() : const HomeEditFooter()),
                      ),
                    ),
                  ),
              SliverToBoxAdapter(child: SizedBox(height: bottom)),
            ],
          );
          // The edit gestures (HomeEditGestures): a long-press below the header
          // enters with that block lifted, a body hold or a handle drag lifts,
          // a lift carries, a tap on the page's blank space leaves.
          return RawGestureDetector(
            behavior: HitTestBehavior.translucent,
            // The ⌟'s strip below its section's box (the plate's outset) is
            // the feed's to hit-test: a finger landing there still grabs it.
            gestures: {
              HomeRowsGrabRecognizer: GestureRecognizerFactoryWithHandlers<HomeRowsGrabRecognizer>(
                () => HomeRowsGrabRecognizer(debugOwner: this),
                (r) => r
                  // The Lyric Cards count handle reaches past its section's box the same way.
                  ..onDown = ((position, t) =>
                      _handleGrabbed = _rows.grabAt(position, t) || _lyricCount.grabAt(position))
                  ..onMove = ((y, t) {
                    _rows.dragTo(y, t);
                    _lyricCount.dragTo(y);
                  })
                  ..onUp = ((t) {
                    _rows.release(timeMs: t);
                    _lyricCount.release();
                  })
                  ..onCancel = () {
                    _rows.release(cancelled: true);
                    _lyricCount.release(cancelled: true);
                  },
              ),
              HomeEditGestureRecognizer: GestureRecognizerFactoryWithHandlers<HomeEditGestureRecognizer>(
                () => HomeEditGestureRecognizer(debugOwner: this),
                (r) => r
                  ..hitTest = (content?.hasAccount ?? false) ? _editHitTest : ((_) => const HomeEditHitExcluded())
                  ..toPage = _toPage
                  ..editing = (() => mounted && ref.read(homeEditProvider).editing)
                  ..carryLetGo = (() => _drag.letGo)
                  ..flingInProgress = _flingInProgress
                  ..run = _runEditActions
                  ..slop = MediaQuery.gestureSettingsOf(context).touchSlop ?? kTouchSlop,
              ),
            },
            child: Listener(
              // A finger takes the page from the bar's Add scroll.
              onPointerDown: (_) => _trayScrolling = false,
              // The compact list covers the feed: nothing in it needs painting meanwhile.
              child: Opacity(
                opacity: _drag.list.covers ? 0 : 1,
                // Two columns: the ⌟ is the grid's (span and units), not the one-column row resize.
                child: twoColumn ? list : HomeRowsScope(engine: _rows, child: list),
              ),
            ),
          );
        },
      ),
    );

    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
        ),
      ),
      child: CompositedTransformTarget(
        link: _pageLink,
        // A PC's trackpad pinches the list in and out the way two fingers do (PointerPanZoom), and a
        // Ctrl + wheel's scale steps it; a mouse uses the header's list / cards switch.
        child: Listener(
          onPointerPanZoomStart: (_) => _trackpadPinch(start: true),
          onPointerPanZoomUpdate: (event) => _trackpadPinch(scale: event.scale),
          onPointerPanZoomEnd: (_) => _trackpadPinch(end: true),
          onPointerSignal: _onPointerSignal,
          child: Stack(
        key: _pageKey,
        fit: StackFit.expand,
        children: [
          // Back from a page pushed over Home, or from Library (Android: Home started again).
          _UncoveredProbe(onUncovered: _homeShown),
          MemoriesPullDetector(
            scroll: _scroll,
            enabled: state is HomeContent && !editing,
            child: NotificationListener<ScrollNotification>(
              onNotification: _onScrollNotification,
              child: HomePaletteGate(
                open: _paletteGate,
                child: SeamScope(
                  flow: _flow,
                  lineY: padding.top + HomeSeamMetrics.tideRest,
                  // Artwork near the bar opens into the bottom field (seamDissolveViewport remainingPx).
                  child: SeamFieldViewport(
                    flow: _flow,
                    background: SeamBackground([colors.surfaceContainer, colors.surface, colors.surfaceContainerLow]),
                    remaining: _remainingScroll,
                    child: AnimatedBuilder(
                      animation: Listenable.merge([_feedAlpha.animation, _feedRise.animation]),
                      child: feed,
                      builder: (context, child) => IgnorePointer(
                        ignoring: state is! HomeContent,
                        child: Opacity(
                          opacity: _feedAlpha.value.clamp(0.0, 1.0),
                          child: Transform.translate(
                            offset: Offset(0, _feedRise.value * HomeFeedMetrics.entranceRise),
                            child: child,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          // The compact list over the feed, inside the tide (HomeEditList): a pinch in brings it.
          if (content != null && content.hasAccount && (editing || _drag.list.shown))
            Positioned.fill(child: _editList(content, layout, density)),
          Positioned.fill(
            child: IgnorePointer(
              child: RepaintBoundary(
                child: CustomPaint(
                  painter: TidePainter(flow: _flow, color: colors.surfaceContainer, statusBarHeight: padding.top),
                ),
              ),
            ),
          ),
          Positioned.fill(
            child: IgnorePointer(child: _LoadingIndicator(visible: state is HomeLoading)),
          ),
          if (state is HomeError) Positioned.fill(child: _ErrorPage(error: state.error)),
        ],
        ),
        ),
      ),
    );
  }

  /// A trackpad's pinch in edit mode (nothing lifted): the list follows its scale as two fingers' would.
  void _trackpadPinch({bool start = false, double? scale, bool end = false}) {
    if (!mounted || !ref.read(homeEditProvider).editing) return;
    final list = _drag.list;
    if (start) {
      if (_drag.liftSection == null && !_drag.isCarrying && !_rows.dragging && !_grid.dragging) list.pinchStart();
    } else if (scale != null) {
      list.pinchBy(scale);
    } else if (end) {
      list.pinchEnd();
    }
  }

  /// Ctrl + wheel (a PointerScaleEvent) in edit mode: in toward the list, out toward the full layout.
  void _onPointerSignal(PointerSignalEvent event) {
    if (event is! PointerScaleEvent || !mounted || !ref.read(homeEditProvider).editing) return;
    if (_drag.liftSection != null || _drag.isCarrying || _drag.list.pinching) return;
    final list = _drag.list;
    if (event.scale < 1 && !list.on) list.set(true);
    if (event.scale > 1 && list.on) list.set(false);
  }

  /// The compact list (HomeEditList): the feed's order, its own gestures run through Home's executor.
  Widget _editList(HomeContent content, HomeLayout layout, HomeFeedDensity density) {
    final colors = Theme.of(context).colorScheme;
    final frame = _frame;
    final padding = MediaQuery.viewPaddingOf(context);
    final edit = ref.read(homeEditProvider.notifier);
    final order = layout.visibleV1Sections;
    return HomeEditList(
      engine: _drag,
      order: order,
      covers: (section) => _stripCovers(content, section),
      background: [colors.surfaceContainer, colors.surface, colors.surfaceContainerLow],
      padding: EdgeInsets.only(top: padding.top + HomeFeedMetrics.headerTopPadding, bottom: _feedBottomInset),
      contentStart: frame?.start ?? 0,
      contentEnd: frame?.end ?? 0,
      scrollArea: _carrySafeArea,
      toPage: _toPage,
      gestures: (r, hitTest, flinging) => r
        ..hitTest = hitTest
        ..toPage = _toPage
        ..editing = (() => mounted && ref.read(homeEditProvider).editing && _drag.list.on)
        ..carryLetGo = (() => _drag.letGo)
        ..flingInProgress = flinging
        ..run = _runEditActions,
      onHide: edit.hide,
      onMove: edit.move,
      tray: const HomeEditTray(),
    );
  }

  List<Widget> _sections(
    HomeContent content,
    HomeLayout layout,
    HomeFeedFrame frame,
    HomeMemorySignals signals, {
    required double e,
    required double spacing,
  }) {
    final out = <Widget>[];
    final editingNow = e > 0.001;
    // All Memories (the memory ledger): shown once it has an entry.
    final ledger = ref.watch(memoryLedgerProvider).value;
    // What the other accounts play most (a switch away); none with one account.
    final otherProfileItems = ref.watch(otherProfileItemsProvider).value ?? const <OtherProfileItem>[];
    // Lyric Cards: the user's own picks (no Gemini Nano here), or the first-use intro.
    final lyricCards = ref.watch(lyricHighlightCardsProvider);
    final lyricIntro = ref.watch(lyricHighlightIntroProvider);
    // Made For You: the service's playlists for this account (none for Subsonic: the section hides).
    final madeForYou = ref.watch(madeForYouProvider);
    // Mid-drag the feed draws the drag's live order: the other blocks make way as its slot changes.
    final visible = (_drag.activeList ? null : _drag.arrangement?.ids) ?? layout.visibleV1Sections;
    // The wiggle follows P and the feed's order (Home is muted with its tickers when covered).
    _wiggle.update(editing: ref.read(homeEditProvider).editing, progress: e, order: visible, reduced: _reduced);
    // The signals are the same account's, or none (a switch in flight).
    final mine = signals.scope == content.scopeKey;
    // Cards the user put away for a while ("not now") stay off the shelf.
    final hidden = ref.watch(jbiHiddenProvider).keysAt(content.scopeKey, ref.read(homeClockProvider)().millisecondsSinceEpoch);
    final jbi = jbiShelfCards(content.grid, mine ? signals : null, hidden);
    final editing = ref.read(homeEditProvider).editing;
    final added = {for (final a in content.recentlyAddedAlbums) a.id};
    final rediscover = mine
        ? [
            for (final r in signals.rediscover)
              if (!added.contains(r.albumId)) r,
          ]
        : const <HomeRediscoverItem>[];
    final nowMs = ref.read(homeClockProvider)().millisecondsSinceEpoch;
    void playLyric(HomeLyricHighlight card) => ref.read(lyricHighlightCardsProvider.notifier).play(card);
    Widget lyricIntroCard(LyricHighlightIntro intro) => LyricHighlightIntroCard(
      intro: intro,
      onDismiss: ref.read(lyricHighlightIntroProvider.notifier).dismiss,
    );
    // Edit mode's "not now": a tap on a card opens its menu (owner 2026-10-13). Always in the tree, so
    // entering and leaving keep the shelf's state.
    Widget jbiScope(Widget child) => JbiHideScope(
      onTap: editing ? (cardContext, card) => _openJbiHideMenu(content.scopeKey, cardContext, card) : null,
      child: child,
    );

    // Whether a section has anything to show (an empty one shows its placeholder only while editing).
    bool has(HomeSection section) => switch (section) {
      HomeSection.activities => true,
      HomeSection.jumpBackIn => jbi.isNotEmpty,
      HomeSection.recentlyAdded => content.recentlyAddedTracks.isNotEmpty || content.recentlyAddedAlbums.isNotEmpty,
      HomeSection.recentlyPlayed => content.recentlyPlayed.isNotEmpty,
      HomeSection.yourPlaylists => content.playlists.isNotEmpty,
      HomeSection.rediscover => rediscover.isNotEmpty,
      HomeSection.memoryGrid => ledger != null && !ledger.isEmpty,
      HomeSection.otherProfiles => otherProfileItems.isNotEmpty,
      HomeSection.madeForYou => madeForYou.isNotEmpty,
      HomeSection.lyricHighlight => lyricIntro != null || lyricCards.isNotEmpty,
    };

    // The staged reveal's beat: the hero first, Jump Back In second, the rest together.
    int beatOf(HomeSection section) => switch (section) {
      HomeSection.activities => 0,
      HomeSection.jumpBackIn => 1,
      _ => 2,
    };

    Widget padded(HomeFeedFrame f, Widget child) => Padding(
      padding: EdgeInsetsDirectional.only(start: f.start, end: f.end),
      child: child,
    );

    // One column: the section's own layout for the feed's frame.
    Widget columnBody(HomeSection section) => switch (section) {
      HomeSection.activities => padded(
        frame,
        ActivitiesSection(content: content, frame: frame, preset: layout.rowsOf(HomeSection.activities)),
      ),
      HomeSection.jumpBackIn => padded(
        frame,
        jbiScope(JumpBackInSection(cards: jbi, frame: frame, preset: layout.rowsOf(HomeSection.jumpBackIn))),
      ),
      HomeSection.recentlyAdded => RecentlyAddedSection(
        tracks: content.recentlyAddedTracks,
        albums: content.recentlyAddedAlbums,
        frame: frame,
      ),
      HomeSection.recentlyPlayed => RecentlyPlayedSection(albums: content.recentlyPlayed, frame: frame),
      HomeSection.yourPlaylists => YourPlaylistsSection(playlists: content.playlists, frame: frame),
      HomeSection.rediscover => RediscoverSection(items: rediscover, frame: frame, nowMs: nowMs),
      HomeSection.memoryGrid => padded(
        frame,
        Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            HomeSectionTitle(HomeStrings.of(context).sectionMemoryGrid),
            const SizedBox(height: HomeSectionMetrics.shelfSpacing),
            HomeEditBlockWiggle(child: MemoryLedgerHomeCard(state: ledger!)),
          ],
        ),
      ),
      HomeSection.otherProfiles => OtherProfilesSection(items: otherProfileItems, frame: frame),
      // The service's playlists for the account; a tap opens the playlist.
      HomeSection.madeForYou => MadeForYouSection(playlists: madeForYou, frame: frame),
      // Lines the user picked, two to four cards; a tap plays from one.
      HomeSection.lyricHighlight =>
        lyricIntro != null
            ? padded(frame, lyricIntroCard(lyricIntro))
            : LyricHighlightSection(
                cards: lyricCards,
                count: lyricCardsFor(layout.rowsOf(HomeSection.lyricHighlight)),
                frame: frame,
                scrollEnabled: !editingNow,
                onPlay: playLyric,
              ),
    };

    // Two columns: the section's own layout on the grid, for its cell's width and its row's units.
    Widget gridBody(HomeSection section, HomeFeedFrame f, HomeColumnCell cell) => switch (section) {
      HomeSection.activities => GridActivitiesSection(content: content, frame: f, cell: cell),
      HomeSection.jumpBackIn => jbiScope(GridJumpBackInSection(cards: jbi, frame: f, cell: cell)),
      HomeSection.recentlyAdded => GridRecentlyAddedSection(
        tracks: content.recentlyAddedTracks,
        albums: content.recentlyAddedAlbums,
        frame: f,
        cell: cell,
        scrollable: !editingNow,
      ),
      HomeSection.recentlyPlayed => gridRecentlyPlayed(context, content.recentlyPlayed, f, cell, !editingNow),
      HomeSection.yourPlaylists => gridYourPlaylists(context, content.playlists, f, cell, !editingNow),
      HomeSection.rediscover => GridRediscoverSection(
        items: rediscover,
        nowMs: nowMs,
        frame: f,
        cell: cell,
        scrollable: !editingNow,
      ),
      HomeSection.memoryGrid => GridMemorySection(state: ledger!, frame: f, cell: cell),
      HomeSection.otherProfiles => gridOtherProfiles(context, otherProfileItems, f, cell, !editingNow),
      HomeSection.madeForYou => gridMadeForYou(context, madeForYou, f, cell, !editingNow),
      HomeSection.lyricHighlight => GridLyricCardsSection(
        cards: lyricCards,
        count: lyricCardsFor(layout.rowsOf(HomeSection.lyricHighlight)),
        frame: f,
        cell: cell,
        onPlay: playLyric,
        scrollable: !editingNow,
        intro: lyricIntro == null ? null : lyricIntroCard(lyricIntro),
      ),
    };

    // A section that lands after the feed is up grows in; the first paint does not.
    Widget appear(HomeSection section, Widget body) {
      final animate = _sectionsSeeded && !_shownSections.contains(section);
      _shownSections.add(section);
      final revealed = section == HomeSection.activities && content.activityEntries.isEmpty
          ? body
          : StagedBeat(
              beat: _reveal.beats[beatOf(section)].animation,
              rise: beatOf(section) == 0 ? HomeRevealMetrics.heroRise : HomeRevealMetrics.rise,
              scaleFrom: beatOf(section) == 0 ? HomeRevealMetrics.heroScaleFrom : 1,
              child: body,
            );
      return SectionAppear(animate: animate, child: revealed);
    }

    if (!_twoColumn) {
      _cellFrames.clear();
      for (final section in visible) {
        final shows = has(section);
        // Editing, an empty section shows its placeholder on its plate.
        if (!shows && !editingNow) continue;
        final i = visible.indexOf(section);
        if (!shows) {
          out.add(_editItem(
            section,
            frame,
            spacing,
            e,
            i,
            visible,
            // No cards: the whole block sways (homeEditBlockWiggle).
            padded(frame, HomeEditBlockWiggle(child: HomeEditPlaceholder(section: section))),
            resizable: false,
          ));
          continue;
        }
        // Rows (D1): a section with presets, showing its cards (not Activities' empty card).
        final resizable = section.supportsRows && !(section == HomeSection.activities && content.activityEntries.isEmpty);
        out.add(_editItem(
          section,
          frame,
          spacing,
          e,
          i,
          visible,
          appear(section, columnBody(section)),
          resizable: resizable,
          childTaps: section == HomeSection.jumpBackIn,
          // Lyric Cards takes its card count from its own dots, not the row resize.
          handle: section == HomeSection.lyricHighlight ? HomeLyricCountHandle(engine: _lyricCount, progress: e) : null,
        ));
      }
    } else {
      // Two columns (HomeFeedRows): rows of one full section or two halves, every cell exactly its row's grid
      // height, each laid out for its own width. A partner with nothing to show leaves its half full width.
      final displayed = [
        for (final s in visible)
          if (editingNow || has(s)) s,
      ];
      final pairs = (_drag.activeList ? null : _drag.arrangement?.pairs) ?? layout.pairs;
      final rows = homeFeedRows(displayed, pairs, true);
      _cellFrames.clear();
      final enabled = layout.visibleV1Sections;
      final total = frame.start + frame.content + frame.end;
      const gap = HomeColumnTokens.rowGap;
      // Every cell in one Stack, keyed by section, each row at the sum of the rows (and gaps) above it: a section
      // moving to another row (a pair forming or splitting, a drop beside) moves within one parent, its state with
      // it — a move between two rows' own boxes would reparent it mid-layout.
      final cells = <(HomeSection, Widget)>[];
      final outlines = <Widget>[];
      var y = 0.0;
      for (final row in rows) {
        y += spacing;
        final live = _grid.liveHeight(row);
        final units = live != null ? _grid.unitTarget : layout.unitsOfRow(row);
        final height = live ?? homeGridHeightDp(units);
        final span = _grid.liveSpan(row);
        final widths = homeRowCellWidths(
          row,
          frame.content.floor(),
          gap.round(),
          span?.$1,
          (span?.$2 ?? frame.content).round(),
        );
        final sections = row.sections;
        final half = row is HomeFeedRowHalves;
        for (var k = 0; k < sections.length; k++) {
          final section = sections[k];
          final w = k < widths.length ? widths[k].toDouble() : frame.content;
          // The cell reaches from the page edge (or the middle of the row's gap) to the other: its own margins.
          final HomeFeedFrame cellFrame;
          final double left;
          if (!half) {
            cellFrame = homeCellFrame(start: frame.start, content: w, end: math.max(0, total - frame.start - w));
            left = 0;
          } else if (k == 0) {
            cellFrame = homeCellFrame(start: frame.start, content: w, end: gap / 2);
            left = 0;
          } else {
            cellFrame = homeCellFrame(start: gap / 2, content: w, end: frame.end);
            left = total - (gap / 2 + w + frame.end);
          }
          _cellFrames[section] = cellFrame;
          final cellWidth = cellFrame.start + cellFrame.content + cellFrame.end;
          if (_lifted == section) _liftSize = Size(cellWidth, height);
          final cell = HomeColumnCell(units: units, half: half || span?.$1 == section, height: height);
          final shows = has(section);
          final body = shows
              ? appear(section, gridBody(section, cellFrame, cell))
              : padded(cellFrame, HomeEditBlockWiggle(child: HomeEditPlaceholder(section: section)));
          final place = HomeGridPlace.of(
            section,
            rows: rows,
            enabledOrder: enabled,
            fullWidth: frame.content,
            layout: layout,
          );
          final i = visible.indexOf(section);
          cells.add((
            section,
            Positioned(
              key: ValueKey(('home-cell', section)),
              left: left,
              top: y,
              width: cellWidth,
              height: height,
              child: _editItem(
                section,
                cellFrame,
                0,
                e,
                i,
                visible,
                HomeGridBox(height: height, child: body),
                resizable: false,
                childTaps: section == HomeSection.jumpBackIn && shows,
                grid: place,
              ),
            ),
          ));
        }
        if (span != null) {
          outlines.add(
            Positioned(
              left: 0,
              right: 0,
              top: y,
              height: height,
              child: IgnorePointer(child: _spanOutline(row, span.$1, frame)),
            ),
          );
        }
        y += height;
      }
      // The dragged block (or the one a span drag widens) draws over the rest.
      final top = _grid.spanSection ?? _drag.onTopSection;
      cells.sort((a, b) => (a.$1 == top ? 1 : 0).compareTo(b.$1 == top ? 1 : 0));
      out.add(
        SizedBox(
          height: y,
          child: Stack(
            clipBehavior: Clip.none,
            children: [for (final (_, cell) in cells) cell, ...outlines],
          ),
        ),
      );
    }
    if (!_sectionsSeeded) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _sectionsSeeded = true);
    }
    return out;
  }

  /// A span drag's dashed outline over [row]: the width [dragged] will snap to.
  Widget _spanOutline(HomeFeedRow row, HomeSection dragged, HomeFeedFrame frame) {
    final half = (frame.content - HomeColumnTokens.rowGap) / 2;
    final toHalf = _grid.spanTarget != HomeSpan.full;
    final width = toHalf ? half : frame.content;
    final rightAnchored = row is HomeFeedRowHalves && row.right == dragged;
    final left = rightAnchored ? frame.start + frame.content - width : frame.start;
    return CustomPaint(
      painter: HomeSpanOutlinePainter(
        color: Theme.of(context).colorScheme.primary,
        left: left,
        width: width,
        top: 0,
        bottom: 0,
        strong: _grid.spanDragging,
      ),
    );
  }

  /// One feed item: its gap above and the edit plate (inert at rest). The
  /// press charge shrinks it about the finger; lifted, it paints above its
  /// neighbours.
  Widget _editItem(
    HomeSection section,
    HomeFeedFrame frame,
    double spacing,
    double e,
    int index,
    List<HomeSection> visible,
    Widget child, {
    required bool resizable,
    bool childTaps = false,
    Widget? handle,
    HomeGridPlace? grid,
  }) {
    final edit = ref.read(homeEditProvider.notifier);
    final lifted = _lifted == section;
    final track = resizable ? _rows.track(section) : null;
    // Two columns: the ⌟ drags the span and the units, the reader steps the units and pairs without a drag.
    final shownNow = edit.shown;
    final gridHandle = grid == null
        ? null
        : HomeGridHandle(
            place: grid,
            resize: _grid,
            progress: e,
            onSpan: (commit) => edit.commitSpan(section, commit),
            onUnits: (units) => edit.setUnits(section, units),
            enabled: () =>
                mounted && ref.read(homeEditProvider).editing && !_drag.isCarrying && _drag.liftSection == null,
          );
    final canBeside = grid != null && !identical(shownNow.withBesidePrevious(section), shownNow);
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final contentLeft = rtl ? frame.end : frame.start;
    // The section itself, under its own key: lifted, it moves (state and the
    // press in progress with it) from the list slot to the overlay and back.
    final body = KeyedSubtree(
      key: _bodyKeyFor(section),
      child: AnimatedBuilder(
        animation: Listenable.merge([
          _press.charge.listenable,
          if (lifted) _drag.lift.listenable,
          if (lifted) _drag.liftTint.listenable,
          ?track?.structure,
        ]),
        child: child,
        builder: (context, child) {
          final plate = HomeEditPlate(
            progress: e,
            childTaps: childTaps,
            rowCount: track != null && track.stopCount > 1 && track.restStop < track.rowCounts.length
                ? track.rowCounts[track.restStop]
                : null,
            onMoreRows: grid != null
                ? (grid.range.contains(grid.units + 1) ? () => edit.setUnits(section, grid.units + 1) : null)
                : track != null && track.stopCount > 1 && _rows.canStep(section, 1)
                ? () => _rows.step(section, 1)
                : null,
            onFewerRows: grid != null
                ? (grid.range.contains(grid.units - 1) ? () => edit.setUnits(section, grid.units - 1) : null)
                : track != null && track.stopCount > 1 && _rows.canStep(section, -1)
                ? () => _rows.step(section, -1)
                : null,
            onPlaceBeside: canBeside ? () => edit.placeBesidePrevious(section) : null,
            onMakeFull: grid != null && grid.span != HomeSpan.full ? () => edit.setFull(section) : null,
            resizeHandle:
                gridHandle ??
                (track != null && track.stopCount > 1
                    ? HomeRowsHandle(section: section, engine: _rows, track: track, progress: e)
                    : handle),
            lift: lifted ? _drag.lift.value.clamp(0.0, 1.0) : 0,
            liftTint: lifted ? _drag.liftTint.value.clamp(0.0, 1.0) : 0,
            grow: _plateGrow(section, e),
            hideKey: _hideKeys.putIfAbsent(section, GlobalKey.new),
            // An edit-mode tap on a block (HomeEditGestures BlockTap): it kicks.
            onPlateTap: () {
              _wiggle.touch();
              _wiggle.impulse(section, HomeWiggleMetrics.kickTap);
            },
            section: section,
            start: frame.start,
            end: frame.end,
            index: index,
            count: visible.length,
            onHide: () => edit.hide(section),
            onMoveUp: index > 0 ? () => edit.move(section, index - 1) : null,
            onMoveDown: index < visible.length - 1 ? () => edit.move(section, index + 1) : null,
            dragHandle: visible.length > 1
                ? _DragHandle(
                    key: _handleKeys.putIfAbsent(section, GlobalKey.new),
                    label: HomeStrings.of(context).reorderSectionCd(HomeStrings.of(context).sectionTitle(section)),
                  )
                : null,
            child: HomeEditWiggleScope(
              wiggle: _wiggle,
              section: section,
              // A lifted section stills as it rises.
              liftGain: () => _drag.liftSection == section ? 1 - _drag.lift.value.clamp(0.0, 1.0) : 1,
              safeArea: _wiggleSafeArea,
              child: child!,
            ),
          );
          // The press charge: the block shrinks about the finger (no charge under reduced motion).
          // Always a Transform (identity at rest), so a charge starting keeps the section's state.
          final charge = _press.section == section ? _press.charge.value : 0.0;
          final origin = Offset(contentLeft + _press.origin.dx, _press.origin.dy);
          return Transform(
            transform: charge > HomeCarryTokens.chargeFloor
                ? _scaleAbout(chargeScale(charge), origin)
                : Matrix4.identity(),
            child: plate,
          );
        },
      ),
    );
    final colors = Theme.of(context).colorScheme;
    final outsetV = HomeEditMetrics.plateOutsetV * e.clamp(0.0, 1.0);
    final plateStart = frame.start - homeEditPlateOutset(frame.start);
    final plateEnd = frame.end - homeEditPlateOutset(frame.end);
    return Padding(
      key: _keyFor(section),
      padding: EdgeInsets.only(top: spacing),
      // Mid-drag the other blocks make way: each one's offset from where the list lays it out springs to 0.
      child: AnimatedBuilder(
        animation: _drag.paint,
        builder: (context, child) => Transform.translate(
          offset: Offset(_drag.makeWayX(section, inList: false), _drag.makeWay(section, inList: false)),
          child: child,
        ),
        // A list paints in order, so a lifted section could never pass over the
        // one below it: while lifted, dragged and landing it paints in the
        // overlay, and its slot keeps its place and height in the feed.
        child: OverlayPortal(
          controller: _portalFor(section),
          overlayChildBuilder: (context) => lifted ? _liftedLayer(section, body, contentLeft) : const SizedBox.shrink(),
          child: lifted
              ? CompositedTransformTarget(
                  link: _linkFor(section),
                  child: AnimatedBuilder(
                    animation: _drag,
                    builder: (context, _) => CustomPaint(
                      // Where the drop lands (rule 4): the slot's plate, framed, while the block follows the finger.
                      painter: _drag.slotShown(section, inList: false)
                          ? HomeDropSlotPainter(
                              fill: colors.surfaceContainerHighest,
                              stroke: colors.outlineVariant,
                              inset: EdgeInsetsDirectional.fromSTEB(
                                plateStart,
                                -outsetV,
                                plateEnd,
                                -outsetV,
                              ).resolve(Directionality.of(context)),
                            )
                          : null,
                      child: SizedBox(width: _liftSize.width, height: _liftSize.height),
                    ),
                  ),
                )
              : body,
        ),
      ),
    );
  }

  /// The plate's frame while it is not simply at rest (Android plateFrame):
  /// the entry plate growing from R_press, else the press charge's pre-show.
  HomeEditPlateGrow? _plateGrow(HomeSection section, double e) {
    final from = _plateFrom;
    final editing = ref.read(homeEditProvider).editing;
    final ripple = homeEditSmoothstep(_wiggle.ripple(section));
    if (from != null && from.section == section && !(editing && ripple >= HomeCarryTokens.plateGrown)) {
      return HomeEditPlateGrow(from.rect, ripple, latchedPlateAlpha(from.latch, e));
    }
    if (from == null && _press.section == section) {
      final charge = _press.charge.value;
      final alpha = charge > HomeCarryTokens.chargeFloor ? chargePlateAlpha(charge) : 0.0;
      if (alpha > e) return HomeEditPlateGrow.press(_press.origin, charge, alpha);
    }
    return null;
  }

  static Matrix4 _scaleAbout(double scale, Offset pivot) => Matrix4.identity()
    ..translateByDouble(pivot.dx, pivot.dy, 0, 1)
    ..scaleByDouble(scale, scale, 1, 1)
    ..translateByDouble(-pivot.dx, -pivot.dy, 0, 1);

  /// Edit mode's "not now" menu over a tapped Jump Back In card: hide it for
  /// three days or a week (owner 2026-10-13). It closes on a pick, a tap
  /// elsewhere, or edit mode ending.
  Future<void> _openJbiHideMenu(String scope, BuildContext cardContext, HomeWidgetCard card) async {
    if (!ref.read(homeEditProvider).editing || _hideMenuBase != null) return;
    if (_drag.isCarrying || _drag.liftSection != null || _rows.dragging || jbiHideKey(card) == null) return;
    YoinHaptics.click();
    _wiggle.touch();
    _wiggle.impulseCard(HomeSection.jumpBackIn, card.stableId, HomeWiggleMetrics.kickTap);
    final strings = HomeStrings.of(context);
    _hideMenuBase = ModalRoute.of(context);
    final JbiHideSpan? span;
    try {
      span = await showYoinDropdownMenu<JbiHideSpan>(
        context: context,
        anchor: globalRectOf(cardContext),
        items: [
          YoinMenuItem(value: JbiHideSpan.threeDays, label: strings.jbiHideThreeDays),
          YoinMenuItem(value: JbiHideSpan.oneWeek, label: strings.jbiHideWeek),
        ],
      );
    } finally {
      _hideMenuBase = null;
    }
    if (span == null || !mounted || !ref.read(homeEditProvider).editing) return;
    final now = ref.read(homeClockProvider)().millisecondsSinceEpoch;
    if (ref.read(jbiHiddenProvider.notifier).hide(scope, card, span, nowMs: now)) YoinHaptics.confirm();
  }

  /// The lifted section over the page, grown by the lift about the finger: on its slot while it is only
  /// lifted; under the finger while dragged (from the page's origin, so a step or an edge scroll laid out
  /// this frame never moves it), and gliding from where it was let go into its slot once dropped.
  Widget _liftedLayer(HomeSection section, Widget body, double contentLeft) => Align(
    alignment: Alignment.topLeft,
    child: AnimatedBuilder(
      animation: Listenable.merge([_drag, _drag.paint]),
      // Its size from the list, tight: the overlay is bounded, and a section given room grows into it.
      child: SizedBox.fromSize(size: _liftSize, child: body),
      // One structure throughout, so the section keeps its state while it moves.
      builder: (context, child) {
        final origin = Offset(contentLeft + _drag.liftOrigin.dx, _drag.liftOrigin.dy);
        final at = _drag.drawnTopLeft(section, inList: false);
        return CompositedTransformFollower(
          link: at == null ? _linkFor(section) : _pageLink,
          offset: at == null ? Offset.zero : Offset(at.dx - contentLeft, at.dy),
          showWhenUnlinked: false,
          child: Transform(transform: _scaleAbout(liftScale(_drag.lift.value), origin), child: child),
        );
      },
    ),
  );

  /// Lifts [section] into the overlay at its body's measured size.
  void _raise(HomeSection section) {
    final body = _bodyKeys[section]?.currentContext?.findRenderObject() as RenderBox?;
    if (body == null || !body.hasSize) return;
    final previous = _lifted;
    if (previous != null && previous != section) _portalFor(previous).hide();
    _liftSize = body.size;
    _portalFor(section).show();
    setState(() => _lifted = section);
  }

  void _lowerNow() {
    final section = _lifted;
    if (section == null) return;
    if (mounted) {
      setState(() => _lifted = null);
    } else {
      _lifted = null;
    }
    _portalFor(section).hide();
  }

  /// The engine lifted or let go a block, a drag started, stepped, ended or settled, or the list came or went.
  void _onDrag() {
    if (!mounted) return;
    _editController.carrying = _drag.isCarrying;
    // A lift puts any row resize at rest first.
    final holding = _drag.isCarrying || _drag.liftSection != null;
    if (holding && !_dragHolding) {
      _rows.snapAll();
      _grid.snap();
    }
    _dragHolding = holding;
    // The feed's block rises into the overlay; a strip of the compact list draws itself.
    final section = _drag.activeList || _drag.list.on ? null : _drag.onTopSection;
    if (section != _lifted) {
      if (section == null) {
        _lowerNow();
      } else {
        _raise(section);
      }
    }
    // The feed's order, cache extent and physics follow the drag and the list.
    setState(() {});
  }

  /// Mid-drag an exit lets the drag go first (HomeEditLayer.deferExit): Done keeps the drop, Back rolls it back.
  bool _deferExit({required bool done}) =>
      _drag.deferExit(done ? HomeEditExitReason.done : HomeEditExitReason.back);

  /// P moved: the entry plate drops its latch past P ≥ .6, and goes once grown or once P is back at rest.
  void _onEditProgress() {
    final from = _plateFrom;
    if (from == null) return;
    final p = _edit.value;
    final editing = ref.read(homeEditProvider).editing;
    final grown = editing && homeEditSmoothstep(_wiggle.ripple(from.section)) >= HomeCarryTokens.plateGrown;
    if (grown || (!editing && p <= HomeCarryTokens.progressRest)) {
      _plateFrom = null;
    } else if (p >= HomeCarryTokens.latchClearP && from.latch != 0) {
      _plateFrom = from.withLatch(0);
    }
  }

  void _onEditing(bool on) {
    if (!mounted) return;
    if (on) {
      final navigator = Navigator.maybeOf(context);
      if (navigator != null) _editBack.attach(navigator);
    } else {
      _editBack.detach();
      if (_drag.isCarrying) {
        // Edit mode went at once (another account): every drag value at rest, nothing written.
        _drag
          ..abortNow()
          ..onSnapExit(_press);
      } else {
        // A block still lifted drops where it is with no kick, the press lets go and the list folds away.
        _drag.onExit(_press);
      }
      // The entry plate retreats with P instead of holding its latch.
      _plateFrom = _plateFrom?.withLatch(0);
      _wiggle.held = null;
      _rows.onExit();
      _grid.snap();
      // The "not now" menu folds away with edit mode (out of the listener's frame).
      final base = _hideMenuBase;
      if (base != null) {
        Future.microtask(() {
          // Only what stands over Home's own route (the menu) is popped.
          if (mounted && identical(_hideMenuBase, base) && base.isActive && !base.isCurrent) {
            Navigator.of(context).popUntil((route) => route == base);
          }
        });
      }
    }
    if (_reduced) {
      _edit.snapTo(on ? 1 : 0);
    } else {
      _edit.animateTo(on ? 1 : 0);
    }
  }

  // ── The edit gestures (HomeEditGestures.kt) ────────────────────────────

  /// Global → the page stack's px (where the feed, the strips and every band live).
  Offset _toPage(Offset global) {
    final page = _pageKey.currentContext?.findRenderObject() as RenderBox?;
    return page == null || !page.attached ? global : page.globalToLocal(global);
  }

  /// [key]'s box in page px, if laid out.
  Rect? _pageRectOf(GlobalKey? key) {
    final box = key?.currentContext?.findRenderObject() as RenderBox?;
    final page = _pageKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || page == null || !homeBoxPlaced(box) || !page.attached) return null;
    final topLeft = page.globalToLocal(box.localToGlobal(Offset.zero));
    return topLeft & box.size;
  }

  /// [section]'s block (top, bottom) in page px, its gap above left out; null when it isn't laid out. Two
  /// columns: its cell (the row carries the gap).
  (double, double)? _bandOf(HomeSection section) {
    final item = _pageRectOf(_itemKeys[section]);
    if (item == null) return null;
    return (item.top + (_twoColumn ? 0 : _spacing), item.bottom);
  }

  /// Two columns: [section]'s cell content edges (left, right) in page px; null when it isn't laid out.
  (double, double)? _cellEdgesOf(HomeSection section) {
    final item = _pageRectOf(_itemKeys[section]);
    final frame = _cellFrames[section];
    if (item == null || frame == null) return null;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final left = item.left + (rtl ? frame.end : frame.start);
    return (left, left + frame.content);
  }

  /// The plate's reach past the content: (horizontal, vertical) at the current P.
  Size _plateOutset() {
    final frame = _frame;
    if (frame == null) return Size.zero;
    return Size(
      math.min(homeEditPlateOutset(frame.start), homeEditPlateOutset(frame.end)),
      HomeEditMetrics.plateOutsetV * _edit.value.clamp(0.0, 1.0),
    );
  }

  /// The feed's content x-range in page px (the frame's live margins).
  (double, double) _contentBounds() {
    final frame = _frame;
    if (frame == null) return (0, 0);
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final left = rtl ? frame.end : frame.start;
    return (left, left + frame.content);
  }

  /// Resolves a press (spec §2.1.2): exclusions first (the header, the hide
  /// buttons, a ⌟ that took it, the tray and the footer), then a drag
  /// handle, then the section bands, else blank.
  HomeEditHit _editHitTest(Offset p) {
    final grabbed = _handleGrabbed;
    _handleGrabbed = false;
    if (grabbed) return const HomeEditHitExcluded();
    final header = _pageRectOf(_headerKey);
    if (header != null && p.dy < header.bottom) return const HomeEditHitExcluded();
    if (_pageRectOf(_trayKey)?.contains(p) ?? false) return const HomeEditHitExcluded();
    final editing = ref.read(homeEditProvider).editing;
    if (editing && _hideKeys.values.any((k) => _pageRectOf(k)?.contains(p) ?? false)) {
      return const HomeEditHitExcluded();
    }
    final order = _shownLayout.visibleV1Sections;
    final bands = <SectionBand>[
      for (final section in order)
        if (_bandOf(section) case (final top, final bottom))
          if (!_twoColumn)
            SectionBand(section, top, bottom, bleeds: homeEditBleedingSections.contains(section))
          else if (_cellEdgesOf(section) case (final left, final right))
            SectionBand(section, top, bottom, left: left, right: right),
    ];
    HomeSection? handle;
    if (editing) {
      for (final entry in _handleKeys.entries) {
        if (_pageRectOf(entry.value)?.contains(p) ?? false) handle = entry.key;
      }
    }
    final (left, right) = _contentBounds();
    return resolveHomeEditHit(
      position: p,
      bands: bands,
      contentLeft: left,
      contentRight: right,
      outset: _plateOutset(),
      editing: editing,
      handle: handle,
    );
  }

  /// A fling is running, or the feed scrolled a moment ago: a blank tap only stops it.
  bool _flingInProgress() {
    if (_scroll.hasClients && _scroll.position.isScrollingNotifier.value) return true;
    return _clock.elapsedMilliseconds - _lastScrollMs < HomeCarryTokens.flingStopMs;
  }

  /// Applies the machine's actions, in order (HomeEditGestureExecutor).
  /// Actions that only make sense in edit mode are dropped once it has ended.
  void _runEditActions(List<HomeEditGestureAction> actions, HomeEditGestureMachine machine) {
    if (!mounted) return;
    for (final action in actions) {
      if (action.editOnly && !ref.read(homeEditProvider).editing) continue;
      switch (action) {
        case HomeEditStartCharge(:final section, :final local):
          _press.start(section, local, _specs);
        case HomeEditReleaseCharge():
          _press.release(_specs);
        case HomeEditLongPressHaptic():
          YoinHaptics.longPress();
        case HomeEditDragStartHaptic():
          YoinHaptics.dragStart();
        case HomeEditEnter(:final origin, :final lifted):
          // The ripple (and its entry kicks) spreads from the section the finger landed on.
          _wiggle
            ..origin = origin
            ..held = lifted ? origin : null;
          ref.read(homeEditProvider.notifier).enter(haptic: false);
        case HomeEditLatchPlate(:final section, :final local, :final size):
          // Read now, before the release that follows starts moving it.
          final charge = _press.charge.value;
          _plateFrom = _specs.reduced
              ? null
              : PlateFrom(section, pressRect(local, size, charge), charge.clamp(0.0, 1.0));
        case HomeEditLift(:final section, :final local):
          _drag.liftBlock(section, local);
        case HomeEditDropLift(:final kick):
          _drag.dropLift(kick);
        case HomeEditOpenPlaceholders():
          // Harmony holds no placeholder back above a lifted block.
          break;
        case HomeEditStartCarry(:final y):
          _drag
            ..fingerX = machine.position.dx
            ..start(y);
        case HomeEditCarryMove(:final y):
          _drag
            ..fingerX = machine.position.dx
            ..move(y);
        case HomeEditCarryRelease(:final cancelled):
          _drag.release(cancelled: cancelled);
        case HomeEditPinchStart():
          // A second finger before any lift: the list follows the fingers' span (Android HomeEditListMode).
          if (_drag.liftSection == null && !_drag.isCarrying) _drag.list.pinchStart();
        case HomeEditPinchBy(:final scale):
          _drag.list.pinchBy(scale);
        case HomeEditPinchEnd():
          _drag.list.pinchEnd();
        case HomeEditBlockTap(:final section, :final local):
          // A press the gestures kept (a drag handle) kicks its block here; a
          // tap on the plate itself goes to the plate (and a Jump Back In card
          // to its "not now").
          if (local == null) {
            _wiggle.impulse(section, HomeWiggleMetrics.kickTap);
          }
        case HomeEditExitBlank():
          ref.read(homeEditProvider.notifier).exit();
        case HomeEditTouch():
          _wiggle.touch();
        case HomeEditPointerDown(:final down):
          _wiggle.pointerDown = down;
        case HomeEditAbandon() || HomeEditConsumeRest() || HomeEditConsumeOther():
          break;
      }
    }
  }

  /// The band between the status tide and the bar (HomeEditSafeArea), page px.
  (double, double) _carrySafeArea() {
    final media = MediaQuery.of(context);
    // The feed fills the page: its height as last laid out (read in build too, so never from a render box).
    final height = _pageHeight ?? media.size.height;
    return (
      media.viewPadding.top + HomeSeamMetrics.tideRest + HomeCarryTokens.safeGap,
      height - _feedBottomInset - HomeCarryTokens.safeGap,
    );
  }

  /// The strip covers of [section]: its own artwork, in its entity shape (HomeEditorialContent homeStripCovers).
  List<StripCover> _stripCovers(HomeContent content, HomeSection section) {
    const count = HomeCarryTokens.stripCoverCount;
    switch (section) {
      case HomeSection.activities:
        final entries = content.activityEntries;
        final hero = selectHeroIndex(entries);
        return [
          if (hero >= 0) entries[hero],
          for (var i = 0; i < entries.length; i++)
            if (i != hero) entries[i],
        ].take(count).map((e) => StripCover(e.cover, e.kind)).toList();
      case HomeSection.jumpBackIn:
        final signals = ref.read(homeMemorySignalsProvider);
        final mine = signals.scope == content.scopeKey;
        final hidden = ref
            .read(jbiHiddenProvider)
            .keysAt(content.scopeKey, ref.read(homeClockProvider)().millisecondsSinceEpoch);
        final cards = jbiShelfCards(content.grid, mine ? signals : null, hidden);
        // A memory's card leads.
        final memory = cards.where((c) => c.target is HomeOpenMemory).firstOrNull;
        return [
          ?memory,
          for (final card in cards)
            if (!identical(card, memory)) card,
        ].take(count).map((card) => StripCover(card.cover, card.kind, requestSize: HomeCoverRequest.jbi)).toList();
      case HomeSection.recentlyAdded:
        return content.recentlyAddedAlbums.isNotEmpty
            ? [for (final a in content.recentlyAddedAlbums.take(count)) StripCover(a.coverArt, HomeCardKind.album)]
            : [for (final t in content.recentlyAddedTracks.take(count)) StripCover(t.coverArt, null)];
      case HomeSection.rediscover:
        final signals = ref.read(homeMemorySignalsProvider);
        if (signals.scope != content.scopeKey) return const [];
        final added = {for (final a in content.recentlyAddedAlbums) a.id};
        return [
          for (final r in signals.rediscover.where((r) => !added.contains(r.albumId)).take(count))
            StripCover(r.cover, HomeCardKind.album),
        ];
      case HomeSection.recentlyPlayed:
        return [for (final a in content.recentlyPlayed.take(count)) StripCover(a.coverArt, HomeCardKind.album)];
      case HomeSection.yourPlaylists:
        return [for (final p in content.playlists.take(count)) StripCover(p.coverArt, HomeCardKind.playlist)];
      case HomeSection.otherProfiles:
        final items = ref.read(otherProfileItemsProvider).value ?? const <OtherProfileItem>[];
        return [
          for (final item in items.take(count))
            StripCover(
              item.cover,
              item.kind == OtherProfileItemKind.playlist ? HomeCardKind.playlist : HomeCardKind.album,
            ),
        ];
      case HomeSection.lyricHighlight:
        return [
          for (final card in ref.read(lyricHighlightCardsProvider).take(count))
            StripCover(card.cover, HomeCardKind.song),
        ];
      case HomeSection.memoryGrid:
        // The albums the latest memories are about, newest first.
        final ledger = ref.read(memoryLedgerProvider).value;
        final seen = <CoverRef>{};
        final out = <StripCover>[];
        for (final entry in ledger?.entries ?? const []) {
          final cover = entry.album?.cover;
          if (cover == null || !seen.add(cover)) continue;
          out.add(StripCover(cover, HomeCardKind.album));
          if (out.length == count) break;
        }
        return out;
      case HomeSection.madeForYou:
        return [for (final p in ref.read(madeForYouProvider).take(count)) StripCover(p.cover, HomeCardKind.playlist)];
    }
  }

  /// The band the wiggle lives in: clear of the status bar and of the bar, by [HomeWiggleMetrics.safeGap].
  (double, double) _wiggleSafeArea() {
    if (!mounted) return (0, double.infinity);
    final media = MediaQuery.of(context);
    return (
      media.viewPadding.top + HomeWiggleMetrics.safeGap,
      media.size.height - _feedBottomInset - HomeWiggleMetrics.safeGap,
    );
  }

  /// The bar's Add: the "Hidden" tray at the feed's end scrolls into view on the spatial spring.
  void _scrollToTray() {
    if (!_scroll.hasClients || !ref.read(homeEditProvider).editing) return;
    final position = _scroll.position;
    _wiggle.touch();
    if (_reduced) {
      position.jumpTo(position.maxScrollExtent);
      return;
    }
    _trayScrolling = true;
    _trayScroll.snapTo(position.pixels);
    _trayScroll.animateTo(position.maxScrollExtent);
  }

  void _onTrayScroll() {
    if (!_trayScrolling || !_scroll.hasClients) return;
    final position = _scroll.position;
    // The end can move as the feed's lazy sections are measured: follow it.
    if ((_trayScroll.value - position.maxScrollExtent).abs() > 1 && position.pixels >= position.maxScrollExtent - 1) {
      _trayScroll.animateTo(position.maxScrollExtent);
    }
    position.jumpTo(_trayScroll.value.clamp(position.minScrollExtent, position.maxScrollExtent));
    if ((_trayScroll.value - position.maxScrollExtent).abs() < 0.5) _trayScrolling = false;
  }

}

/// The feed under a held ⌟ (HomeRowsAutoScroll): scrolls while the finger
/// is within [HomeEditMetrics.autoScrollEdge] of the status bar or the bar,
/// eased in to [HomeEditMetrics.autoScrollMax].
class _RowsAutoScroll implements HomeRowsAutoScroll {
  _RowsAutoScroll(this._home);
  final _HomeScreenState _home;

  @override
  double speed(double fingerGlobalY) {
    final home = _home;
    if (!home.mounted) return 0;
    final box = home._listKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize || !home._scroll.hasClients) return 0;
    final topEdge = box.localToGlobal(Offset.zero).dy + MediaQuery.viewPaddingOf(home.context).top;
    final bottomEdge = box.localToGlobal(Offset(0, box.size.height)).dy - home._feedBottomInset;
    const edge = HomeEditMetrics.autoScrollEdge;
    if (fingerGlobalY < topEdge + edge) {
      return -HomeEditMetrics.autoScrollMax * homeRowsEdgeRamp((topEdge + edge - fingerGlobalY) / edge);
    }
    if (fingerGlobalY > bottomEdge - edge) {
      return HomeEditMetrics.autoScrollMax * homeRowsEdgeRamp((fingerGlobalY - (bottomEdge - edge)) / edge);
    }
    return 0;
  }

  @override
  double scrollBy(double px) {
    final scroll = _home._scroll;
    if (!scroll.hasClients) return 0;
    final position = scroll.position;
    final to = (position.pixels + px).clamp(position.minScrollExtent, position.maxScrollExtent);
    final moved = to - position.pixels;
    if (moved != 0) position.jumpTo(to);
    return moved;
  }
}

/// A section's drag handle: the edit gestures find it by its key (a press
/// on it lifts and carries at the first slop).
class _DragHandle extends StatelessWidget {
  const _DragHandle({super.key, required this.label});

  final String label;

  @override
  Widget build(BuildContext context) => Semantics(
    // Reading it moves nothing: the plate's Move up / Move down actions do.
    container: true,
    label: label,
    excludeSemantics: true,
    child: SizedBox.square(
      dimension: HomeHeaderMetrics.touchBox,
      child: Icon(YoinSymbols.dragHandle, color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}

/// The feed as the drag sees it (Android LazyListDragHost over the feed): page px. In two columns its rows are
/// the drawn arrangement's, each band its first section's cell, and a slot's content starts at its cell's.
class _FeedDragHost implements HomeDragHost {
  _FeedDragHost(this._home);

  final _HomeScreenState _home;

  @override
  bool get isList => false;

  @override
  bool get twoColumn => _home._twoColumn;

  @override
  List<HomeSection> order() => _home._shownLayout.visibleV1Sections;

  @override
  Map<HomeSection, HomeSection> pairs() => _home._twoColumn ? _home._shownLayout.pairs : const {};

  @override
  List<DragItemBand> visibleRows() {
    final drag = _home._drag;
    final drawn = (drag.activeList ? null : drag.arrangement) ?? DragArrangement(order(), pairs());
    final keys = _home._twoColumn ? [for (final row in dragRows(drawn.ids, drawn.pairs, true)) row.first] : drawn.ids;
    final out = <DragItemBand>[
      for (final s in keys)
        if (_home._bandOf(s) case (final top, final bottom)) DragItemBand(s, top, bottom),
    ];
    out.sort((a, b) => a.top.compareTo(b.top));
    return out;
  }

  @override
  double? extentOf(HomeSection section) => _home._pageRectOf(_home._itemKeys[section])?.height;

  @override
  double contentLeft() => _home._contentBounds().$1;

  @override
  double contentWidth() {
    final (left, right) = _home._contentBounds();
    return right - left;
  }

  @override
  Offset? slotOf(HomeSection section) {
    final band = _home._bandOf(section);
    if (band == null) return null;
    if (!_home._twoColumn) return Offset(contentLeft(), band.$1);
    final edges = _home._cellEdgesOf(section);
    return edges == null ? null : Offset(edges.$1, band.$1);
  }

  @override
  Map<HomeSection, Offset> placesOf(DragArrangement arrangement) {
    if (!_home._twoColumn) return homeStackPlaces(arrangement.ids, extentOf);
    // Two columns: rows at the sum of the row heights (and gaps) above them; a right half a half and a gap in.
    final layout = _home._shownLayout;
    final half = (contentWidth() - HomeColumnTokens.rowGap) / 2;
    final out = <HomeSection, Offset>{};
    var y = 0.0;
    for (final row in dragRows(arrangement.ids, arrangement.pairs, true)) {
      switch (row) {
        case HomeFeedRowFull(:final section):
          out[section] = Offset(0, y);
        case HomeFeedRowHalves(:final left, :final right):
          out[left] = Offset(0, y);
          out[right] = Offset(half + HomeColumnTokens.rowGap, y);
      }
      y += homeGridHeightDp(layout.unitsOfRow(row)) + _home._spacing;
    }
    return out;
  }

  @override
  (double, double) scrollArea() => _home._carrySafeArea();

  @override
  double scrollBy(double px) {
    final scroll = _home._scroll;
    if (!scroll.hasClients) return 0;
    final position = scroll.position;
    final to = (position.pixels + px).clamp(position.minScrollExtent, position.maxScrollExtent);
    final moved = to - position.pixels;
    if (moved != 0) position.jumpTo(to);
    return moved;
  }
}

/// Home's YoinLoadingIndicator: nothing for 180 ms, then the M3 Expressive
/// contained loading indicator fades (effects) and scales in from 0.6
/// (spatial); it leaves the same way.
class _LoadingIndicator extends StatefulWidget {
  const _LoadingIndicator({required this.visible});
  final bool visible;

  @override
  State<_LoadingIndicator> createState() => _LoadingIndicatorState();
}

class _LoadingIndicatorState extends State<_LoadingIndicator> with TickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _scale = SpringValue(vsync: this, initial: homeLoadingIndicatorScaleFrom, spring: YoinSprings.defaultSpatial);
  Timer? _delay;

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _alpha;
    _scale;
    _sync();
  }

  @override
  void didUpdateWidget(_LoadingIndicator old) {
    super.didUpdateWidget(old);
    if (old.visible != widget.visible) _sync();
  }

  void _sync() {
    _delay?.cancel();
    if (widget.visible) {
      _delay = Timer(HomeTimings.loadingIndicatorDelay, () {
        _alpha.animateTo(1);
        _scale.animateTo(1);
      });
    } else {
      _alpha.animateTo(0);
      _scale.animateTo(homeLoadingIndicatorScaleFrom);
    }
  }

  @override
  void dispose() {
    _delay?.cancel();
    _alpha.dispose();
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_alpha.animation, _scale.animation]),
    builder: (context, _) {
      final alpha = _alpha.value.clamp(0.0, 1.0);
      if (alpha <= 0.001) return const SizedBox.shrink();
      return Center(
        child: Opacity(
          opacity: alpha,
          child: Transform.scale(scale: _scale.value, child: const ExpressiveLoadingIndicator.yoin()),
        ),
      );
    },
  );
}

/// Error: the message (error colour), then Retry and Settings.
class _ErrorPage extends ConsumerWidget {
  const _ErrorPage({required this.error});
  final Object error;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    // The failure's own line (connectivity, Apple Music, the server's words), else Home's.
    final message = userMessageText(context, error, strings.errorLoadFailed);
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeFeedMetrics.cappedMargin),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error),
            ),
            const SizedBox(height: HomeErrorMetrics.gap),
            Row(
              mainAxisSize: MainAxisSize.min,
              spacing: HomeErrorMetrics.buttonGap,
              children: [
                TextButton(onPressed: () => ref.read(homeViewModelProvider.notifier).refresh(), child: Text(strings.errorRetry)),
                TextButton(onPressed: () => openHomeSettings(context), child: Text(strings.errorSettings)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Calls [onUncovered] when Home is back in front after something hid it
/// whole (Android: Home re-entered, its LifecycleStartEffect ran again): a page
/// pushed over it went, or the shell brought it back from Library. Both mute
/// Home's tickers while it is hidden (the overlay's offstage entries, the
/// shell's hidden page); a sheet or a menu over Home doesn't. Paints nothing.
class _UncoveredProbe extends StatefulWidget {
  const _UncoveredProbe({required this.onUncovered});

  final VoidCallback onUncovered;

  @override
  State<_UncoveredProbe> createState() => _UncoveredProbeState();
}

class _UncoveredProbeState extends State<_UncoveredProbe> {
  ValueListenable<TickerModeData>? _ticking;
  bool _covered = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final ticking = TickerMode.getValuesNotifier(context);
    if (identical(ticking, _ticking)) return;
    _ticking?.removeListener(_onTicking);
    _ticking = ticking..addListener(_onTicking);
    _onTicking();
  }

  void _onTicking() {
    if (!(_ticking?.value.enabled ?? true)) {
      _covered = true;
    } else if (_covered) {
      _covered = false;
      widget.onUncovered();
    }
  }

  @override
  void dispose() {
    _ticking?.removeListener(_onTicking);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => const SizedBox.shrink();
}
