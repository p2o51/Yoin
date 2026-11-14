import 'dart:async';

import 'package:flutter/material.dart';
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
import 'activity_bento.dart';
import 'home_edit.dart';
import 'home_feed_density.dart';
import 'home_header.dart';
import 'home_memory_signals.dart';
import 'home_metrics.dart';
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

  // A section being dragged to a new place (edit mode).
  HomeSection? _dragging;
  double _dragDy = 0;
  List<HomeSection> _dragOrder = const [];
  List<double> _dragTops = const [];
  List<double> _dragHeights = const [];
  int _dragTarget = 0;

  /// The app going to the background folds edit mode away.
  AppLifecycleListener? _lifecycle;

  GlobalKey _keyFor(HomeSection s) => _itemKeys.putIfAbsent(s, GlobalKey.new);
  SpringValue _shiftOf(HomeSection s) =>
      _shift.putIfAbsent(s, () => SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial));

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
          final e = _edit.value;
          final spacing = frame.itemSpacing + (HomeEditMetrics.editItemSpacing - frame.itemSpacing) * homeEditSmoothstep(e);
          final list = CustomScrollView(
            controller: _scroll,
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
            child: list,
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
    final jbi = mine ? withJbiSignals(content.grid, signals.wideCards) : content.grid;
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
        HomeSection.activities => (padded(ActivitiesSection(content: content, frame: frame)), 0),
        HomeSection.jumpBackIn => (jbi.isEmpty ? null : padded(JumpBackInSection(cards: jbi, frame: frame)), 1),
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
        out.add(_editItem(section, frame, spacing, e, i, visible, padded(HomeEditPlaceholder(section: section))));
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
      out.add(_editItem(section, frame, spacing, e, visible.indexOf(section), visible, SectionAppear(animate: animate, child: revealed)));
    }
    if (!_sectionsSeeded) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _sectionsSeeded = true);
    }
    return out;
  }

  /// One feed item: its gap above, the edit plate (inert at rest) and the
  /// drag's displacement.
  Widget _editItem(HomeSection section, HomeFeedFrame frame, double spacing, double e, int index, List<HomeSection> visible, Widget child) {
    final edit = ref.read(homeEditProvider.notifier);
    final shift = _shiftOf(section);
    final dragging = _dragging == section;
    return Padding(
      key: _keyFor(section),
      padding: EdgeInsets.only(top: spacing),
      child: AnimatedBuilder(
        animation: shift.animation,
        builder: (context, child) => Transform.translate(
          offset: Offset(0, dragging ? _dragDy : shift.value),
          child: Transform.scale(scale: dragging ? HomeEditMetrics.dragLiftScale : 1, child: child),
        ),
        child: HomeEditPlate(
          progress: e,
          section: section,
          start: frame.start,
          end: frame.end,
          index: index,
          count: visible.length,
          onHide: () => edit.hide(section),
          onMoveUp: index > 0 ? () => edit.move(section, index - 1) : null,
          onMoveDown: index < visible.length - 1 ? () => edit.move(section, index + 1) : null,
          dragHandle: visible.length > 1
              ? _DragHandle(onStart: () => _startDrag(section, visible), onUpdate: _updateDrag, onEnd: _endDrag)
              : null,
          child: child,
        ),
      ),
    );
  }

  void _onEditing(bool on) {
    if (!mounted) return;
    if (on) {
      final navigator = Navigator.maybeOf(context);
      if (navigator != null) _editBack.attach(navigator);
    } else {
      _editBack.detach();
      _cancelDrag();
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

  void _startDrag(HomeSection section, List<HomeSection> order) {
    final tops = <double>[];
    final heights = <double>[];
    for (final s in order) {
      final box = _itemKeys[s]?.currentContext?.findRenderObject() as RenderBox?;
      if (box == null || !box.hasSize) return;
      tops.add(box.localToGlobal(Offset.zero).dy);
      heights.add(box.size.height);
    }
    YoinHaptics.longPress();
    setState(() {
      _dragging = section;
      _dragDy = 0;
      _dragOrder = order;
      _dragTops = tops;
      _dragHeights = heights;
      _dragTarget = order.indexOf(section);
    });
  }

  void _updateDrag(double dy) {
    final section = _dragging;
    if (section == null) return;
    final a = _dragOrder.indexOf(section);
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

  void _endDrag() {
    final section = _dragging;
    if (section == null) return;
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
    if (b != a) ref.read(homeEditProvider.notifier).move(section, b);
  }

  void _cancelDrag() {
    if (_dragging == null) return;
    for (final s in _dragOrder) {
      _shiftOf(s).animateTo(0);
    }
    setState(() => _dragging = null);
  }
}

/// A section's drag handle: hold and drag to move the section.
class _DragHandle extends StatelessWidget {
  const _DragHandle({required this.onStart, required this.onUpdate, required this.onEnd});

  final VoidCallback onStart;
  final ValueChanged<double> onUpdate;
  final VoidCallback onEnd;

  @override
  Widget build(BuildContext context) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onVerticalDragStart: (_) => onStart(),
    onVerticalDragUpdate: (d) => onUpdate(d.delta.dy),
    onVerticalDragEnd: (_) => onEnd(),
    onVerticalDragCancel: onEnd,
    child: SizedBox.square(
      dimension: HomeHeaderMetrics.touchBox,
      child: Icon(YoinSymbols.dragHandle, color: Theme.of(context).colorScheme.onSurfaceVariant),
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
