import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../detail/detail_routes.dart';
import '../playback/playback_context.dart';
import '../shell/shell_state.dart';
import '../theme/motion.dart';
import '../theme/playback_theme.dart';
import '../theme/tokens.dart';
import 'cover_swap.dart';
import 'flight.dart';
import 'haptics.dart';
import 'lyrics_view.dart';
import 'marquee.dart';
import 'now_playing_background.dart';
import 'now_playing_view_model.dart';
import 'nowplaying_strings.dart';
import 'overlay_controller.dart';
import 'playhead_clock.dart';
import 'queue_sheet.dart';
import 'rating_and_heart.dart';
import 'stage_budget.dart';
import 'transport.dart';

double _smooth(double edge0, double edge1, double x) {
  final t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// An [Animation] whose value is [map] of its parent's (no rebuilds, just
/// derived reads for transitions).
class _Mapped extends Animation<double> with AnimationWithParentMixin<double> {
  _Mapped(this.parent, this.map);

  @override
  final Animation<double> parent;
  final double Function(double) map;

  @override
  double get value => map(parent.value);
}

/// Expanded top bar's docked cover (44 in a 48 slot after the 48 chevron).
const double _dockedCover = 44;
const double _dockedCoverX = 4 + 48 + 4 + 2;
const double _dockedCoverY = (NowPlayingTokens.topBarNominal - _dockedCover) / 2;

/// The Now Playing player (phone column). The column is one fixed-height
/// slot layout from [resolveStageColumnBudget]; the Expanded lyrics stage
/// interpolates every slot on the controller's detail spring by relayout
/// only (the column is not rebuilt per frame).
class NowPlayingScreen extends ConsumerStatefulWidget {
  const NowPlayingScreen({super.key, required this.controller});

  final NowPlayingOverlayController controller;

  @override
  ConsumerState<NowPlayingScreen> createState() => _NowPlayingScreenState();
}

class _NowPlayingScreenState extends ConsumerState<NowPlayingScreen> {
  final ValueNotifier<TransportKey> _pressed = ValueNotifier(TransportKey.none);
  final ValueNotifier<bool> _autoFollow = ValueNotifier(true);
  LyricFrame? _lastFrame;

  @override
  void dispose() {
    _pressed.dispose();
    _autoFollow.dispose();
    super.dispose();
  }

  StageMetrics _metrics(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final scaler = MediaQuery.textScalerOf(context);
    double line(TextStyle s, {double sizeFactor = 1}) =>
        scaler.scale(s.fontSize! * sizeFactor) * (s.height ?? 1.25) / sizeFactor;
    return StageMetrics(
      heroArtistLine: line(text.titleMedium!),
      heroTitleLine: line(text.displaySmall!),
      activeLyricLine: line(text.bodyLarge!),
      inactiveLyricLine: line(text.bodyMedium!),
      timeLabelLine: line(text.labelLarge!),
    );
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(nowPlayingViewModelProvider);
    final field = ref.watch(playbackThemeProvider.select((t) => t.field));
    final colors = Theme.of(context).colorScheme;
    final padding = MediaQuery.paddingOf(context);
    final insets = EdgeInsets.fromLTRB(
      padding.left,
      padding.top,
      padding.right,
      math.max(padding.bottom, NowPlayingTokens.columnBottomFloor),
    );
    final strings = NowPlayingStrings.of(context);
    return ColoredBox(
      color: colors.surface,
      child: Stack(
        fit: StackFit.expand,
        children: [
          NowPlayingBackground(field: field),
          switch (state) {
            NowPlayingIdle() => Center(
              child: Text(
                strings.emptyNothingPlaying,
                style: Theme.of(context).textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant),
              ),
            ),
            NowPlayingContent() => Padding(
              padding: insets,
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final budget = resolveStageColumnBudget(
                    width: constraints.maxWidth,
                    height: constraints.maxHeight,
                    metrics: _metrics(context),
                    devicePixelRatio: MediaQuery.devicePixelRatioOf(context),
                    previousFrame: _lastFrame,
                  );
                  _lastFrame = budget.lyricFrame;
                  return _Stage(
                    budget: budget,
                    state: state,
                    controller: widget.controller,
                    pressed: _pressed,
                    autoFollow: _autoFollow,
                    windowTop: insets.top,
                  );
                },
              ),
            ),
          },
        ],
      ),
    );
  }
}

enum _Slot { topBar, cover, rating, lyricsWindow, lyricsFull, controls, hero, accessory }

class _StageDelegate extends MultiChildLayoutDelegate {
  _StageDelegate(this.budget, this.detail) : super(relayout: detail);

  final StageColumnBudget budget;
  final Animation<double> detail;

  @override
  void performLayout(Size size) {
    final d = detail.value.clamp(0.0, 1.0);
    final s = StageSlots.lerp(budget, d);
    final full = StageSlots.lerp(budget, 1);
    final w = size.width;

    layoutChild(_Slot.topBar, BoxConstraints.tight(Size(w, s.topBar)));
    positionChild(_Slot.topBar, Offset.zero);

    // The cover flies from its row to the docked slot; it only moves and scales.
    final f = _smooth(0, 1, d);
    final coverSize = lerpDouble(budget.cover, _dockedCover, f)!;
    if (hasChild(_Slot.cover)) {
      layoutChild(_Slot.cover, BoxConstraints.tight(Size.square(math.max(0, coverSize))));
      positionChild(
        _Slot.cover,
        Offset(
          lerpDouble(NowPlayingTokens.columnPadding, _dockedCoverX, f)!,
          lerpDouble(budget.topBar, _dockedCoverY, f)!,
        ),
      );
    }
    if (hasChild(_Slot.rating)) {
      layoutChild(_Slot.rating, BoxConstraints.tight(Size(NowPlayingTokens.phoneRatingColumn, budget.cover)));
      positionChild(
        _Slot.rating,
        Offset(w - NowPlayingTokens.columnPadding - NowPlayingTokens.phoneRatingColumn, s.topBar),
      );
    }

    final lyricsTop = s.topBar + s.coverRow + s.coverGap;
    layoutChild(_Slot.lyricsWindow, BoxConstraints.tight(Size(w, math.max(0, s.lyrics))));
    positionChild(_Slot.lyricsWindow, Offset(0, lyricsTop));
    if (hasChild(_Slot.lyricsFull)) {
      // Laid out at its FINAL height always; only clipped / moved during the reshape.
      layoutChild(_Slot.lyricsFull, BoxConstraints.tight(Size(w, math.max(0, full.lyrics))));
      positionChild(_Slot.lyricsFull, Offset(0, lyricsTop + (s.lyrics - full.lyrics)));
    }

    final controlsTop = lyricsTop + s.lyrics;
    layoutChild(_Slot.controls, BoxConstraints.tightFor(width: w, height: budget.controls));
    positionChild(_Slot.controls, Offset(0, controlsTop));

    final heroTop = controlsTop + s.controls;
    layoutChild(_Slot.hero, BoxConstraints.tight(Size(w, budget.hero)));
    positionChild(_Slot.hero, Offset(0, heroTop));

    layoutChild(_Slot.accessory, BoxConstraints.tight(Size(w, s.accessory)));
    positionChild(_Slot.accessory, Offset(0, heroTop + s.hero));
  }

  @override
  bool shouldRelayout(_StageDelegate old) => old.budget != budget || old.detail != detail;
}

class _Stage extends ConsumerWidget {
  const _Stage({
    required this.budget,
    required this.state,
    required this.controller,
    required this.pressed,
    required this.autoFollow,
    required this.windowTop,
  });

  final StageColumnBudget budget;
  final NowPlayingContent state;
  final NowPlayingOverlayController controller;
  final ValueNotifier<TransportKey> pressed;
  final ValueNotifier<bool> autoFollow;
  final double windowTop;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // Rebuild on stage / open changes (not per frame).
    NowPlayingOverlayScope.maybeOf(context);
    final vm = ref.read(nowPlayingViewModelProvider.notifier);
    final clock = ref.watch(playheadClockProvider);
    final detail = controller.detail.animation;
    final compact = _Mapped(detail, (d) => (1 - d).clamp(0.0, 1.0));
    final expandedStage = controller.stage == NowPlayingStage.expanded;
    final track = state.track;

    return CustomMultiChildLayout(
      delegate: _StageDelegate(budget, detail),
      children: [
        LayoutId(
          id: _Slot.topBar,
          child: _TopBar(
            controller: controller,
            context0: state.context,
            onContextTap: _contextTarget(state.context, controller),
          ),
        ),
        if (!budget.coverHidden)
          LayoutId(
            id: _Slot.cover,
            child: GestureDetector(
              onTap: expandedStage ? controller.stepBackStage : null,
              child: FlightSlot(
                element: FlightElement.cover,
                pill: false,
                child: _StageCover(state: state, detail: detail, requestEdge: budget.cover),
              ),
            ),
          ),
        if (!budget.coverHidden)
          LayoutId(
            id: _Slot.rating,
            child: IgnorePointer(
              ignoring: expandedStage,
              child: AnimatedBuilder(
                animation: detail,
                builder: (context, child) {
                  final d = detail.value.clamp(0.0, 1.0);
                  return Opacity(
                    opacity: 1 - d,
                    child: Transform.translate(offset: Offset(72 * d, 0), child: child),
                  );
                },
                child: Column(
                  children: [
                    Expanded(
                      // Local 0–10 rating (shown for every source, like Android's local_ratings).
                      child: RatingSlider(rating: state.rating, songKey: state.songKey, onCommit: vm.commitRating),
                    ),
                    if (state.canFavorite) ...[
                      const SizedBox(height: NowPlayingTokens.ratingToHeartGap - 2),
                      FavoriteHeart(starred: state.isStarred, onToggle: vm.toggleFavorite),
                    ],
                    const SizedBox(height: NowPlayingTokens.heartBottomGap - 2),
                  ],
                ),
              ),
            ),
          ),
        LayoutId(
          id: _Slot.lyricsWindow,
          child: IgnorePointer(
            ignoring: expandedStage,
            child: FadeTransition(
              opacity: compact,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: controller.expandStage,
                child: LyricsView(
                  mode: LyricsListMode.window,
                  lyrics: state.lyrics,
                  songKey: state.songKey,
                  clock: clock,
                  onSeek: vm.seekTo,
                ),
              ),
            ),
          ),
        ),
        if (expandedStage || controller.detail.value > 0.01)
          LayoutId(
            id: _Slot.lyricsFull,
            child: AnimatedBuilder(
              animation: detail,
              builder: (context, child) {
                final d = detail.value.clamp(0.0, 1.0);
                return Opacity(
                  opacity: ((d - 0.1) / 0.6).clamp(0.0, 1.0),
                  child: Transform.scale(scale: 0.96 + 0.04 * d, child: child),
                );
              },
              child: ClipRect(
                child: LyricsView(
                  mode: LyricsListMode.full,
                  lyrics: state.lyrics,
                  songKey: state.songKey,
                  clock: clock,
                  onSeek: vm.seekTo,
                  title: track.title,
                  artist: track.artist,
                  autoFollow: autoFollow,
                  windowTop: windowTop + NowPlayingTokens.topBarNominal + NowPlayingTokens.coverGapMin,
                ),
              ),
            ),
          ),
        LayoutId(
          id: _Slot.controls,
          child: IgnorePointer(
            ignoring: expandedStage,
            child: AnimatedBuilder(
              animation: detail,
              builder: (context, child) {
                final d = detail.value.clamp(0.0, 1.0);
                return Opacity(
                  opacity: 1 - d,
                  child: Transform.translate(offset: Offset(0, 48 * d), child: child),
                );
              },
              child: Align(
                alignment: Alignment.topCenter,
                child: TransportControls(
                  isPlaying: state.isPlaying,
                  isWavePlaying: state.isWavePlaying,
                  playMode: state.playMode,
                  clock: clock,
                  durationMs: state.durationMs,
                  labelsUnder: budget.labelsUnderWave,
                  waveVisible: !expandedStage,
                  onPlayPause: vm.togglePlayPause,
                  onNext: vm.next,
                  onPrevious: vm.previous,
                  onPlayMode: vm.cyclePlayMode,
                  onSeek: vm.seekToFraction,
                  pressed: pressed,
                ),
              ),
            ),
          ),
        ),
        LayoutId(
          id: _Slot.hero,
          child: _Hero(
            title: track.title ?? '',
            artist: track.artist ?? '',
            onTitleTap: switch (track.albumId) {
              final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, id)),
              null => null,
            },
            onArtistTap: switch (track.artistId) {
              final id? => () => _leaveTo(controller, (nav) => DetailRoutes.openArtist(nav, id, name: track.artist)),
              null => null,
            },
            isPlaying: state.isPlaying,
            pressed: pressed,
            oneLine: budget.heroOneLine,
          ),
        ),
        LayoutId(
          id: _Slot.accessory,
          child: _Accessory(controller: controller, compact: compact, autoFollow: autoFollow, onSkip: vm.skipTo),
        ),
      ],
    );
  }
}

/// Closes the player, then opens a detail page on the app Navigator (the
/// player sits above it; Android NowPlayingScreen onAlbumClick /
/// onArtistClick / onPlaylistClick). The chrome's back entry detaches in a
/// microtask scheduled by the close, so the push is queued after it.
void _leaveTo(NowPlayingOverlayController controller, void Function(BuildContext navigator) open) {
  controller.close();
  scheduleMicrotask(() {
    final navigator = appNavigatorKey.currentContext;
    if (navigator != null && navigator.mounted) open(navigator);
  });
}

/// PLAYING FROM's destination: the album, artist or playlist the play
/// started from; null (not tappable) for none / Favorites.
VoidCallback? _contextTarget(PlaybackContext context, NowPlayingOverlayController controller) => switch (context) {
  AlbumPlaybackContext(:final albumId) => () => _leaveTo(controller, (nav) => DetailRoutes.openAlbum(nav, albumId)),
  ArtistPlaybackContext(:final artistId, :final name) => () => _leaveTo(
    controller,
    (nav) => DetailRoutes.openArtist(nav, artistId, name: name),
  ),
  PlaylistPlaybackContext(:final playlistId) => () => _leaveTo(
    controller,
    (nav) => DetailRoutes.openPlaylist(nav, playlistId),
  ),
  NoPlaybackContext() || FavoritesPlaybackContext() => null,
};

/// The resting cover (16 dp continuous corners, no stroke), flying to the
/// 44 dp docked thumb as the stage expands (radius 16 → 8). Decoded once at
/// the resting size so the flight never reloads it. Song changes cross-fade
/// in CoverSwap on the slow artworkDissolve spring (the DotDissolve sweep is
/// deferred).
class _StageCover extends StatelessWidget {
  const _StageCover({required this.state, required this.detail, required this.requestEdge});

  final NowPlayingContent state;
  final Animation<double> detail;
  final double requestEdge;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final px = (requestEdge * MediaQuery.devicePixelRatioOf(context)).round();
    return Semantics(
      image: true,
      label: strings.cdAlbumCover,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final edge = constraints.maxWidth;
          return AnimatedBuilder(
            animation: detail,
            builder: (context, _) {
              final radius = lerpDouble(ShapeTokens.nowPlayingCover, 8, _smooth(0, 1, detail.value.clamp(0.0, 1.0)))!;
              final cover = state.track.coverArt;
              if (cover == null) {
                return ClipRSuperellipse(
                  borderRadius: BorderRadius.circular(radius),
                  child: ColoredBox(
                    color: colors.surfaceContainerHighest,
                    child: Center(
                      child: Icon(YoinSymbols.playArrow, size: edge * 0.3, color: colors.onSurfaceVariant),
                    ),
                  ),
                );
              }
              return CoverSwap(
                cover: cover,
                size: edge,
                radius: radius,
                requestSize: px,
                // The pill's decode (in memory): under a cold first load.
                fallbackRequestSize: (pillCoverRequestDp * MediaQuery.devicePixelRatioOf(context)).round(),
                spring: CoverSwap.artworkDissolve,
              );
            },
          );
        },
      ),
    );
  }
}

/// The hero's horizontal breathing scale (title, artist): held > playing >
/// paused, gentler under reduced motion. The shared flight lands on the
/// same values so the hand-over to the real text doesn't jump.
({double title, double artist}) heroRestScale({required bool playing, bool held = false, bool reduced = false}) =>
    held
    ? (title: reduced ? 1.02 : 1.05, artist: reduced ? 1.015 : 1.04)
    : playing
    ? (title: reduced ? 1.01 : 1.03, artist: reduced ? 1.008 : 1.02)
    : (title: reduced ? 0.99 : 0.97, artist: reduced ? 0.992 : 0.98);

/// Chevron + "PLAYING FROM …". The chevron turns 180° with the drag and 90°
/// with the stage (Expanded: points back).
class _TopBar extends StatelessWidget {
  const _TopBar({required this.controller, required this.context0, this.onContextTap});

  final NowPlayingOverlayController controller;
  final PlaybackContext context0;

  /// Opens where the play started (null: not a place).
  final VoidCallback? onContextTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final (kind, name) = switch (context0) {
      AlbumPlaybackContext(:final name) => (strings.fromAlbum, name),
      PlaylistPlaybackContext(:final name) => (strings.fromPlaylist, name),
      ArtistPlaybackContext(:final name) => (strings.fromArtist, name),
      NoPlaybackContext() || FavoritesPlaybackContext() => (strings.fromNowPlaying, null),
    };
    final expanded = controller.stage == NowPlayingStage.expanded;
    return Align(
      alignment: Alignment.bottomLeft,
      child: SizedBox(
        height: math.max(NowPlayingTokens.topBarMin, 48),
        child: Padding(
          padding: const EdgeInsetsDirectional.only(start: 4, end: NowPlayingTokens.columnPadding),
          child: Row(
            children: [
              Semantics(
                button: true,
                label: expanded ? strings.cdBackNowPlaying : strings.cdCloseNowPlaying,
                excludeSemantics: true,
                child: IconButton(
                  onPressed: controller.onChevron,
                  icon: AnimatedBuilder(
                    animation: Listenable.merge([controller.dragDy.animation, controller.detail.animation]),
                    builder: (context, child) => Transform.rotate(
                      angle:
                          math.pi * controller.dismissFraction + math.pi / 2 * controller.detail.value.clamp(0.0, 1.0),
                      child: child,
                    ),
                    child: Icon(
                      YoinSymbols.chevronDown,
                      size: NowPlayingTokens.chevronGlyph,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 4),
              // The docked cover's slot opens with the stage (the cover itself is laid out by the stage).
              AnimatedBuilder(
                animation: controller.detail.animation,
                builder: (context, _) => SizedBox(
                  width:
                      48 * _smooth(0, 1, controller.detail.value.clamp(0.0, 1.0)) +
                      10 * controller.detail.value.clamp(0.0, 1.0),
                ),
              ),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: onContextTap,
                  child: name == null
                      ? Text(
                          kind,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.72)),
                        )
                      : Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              kind,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.55)),
                            ),
                            Text(
                              name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.labelMedium!.copyWith(color: colors.onSurface),
                            ),
                          ],
                        ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Artist over the marquee title, both stretched in X from the start edge
/// by the play state (title 1.05 held / 1.03 playing / 0.97 paused; artist
/// 1.04 / 1.02 / 0.98; reduced motion 1.02 / 1.01 / 0.99 and 1.015 / 1.008 /
/// 0.992) on the Expressive slow spatial spring.
class _Hero extends StatefulWidget {
  const _Hero({
    required this.title,
    required this.artist,
    required this.isPlaying,
    required this.pressed,
    required this.oneLine,
    this.onTitleTap,
    this.onArtistTap,
  });

  final String title;
  final String artist;

  /// The title opens the album, the artist line the artist (null: no id).
  final VoidCallback? onTitleTap;
  final VoidCallback? onArtistTap;
  final bool isPlaying;
  final ValueNotifier<TransportKey> pressed;
  final bool oneLine;

  @override
  State<_Hero> createState() => _HeroState();
}

class _HeroState extends State<_Hero> with TickerProviderStateMixin {
  // Mounted at rest (no 1 → 0.97 squeeze while the shared flight lands on it:
  // the flight draws the same rest scale, so the hand-over is seamless).
  late final SpringValue _title = SpringValue(
    vsync: this,
    initial: heroRestScale(playing: widget.isPlaying).title,
    spring: YoinSprings.slowSpatial,
  );
  late final SpringValue _artist = SpringValue(
    vsync: this,
    initial: heroRestScale(playing: widget.isPlaying).artist,
    spring: YoinSprings.slowSpatial,
  );

  @override
  void initState() {
    super.initState();
    widget.pressed.addListener(_retarget);
    WidgetsBinding.instance.addPostFrameCallback((_) => _retarget());
  }

  @override
  void didUpdateWidget(_Hero oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.pressed != widget.pressed) {
      oldWidget.pressed.removeListener(_retarget);
      widget.pressed.addListener(_retarget);
    }
    _retarget();
  }

  @override
  void dispose() {
    widget.pressed.removeListener(_retarget);
    _title.dispose();
    _artist.dispose();
    super.dispose();
  }

  void _retarget() {
    if (!mounted) return;
    final reduced = MediaQuery.disableAnimationsOf(context);
    final held = widget.pressed.value != TransportKey.none;
    final playing = widget.isPlaying;
    final spring = reduced ? YoinSprings.standardFastSpatial : YoinSprings.slowSpatial;
    _title.spring = spring;
    _artist.spring = spring;
    final target = heroRestScale(playing: playing, held: held, reduced: reduced);
    _title.animateTo(target.title);
    _artist.animateTo(target.artist);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final artistStyle = text.titleMedium!.copyWith(
      fontSize: text.titleMedium!.fontSize! * 0.9,
      height: (text.titleMedium!.height ?? 1.5) / 0.9,
      color: colors.onSurfaceVariant,
    );
    final titleStyle = text.displaySmall!.copyWith(color: colors.onSurface);
    final artist = GestureDetector(
      onTap: widget.onArtistTap,
      child: FlightSlot(
        element: FlightElement.artist,
        pill: false,
        child: AnimatedBuilder(
          animation: _artist.animation,
          builder: (context, child) =>
              Transform.scale(scaleX: _artist.value, alignment: AlignmentDirectional.centerStart, child: child),
          child: Text(widget.artist, maxLines: 1, overflow: TextOverflow.ellipsis, style: artistStyle),
        ),
      ),
    );
    final title = GestureDetector(
      onTap: widget.onTitleTap,
      child: ClipRect(
        child: FlightSlot(
          element: FlightElement.title,
          pill: false,
          child: AnimatedBuilder(
            animation: _title.animation,
            builder: (context, child) =>
                Transform.scale(scaleX: _title.value, alignment: AlignmentDirectional.centerStart, child: child),
            child: Marquee(key: ValueKey(widget.title), text: widget.title, style: titleStyle, edgeFade: 28),
          ),
        ),
      ),
    );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
      child: widget.oneLine
          ? Row(
              crossAxisAlignment: CrossAxisAlignment.baseline,
              textBaseline: TextBaseline.alphabetic,
              children: [
                Expanded(child: title),
                const SizedBox(width: NowPlayingTokens.heroOneLineGap),
                Flexible(
                  flex: 0,
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                      maxWidth: MediaQuery.sizeOf(context).width * NowPlayingTokens.heroOneLineArtistShare,
                    ),
                    child: artist,
                  ),
                ),
              ],
            )
          : Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                artist,
                const SizedBox(height: NowPlayingTokens.heroLineGap),
                title,
              ],
            ),
    );
  }
}

/// The accessory row: the Queue pill at rest, the Recenter key in Expanded.
class _Accessory extends StatelessWidget {
  const _Accessory({required this.controller, required this.compact, required this.autoFollow, required this.onSkip});

  final NowPlayingOverlayController controller;
  final Animation<double> compact;
  final ValueNotifier<bool> autoFollow;
  final ValueChanged<int> onSkip;

  @override
  Widget build(BuildContext context) {
    final expanded = controller.stage == NowPlayingStage.expanded;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
      child: Stack(
        children: [
          Align(
            alignment: AlignmentDirectional.bottomStart,
            child: IgnorePointer(
              ignoring: expanded,
              child: FadeTransition(
                opacity: compact,
                child: _QueuePill(
                  onTap: () {
                    YoinHaptics.contextClick();
                    showQueueSheet(context, onSkip: onSkip);
                  },
                ),
              ),
            ),
          ),
          if (expanded)
            Align(
              alignment: AlignmentDirectional.bottomStart,
              child: FadeTransition(
                opacity: _Mapped(compact, (c) => (1 - c).clamp(0.0, 1.0)),
                child: _RecenterKey(autoFollow: autoFollow),
              ),
            ),
        ],
      ),
    );
  }
}

class _QueuePill extends StatefulWidget {
  const _QueuePill({required this.onTap});

  final VoidCallback onTap;

  @override
  State<_QueuePill> createState() => _QueuePillState();
}

class _QueuePillState extends State<_QueuePill> with TickerProviderStateMixin {
  late final SpringValue _press = SpringValue(vsync: this, initial: 0, spring: YoinSprings.standardFastSpatial);

  @override
  void dispose() {
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    final style = Theme.of(context).textTheme.labelMedium!.copyWith(color: colors.onPrimary);
    final labelWidth = (TextPainter(
      text: TextSpan(text: strings.pillQueue, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
      maxLines: 1,
    )..layout()).width;
    const icon = NowPlayingTokens.pillHeight * 0.36;
    const pad = 13.2;
    return Semantics(
      button: true,
      label: strings.pillQueue,
      excludeSemantics: true,
      child: GestureDetector(
        onTapDown: (_) => _press.animateTo(1),
        onTapUp: (_) => _press.animateTo(0),
        onTapCancel: () => _press.animateTo(0),
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _press.animation,
          builder: (context, _) => Container(
            height: NowPlayingTokens.pillHeight,
            width: pad * 2 + icon + 8 + labelWidth * (1 + 0.8 * _press.value),
            decoration: ShapeDecoration(color: colors.primary, shape: const StadiumBorder()),
            alignment: Alignment.center,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(YoinSymbols.queue, size: icon, color: colors.onPrimary),
                const SizedBox(width: 8),
                Text(strings.pillQueue, style: style, maxLines: 1),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RecenterKey extends StatelessWidget {
  const _RecenterKey({required this.autoFollow});

  final ValueNotifier<bool> autoFollow;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NowPlayingStrings.of(context);
    return ValueListenableBuilder<bool>(
      valueListenable: autoFollow,
      builder: (context, following, _) => Semantics(
        button: true,
        enabled: !following,
        label: strings.cdReturnCurrentLine,
        excludeSemantics: true,
        child: Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: Material(
            color: following ? colors.primaryContainer.withValues(alpha: 0.38) : colors.primaryContainer,
            shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(52 * 0.31)),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: following
                  ? null
                  : () {
                      YoinHaptics.tick();
                      autoFollow.value = true;
                    },
              child: SizedBox.square(
                dimension: 52,
                child: Icon(
                  YoinSymbols.recenter,
                  color: colors.onPrimaryContainer.withValues(alpha: following ? 0.38 : 1),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
