import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/source/music_source.dart';
import '../memories/memories_pull.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/expressive/loading_indicator.dart';
import 'activity_bento.dart';
import 'home_feed_density.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'home_view_model.dart';
import 'home_shelves.dart';
import 'jump_back_in.dart';
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

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _flow;
    _reveal;
    _feedAlpha;
    _feedRise;
    _frameBlend;
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
    final colors = Theme.of(context).colorScheme;
    final density = _density ?? HomeFeedDensity.of(YoinWindowInfo.of(context));
    final padding = MediaQuery.viewPaddingOf(context);
    final content = state is HomeContent ? state : _lastContent;

    final feed = AnimatedBuilder(
      animation: _frameBlend.animation,
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
        return CustomScrollView(
          controller: _scroll,
          slivers: [
            SliverPadding(
              padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
              sliver: SliverToBoxAdapter(
                child: HomeHeader(accountTitle: content?.accountTitle, compactHeight: frame.landscape),
              ),
            ),
            if (content != null) SliverList.list(children: _sections(content, layout, frame)),
            SliverToBoxAdapter(child: SizedBox(height: bottom)),
          ],
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
            enabled: state is HomeContent,
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
          Positioned.fill(child: IgnorePointer(child: _LoadingIndicator(visible: state is HomeLoading))),
          if (state is HomeError) Positioned.fill(child: _ErrorPage(error: state.error)),
        ],
      ),
    );
  }

  List<Widget> _sections(HomeContent content, HomeLayout layout, HomeFeedFrame frame) {
    final out = <Widget>[];
    Widget padded(Widget child) => Padding(padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end), child: child);
    for (final section in layout.visibleV1Sections) {
      final (Widget? body, int beat) = switch (section) {
        HomeSection.activities => (padded(ActivitiesSection(content: content, frame: frame)), 0),
        HomeSection.jumpBackIn => (content.grid.isEmpty ? null : padded(JumpBackInSection(cards: content.grid, frame: frame)), 1),
        HomeSection.recentlyAdded => (
            content.recentlyAddedTracks.isEmpty && content.recentlyAddedAlbums.isEmpty
                ? null
                : RecentlyAddedSection(tracks: content.recentlyAddedTracks, albums: content.recentlyAddedAlbums, frame: frame),
            2
          ),
        HomeSection.recentlyPlayed => (
            content.recentlyPlayed.isEmpty ? null : RecentlyPlayedSection(albums: content.recentlyPlayed, frame: frame),
            2
          ),
        HomeSection.yourPlaylists => (
            content.playlists.isEmpty ? null : YourPlaylistsSection(playlists: content.playlists, frame: frame),
            2
          ),
        HomeSection.rediscover || HomeSection.otherProfiles || HomeSection.lyricHighlight || HomeSection.memoryGrid => (null, 0),
      };
      if (body == null) continue;
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
      out.add(Padding(
        key: ValueKey(section),
        padding: EdgeInsets.only(top: frame.itemSpacing),
        child: SectionAppear(animate: animate, child: revealed),
      ));
    }
    if (!_sectionsSeeded) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _sectionsSeeded = true);
    }
    return out;
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
    final err = error;
    final message = err is SourceException && err.message.isNotEmpty ? err.message : strings.errorLoadFailed;
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeFeedMetrics.cappedMargin),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(message, textAlign: TextAlign.center, style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error)),
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
