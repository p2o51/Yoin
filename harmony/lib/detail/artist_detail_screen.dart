import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/release/release_marks.dart';
import '../data/source/music_source.dart';
import '../platform/share/text_share.dart';
import '../shell/detail_chrome.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/expressive_segment_chip.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/user_message.dart';
import 'album_hero_marks.dart' show SpringSizeReveal;
import 'artist_detail_view_model.dart';
import 'artist_most_played.dart';
import 'detail_layout.dart';
import 'detail_listening.dart';
import 'detail_page.dart';
import 'dot_dissolve.dart';
import 'detail_palette.dart';
import 'detail_play.dart';
import 'detail_routes.dart';
import 'detail_share.dart';
import 'detail_strings.dart';
import 'detail_track_rows.dart';
import 'pull_up_reveal.dart';
import 'release_kind_labels.dart';

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
              // A landscape handset: the page starts past the edge capsules (detailChromeBand).
              ShellChromeBand(
                child: Column(
                  children: [
                    SafeArea(bottom: false, child: _ArtistHeader(content: content)),
                    Expanded(
                      child: SpringCrossFade(
                        childKey: state.runtimeType,
                        child: switch (state) {
                          ArtistDetailLoading() => const DetailLoadingView(),
                          ArtistDetailError(:final notFound, :final cause, :final diagnostics) => DetailErrorView(
                            message: notFound
                                ? strings.artistNotFound
                                : userMessageText(context, cause, strings.artistLoadError),
                            diagnostics: notFound ? null : diagnostics,
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
    // From Medium up the hero carries the name and the star: the header keeps only back,
    // so the name appears once (ArtistTopHeader showIdentity).
    final heroCarriesIdentity = switch (resolveDetailLayout(YoinWindowInfo.of(context))) {
      DetailLayout.medium || DetailLayout.wide => true,
      _ => false,
    };
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
                  if (!heroCarriesIdentity) DetailTitle(content?.artist.name ?? ''),
                  if (span != null && !heroCarriesIdentity)
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
            if (content != null && !heroCarriesIdentity) _ArtistStar(artist: content.artist, strings: strings),
          ],
        ),
      ),
    );
  }
}

/// (idle, active) labels for the star (artistFollowLabels): Spotify has a
/// real Follow; the others only star, so it reads Favorite.
(String, String) artistFollowLabels(MediaId artistId, DetailStrings strings) =>
    artistId.provider == MediaId.providerSpotify
    ? (strings.artistFollow, strings.artistFollowing)
    : (strings.artistFavorite, strings.artistFavorited);

class _ArtistStar extends ConsumerWidget {
  const _ArtistStar({required this.artist, required this.strings, this.showLabel = false});

  final ArtistDetail artist;
  final DetailStrings strings;

  /// The wide hero names the action beside the star (Follow / Favorite).
  final bool showLabel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // A provider without favourites has nothing to follow: no star, in the header or the hero (supportsFollow).
    final canFollow = ref.watch(
      activeSourceProvider.select((s) => s.value?.capabilities.contains(Capability.favorites) ?? false),
    );
    if (!canFollow) return const SizedBox.shrink();
    final on = ref.watch(favoriteOverridesProvider.select((m) => m[artist.id] ?? artist.isStarred));
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final labels = artistFollowLabels(artist.id, strings);
    final star = DetailColorsBuilder(
      builder: (context, page) => PopToggle(
        on: on,
        size: 48,
        tooltip: on ? labels.$2 : labels.$1,
        onToggle: () => ref.read(favoriteOverridesProvider.notifier).setFavorite(artist.id, target: !on, previous: on),
        builder: (t) => Icon(
          t > 0.5 ? YoinSymbols.starFilled : YoinSymbols.star,
          color: Color.lerp(colors.onSurfaceVariant, page.accent, t),
        ),
      ),
    );
    if (!showLabel) return star;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SpringCrossFade(
          childKey: on,
          fit: StackFit.loose,
          child: Text(
            on ? labels.$2 : labels.$1,
            key: ValueKey(on),
            style: theme.textTheme.labelLarge?.copyWith(
              decoration: TextDecoration.underline,
              decorationColor: colors.onSurface,
              fontWeight: FontWeight.w500,
              color: colors.onSurface,
            ),
          ),
        ),
        const SizedBox(width: DetailLayoutTokens.artistFollowLabelGap),
        star,
      ],
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
    final colors = Theme.of(context).colorScheme;
    final listening = ref.watch(artistListeningProvider(artistId));
    // Height first: a landscape handset turns the hero sideways; from Medium up the
    // hero carries the identity; a Wide window sets Most Played beside the discography.
    final layout = resolveDetailLayout(YoinWindowInfo.of(context));
    final landscape = layout == DetailLayout.landscape;
    final desktop = layout == DetailLayout.wide;
    final mostPlayedVisible = listening.mostPlayed.isNotEmpty;
    return LayoutBuilder(
      builder: (context, constraints) {
        final Widget hero = switch (layout) {
          DetailLayout.landscape => _ArtistLandscapeHero(
            content: content,
            heroUrl: heroUrl,
            scroll: scroll,
            listening: listening,
          ),
          DetailLayout.medium ||
          DetailLayout.wide => _ArtistWideHero(content: content, heroUrl: heroUrl, scroll: scroll, desktop: desktop),
          DetailLayout.compact || DetailLayout.short => () {
            final portrait = math.min(
              constraints.maxWidth * DetailTokens.artistPortraitWidthFraction,
              DetailTokens.artistPortraitMax,
            );
            return SizedBox(
              height: portrait + DetailTokens.artistBandExtra,
              child: Center(
                child: _RingPortrait(portrait: portrait, url: heroUrl, scroll: scroll),
              ),
            );
          }(),
        };
        final Widget sections;
        if (desktop) {
          // Wide full window (ArtistDesktop): Most Played (480) beside the discography. It arrives
          // with the personal layer and widens in from the start, carrying its 48 gap, so the
          // discography narrows with it instead of jumping.
          sections = Padding(
            padding: const EdgeInsets.symmetric(horizontal: DetailLayoutTokens.artistDesktopInset),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SpringSizeReveal(
                  axis: Axis.horizontal,
                  visible: mostPlayedVisible,
                  child: Padding(
                    padding: const EdgeInsetsDirectional.only(end: DetailLayoutTokens.artistDesktopMostPlayedGap),
                    child: SizedBox(
                      width: DetailLayoutTokens.artistDesktopMostPlayed,
                      child: ArtistMostPlayed(artist: content.artist, listening: listening),
                    ),
                  ),
                ),
                Expanded(
                  child: _Discography(content: content, artistId: artistId),
                ),
              ],
            ),
          );
        } else {
          final column = Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // The personal layer: arrives after the page paints, grows in instead of popping.
              // Landscape shows it beside the portrait already.
              if (!landscape)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: _VerticalSpringReveal(
                    visible: mostPlayedVisible,
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: ArtistMostPlayedTokens.bottomGap),
                      child: ArtistMostPlayed(artist: content.artist, listening: listening),
                    ),
                  ),
                ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _Discography(content: content, artistId: artistId),
              ),
            ],
          );
          // Medium: the sections keep to the 720 page column, centred.
          sections = layout == DetailLayout.medium
              ? Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: DetailLayoutTokens.pageMaxWidth),
                    child: column,
                  ),
                )
              : column;
        }
        // Scrolls under the fixed header through the chrome seam (the user's Scroll edge style).
        return DetailColorsBuilder(
          builder: (context, page) => ChromeSeam(
            controller: scroll,
            background: SeamBackground([page.wash, colors.surface, colors.surfaceContainerLow]),
            field: true,
            child: SingleChildScrollView(
              controller: scroll,
              // Landscape keeps the group in the cutout band: only the nav bar needs clearing.
              padding: EdgeInsets.only(
                bottom:
                    (landscape ? DetailLayoutTokens.artistLandscapeBottom : DetailTokens.artistBottomPadding) +
                    bottomInset,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  hero,
                  SizedBox(height: landscape ? DetailLayoutTokens.artistLandscapeSectionGap : 32),
                  sections,
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// Landscape handset (ArtistLandscapeHero): the 220 portrait in its ring on
/// the left — kept whole, clear of the capsule band and the scroll edge —
/// with Most Played on the right. The name stays in the header row.
class _ArtistLandscapeHero extends StatelessWidget {
  const _ArtistLandscapeHero({
    required this.content,
    required this.heroUrl,
    required this.scroll,
    required this.listening,
  });

  final ArtistDetailContent content;
  final String? heroUrl;
  final ScrollController scroll;
  final ArtistListening listening;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(
      start: DetailLayoutTokens.artistLandscapeStart,
      end: DetailLayoutTokens.artistLandscapeEnd,
      top: DetailLayoutTokens.artistLandscapeTop,
    ),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _SideRingPortrait(
          portrait: DetailLayoutTokens.artistLandscapePortrait,
          reach: DetailLayoutTokens.artistLandscapeRingReach,
          url: heroUrl,
          scroll: scroll,
        ),
        const SizedBox(width: DetailLayoutTokens.artistLandscapeGap),
        Expanded(
          child: _VerticalSpringReveal(
            visible: listening.mostPlayed.isNotEmpty,
            child: ArtistMostPlayed(artist: content.artist, listening: listening),
          ),
        ),
      ],
    ),
  );
}

/// The Medium / Wide hero (ArtistWideHero): the ringed portrait on the left
/// (180 Medium, 220 Wide), the identity column on the right — the only place
/// the name appears here — with "N releases" and the span, and the star with
/// its label. Medium keeps to the 720 page column.
class _ArtistWideHero extends StatelessWidget {
  const _ArtistWideHero({required this.content, required this.heroUrl, required this.scroll, required this.desktop});

  final ArtistDetailContent content;
  final String? heroUrl;
  final ScrollController scroll;
  final bool desktop;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final nameStyle = desktop ? theme.textTheme.displayMedium : theme.textTheme.headlineLarge;
    final count = content.releases.length;
    final span = content.activeSpan;
    final row = Padding(
      // The ring stays inside the page (a column's edge would clip it otherwise).
      padding: EdgeInsets.symmetric(
        horizontal: desktop ? DetailLayoutTokens.artistWideHeroInset : DetailLayoutTokens.artistMediumHeroInset,
        vertical: DetailLayoutTokens.artistWideHeroGap,
      ),
      child: Row(
        children: [
          _SideRingPortrait(
            portrait: desktop ? DetailLayoutTokens.artistWidePortrait : DetailLayoutTokens.artistMediumPortrait,
            reach: desktop ? DetailLayoutTokens.artistWideRingReach : DetailLayoutTokens.artistMediumRingReach,
            url: heroUrl,
            scroll: scroll,
          ),
          const SizedBox(width: DetailLayoutTokens.artistWideHeroGap),
          Expanded(
            child: SeamFade(
              fontSize: nameStyle?.fontSize ?? 36,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: DetailLayoutTokens.artistIdentityGap,
                children: [
                  DetailColorsBuilder(
                    builder: (context, page) => Text(
                      content.artist.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: nameStyle?.copyWith(color: page.accent),
                    ),
                  ),
                  MetaLine(
                    style: theme.textTheme.titleSmall?.copyWith(fontFeatures: const [FontFeature.tabularFigures()]),
                    groups: [MetaStat('$count', strings.unitRelease(count)), if (span != null) MetaPlain(span)],
                  ),
                  _ArtistStar(artist: content.artist, strings: strings, showLabel: true),
                ],
              ),
            ),
          ),
        ],
      ),
    );
    if (desktop) return row;
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: DetailLayoutTokens.pageMaxWidth),
        child: row,
      ),
    );
  }
}

/// A ringed portrait beside the page's text (landscape, Medium, Wide): the
/// box is the portrait's, the ring (outer radius [reach] × the portrait) is
/// drawn centred on it past its bounds, turning and dissolving with the
/// page's scroll like the phone hero's.
class _SideRingPortrait extends StatelessWidget {
  const _SideRingPortrait({required this.portrait, required this.reach, required this.url, required this.scroll});

  final double portrait;
  final double reach;
  final String? url;
  final ScrollController scroll;

  @override
  Widget build(BuildContext context) {
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final reduce = MediaQuery.disableAnimationsOf(context);
    final ring = ArtistRingGeometry.stage(portrait, reach: reach);
    final inset = (ring - portrait) / 2;
    return SizedBox.square(
      dimension: portrait,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Positioned(
            left: -inset,
            top: -inset,
            width: ring,
            height: ring,
            child: IgnorePointer(
              child: DotDissolve(
                repaint: scroll,
                progress: () => reduce ? 0 : ArtistRingGeometry.leaving(scroll),
                child: DetailColorsBuilder(
                  builder: (context, page) => CustomPaint(
                    painter: _RingPainter(
                      scroll: scroll,
                      portrait: portrait,
                      ring: page.accent,
                      dot: page.ring,
                      turns: !reduce,
                      reach: reach,
                    ),
                  ),
                ),
              ),
            ),
          ),
          Positioned.fill(
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
        ],
      ),
    );
  }
}

/// The open-ring portrait (DetailBackdrops.kt ArtistOpenRing): ring and
/// dot turn with scroll (0.06°/px) and break up into fine dots as the band
/// leaves (DotDissolve); read at paint time, no rebuild. Reduced motion: no
/// turn and no dissolve, the hero just scrolls away whole.
class _RingPortrait extends StatelessWidget {
  const _RingPortrait({required this.portrait, required this.url, required this.scroll});

  final double portrait;
  final String? url;
  final ScrollController scroll;

  @override
  Widget build(BuildContext context) {
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final reduce = MediaQuery.disableAnimationsOf(context);
    final reach = portrait * ArtistRingGeometry.reach;
    return SizedBox.square(
      dimension: reach * 2,
      child: Stack(
        fit: StackFit.expand,
        children: [
          DotDissolve(
            repaint: scroll,
            progress: () => reduce ? 0 : ArtistRingGeometry.leaving(scroll),
            child: DetailColorsBuilder(
              builder: (context, page) => CustomPaint(
                painter: _RingPainter(
                  scroll: scroll,
                  portrait: portrait,
                  ring: page.accent,
                  dot: page.ring,
                  turns: !reduce,
                ),
              ),
            ),
          ),
          Center(
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
        ],
      ),
    );
  }
}

/// The ring's geometry from the portrait size.
abstract final class ArtistRingGeometry {
  /// Outer radius as a share of the portrait (the phone hero's).
  static const double reach = 0.84;

  /// The opening: centred at −45° (upper right), 0.9 rad wide.
  static const double openingCenter = -math.pi / 4;
  static const double openingWidth = 0.9;

  /// Fully gone after this much scroll.
  static const double leaveDistance = 220;

  static double room(double portrait, {double reach = ArtistRingGeometry.reach}) => portrait * (reach - 0.5);
  static double stroke(double portrait, {double reach = ArtistRingGeometry.reach}) =>
      room(portrait, reach: reach) * 0.59;
  static double radius(double portrait, {double reach = ArtistRingGeometry.reach}) =>
      portrait / 2 + room(portrait, reach: reach) * 0.41 + stroke(portrait, reach: reach) / 2;
  static double dotRadius(double portrait, {double reach = ArtistRingGeometry.reach}) =>
      stroke(portrait, reach: reach) * 0.45;

  /// The square the ring is drawn in, centred on the portrait (ArtistOpenRing's requiredSize).
  static double stage(double portrait, {double reach = ArtistRingGeometry.reach}) =>
      portrait * 2 * reach + portrait * 0.1;

  /// How far the ring has left (0 = whole, 1 = dissolved) at the page's scroll.
  static double leaving(ScrollController scroll) =>
      PullUpMath.smoothStep(0, leaveDistance, scroll.hasClients ? scroll.offset.clamp(0.0, double.infinity) : 0.0);
}

class _RingPainter extends CustomPainter {
  _RingPainter({
    required this.scroll,
    required this.portrait,
    required this.ring,
    required this.dot,
    required this.turns,
    this.reach = ArtistRingGeometry.reach,
  }) : super(repaint: scroll);

  final ScrollController scroll;
  final double portrait;
  final Color ring;
  final Color dot;
  final bool turns;
  final double reach;

  @override
  void paint(Canvas canvas, Size size) {
    final offset = scroll.hasClients ? scroll.offset.clamp(0.0, double.infinity) : 0.0;
    final turn = turns ? offset * DetailTokens.artistMarkDegreesPerPx * math.pi / 180 : 0.0;
    final c = size.center(Offset.zero);
    final r = ArtistRingGeometry.radius(portrait, reach: reach);
    final stroke = ArtistRingGeometry.stroke(portrait, reach: reach);
    final gapCenter = ArtistRingGeometry.openingCenter + turn;
    final start = gapCenter + ArtistRingGeometry.openingWidth / 2;
    final sweep = 2 * math.pi - ArtistRingGeometry.openingWidth;
    canvas.drawArc(
      Rect.fromCircle(center: c, radius: r),
      start,
      sweep,
      false,
      Paint()
        ..color = ring
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawCircle(
      c + Offset(math.cos(gapCenter), math.sin(gapCenter)) * r,
      ArtistRingGeometry.dotRadius(portrait, reach: reach),
      Paint()..color = dot,
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.ring != ring ||
      old.dot != dot ||
      old.portrait != portrait ||
      old.scroll != scroll ||
      old.turns != turns ||
      old.reach != reach;
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
    final filters = content.filters;
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
          child: SeamFade(
            fontSize: theme.textTheme.titleMedium?.fontSize ?? 16,
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
        ),
        if (filters.isNotEmpty) ...[
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (final f in filters) ...[
                  ExpressiveSegmentChip(
                    label: releaseKindFilterLabel(strings, f),
                    selected: content.filter == f,
                    onTap: () => notifier.setFilter(f),
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
    final kind = releaseKindLabel(strings, ReleaseMarks.headerKind(album.releaseType));
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
                      ? SeamFade(
                          fontSize: theme.textTheme.labelLarge?.fontSize ?? 14,
                          child: Text(
                            album.year?.toString() ?? '–',
                            style: theme.textTheme.labelLarge?.copyWith(
                              color: colors.onSurfaceVariant,
                              fontFeatures: const [FontFeature.tabularFigures()],
                            ),
                          ),
                        )
                      : null,
                ),
              ),
              SizedBox.square(
                dimension: 56,
                child: SeamDissolve(
                  child: MediaArtwork(
                    url: url,
                    fallbackIcon: YoinSymbols.album,
                    radius: ShapeTokens.cover,
                    decodeWidth: 168,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: SeamFade(
                  fontSize: theme.textTheme.titleMedium?.fontSize ?? 16,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // The kind is on the meta line: the name without Apple Music's " - EP" / " - Single".
                      VersionTitleRow.orTitle(
                        name: ReleaseMarks.nameWithoutKindSuffix(
                          albumTitleWithoutVersion(album.name, album.version),
                          album.releaseType,
                        ),
                        nameStyle: theme.textTheme.titleMedium!,
                        version: album.version,
                        explicit: album.isExplicit,
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

    // ▾ after Shuffle play (ArtistDetailActivity rememberDetailMenu + promotable): Play next and Add to
    // queue read the whole discography like Play; Open in Apple Music once the artist's page is known;
    // Share sends the name with that page (a server makes no share link for an artist).
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

    final link = content == null
        ? null
        : ref.watch(detailWebLinkProvider((WebLinkKind.artist, content.artist.id))).value;
    final openIn = openInLabel(link, strings);
    final container = ProviderScope.containerOf(context, listen: false);

    return DetailChromeRegistration(
      actions: DetailPlayActions(
        onPlay: () => play(shuffle: false),
        onShuffle: () => play(shuffle: true),
        menuItems: content == null
            ? const []
            : [
                if (content.releases.isNotEmpty) ...[
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
                if ((link, openIn) case (final url?, final label?))
                  DetailMenuAction(icon: YoinSymbols.launch, label: label, onTap: () => openDetailLink(container, url)),
                DetailMenuAction(
                  icon: YoinSymbols.share,
                  label: strings.share,
                  promotable: true,
                  onTap: () {
                    final name = content.artist.name.trim();
                    container
                        .read(textShareProvider)
                        .shareText(
                          detailShareText(name.isEmpty ? strings.shareArtist : name, link),
                          title: name.isEmpty ? strings.shareArtist : name,
                        );
                  },
                ),
              ],
        playContainer: palette?.accent,
        playContent: palette?.onAccent,
      ),
      child: child,
    );
  }
}
