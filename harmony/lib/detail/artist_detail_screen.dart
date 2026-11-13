import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../shell/detail_chrome.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/spring_cross_fade.dart';
import 'artist_detail_view_model.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_routes.dart';
import 'detail_strings.dart';
import 'detail_track_rows.dart';
import 'pull_up_reveal.dart';

/// The Artist page (PushPage; Android ArtistDetailScreen, Compact): header
/// with the active span and the favorite star, the open-ring portrait that
/// turns with scroll and fades as it leaves, then the discography timeline.
/// Play / Shuffle (bar) queue the whole discography.
class ArtistDetailScreen extends ConsumerStatefulWidget {
  const ArtistDetailScreen({super.key, required this.artistId});

  final MediaId artistId;

  @override
  ConsumerState<ArtistDetailScreen> createState() => _ArtistDetailScreenState();
}

class _ArtistDetailScreenState extends ConsumerState<ArtistDetailScreen> {
  // Scroll offset is visual state: the ring reads it at paint time.
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(artistDetailProvider(widget.artistId));
    final source = ref.watch(activeSourceProvider).value;
    final strings = DetailStrings.of(context);
    final content = state is ArtistDetailContent ? state : null;
    final hero = content?.heroCover;
    final heroUrl = hero == null ? null : source?.resolveCoverUrl(hero);
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: DetailPalette(
        coverUrl: heroUrl,
        child: _ArtistChrome(
          content: content,
          child: Stack(
            children: [
              const Positioned.fill(child: DetailPageBackground()),
              Column(
                children: [
                  SafeArea(bottom: false, child: _ArtistHeader(content: content)),
                  Expanded(
                    child: SpringCrossFade(
                      childKey: state.runtimeType,
                      child: switch (state) {
                        ArtistDetailLoading() => const DetailLoadingView(),
                        ArtistDetailError(:final notFound) => DetailErrorView(
                          message: notFound ? strings.artistNotFound : strings.artistLoadError,
                          onRetry: () => ref.read(artistDetailProvider(widget.artistId).notifier).retry(),
                        ),
                        ArtistDetailContent() => _ArtistBody(
                          content: state,
                          heroUrl: heroUrl,
                          scroll: _scroll,
                          artistId: widget.artistId,
                        ),
                      },
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ArtistHeader extends ConsumerWidget {
  const _ArtistHeader({required this.content});

  final ArtistDetailContent? content;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final content = this.content;
    final strings = DetailStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final span = content?.activeSpan;
    return Padding(
      padding: const EdgeInsetsDirectional.only(
        start: DetailHeaderMetrics.paddingStart,
        end: DetailHeaderMetrics.paddingEnd,
        top: DetailHeaderMetrics.paddingTop,
        bottom: DetailHeaderMetrics.paddingBottom,
      ),
      child: SizedBox(
        height: DetailHeaderMetrics.rowHeight,
        child: Row(
          children: [
            const DetailBackButton(),
            const SizedBox(width: DetailHeaderMetrics.titleGap - 4),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  DetailTitle(content?.artist.name ?? ''),
                  if (span != null)
                    Text(
                      span,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: colors.onSurfaceVariant,
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                ],
              ),
            ),
            if (content != null) _ArtistStar(artist: content.artist, strings: strings),
          ],
        ),
      ),
    );
  }
}

class _ArtistStar extends ConsumerWidget {
  const _ArtistStar({required this.artist, required this.strings});

  final ArtistDetail artist;
  final DetailStrings strings;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(favoriteOverridesProvider.select((m) => m[artist.id] ?? artist.isStarred));
    final colors = Theme.of(context).colorScheme;
    return DetailColorsBuilder(
      builder: (context, page) => PopToggle(
        on: on,
        size: 48,
        tooltip: on ? strings.artistFavorited : strings.artistFavorite,
        onToggle: () => ref.read(favoriteOverridesProvider.notifier).setFavorite(artist.id, target: !on, previous: on),
        builder: (t) => Icon(
          t > 0.5 ? YoinSymbols.starFilled : YoinSymbols.star,
          color: Color.lerp(colors.onSurfaceVariant, page.accent, t),
        ),
      ),
    );
  }
}

class _ArtistBody extends ConsumerWidget {
  const _ArtistBody({required this.content, required this.heroUrl, required this.scroll, required this.artistId});

  final ArtistDetailContent content;
  final String? heroUrl;
  final ScrollController scroll;
  final MediaId artistId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    return LayoutBuilder(
      builder: (context, constraints) {
        final portrait = math.min(
          constraints.maxWidth * DetailTokens.artistPortraitWidthFraction,
          DetailTokens.artistPortraitMax,
        );
        return SingleChildScrollView(
          controller: scroll,
          padding: EdgeInsets.only(bottom: DetailTokens.artistBottomPadding + bottomInset),
          child: Column(
            children: [
              SizedBox(
                height: portrait + DetailTokens.artistBandExtra,
                child: Center(
                  child: _RingPortrait(portrait: portrait, url: heroUrl, scroll: scroll),
                ),
              ),
              const SizedBox(height: 32),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _Discography(content: content, artistId: artistId),
              ),
            ],
          ),
        );
      },
    );
  }
}

/// The open-ring portrait (DetailBackdrops.kt ArtistOpenRing): ring and
/// dot turn with scroll (0.06°/px) and fade as the band leaves (v1 fades
/// instead of breaking into dots); read at paint time, no rebuild.
class _RingPortrait extends StatelessWidget {
  const _RingPortrait({required this.portrait, required this.url, required this.scroll});

  final double portrait;
  final String? url;
  final ScrollController scroll;

  @override
  Widget build(BuildContext context) {
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final reach = portrait * ArtistRingGeometry.reach;
    return SizedBox.square(
      dimension: reach * 2,
      child: DetailColorsBuilder(
        builder: (context, page) => CustomPaint(
          painter: _RingPainter(scroll: scroll, portrait: portrait, ring: page.accent, dot: page.ring),
          child: Center(
            child: SizedBox.square(
              dimension: portrait,
              child: Material(
                shape: const CircleBorder(),
                clipBehavior: Clip.antiAlias,
                elevation: 0,
                surfaceTintColor: Colors.transparent,
                child: MediaArtwork(
                  url: url,
                  fallbackIcon: YoinSymbols.artist,
                  circle: true,
                  decodeWidth: math.min(720, (portrait * dpr).round()),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The ring's geometry from the portrait size.
abstract final class ArtistRingGeometry {
  /// Outer radius as a share of the portrait.
  static const double reach = 0.84;

  /// The opening: centred at −45° (upper right), 0.9 rad wide.
  static const double openingCenter = -math.pi / 4;
  static const double openingWidth = 0.9;

  /// Fully gone after this much scroll.
  static const double leaveDistance = 220;

  static double room(double portrait) => portrait * (reach - 0.5);
  static double stroke(double portrait) => room(portrait) * 0.59;
  static double radius(double portrait) => portrait / 2 + room(portrait) * 0.41 + stroke(portrait) / 2;
  static double dotRadius(double portrait) => stroke(portrait) * 0.45;
}

class _RingPainter extends CustomPainter {
  _RingPainter({required this.scroll, required this.portrait, required this.ring, required this.dot})
    : super(repaint: scroll);

  final ScrollController scroll;
  final double portrait;
  final Color ring;
  final Color dot;

  @override
  void paint(Canvas canvas, Size size) {
    final offset = scroll.hasClients ? scroll.offset.clamp(0.0, double.infinity) : 0.0;
    final leaving = PullUpMath.smoothStep(0, ArtistRingGeometry.leaveDistance, offset);
    if (leaving >= 1) return;
    final turn = offset * DetailTokens.artistMarkDegreesPerPx * math.pi / 180;
    final c = size.center(Offset.zero);
    final r = ArtistRingGeometry.radius(portrait);
    final stroke = ArtistRingGeometry.stroke(portrait);
    final gapCenter = ArtistRingGeometry.openingCenter + turn;
    final start = gapCenter + ArtistRingGeometry.openingWidth / 2;
    final sweep = 2 * math.pi - ArtistRingGeometry.openingWidth;
    canvas.drawArc(
      Rect.fromCircle(center: c, radius: r),
      start,
      sweep,
      false,
      Paint()
        ..color = ring.withValues(alpha: ring.a * (1 - leaving))
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawCircle(
      c + Offset(math.cos(gapCenter), math.sin(gapCenter)) * r,
      ArtistRingGeometry.dotRadius(portrait),
      Paint()..color = dot.withValues(alpha: dot.a * (1 - leaving)),
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.ring != ring || old.dot != dot || old.portrait != portrait || old.scroll != scroll;
}

class _Discography extends ConsumerWidget {
  const _Discography({required this.content, required this.artistId});

  final ArtistDetailContent content;
  final MediaId artistId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final releases = content.filteredReleases;
    final latest = content.releases.firstOrNull?.id;
    final kinds = content.kinds;
    final notifier = ref.read(artistDetailProvider(artistId).notifier);
    final first = releases.take(DetailTokens.artistCollapsedCount).toList();
    final rest = releases.skip(DetailTokens.artistCollapsedCount).toList();
    Widget row(int i, Album album) =>
        _ReleaseRow(album: album, showYear: i == 0 || releases[i - 1].year != album.year, isLatest: album.id == latest);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              Text(
                strings.artistDiscography,
                style: theme.textTheme.titleMedium?.copyWith(
                  decoration: TextDecoration.underline,
                  decorationColor: colors.onSurface,
                ),
              ),
              const Spacer(),
              Text(
                strings.artistReleases(content.releases.length),
                style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
              ),
            ],
          ),
        ),
        if (kinds.length >= 2) ...[
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (final (kind, label) in [
                  (null, strings.filterAll),
                  if (kinds.contains(ReleaseKind.album)) (ReleaseKind.album, strings.filterAlbums),
                  if (kinds.contains(ReleaseKind.singlesAndEps)) (ReleaseKind.singlesAndEps, strings.filterSingles),
                  if (kinds.contains(ReleaseKind.compilation)) (ReleaseKind.compilation, strings.filterCompilations),
                ]) ...[
                  ExpressiveSegmentChip(
                    label: label,
                    selected: content.filter == kind,
                    onTap: () => notifier.setFilter(kind),
                  ),
                  const SizedBox(width: LibraryTokens.chipGap),
                ],
              ],
            ),
          ),
        ],
        const SizedBox(height: 8),
        if (releases.isEmpty)
          Padding(
            padding: const EdgeInsets.all(8),
            child: Text(strings.artistNoReleases, style: theme.textTheme.bodyMedium),
          ),
        for (final (i, a) in first.indexed) row(i, a),
        if (rest.isNotEmpty) ...[
          _VerticalSpringReveal(
            visible: content.showAll,
            child: Column(children: [for (final (j, a) in rest.indexed) row(first.length + j, a)]),
          ),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: Padding(
              padding: const EdgeInsetsDirectional.only(start: 48),
              child: DetailColorsBuilder(
                builder: (context, page) => TextButton(
                  style: TextButton.styleFrom(foregroundColor: page.accent),
                  onPressed: notifier.toggleShowAll,
                  child: Text(content.showAll ? strings.artistShowFewer : strings.artistShowAll(releases.length)),
                ),
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// expandVertically + fade on the spatial spring (Show all).
class _VerticalSpringReveal extends StatefulWidget {
  const _VerticalSpringReveal({required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<_VerticalSpringReveal> createState() => _VerticalSpringRevealState();
}

class _VerticalSpringRevealState extends State<_VerticalSpringReveal> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_VerticalSpringReveal old) {
    super.didUpdateWidget(old);
    _size.animateTo(widget.visible ? 1 : 0);
    _alpha.animateTo(widget.visible ? 1 : 0);
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_size.animation, _alpha.animation]),
    child: widget.child,
    builder: (context, child) {
      final h = _size.value.clamp(0.0, 1.2);
      if (h <= 0.001 && !widget.visible) return const SizedBox.shrink();
      return ClipRect(
        child: Align(
          alignment: Alignment.topCenter,
          heightFactor: h,
          child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        ),
      );
    },
  );
}

class _ReleaseRow extends ConsumerWidget {
  const _ReleaseRow({required this.album, required this.showYear, required this.isLatest});

  final Album album;
  final bool showYear;
  final bool isLatest;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = DetailStrings.of(context);
    final source = ref.watch(activeSourceProvider).value;
    final cover = album.coverArt;
    final url = cover == null ? null : source?.resolveCoverUrl(cover);
    final kind = switch (album.releaseType) {
      ReleaseType.ep => strings.kindEp,
      ReleaseType.single => strings.kindSingle,
      ReleaseType.compilation => strings.kindCompilation,
      ReleaseType.album || null => strings.kindAlbum,
    };
    final songs = album.songCount;
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        onTap: () => DetailRoutes.openAlbum(context, album.id, preview: album),
        child: Padding(
          padding: const EdgeInsetsDirectional.only(end: 8, top: 8, bottom: 8),
          child: Row(
            children: [
              SizedBox(
                width: DetailTokens.artistYearColumn,
                child: Padding(
                  padding: const EdgeInsetsDirectional.only(start: 8),
                  child: showYear
                      ? Text(
                          album.year?.toString() ?? '–',
                          style: theme.textTheme.labelLarge?.copyWith(
                            color: colors.onSurfaceVariant,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        )
                      : null,
                ),
              ),
              SizedBox.square(
                dimension: 56,
                child: MediaArtwork(
                  url: url,
                  fallbackIcon: YoinSymbols.album,
                  radius: ShapeTokens.cover,
                  decodeWidth: 168,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            albumTitleWithoutVersion(album.name, album.version),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleMedium,
                          ),
                        ),
                        if (album.version != null) ...[const SizedBox(width: 8), AlbumVersionTag(album.version!)],
                      ],
                    ),
                    MetaLine(
                      groups: [
                        if (isLatest) MetaKind(strings.artistLatest),
                        MetaKind(kind, color: colors.onSurfaceVariant),
                        if (songs != null) MetaStat('$songs', strings.unitSong(songs)),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ArtistChrome extends ConsumerWidget {
  const _ArtistChrome({required this.content, required this.child});

  final ArtistDetailContent? content;
  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final palette = DetailPaletteScope.maybeOf(context)?.target;
    final content = this.content;
    void play({required bool shuffle}) {
      if (content == null || content.releases.isEmpty) return;
      playArtist(
        DetailPlayer.widget(ref),
        artist: content.artist,
        releases: content.releases,
        cover: content.artist.coverArt,
        shuffle: shuffle,
      );
    }

    // ▾ after Shuffle play (ArtistDetailActivity rememberDetailMenu): Play next and Add to queue, read
    // the whole discography like Play. Share waits for Subsonic share links.
    final strings = DetailStrings.of(context);
    void queue({required bool next}) {
      if (content == null || content.releases.isEmpty) return;
      final player = DetailPlayer.widget(ref);
      queueFromDetail(player, () => loadArtistTracks(content.releases, player.cache.album), next: next).then((count) {
        if (count > 0 && context.mounted) {
          showDetailMessage(context, next ? strings.menuPlayNextCount(count) : strings.menuQueued(count));
        }
      });
    }

    return DetailChromeRegistration(
      actions: DetailPlayActions(
        onPlay: () => play(shuffle: false),
        onShuffle: () => play(shuffle: true),
        menuItems: content == null || content.releases.isEmpty
            ? const []
            : [
                DetailMenuAction(
                  icon: YoinSymbols.skipNext,
                  label: strings.menuPlayNext,
                  onTap: () => queue(next: true),
                ),
                DetailMenuAction(
                  icon: YoinSymbols.queue,
                  label: strings.menuAddToQueue,
                  onTap: () => queue(next: false),
                ),
              ],
        playContainer: palette?.accent,
        playContent: palette?.onAccent,
      ),
      child: child,
    );
  }
}
