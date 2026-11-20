import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart' show ValueListenable, listEquals;
import 'package:flutter/gestures.dart' show kTouchSlop;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show RenderProxySliver;
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../memories/ledger/memory_ledger_home_card.dart';
import '../memories/ledger/memory_ledger_view_model.dart';
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
import 'home_carry.dart';
import 'home_carry_stack.dart';
import 'home_edit.dart';
import 'home_edit_gestures.dart';
import 'home_edit_wiggle.dart';
import 'home_feed_density.dart';
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

  /// Pick up and carry (HomeEditGestures, HomeCarryEngine): the springs, the
  /// normal-mode press charge, the lift and the strip carry.
  HomeCarrySpecs _carrySpecs = HomeCarrySpecs(reduced: false);
  late final HomeEditPressState _press = HomeEditPressState(this);
  late final HomeCarryEngine _carry = HomeCarryEngine(
    vsync: this,
    specs: () => _carrySpecs,
    wiggle: _wiggle,
    commitOrder: (order) => ref.read(homeEditProvider.notifier).commitOrder(order),
    finishDeferredExit: ({required done}) {
      if (mounted) ref.read(homeEditProvider.notifier).finishExit(done: done);
    },
  )..host = _FeedCarryHost(this);

  /// The entry plate growing from the long-press's square (port sheet §2.3).
  PlateFrom? _plateFrom;

  /// How many strip labels are built ahead of a carry (StripWarmth), and the warm-up in flight.
  final ValueNotifier<int> _stripWarm = ValueNotifier(0);
  StripWarmth? _warmth;
  int _warmGeneration = 0;

  /// The page stack every edit position is measured in (the feed sits at its origin).
  final GlobalKey _pageKey = GlobalKey();
  final GlobalKey _trayKey = GlobalKey();
  final Map<HomeSection, GlobalKey> _hideKeys = {};
  final Map<HomeSection, GlobalKey> _handleKeys = {};

  /// The ⌟ (or the Lyric Cards count) took the press being dispatched: the edit gestures leave it.
  bool _handleGrabbed = false;

  /// A block is lifted or carried (the row resizes were put at rest as it began).
  bool _carryHolding = false;

  /// The feed's frame and section spacing last laid out (the carry reads them between frames).
  HomeFeedFrame? _frame;
  double _spacing = 0;
  double? _pageHeight;
  double? _carryWidth;

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
    carrying: () => _carry.isCarrying || _carry.liftSection != null,
    currentPreset: (section) => ref.read(homeLayoutProvider).rowsOf(section),
    commit: (section, preset) => ref.read(homeEditProvider.notifier).setRows(section, preset),
    reduced: () => _reduced,
  )..autoScroll = _RowsAutoScroll(this);

  /// Edit mode's Lyric Cards count: its ⌟ handle's drag.
  late final HomeLyricCountEngine _lyricCount = HomeLyricCountEngine(
    vsync: this,
    editing: () => mounted && ref.read(homeEditProvider).editing,
    blocked: () => _carry.isCarrying || _carry.liftSection != null || _rows.dragging,
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
    _wiggle;
    _press;
    _carry.addListener(_onCarry);
    _edit.animation.addListener(_onEditProgress);
    (_edit.animation as AnimationController).addStatusListener((_) => _syncStripWarmth());
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
    _scroll.addListener(() {
      // The anchor's scroll under the strips is no scroll the seams follow.
      if (_carry.fold.value > 0) {
        _flow.jumpTo(_scroll.position.pixels);
      } else {
        _flow.onScroll(_scroll.position.pixels);
      }
    });
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
    _carry.removeListener(_onCarry);
    _carry.dispose();
    _press.dispose();
    _stripWarm.dispose();
    _rows.dispose();
    _lyricCount.dispose();
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
    // Scrolling the feed down takes the bar out, up brings it back (ShellBarScrollHide) — not the
    // carry's anchor under the strips.
    if (_carry.fold.value <= 0) {
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
        _paletteGate.value = true;
      } else {
        _armGate();
      }
    }
    return false;
  }

  @override
  Widget build(BuildContext context) {
    _reduced = MediaQuery.disableAnimationsOf(context);
    _flow.reduced = _reduced;
    if (_carrySpecs.reduced != _reduced) _carrySpecs = HomeCarrySpecs(reduced: _reduced);
    ref.listen(homeViewModelProvider, (_, next) => _onState(next));
    final state = ref.watch(homeViewModelProvider);
    final layout = ref.watch(homeLayoutProvider);
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
      animation: Listenable.merge([_frameBlend.animation, _edit.animation]),
      builder: (context, _) => LayoutBuilder(
        builder: (context, constraints) {
          // Margins and widths follow the LIVE width; N and the cover columns the resting one.
          final width = constraints.maxWidth;
          // A resize mid-carry invalidates the strips: let go where it is.
          if (_carry.isCarrying && _carryWidth != null && _carryWidth != width) {
            scheduleMicrotask(() {
              if (mounted) _carry.release(cancelled: true, uptimeMs: _clock.elapsedMilliseconds);
            });
          }
          _carryWidth = width;
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
            // While a block is lifted or carried every section stays laid out:
            // each strip flies from its own plate, and the drop lands on a
            // measured place.
            cacheExtent: _carry.liftSection == null && !_carry.isCarrying ? null : HomeEditMetrics.dragCacheExtent,
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
                  ),
                ),
              ),
              if (content != null)
                _FoldGate(
                  fold: _carry.fold,
                  sliver: SliverList.list(
                    children: _sections(content, layout, frame, signals, e: e, spacing: spacing),
                  ),
                ),
              if (content != null && content.hasAccount)
                _FoldGate(
                  fold: _carry.fold,
                  sliver: SliverPadding(
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
                  ..carryBusy = (() => _carry.isBusy)
                  ..flingInProgress = _flingInProgress
                  ..run = _runEditActions
                  ..slop = MediaQuery.gestureSettingsOf(context).touchSlop ?? kTouchSlop,
              ),
            },
            child: Listener(
              // A finger takes the page from the bar's Add scroll.
              onPointerDown: (_) => _trayScrolling = false,
              child: CustomPaint(
                // The feed fading under the folding strips (feedFoldWashOver).
                foregroundPainter: _FoldWashPainter(
                  fold: _carry.fold,
                  colors: homePageGround(colors),
                  headerBottom: _headerBottomInList,
                ),
                child: HomeRowsScope(engine: _rows, child: list),
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
          // The strips fold over the feed, inside the tide (port sheet §0.2 item 10).
          if (content != null && content.hasAccount)
            Positioned.fill(
              child: HomeCarryStack(
                engine: _carry,
                covers: (section) => _stripCovers(content, section),
                sections: layout.visibleV1Sections,
                warmCount: _stripWarm,
              ),
            ),
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
    final visible = layout.visibleV1Sections;
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
    Widget padded(Widget child) => Padding(
      padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
      child: child,
    );
    for (final section in visible) {
      final (Widget? body, int beat) = switch (section) {
        HomeSection.activities => (
          padded(ActivitiesSection(content: content, frame: frame, preset: layout.rowsOf(HomeSection.activities))),
          0,
        ),
        HomeSection.jumpBackIn => (
          jbi.isEmpty
              ? null
              : padded(JbiHideScope(
                  // Edit mode's "not now": a tap on a card opens its menu
                  // (owner 2026-10-13). Always in the tree, so entering and
                  // leaving keep the shelf's state.
                  onTap: editing ? (cardContext, card) => _openJbiHideMenu(content.scopeKey, cardContext, card) : null,
                  child: JumpBackInSection(cards: jbi, frame: frame, preset: layout.rowsOf(HomeSection.jumpBackIn)),
                )),
          1,
        ),
        HomeSection.recentlyAdded => (
          content.recentlyAddedTracks.isEmpty && content.recentlyAddedAlbums.isEmpty
              ? null
              : RecentlyAddedSection(tracks: content.recentlyAddedTracks, albums: content.recentlyAddedAlbums, frame: frame),
          2,
        ),
        HomeSection.recentlyPlayed => (
          content.recentlyPlayed.isEmpty ? null : RecentlyPlayedSection(albums: content.recentlyPlayed, frame: frame),
          2,
        ),
        HomeSection.yourPlaylists => (
          content.playlists.isEmpty ? null : YourPlaylistsSection(playlists: content.playlists, frame: frame),
          2,
        ),
        HomeSection.rediscover => (
          rediscover.isEmpty
              ? null
              : RediscoverSection(items: rediscover, frame: frame, nowMs: ref.read(homeClockProvider)().millisecondsSinceEpoch),
          2,
        ),
        HomeSection.memoryGrid => (
            ledger == null || ledger.isEmpty
                ? null
                : padded(Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      HomeSectionTitle(HomeStrings.of(context).sectionMemoryGrid),
                      const SizedBox(height: HomeSectionMetrics.shelfSpacing),
                      HomeEditBlockWiggle(child: MemoryLedgerHomeCard(state: ledger)),
                    ],
                  )),
            2
          ),
        HomeSection.otherProfiles => (
          otherProfileItems.isEmpty ? null : OtherProfilesSection(items: otherProfileItems, frame: frame),
          2,
        ),
        // The service's playlists for the account; a tap opens the playlist.
        HomeSection.madeForYou => (
          madeForYou.isEmpty ? null : MadeForYouSection(playlists: madeForYou, frame: frame),
          2,
        ),
        // Lines the user picked, two to four cards; a tap plays from one.
        HomeSection.lyricHighlight => (
          lyricIntro != null
              ? padded(LyricHighlightIntroCard(
                  intro: lyricIntro,
                  onDismiss: ref.read(lyricHighlightIntroProvider.notifier).dismiss,
                ))
              : lyricCards.isEmpty
              ? null
              : LyricHighlightSection(
                  cards: lyricCards,
                  count: lyricCardsFor(layout.rowsOf(HomeSection.lyricHighlight)),
                  frame: frame,
                  scrollEnabled: !editingNow,
                  onPlay: (card) => ref.read(lyricHighlightCardsProvider.notifier).play(card),
                ),
          2,
        ),
      };
      // Editing, an empty section shows its placeholder on its plate.
      if (body == null && !editingNow) continue;
      if (body == null) {
        final i = visible.indexOf(section);
        out.add(_editItem(
          section,
          frame,
          spacing,
          e,
          i,
          visible,
          // No cards: the whole block sways (homeEditBlockWiggle).
          padded(HomeEditBlockWiggle(child: HomeEditPlaceholder(section: section))),
          resizable: false,
        ));
        continue;
      }
      // A section that lands after the feed is up grows in; the first paint does not.
      final animate = _sectionsSeeded && !_shownSections.contains(section);
      _shownSections.add(section);
      final revealed = section == HomeSection.activities && content.activityEntries.isEmpty
          ? body
          : StagedBeat(
              beat: _reveal.beats[beat].animation,
              rise: beat == 0 ? HomeRevealMetrics.heroRise : HomeRevealMetrics.rise,
              scaleFrom: beat == 0 ? HomeRevealMetrics.heroScaleFrom : 1,
              child: body,
            );
      // Rows (D1): a section with presets, showing its cards (not Activities' empty card).
      final resizable = section.supportsRows && !(section == HomeSection.activities && content.activityEntries.isEmpty);
      out.add(_editItem(
        section,
        frame,
        spacing,
        e,
        visible.indexOf(section),
        visible,
        SectionAppear(animate: animate, child: revealed),
        resizable: resizable,
        childTaps: section == HomeSection.jumpBackIn,
        // Lyric Cards takes its card count from its own dots, not the row resize.
        handle: section == HomeSection.lyricHighlight ? HomeLyricCountHandle(engine: _lyricCount, progress: e) : null,
      ));
    }
    if (!_sectionsSeeded) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _sectionsSeeded = true);
    }
    return out;
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
  }) {
    final edit = ref.read(homeEditProvider.notifier);
    final lifted = _lifted == section;
    final track = resizable ? _rows.track(section) : null;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    final contentLeft = rtl ? frame.end : frame.start;
    // The section itself, under its own key: lifted, it moves (state and the
    // press in progress with it) from the list slot to the overlay and back.
    final body = KeyedSubtree(
      key: _bodyKeyFor(section),
      child: AnimatedBuilder(
        animation: Listenable.merge([
          _press.charge.listenable,
          if (lifted) _carry.lift.listenable,
          if (lifted) _carry.liftTint.listenable,
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
            onMoreRows: track != null && track.stopCount > 1 && _rows.canStep(section, 1)
                ? () => _rows.step(section, 1)
                : null,
            onFewerRows: track != null && track.stopCount > 1 && _rows.canStep(section, -1)
                ? () => _rows.step(section, -1)
                : null,
            resizeHandle: track != null && track.stopCount > 1
                ? HomeRowsHandle(section: section, engine: _rows, track: track, progress: e)
                : handle,
            lift: lifted ? _carry.lift.value.clamp(0.0, 1.0) : 0,
            liftTint: lifted ? _carry.liftTint.value.clamp(0.0, 1.0) : 0,
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
              liftGain: () => _carry.liftSection == section ? 1 - _carry.lift.value.clamp(0.0, 1.0) : 1,
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
    return Padding(
      key: _keyFor(section),
      padding: EdgeInsets.only(top: spacing),
      // A list paints in order, so a lifted section could never pass over the
      // one below it: while lifted (and landing) it paints in the overlay,
      // pinned to its slot, which keeps its place and height in the feed.
      child: OverlayPortal(
        controller: _portalFor(section),
        overlayChildBuilder: (context) => lifted ? _liftedLayer(section, body, contentLeft) : const SizedBox.shrink(),
        child: lifted
            ? CompositedTransformTarget(
                link: _linkFor(section),
                child: SizedBox(width: _liftSize.width, height: _liftSize.height),
              )
            : body,
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
    if (_carry.isCarrying || _carry.liftSection != null || _rows.dragging || jbiHideKey(card) == null) return;
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

  /// The lifted section over the page: on its slot, grown by the lift about
  /// the finger, fading with the feed as the strips fold in.
  Widget _liftedLayer(HomeSection section, Widget body, double contentLeft) => Align(
    alignment: Alignment.topLeft,
    child: CompositedTransformFollower(
      link: _linkFor(section),
      showWhenUnlinked: false,
      child: AnimatedBuilder(
        animation: Listenable.merge([_carry.lift.listenable, _carry.fold.listenable]),
        // Its size from the list, tight: the overlay is bounded, and a section given room grows into it.
        child: SizedBox.fromSize(size: _liftSize, child: body),
        // One structure throughout, so the section keeps its state under the strips.
        builder: (context, child) {
          final origin = Offset(contentLeft + _carry.liftOrigin.dx, _carry.liftOrigin.dy);
          return Opacity(
            opacity: feedFoldAlpha(_carry.fold.value).clamp(0.0, 1.0),
            child: Transform(transform: _scaleAbout(liftScale(_carry.lift.value), origin), child: child),
          );
        },
      ),
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

  /// The engine lifted or let go a block, or a carry started, changed phase or ended.
  void _onCarry() {
    if (!mounted) return;
    _editController.carrying = _carry.isCarrying;
    // A carry folds every block into a strip: any row resize lands at rest first.
    final holding = _carry.isCarrying || _carry.liftSection != null;
    if (holding && !_carryHolding) _rows.snapAll();
    _carryHolding = holding;
    final section = _carry.liftSection;
    if (section != _lifted) {
      if (section == null) {
        _lowerNow();
      } else {
        _raise(section);
      }
    }
    // The feed's cache extent follows the lift.
    setState(() {});
  }

  /// Mid-carry, an exit waits for the drop (HomeEditLayer.deferExit).
  bool _deferExit({required bool done}) {
    if (!_carry.isCarrying) return false;
    _carry.deferExit(done: done);
    return true;
  }

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

  /// The strip labels build once entering has settled, ahead of any carry,
  /// one label a frame, and go once an exit has (HomeCarryStack's warmCount).
  void _syncStripWarmth() {
    if (!mounted) return;
    final warmth = stripWarmth(
      editing: ref.read(homeEditProvider).editing,
      moving: (_edit.animation as AnimationController).isAnimating,
    );
    if (warmth == _warmth) return;
    _warmth = warmth;
    final generation = ++_warmGeneration;
    switch (warmth) {
      case StripWarmth.warm:
        // Past the entry's own frames first.
        _warmStep(generation, HomeCarryTokens.stripWarmFrames);
      case StripWarmth.keep:
        break;
      case StripWarmth.cold:
        _stripWarm.value = 0;
    }
  }

  void _warmStep(int generation, int wait) {
    SchedulerBinding.instance.scheduleFrameCallback((_) {
      if (!mounted || generation != _warmGeneration) return;
      if (wait > 0) {
        _warmStep(generation, wait - 1);
        return;
      }
      if (_stripWarm.value >= HomeSection.values.length) return;
      _stripWarm.value++;
      _warmStep(generation, 0);
    });
  }

  void _onEditing(bool on) {
    if (!mounted) return;
    if (on) {
      final navigator = Navigator.maybeOf(context);
      if (navigator != null) _editBack.attach(navigator);
    } else {
      _editBack.detach();
      if (_carry.isCarrying) {
        // Edit mode went at once (another account): every carry value at rest, nothing written.
        _carry
          ..abortNow(commit: false)
          ..onSnapExit(_press);
      } else {
        // A block still lifted drops where it is with no kick, and the press lets go.
        _carry.onExit(_press);
      }
      // The entry plate retreats with P instead of holding its latch.
      _plateFrom = _plateFrom?.withLatch(0);
      _wiggle.held = null;
      _rows.onExit();
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
    _syncStripWarmth();
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
    if (box == null || page == null || !box.attached || !box.hasSize || !page.attached) return null;
    final topLeft = page.globalToLocal(box.localToGlobal(Offset.zero));
    return topLeft & box.size;
  }

  /// [section]'s block (top, bottom) in page px, its gap above left out; null when it isn't laid out.
  (double, double)? _bandOf(HomeSection section) {
    final item = _pageRectOf(_itemKeys[section]);
    if (item == null) return null;
    return (item.top + _spacing, item.bottom);
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
    final order = ref.read(homeLayoutProvider).visibleV1Sections;
    final bands = <SectionBand>[
      for (final section in order)
        if (_bandOf(section) case (final top, final bottom))
          SectionBand(section, top, bottom, bleeds: homeEditBleedingSections.contains(section)),
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
          _press.start(section, local, _carrySpecs);
        case HomeEditReleaseCharge():
          _press.release(_carrySpecs);
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
          _plateFrom = _carrySpecs.reduced
              ? null
              : PlateFrom(section, pressRect(local, size, charge), charge.clamp(0.0, 1.0));
        case HomeEditLift(:final section, :final local):
          _carry.liftBlock(section, local);
        case HomeEditDropLift(:final kick):
          _carry.dropLift(kick);
        case HomeEditOpenPlaceholders():
          // Harmony holds no placeholder back above a lifted block.
          break;
        case HomeEditStartCarry(:final y, :final t):
          _carry.start(y, t);
        case HomeEditCarryMove(:final y, :final t):
          _carry.move(y, t);
        case HomeEditCarryRelease(:final cancelled, :final t):
          _carry.release(cancelled: cancelled, uptimeMs: t);
        case HomeEditTryRegrab(:final position, :final t):
          if (_carry.tryRegrab(position, t)) _runEditActions(machine.handle(const HomeEditRegrabbed()), machine);
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

  /// The header's bottom in the list's px, 0 once it has scrolled away.
  double _headerBottomInList() {
    final header = _pageRectOf(_headerKey);
    final list = _pageRectOf(_listKey);
    if (header == null || list == null) return 0;
    return math.max(0, header.bottom - list.top);
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

  /// [section]'s plate in page px (the content out by the plate's reach), if laid out.
  Rect? _plateRectOf(HomeSection section) {
    final band = _bandOf(section);
    if (band == null) return null;
    final (left, right) = _contentBounds();
    final outset = _plateOutset();
    return Rect.fromLTRB(left - outset.width, band.$1 - outset.height, right + outset.width, band.$2 + outset.height);
  }

  /// One jump puts the dropped plate's top on its slot (port sheet §4.9),
  /// unless the header still shows below the status band; then this waits
  /// for that layout. The new order is laid out first.
  Future<void> _anchorDropped(HomeSection dropped, List<HomeSection> order, double slotTop) async {
    await SchedulerBinding.instance.endOfFrame;
    if (!mounted || !_scroll.hasClients) return;
    final statusBand = _carrySafeArea().$1 - HomeCarryTokens.safeGap;
    final header = _pageRectOf(_headerKey);
    // The header still shows below the status band (critique 34): the scroll stays.
    if (header != null && header.bottom > statusBand) return;
    final plate = _plateRectOf(dropped);
    if (plate == null) return;
    final position = _scroll.position;
    // The clamp keeps the header out: the first section's top at the list's top at most.
    var minPixels = position.minScrollExtent;
    for (final section in order) {
      final band = _bandOf(section);
      if (band == null) continue;
      minPixels = position.pixels + band.$1 - (_pageRectOf(_listKey)?.top ?? 0);
      break;
    }
    final max = position.maxScrollExtent;
    final double target = (position.pixels + plate.top - slotTop).clamp(math.min(minPixels, max), max);
    if ((target - position.pixels).abs() < 0.5) return;
    position.jumpTo(target);
    await SchedulerBinding.instance.endOfFrame;
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

/// The feed host the carry measures (Android LazyListCarryHost).
class _FeedCarryHost implements CarryHost {
  _FeedCarryHost(this._home);

  final _HomeScreenState _home;

  @override
  List<HomeSection> displayedOrder() => _home.ref.read(homeLayoutProvider).visibleV1Sections;

  @override
  Rect? plateRect(HomeSection section) => _home._plateRectOf(section);

  @override
  bool isAbove(HomeSection section) {
    final order = displayedOrder();
    final safe = safeArea();
    final visible = [
      for (final s in order)
        if (_home._bandOf(s) case (final top, final bottom) when bottom > safe.$1 && top < safe.$2) s,
    ];
    return carryIsAbove(section, order, visible, pastHeader: _home._headerBottomInList() <= 0);
  }

  @override
  (double, double) safeArea() => _home._carrySafeArea();

  @override
  double contentLeft() => _home._contentBounds().$1;

  @override
  double contentWidth() {
    final (left, right) = _home._contentBounds();
    return right - left;
  }

  @override
  Future<void> anchorAndAwaitLayout(HomeSection dropped, List<HomeSection> order, double slotTop) =>
      _home.mounted ? _home._anchorDropped(dropped, order, slotTop) : Future.value();
}

/// The feed's sections (or tray) under the fold: drawn, or skipped once the wash covers them (feedFoldShown).
class _FoldGate extends StatefulWidget {
  const _FoldGate({required this.fold, required this.sliver});

  final CarrySpring fold;
  final Widget sliver;

  @override
  State<_FoldGate> createState() => _FoldGateState();
}

class _FoldGateState extends State<_FoldGate> {
  late _FoldShown _shown;

  @override
  void initState() {
    super.initState();
    _shown = _FoldShown(widget.fold);
  }

  @override
  void didUpdateWidget(_FoldGate oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.fold, widget.fold)) {
      _shown.dispose();
      _shown = _FoldShown(widget.fold);
    }
  }

  @override
  void dispose() {
    _shown.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<bool>(
    valueListenable: _shown,
    builder: (context, shown, child) => _SliverPaintGate(shown: shown, sliver: child!),
    child: widget.sliver,
  );
}

/// Whether the fold leaves the feed showing, as a listenable that only changes when that flips.
class _FoldShown extends ValueNotifier<bool> {
  _FoldShown(this._fold) : super(feedFoldShown(_fold.value) > 0);

  final CarrySpring _fold;

  void _sync() => value = feedFoldShown(_fold.value) > 0;

  @override
  void addListener(VoidCallback listener) {
    if (!hasListeners) _fold.listenable.addListener(_sync);
    super.addListener(listener);
  }

  @override
  void removeListener(VoidCallback listener) {
    super.removeListener(listener);
    if (!hasListeners) _fold.listenable.removeListener(_sync);
  }
}

/// A sliver painted only while [shown]; layout and hit testing as ever.
class _SliverPaintGate extends SingleChildRenderObjectWidget {
  const _SliverPaintGate({required this.shown, required Widget sliver}) : super(child: sliver);

  final bool shown;

  @override
  _RenderSliverPaintGate createRenderObject(BuildContext context) => _RenderSliverPaintGate(shown);

  @override
  void updateRenderObject(BuildContext context, _RenderSliverPaintGate render) => render.shown = shown;
}

class _RenderSliverPaintGate extends RenderProxySliver {
  _RenderSliverPaintGate(this._shown);

  bool _shown;
  set shown(bool value) {
    if (_shown == value) return;
    _shown = value;
    markNeedsPaint();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (_shown) super.paint(context, offset);
  }
}

/// The page's own ground washed over the sections and tray, from the header's
/// bottom down, while the strips fold in (feedFoldWashOver): the header never
/// fades. Drawn only mid-fade.
class _FoldWashPainter extends CustomPainter {
  _FoldWashPainter({required this.fold, required this.colors, required this.headerBottom})
    : super(repaint: fold.listenable);

  final CarrySpring fold;
  final List<Color> colors;
  final double Function() headerBottom;

  @override
  void paint(Canvas canvas, Size size) {
    final wash = feedFoldWash(fold.value);
    if (wash <= 0 || wash >= 1) return;
    final top = headerBottom().clamp(0.0, size.height);
    final shader = LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [for (final c in colors) c.withValues(alpha: c.a * wash)],
    ).createShader(Offset.zero & size);
    canvas.drawRect(Rect.fromLTRB(0, top, size.width, size.height), Paint()..shader = shader);
  }

  @override
  bool shouldRepaint(_FoldWashPainter old) => old.fold != fold || !listEquals(old.colors, colors);
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
