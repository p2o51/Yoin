import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/source/music_source.dart';
import '../memories/ledger/memory_ledger_home_card.dart';
import '../memories/ledger/memory_ledger_view_model.dart';
import '../memories/memories_pull.dart';
import '../nowplaying/haptics.dart';
import '../shell/chrome_back.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/expressive/loading_indicator.dart';
import '../ui/component/yoin_dropdown_menu.dart';
import 'activity_bento.dart';
import 'home_edit.dart';
import 'home_feed_density.dart';
import 'home_jbi_hidden.dart';
import 'home_header.dart';
import 'home_memory_signals.dart';
import 'home_metrics.dart';
import 'home_rows_handle.dart';
import 'home_rows_resize.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'rediscover_selection.dart';
import 'home_shelves.dart';
import 'jump_back_in.dart';
import 'rediscover_section.dart';
import 'widgets/cover_palette.dart';
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

  /// System back leaves edit mode (= Done) while it is on.
  late final ChromeBackEntry _editBack = ChromeBackEntry(() => scheduleMicrotask(() {
    if (mounted) ref.read(homeEditProvider.notifier).exit();
  }));
  final GlobalKey _headerKey = GlobalKey();
  final Map<HomeSection, GlobalKey> _itemKeys = {};
  final Map<HomeSection, SpringValue> _shift = {};

  // A section being dragged to a new place (edit mode). The feed is lazy:
  // sections off screen are not built, so their places are estimated from
  // the built ones and refined as they scroll in. Tops are in the feed's
  // content space (global y + scroll offset), so auto-scroll keeps them valid.
  HomeSection? _dragging;
  double _dragDy = 0;
  List<HomeSection> _dragOrder = const [];
  List<double> _dragTops = const [];
  List<double> _dragHeights = const [];
  int _dragTarget = 0;

  /// The section painted in the overlay (dragged, or landing after a drop).
  HomeSection? _lifted;
  Size _liftSize = Size.zero;

  /// 0 → 1: the lift's scale and shadow (fast spatial up, default spatial down).
  late final SpringValue _lift = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);
  final Map<HomeSection, GlobalKey> _bodyKeys = {};
  final Map<HomeSection, LayerLink> _links = {};
  final Map<HomeSection, OverlayPortalController> _portals = {};

  /// The finger's global y while a section is dragged (drives auto-scroll).
  double? _dragPointerY;
  final GlobalKey _listKey = GlobalKey();
  late final Ticker _autoScroll = createTicker(_onAutoScrollTick);
  Duration? _autoScrollLast;

  /// The feed's bottom padding (the bar's room): auto-scroll's lower edge sits above it.
  double _feedBottomInset = 0;

  /// The app going to the background folds edit mode away.
  AppLifecycleListener? _lifecycle;

  /// Edit mode's row resize (Activities, Jump Back In): the ⌟ handles' engine.
  late final HomeRowsEngine _rows = HomeRowsEngine(
    vsync: this,
    editing: () => mounted && ref.read(homeEditProvider).editing,
    carrying: () => _dragging != null,
    currentPreset: (section) => ref.read(homeLayoutProvider).rowsOf(section),
    commit: (section, preset) => ref.read(homeEditProvider.notifier).setRows(section, preset),
    reduced: () => _reduced,
  )..autoScroll = _RowsAutoScroll(this);

  GlobalKey _keyFor(HomeSection s) => _itemKeys.putIfAbsent(s, GlobalKey.new);
  GlobalKey _bodyKeyFor(HomeSection s) => _bodyKeys.putIfAbsent(s, GlobalKey.new);
  LayerLink _linkFor(HomeSection s) => _links.putIfAbsent(s, LayerLink.new);
  OverlayPortalController _portalFor(HomeSection s) => _portals.putIfAbsent(s, OverlayPortalController.new);
  SpringValue _shiftOf(HomeSection s) => _shift.putIfAbsent(s, () {
    final v = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
    (v.animation as AnimationController).addStatusListener((_) => _maybeLower());
    return v;
  });

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
    (_lift.animation as AnimationController).addStatusListener((_) => _maybeLower());
    _lifecycle = AppLifecycleListener(onHide: () {
      if (mounted) ref.read(homeEditProvider.notifier).exit();
    });
    _scroll.addListener(() => _flow.onScroll(_scroll.position.pixels));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _onState(ref.read(homeViewModelProvider));
    });
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
    _editBack.dispose();
    for (final s in _shift.values) {
      s.dispose();
    }
    _autoScroll.dispose();
    _rows.dispose();
    _lift.dispose();
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

  bool _onScrollNotification(ScrollNotification n) {
    if (n.depth != 0) return false;
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
    ref.listen(homeViewModelProvider, (_, next) => _onState(next));
    final state = ref.watch(homeViewModelProvider);
    final layout = ref.watch(homeLayoutProvider);
    final signals = ref.watch(homeMemorySignalsProvider);
    final editing = ref.watch(homeEditProvider).editing;
    ref.listen(homeEditProvider.select((e) => e.editing), (_, on) => _onEditing(on));
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
          final list = CustomScrollView(
            key: _listKey,
            controller: _scroll,
            // While a section is lifted every section stays laid out: the lifted
            // one never leaves the tree under auto-scroll, and the others are
            // measured instead of guessed from the next frame on.
            cacheExtent: _dragging == null ? null : HomeEditMetrics.dragCacheExtent,
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
                SliverList.list(
                  children: _sections(content, layout, frame, signals, e: e, spacing: spacing),
                ),
              if (content != null && content.hasAccount)
                SliverPadding(
                  padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end, top: spacing),
                  sliver: SliverToBoxAdapter(
                    child: e > 0.001
                        ? Opacity(opacity: e.clamp(0.0, 1.0), child: const HomeEditTray())
                        : (layout.visibleV1Sections.isEmpty ? const HomeEmptyLayoutCard() : const HomeEditFooter()),
                  ),
                ),
              SliverToBoxAdapter(child: SizedBox(height: bottom)),
            ],
          );
          // Long-press anywhere below the header enters; a tap on the page's
          // blank space (not a plate) leaves.
          return GestureDetector(
            behavior: HitTestBehavior.translucent,
            onLongPressStart: content?.hasAccount ?? false ? _maybeEnterEdit : null,
            // The right click / two-finger tap is the long-press on a PC.
            onSecondaryTapUp: content?.hasAccount ?? false
                ? (d) => _maybeEnterEdit(LongPressStartDetails(globalPosition: d.globalPosition))
                : null,
            onTap: editing ? ref.read(homeEditProvider.notifier).exit : null,
            child: HomeRowsScope(engine: _rows, child: list),
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
        fit: StackFit.expand,
        children: [
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
                  child: AnimatedBuilder(
                    animation: Listenable.merge([_feedAlpha.animation, _feedRise.animation]),
                    child: feed,
                    builder: (context, child) => IgnorePointer(
                      ignoring: state is! HomeContent,
                      child: Opacity(
                        opacity: _feedAlpha.value.clamp(0.0, 1.0),
                        child: Transform.translate(offset: Offset(0, _feedRise.value * HomeFeedMetrics.entranceRise), child: child),
                      ),
                    ),
                  ),
                ),
              ),
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
    final visible = layout.visibleV1Sections;
    // The signals are the same account's, or none (a switch in flight).
    final mine = signals.scope == content.scopeKey;
    // Cards the user put away for a while ("not now") stay off the shelf.
    final hidden = ref.watch(jbiHiddenProvider).keysAt(content.scopeKey, ref.read(homeClockProvider)().millisecondsSinceEpoch);
    final jbi = withoutHiddenJbi(mine ? withJbiSignals(content.grid, signals.wideCards) : content.grid, hidden);
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
              : padded(_jbiHideScope(
                  content.scopeKey,
                  editingNow,
                  JumpBackInSection(cards: jbi, frame: frame, preset: layout.rowsOf(HomeSection.jumpBackIn)),
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
                      MemoryLedgerHomeCard(state: ledger),
                    ],
                  )),
            2
          ),
        HomeSection.otherProfiles || HomeSection.lyricHighlight => (null, 0),
      };
      // Editing, an empty section shows its placeholder on its plate.
      if (body == null && !editingNow) continue;
      if (body == null) {
        final i = visible.indexOf(section);
        out.add(_editItem(section, frame, spacing, e, i, visible, padded(HomeEditPlaceholder(section: section)), resizable: false));
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
      ));
    }
    if (!_sectionsSeeded) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _sectionsSeeded = true);
    }
    return out;
  }

  /// One feed item: its gap above, the edit plate (inert at rest) and the
  /// drag's displacement.
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
  }) {
    final edit = ref.read(homeEditProvider.notifier);
    final shift = _shiftOf(section);
    final lifted = _lifted == section;
    final track = resizable ? _rows.track(section) : null;
    // The section itself, under its own key: lifted, it moves (state and the
    // drag in progress with it) from the list slot to the overlay and back.
    final body = KeyedSubtree(
      key: _bodyKeyFor(section),
      child: AnimatedBuilder(
        animation: Listenable.merge([
          lifted ? _lift.animation : const AlwaysStoppedAnimation(0.0),
          ?track?.structure,
        ]),
        child: child,
        builder: (context, child) => HomeEditPlate(
          progress: e,
          childTaps: childTaps,
          rowCount: track != null && track.stopCount > 1 && track.restStop < track.rowCounts.length
              ? track.rowCounts[track.restStop]
              : null,
          onMoreRows: track != null && track.stopCount > 1 && _rows.canStep(section, 1) ? () => _rows.step(section, 1) : null,
          onFewerRows: track != null && track.stopCount > 1 && _rows.canStep(section, -1) ? () => _rows.step(section, -1) : null,
          resizeHandle: track != null && track.stopCount > 1
              ? HomeRowsHandle(section: section, engine: _rows, track: track, progress: e)
              : null,
          lift: lifted ? _lift.value.clamp(0.0, 1.0) : 0,
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
                  label: HomeStrings.of(context).reorderSectionCd(HomeStrings.of(context).sectionTitle(section)),
                  onStart: (y) => _startDrag(section, visible, y),
                  onUpdate: _updateDrag,
                  onEnd: _endDrag,
                )
              : null,
          child: child!,
        ),
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
        overlayChildBuilder: (context) => lifted ? _liftedLayer(section, body) : const SizedBox.shrink(),
        child: lifted
            ? CompositedTransformTarget(
                link: _linkFor(section),
                child: SizedBox(width: _liftSize.width, height: _liftSize.height),
              )
            : AnimatedBuilder(
                animation: shift.animation,
                builder: (context, child) => Transform.translate(offset: Offset(0, shift.value), child: child),
                child: body,
              ),
      ),
    );
  }

  /// Jump Back In under edit mode's "not now": a tap on a card opens a menu
  /// over it — hide it for three days or a week (owner 2026-10-13).
  Widget _jbiHideScope(String scope, bool editing, Widget child) {
    if (!editing) return child;
    return JbiHideScope(
      onTap: (cardContext, card) => _openJbiHideMenu(scope, cardContext, card),
      child: child,
    );
  }

  Future<void> _openJbiHideMenu(String scope, BuildContext cardContext, HomeWidgetCard card) async {
    if (_dragging != null || _rows.dragging || jbiHideKey(card) == null) return;
    YoinHaptics.click();
    final strings = HomeStrings.of(context);
    final span = await showYoinDropdownMenu<JbiHideSpan>(
      context: context,
      anchor: globalRectOf(cardContext),
      items: [
        YoinMenuItem(value: JbiHideSpan.threeDays, label: strings.jbiHideThreeDays),
        YoinMenuItem(value: JbiHideSpan.oneWeek, label: strings.jbiHideWeek),
      ],
    );
    if (span == null || !mounted || !ref.read(homeEditProvider).editing) return;
    final now = ref.read(homeClockProvider)().millisecondsSinceEpoch;
    if (ref.read(jbiHiddenProvider.notifier).hide(scope, card, span, nowMs: now)) YoinHaptics.confirm();
  }

  /// The lifted section over the page: on its slot, offset by the finger (or
  /// the landing spring), grown by the lift.
  Widget _liftedLayer(HomeSection section, Widget body) => Align(
    alignment: Alignment.topLeft,
    child: CompositedTransformFollower(
      link: _linkFor(section),
      showWhenUnlinked: false,
      child: AnimatedBuilder(
        animation: Listenable.merge([_shiftOf(section).animation, _lift.animation]),
        // Its size from the list, tight: the overlay is bounded, and a section given room grows into it.
        child: SizedBox.fromSize(size: _liftSize, child: body),
        builder: (context, child) => Transform.translate(
          offset: Offset(0, _dragging == section ? _dragDy : _shiftOf(section).value),
          child: Transform.scale(scale: 1 + (HomeEditMetrics.dragLiftScale - 1) * _lift.value, child: child),
        ),
      ),
    ),
  );

  /// Lifts [section] into the overlay at [size] (its body's measured size).
  void _raise(HomeSection section, Size size) {
    final previous = _lifted;
    if (previous != null && previous != section) _portalFor(previous).hide();
    _lifted = section;
    _liftSize = size;
    _portalFor(section).show();
    if (_reduced) {
      _lift.snapTo(1);
    } else {
      _lift.spring = YoinSprings.fastSpatial;
      _lift.animateTo(1);
    }
  }

  /// Once the landing spring and the lift have both settled, the section goes back into its slot.
  void _maybeLower() {
    final section = _lifted;
    if (section == null || _dragging != null || !mounted) return;
    final shiftMoving = (_shiftOf(section).animation as AnimationController).isAnimating;
    final liftMoving = (_lift.animation as AnimationController).isAnimating;
    if (shiftMoving || liftMoving) return;
    _lowerNow();
  }

  void _lowerNow() {
    final section = _lifted;
    if (section == null) return;
    // Cleared first: the snap below reports a status that lands back in _maybeLower.
    if (mounted) {
      setState(() => _lifted = null);
    } else {
      _lifted = null;
    }
    _portalFor(section).hide();
    _lift.snapTo(0);
  }

  void _onEditing(bool on) {
    if (!mounted) return;
    if (on) {
      final navigator = Navigator.maybeOf(context);
      if (navigator != null) _editBack.attach(navigator);
    } else {
      _editBack.detach();
      _cancelDrag();
      _rows.onExit();
    }
    if (_reduced) {
      _edit.snapTo(on ? 1 : 0);
    } else {
      _edit.animateTo(on ? 1 : 0);
    }
  }

  void _maybeEnterEdit(LongPressStartDetails details) {
    final header = _headerKey.currentContext?.findRenderObject() as RenderBox?;
    // The header (the account avatar's hold, Memories' bubble) is not ours.
    if (header != null && header.hasSize && details.globalPosition.dy < header.localToGlobal(Offset(0, header.size.height)).dy) return;
    ref.read(homeEditProvider.notifier).enter();
  }

  void _startDrag(HomeSection section, List<HomeSection> order, double pointerY) {
    // A held ⌟ has the page; a section drag puts every resize at rest.
    if (_rows.dragging) return;
    _rows.snapAll();
    final tops = List<double>.filled(order.length, 0);
    final heights = List<double>.filled(order.length, 0);
    // The dragged section is under the finger, so it is built.
    if (!_measure(order, tops, heights, require: order.indexOf(section))) return;
    final body = _bodyKeys[section]?.currentContext?.findRenderObject() as RenderBox?;
    if (body == null || !body.hasSize) return;
    YoinHaptics.longPress();
    _raise(section, body.size);
    setState(() {
      _dragging = section;
      _dragDy = 0;
      _dragOrder = order;
      _dragTops = tops;
      _dragHeights = heights;
      _dragTarget = order.indexOf(section);
      _dragPointerY = pointerY;
    });
  }

  /// Fills [tops] (content space) and [heights] for [order]: built sections
  /// measured, the rest stacked on from their nearest built neighbour at the
  /// built ones' mean height. False when [require] is not built.
  bool _measure(List<HomeSection> order, List<double> tops, List<double> heights, {required int require}) {
    final offset = _scroll.hasClients ? _scroll.offset : 0.0;
    final built = List<bool>.filled(order.length, false);
    var sum = 0.0;
    var n = 0;
    for (var i = 0; i < order.length; i++) {
      final box = _itemKeys[order[i]]?.currentContext?.findRenderObject() as RenderBox?;
      if (box == null || !box.attached || !box.hasSize) continue;
      tops[i] = box.localToGlobal(Offset.zero).dy + offset;
      heights[i] = box.size.height;
      built[i] = true;
      sum += heights[i];
      n++;
    }
    if (require < 0 || !built[require]) return false;
    final estimate = sum / n;
    final first = built.indexOf(true);
    final last = built.lastIndexOf(true);
    for (var i = first - 1; i >= 0; i--) {
      heights[i] = estimate;
      tops[i] = tops[i + 1] - estimate;
    }
    for (var i = last + 1; i < order.length; i++) {
      heights[i] = estimate;
      tops[i] = tops[i - 1] + heights[i - 1];
    }
    return true;
  }

  void _updateDrag(double dy, double pointerY) {
    if (_dragging == null) return;
    _dragPointerY = pointerY;
    _dragBy(dy);
    if (_autoScrollSpeed() != 0 && !_autoScroll.isActive) {
      _autoScrollLast = null;
      _autoScroll.start();
    }
  }

  /// Moves the lifted section by [dy] (content space) and re-targets the others.
  void _dragBy(double dy) {
    final section = _dragging;
    if (section == null) return;
    final a = _dragOrder.indexOf(section);
    // Sections scrolled into view since the lift replace their estimates.
    final tops = List<double>.of(_dragTops);
    final heights = List<double>.of(_dragHeights);
    if (_measure(_dragOrder, tops, heights, require: a)) {
      _dragTops = tops;
      _dragHeights = heights;
    }
    final h = _dragHeights[a];
    final center = _dragTops[a] + _dragDy + dy + h / 2;
    var target = a;
    for (var i = 0; i < _dragOrder.length; i++) {
      if (i == a) continue;
      final mid = _dragTops[i] + _dragHeights[i] / 2;
      double to = 0;
      if (i < a && center < mid) {
        to = h;
        target--;
      } else if (i > a && center > mid) {
        to = -h;
        target++;
      }
      _shiftOf(_dragOrder[i]).animateTo(to);
    }
    if (target != _dragTarget) YoinHaptics.tick();
    setState(() {
      _dragDy += dy;
      _dragTarget = target;
    });
  }

  /// dp/s the feed scrolls at for the finger's place: 0 away from the edges,
  /// ramping to [HomeEditMetrics.autoScrollMax] at them (negative = up).
  double _autoScrollSpeed() {
    final y = _dragPointerY;
    final box = _listKey.currentContext?.findRenderObject() as RenderBox?;
    if (y == null || box == null || !box.hasSize || !_scroll.hasClients) return 0;
    final topEdge = box.localToGlobal(Offset.zero).dy + MediaQuery.viewPaddingOf(context).top;
    final bottomEdge = box.localToGlobal(Offset(0, box.size.height)).dy - _feedBottomInset;
    const edge = HomeEditMetrics.autoScrollEdge;
    final position = _scroll.position;
    if (y < topEdge + edge && position.pixels > position.minScrollExtent) {
      return -HomeEditMetrics.autoScrollMax * ((topEdge + edge - y) / edge).clamp(0.0, 1.0);
    }
    if (y > bottomEdge - edge && position.pixels < position.maxScrollExtent) {
      return HomeEditMetrics.autoScrollMax * ((y - (bottomEdge - edge)) / edge).clamp(0.0, 1.0);
    }
    return 0;
  }

  void _onAutoScrollTick(Duration elapsed) {
    final last = _autoScrollLast;
    _autoScrollLast = elapsed;
    final speed = _dragging == null ? 0.0 : _autoScrollSpeed();
    if (speed == 0) {
      _autoScroll.stop();
      return;
    }
    if (last == null) return;
    final dt = (elapsed - last).inMicroseconds / Duration.microsecondsPerSecond;
    final position = _scroll.position;
    final to = (position.pixels + speed * dt).clamp(position.minScrollExtent, position.maxScrollExtent);
    final moved = to - position.pixels;
    if (moved == 0) return;
    position.jumpTo(to);
    // The lifted section rides the finger, not the content.
    _dragBy(moved);
  }

  void _endDrag() {
    final section = _dragging;
    if (section == null) return;
    _stopAutoScroll();
    final a = _dragOrder.indexOf(section);
    final b = _dragTarget;
    var moved = 0.0;
    if (b > a) {
      for (var i = a + 1; i <= b; i++) {
        moved += _dragHeights[i];
      }
    } else {
      for (var i = b; i < a; i++) {
        moved -= _dragHeights[i];
      }
    }
    for (final s in _dragOrder) {
      _shiftOf(s).snapTo(0);
    }
    // It lands from where the finger left it, on the spatial spring.
    final residual = _dragDy - moved;
    final shift = _shiftOf(section)..snapTo(residual);
    if (_reduced) {
      shift.snapTo(0);
    } else {
      shift.animateTo(0);
    }
    setState(() => _dragging = null);
    if (_reduced) {
      _lift.snapTo(0);
    } else {
      _lift.spring = YoinSprings.defaultSpatial;
      _lift.animateTo(0);
    }
    if (b != a) ref.read(homeEditProvider.notifier).move(section, b);
    _maybeLower();
  }

  void _stopAutoScroll() {
    _dragPointerY = null;
    if (_autoScroll.isActive) _autoScroll.stop();
  }

  void _cancelDrag() {
    if (_dragging == null) return;
    _stopAutoScroll();
    for (final s in _dragOrder) {
      _shiftOf(s).animateTo(0);
    }
    setState(() => _dragging = null);
    _lift.animateTo(0);
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

/// A section's drag handle: hold and drag to move the section.
class _DragHandle extends StatelessWidget {
  const _DragHandle({required this.label, required this.onStart, required this.onUpdate, required this.onEnd});

  final String label;
  final ValueChanged<double> onStart;
  final void Function(double dy, double pointerY) onUpdate;
  final VoidCallback onEnd;

  @override
  Widget build(BuildContext context) => Semantics(
    // Reading it moves nothing: the plate's Move up / Move down actions do.
    container: true,
    label: label,
    excludeSemantics: true,
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onVerticalDragStart: (d) => onStart(d.globalPosition.dy),
      onVerticalDragUpdate: (d) => onUpdate(d.delta.dy, d.globalPosition.dy),
      onVerticalDragEnd: (_) => onEnd(),
      onVerticalDragCancel: onEnd,
      child: SizedBox.square(
        dimension: HomeHeaderMetrics.touchBox,
        child: Icon(YoinSymbols.dragHandle, color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    ),
  );
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
    final err = error;
    final message = err is SourceException && err.message.isNotEmpty ? err.message : strings.errorLoadFailed;
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
