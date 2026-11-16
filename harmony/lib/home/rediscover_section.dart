import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../detail/detail_routes.dart';
import '../memories/copy/memory_copy.dart';
import '../theme/tokens.dart';
import '../ui/component/seam/seam_dissolve.dart' show SeamSurface;
import 'home_edit.dart';
import 'home_edit_wiggle.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'rediscover_selection.dart';
import 'widgets/cover_palette.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_motion.dart';
import 'widgets/home_text.dart';
import 'widgets/seam.dart';

/// Rediscover (RediscoverSection.kt): albums you kept something on that
/// haven't played in Yoin for 90+ days — one line of what was kept and how
/// long it has been away, the score as a sticker on the cover's corner — and
/// albums you wrote about but never played here. A tap opens the album.
/// Phone: a full-bleed shelf peeking the next card (0.86 of the content; a
/// lone card fills it); wider feeds: two or three side by side, still.
class RediscoverSection extends StatelessWidget {
  const RediscoverSection({super.key, required this.items, required this.frame, required this.nowMs});

  final List<HomeRediscoverItem> items;
  final HomeFeedFrame frame;
  final int nowMs;

  @override
  Widget build(BuildContext context) {
    final visible = rediscoverVisibleCount(frame.units, landscape: frame.landscape);
    final shelf = isRediscoverShelf(visible);
    final shown = items.take(visible).toList();
    const gap = HomeRediscoverMetrics.gap;
    final width = !shelf
        ? (frame.content - gap * (visible - 1)) / visible
        : (shown.length <= 1 ? frame.content : frame.content * HomeRediscoverMetrics.shelfCardFraction);
    final scale = MediaQuery.textScalerOf(context).scale(1).clamp(1.0, double.infinity);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
          child: HomeSectionTitle(HomeStrings.of(context).sectionRediscover),
        ),
        const SizedBox(height: HomeSectionMetrics.shelfSpacing),
        // Edit mode: the bleeding shelf is cut back to its plate.
        HomeEditShelfClip(
          escapeStart: frame.start,
          escapeEnd: frame.end,
          child: EdgeFadeShelf(
            height: HomeRediscoverMetrics.cardHeight * scale,
            padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
            gap: gap,
            scrollable: shelf && shown.length > 1,
            itemCount: shown.length,
            itemBuilder: (context, i) => SizedBox(
              key: ValueKey(shown[i].albumId),
              width: width.clamp(0.0, double.infinity),
              child: RediscoverCard(item: shown[i], nowMs: nowMs),
            ),
          ),
        ),
      ],
    );
  }
}

/// One album card on the Activities wash (the cover's own base, 30 % over
/// surfaceContainerLow); the eyebrow, title and footnote in the card's ink
/// (the palette's base on light, its accent on dark).
class RediscoverCard extends ConsumerWidget {
  const RediscoverCard({super.key, required this.item, required this.nowMs});

  final HomeRediscoverItem item;
  final int nowMs;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final dark = theme.brightness == Brightness.dark;
    final image = resolveHomeCover(ref, context, item.cover,
        requestSize: HomeCoverRequest.standard, side: HomeRediscoverMetrics.backdrop * HomeBackdropMetrics.artworkFraction);
    final shape = RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card));
    return HomeEditCard(
      kickKey: item.albumId,
      child: ElasticPress(
        onTap: () => DetailRoutes.openAlbum(context, item.albumId, preview: item.album),
        child: BackdropColorBuilder(
          cacheKey: image?.url,
          image: image?.image,
          fallback: colors.secondaryContainer,
          // The tinted card, its cover and badge break up as one print near the bar; the text stays whole.
          builder: (context, base, child) => SeamSurface(
            color: Color.lerp(colors.surfaceContainerLow, base, HomeTokens.cardWashFraction)!,
            shape: shape,
            clipBehavior: Clip.antiAlias,
            child: child!,
          ),
          child: BackdropColorBuilder(
            cacheKey: image?.url,
            image: image?.image,
            fallback: dark ? colors.tertiary : colors.secondary,
            accent: dark,
            builder: (context, ink, _) => Padding(
              padding: const EdgeInsets.all(HomeRediscoverMetrics.padding),
              child: Row(
                children: [
                  SizedBox.square(
                    dimension: HomeRediscoverMetrics.backdrop,
                    child: Stack(
                      clipBehavior: Clip.none,
                      children: [
                        BackdropCover(cover: item.cover, kind: HomeCardKind.album, size: HomeRediscoverMetrics.backdrop),
                        if (item.score != null)
                          PositionedDirectional(
                            end: -HomeRediscoverMetrics.badgeHang,
                            bottom: 0,
                            child: _ScoreBadge(score: MemoryScores.text(item.score!), kind: item.scoreKind, ink: ink),
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(width: HomeRediscoverMetrics.padding),
                  Expanded(child: _RediscoverCopy(item: item, nowMs: nowMs, ink: ink)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RediscoverCopy extends StatelessWidget {
  const _RediscoverCopy({required this.item, required this.nowMs, required this.ink});

  final HomeRediscoverItem item;
  final int nowMs;
  final Color ink;

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final text = Theme.of(context).textTheme;
    final colors = Theme.of(context).colorScheme;
    final eyebrow = text.labelMedium!.copyWith(color: ink);
    final reason = rediscoverReason(item);
    final notesStat = reason == RediscoverReason.notes && !item.hasReview ? item.noteCount : null;
    final away = item.fromNotes ? null : rediscoverAway(item.lastPlayedAt, nowMs);
    final footnote = <MetaGroup>[
      if (item.firstPlayedAt != null) MetaPlain(_month(item.firstPlayedAt!)),
      if (item.playCount > 0) MetaStat('${item.playCount}', strings.playsUnit(item.playCount)),
    ];
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (notesStat != null)
          SeamFade(
            fontSize: eyebrow.fontSize!,
            child: MetaLine(groups: [MetaStat('$notesStat', strings.notesUnit(notesStat))], style: eyebrow),
          )
        else if (reason != null)
          SeamText(switch (reason) {
            RediscoverReason.reviewed => strings.rediscoverReviewed,
            RediscoverReason.notes => strings.rediscoverNotes(item.noteCount),
            RediscoverReason.tracksRated => strings.rediscoverTracksRated(item.ratedTrackCount),
          }, style: eyebrow),
        if (away != null) SeamText(strings.rediscoverAway(away.count, years: away.years), style: eyebrow),
        Padding(
          padding: const EdgeInsets.only(top: HomeRediscoverMetrics.titleTop),
          child: SeamFade(
            fontSize: HomeRediscoverMetrics.titleSize,
            child: MarqueeText(
              item.albumName,
              style: text.titleLarge!.copyWith(
                fontWeight: FontWeight.w600,
                fontSize: HomeRediscoverMetrics.titleSize,
                height: HomeRediscoverMetrics.titleLineHeight / HomeRediscoverMetrics.titleSize,
                color: colors.onSurface,
              ),
            ),
          ),
        ),
        if (item.artistName != null && item.artistName!.trim().isNotEmpty)
          SeamText(item.artistName!, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
        if (footnote.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(top: HomeRediscoverMetrics.footnoteTop),
            child: SeamFade(
              fontSize: text.labelSmall!.fontSize!,
              child: MetaLine(groups: footnote, style: text.labelSmall!.copyWith(color: colors.onSurfaceVariant)),
            ),
          ),
      ],
    );
  }

  /// The first-play month, `yyyy.MM` in every locale.
  static String _month(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${d.year}.${d.month.toString().padLeft(2, '0')}';
  }
}

/// The score as a sticker, in the Memories seal's grammar: your album score
/// a solid ink pill, a track average an ink outline on the card's wash.
class _ScoreBadge extends StatelessWidget {
  const _ScoreBadge({required this.score, required this.kind, required this.ink});

  final String score;
  final RediscoverScoreKind kind;
  final Color ink;

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final solid = kind != RediscoverScoreKind.average;
    final wash = colors.surfaceContainerLow;
    return Semantics(
      label: solid ? strings.rediscoverCdYourRating(score) : strings.rediscoverCdTrackAverage(score),
      excludeSemantics: true,
      // The sticker breaks up with the card near the bar; its score stays whole.
      child: SeamSurface(
        color: solid ? ink : wash,
        shape: StadiumBorder(side: solid ? BorderSide.none : BorderSide(color: ink, width: HomeRediscoverMetrics.badgeLine)),
        child: Container(
          height: HomeRediscoverMetrics.badgeHeight,
          padding: const EdgeInsets.symmetric(horizontal: HomeRediscoverMetrics.badgePadding),
          alignment: Alignment.center,
          child: Text(
            score,
            maxLines: 1,
            style: Theme.of(context).textTheme.labelLarge!.copyWith(
              fontWeight: FontWeight.w700,
              color: solid ? wash : ink,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ),
      ),
    );
  }
}
