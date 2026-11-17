import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/seam/seam_dissolve.dart' show SeamSurface;
import 'home_actions.dart';
import 'home_edit_wiggle.dart';
import 'home_feed_density.dart';
import 'home_header.dart';
import 'home_metrics.dart';
import 'home_row_presets.dart';
import 'home_rows_resize.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/cover_palette.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';
import 'widgets/home_text.dart';
import 'widgets/seam.dart';
import 'widgets/spring_height.dart';

/// Activities: the title and the bento at its row preset (S / M / L / XL,
/// home_row_presets.dart), or one "No activity yet" card (no title) when
/// there is no history. Under Home's [HomeRowsScope] the bento renders
/// through [HomeRowsBody], so edit mode's ⌟ can resize it card by card.
class ActivitiesSection extends StatelessWidget {
  const ActivitiesSection({super.key, required this.content, required this.frame, this.preset = HomeRowPreset.defaultPreset});

  final HomeContent content;
  final HomeFeedFrame frame;
  final HomeRowPreset preset;

  @override
  Widget build(BuildContext context) {
    final entries = content.activityEntries;
    final strings = HomeStrings.of(context);
    // No cards: the whole block sways in edit mode (homeEditBlockWiggle).
    if (entries.isEmpty) return HomeEditBlockWiggle(child: HomeEmptyCard(title: strings.activitiesEmptyTitle));
    final units = bentoRecipeFor(frame) == BentoRecipe.units;
    final scale = MediaQuery.textScalerOf(context).scale(1).clamp(1.0, double.infinity);
    final title = Padding(
      padding: const EdgeInsets.only(bottom: HomeSectionMetrics.titleBottomPadding),
      child: HomeSectionTitle(strings.sectionActivities),
    );
    // Deep enough for XL; the hero is still found where it always was (the
    // first 6 / 16), so every other preset seats exactly what it did.
    final pool = entries.take(units ? HomeBentoMetrics.unitsEntries : HomeBentoMetrics.phoneEntries).toList();
    final heroIndex = selectHeroIndex(pool, window: units ? HomeBentoMetrics.unitsHeroWindow : HomeBentoMetrics.heroWindow);
    final hero = heroIndex < 0 ? null : pool[heroIndex];
    final candidates = [
      for (var i = 0; i < pool.length; i++)
        if (i != heroIndex) pool[i],
    ];
    final spec = activityBentoSpec(
      feedUnits: frame.units,
      compactHeight: frame.landscape,
      seed: activityLayoutSeed(hero?.entityKey),
      hasHero: hero != null,
    );
    final ladder = activityRowLadder(spec, hasHero: hero != null, supportingCount: candidates.length);
    final track = HomeRowsScope.maybeOf(context)?.track(HomeSection.activities);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        title,
        const SizedBox(height: HomeSectionMetrics.activitiesSpacing),
        // A width class (N) re-seats in place — no crossfade (2026-11-12) —
        // and the section's height springs between the two. A row preset
        // updates in place (edit mode's resize interpolates it).
        SpringHeight(
          heightKey: spec,
          child: HomeRowsBody<ActivityPresetLayout>(
            track: track,
            ladder: ladder,
            // What the ladder is built from (Android remembers it on the same): a new
            // play reordering the entries keeps a resize in flight and its measures.
            ladderKey: (spec, hero != null, candidates.length),
            preset: preset,
            builder: (context, stop) {
              final composition = stop.payload;
              // Without a hero, row 1's lead slot is a supporting entry too.
              final supporting = candidates.take(composition.seated).toList();
              if (spec.recipe == BentoRecipe.units) {
                return _UnitGrid(
                  spec: spec,
                  slots: composition.slots,
                  hero: hero,
                  supporting: supporting,
                  scale: scale,
                  nowMs: content.activitiesAtMs,
                  footnote: content.heroFootnote,
                );
              }
              return _CodeRows(
                rows: composition.rows,
                hero: hero,
                supporting: supporting,
                scale: scale,
                nowMs: content.activitiesAtMs,
                footnote: content.heroFootnote,
              );
            },
          ),
        ),
      ],
    );
  }
}

/// One bento card, on the row resize's card layer (its entry's id, its kind).
Widget _activityCard(BuildContext context, SlotKind kind, HomeActivityEntry entry, int nowMs, HeroFootnote? footnote) {
  final ago = HomeStrings.of(context).timeAgo(timeAgoOf(nowMs: nowMs, timestampMs: entry.timestampMs));
  return HomeRowsCard(
    cardKey: entry.stableId,
    kind: kind,
    child: switch (kind) {
      SlotKind.hero => ActivityHeroCard(entry: entry, timeAgo: ago, footnote: footnote),
      SlotKind.wide => ActivityWideCard(entry: entry, timeAgo: ago),
      SlotKind.small => ActivitySmallCard(entry: entry, timeAgo: ago),
      SlotKind.strip => ActivityStripCard(entry: entry, timeAgo: ago),
    },
  );
}

/// The code-built bento (feed units ≤ 2 and the landscape handset) from its
/// [rows] (activityCodeRows): phone — a full-width hero, a small square + wide
/// row, one strip; narrow phone — two equal smalls per row; landscape — one
/// row. At L each is today's composition. Rows 10 apart; slots past the
/// supply give their weight to the row's last card (W8), a small that grows
/// reading as a wide.
class _CodeRows extends StatelessWidget {
  const _CodeRows({
    required this.rows,
    required this.hero,
    required this.supporting,
    required this.scale,
    required this.nowMs,
    required this.footnote,
  });

  final List<ActivityCodeRow> rows;
  final HomeActivityEntry? hero;
  final List<HomeActivityEntry> supporting;
  final double scale;
  final int nowMs;
  final HeroFootnote? footnote;

  HomeActivityEntry? _entryOf(ActivityCodeSlot slot) =>
      slot.entryIndex < 0 ? hero : (slot.entryIndex < supporting.length ? supporting[slot.entryIndex] : null);

  @override
  Widget build(BuildContext context) {
    final children = <Widget>[];
    for (final row in rows) {
      switch (row.shape) {
        case ActivityRowShape.hero || ActivityRowShape.strip:
          final slot = row.slots.first;
          final entry = _entryOf(slot);
          if (entry == null) continue;
          final card = _activityCard(context, slot.kind, entry, nowMs, footnote);
          children.add(KeyedSubtree(
            key: ValueKey(entry.stableId),
            child: row.shape == ActivityRowShape.hero ? SizedBox(height: HomeBentoMetrics.heroHeight * scale, child: card) : card,
          ));
        case ActivityRowShape.cards:
          final shown = [
            for (final slot in row.slots)
              if (_entryOf(slot) case final entry?) (slot, entry),
          ];
          if (shown.isEmpty) continue;
          final spare = row.slots.fold<double>(0, (n, s) => n + s.weight) - shown.fold<double>(0, (n, p) => n + p.$1.weight);
          children.add(SizedBox(
            height: row.height * scale,
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < shown.length; i++) ...[
                  if (i > 0) SizedBox(width: row.gap),
                  () {
                    final (slot, entry) = shown[i];
                    final stretched = i == shown.length - 1 && spare > 0;
                    final kind = stretched && slot.kind == SlotKind.small ? SlotKind.wide : slot.kind;
                    final weight = stretched ? slot.weight + spare : slot.weight;
                    return Expanded(
                      key: ValueKey(entry.stableId),
                      flex: (weight * 10).round(),
                      child: _activityCard(context, kind, entry, nowMs, footnote),
                    );
                  }(),
                ],
              ],
            ),
          ));
      }
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var i = 0; i < children.length; i++) ...[
          if (i > 0) const SizedBox(height: HomeTokens.activityRowGap),
          children[i],
        ],
      ],
    );
  }
}

/// The bento on true feed units (N ≥ 3, ActivityUnitGrid): each card on the
/// unit grid — a small on one unit, a wide or the hero on two or three — at
/// fixed row heights (row 1 [ActivityBentoSpec.row1Height], the others 118,
/// × font scale); strips share the last row in equal shares on the same
/// snapped lines. Widths come from the live width.
class _UnitGrid extends StatelessWidget {
  const _UnitGrid({
    required this.spec,
    required this.slots,
    required this.hero,
    required this.supporting,
    required this.scale,
    required this.nowMs,
    required this.footnote,
  });

  final ActivityBentoSpec spec;
  final List<ActivitySlot> slots;
  final HomeActivityEntry? hero;
  final List<HomeActivityEntry> supporting;
  final double scale;
  final int nowMs;
  final HeroFootnote? footnote;

  @override
  Widget build(BuildContext context) {
    const gap = HomeTokens.activityRowGap;
    final placed = <(ActivitySlot, HomeActivityEntry)>[
      for (final slot in slots)
        if ((slot.kind == SlotKind.hero ? hero : (slot.entryIndex < supporting.length ? supporting[slot.entryIndex] : null))
            case final entry?)
          (slot, entry),
    ];
    final rowIndices = {for (final p in placed) p.$1.row}.toList()..sort();
    final rows = [for (final r in rowIndices) placed.where((p) => p.$1.row == r).toList()];
    Widget card(ActivitySlot slot, HomeActivityEntry entry) =>
        KeyedSubtree(key: ValueKey(entry.stableId), child: _activityCard(context, slot.kind, entry, nowMs, footnote));

    return LayoutBuilder(builder: (context, constraints) {
      final width = constraints.maxWidth;
      // Column pitch (col + gap) and a cell's snapped left / width (JbiCellPlacement).
      double pitchOf(int columns) => (width - gap * (columns - 1)) / columns + gap;
      final unitPitch = pitchOf(spec.units);
      final stripPitch = pitchOf(spec.strips < 1 ? 1 : spec.strips);
      double left(ActivitySlot s, double pitch) => s.startUnit * pitch;
      double cellWidth(ActivitySlot s, double pitch) => (s.startUnit + s.span) * pitch - gap - left(s, pitch);
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var i = 0; i < rowIndices.length; i++) ...[
            if (i > 0) const SizedBox(height: gap),
            if (rows[i].first.$1.kind == SlotKind.strip)
              Row(
                children: [
                  for (final (slot, entry) in rows[i])
                    Padding(
                      padding: EdgeInsetsDirectional.only(
                        start: identical(slot, rows[i].first.$1) ? left(slot, stripPitch) : gap,
                      ),
                      child: SizedBox(width: cellWidth(slot, stripPitch), child: card(slot, entry)),
                    ),
                ],
              )
            else
              SizedBox(
                height: (rowIndices[i] == 0 ? spec.row1Height : HomeTokens.activityRowHeight) * scale,
                child: Stack(
                  children: [
                    for (final (slot, entry) in rows[i])
                      PositionedDirectional(
                        key: ValueKey(entry.stableId),
                        start: left(slot, unitPitch),
                        top: 0,
                        bottom: 0,
                        width: cellWidth(slot, unitPitch),
                        child: card(slot, entry),
                      ),
                  ],
                ),
              ),
          ],
        ],
      );
    });
  }
}

String _subtitleOf(HomeActivityEntry e, HomeStrings strings) =>
    e.subtitle ?? (e.entityType == ActivityEntityType.artist ? strings.activityFallbackArtist : strings.activityFallbackRecent);

String _typeLabel(HomeActivityEntry e, HomeStrings strings) => switch (e.entityType) {
      ActivityEntityType.album => strings.activityTypeAlbum,
      ActivityEntityType.artist => strings.activityTypeArtist,
      ActivityEntityType.playlist => strings.activityTypePlaylist,
      ActivityEntityType.song => strings.activityTypeTrack,
    };

/// A bento card: continuous 16 corners, no elevation, the container washed
/// 30 % toward the card's own cover base, artists included (all-or-none, and
/// all won); only the artist's artwork slot is a bare circle.
/// Press 0.97, no ripple.
class _BentoCard extends ConsumerWidget {
  const _BentoCard({required this.entry, required this.artworkSide, required this.child, this.shape});

  final HomeActivityEntry entry;
  final double artworkSide;
  final Widget child;
  final ShapeBorder? shape;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = Theme.of(context).colorScheme;
    final low = colors.surfaceContainerLow;
    final shapeBorder = shape ?? RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card));
    final image = resolveHomeCover(ref, context, entry.cover,
        requestSize: HomeCoverRequest.standard, side: artworkSide * HomeBackdropMetrics.artworkFraction);
    return HomeEditCard(
      kickKey: entry.stableId,
      child: ElasticPress(
        onTap: () => openHomeTarget(context, ref, entry.target),
        child: BackdropColorBuilder(
          cacheKey: image?.url,
          image: image?.image,
          fallback: colors.secondaryContainer,
          child: child,
          // The tinted card and its cover break up as one print near the bar; the text on it stays whole.
          builder: (context, base, child) => SeamSurface(
            color: Color.lerp(low, base, HomeTokens.cardWashFraction)!,
            shape: shapeBorder,
            clipBehavior: Clip.antiAlias,
            child: child!,
          ),
        ),
      ),
    );
  }
}

TextStyle _muted(BuildContext context, TextStyle? style) => style!.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);

TextStyle _strong(BuildContext context, TextStyle? style) =>
    style!.copyWith(fontWeight: FontWeight.w600, color: Theme.of(context).colorScheme.onSurface);

/// Hero: 96 backdrop cover; time-ago, the title (marquee, version tag), the
/// subtitle and the album footnote (year · songs · minutes) growing in.
class ActivityHeroCard extends StatelessWidget {
  const ActivityHeroCard({super.key, required this.entry, required this.timeAgo, this.footnote});

  final HomeActivityEntry entry;
  final String timeAgo;
  final HeroFootnote? footnote;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    final isAlbum = entry.entityType == ActivityEntityType.album;
    final note = isAlbum ? footnote : null;
    return _BentoCard(
      entry: entry,
      artworkSide: HomeBentoMetrics.heroCover,
      child: Padding(
        padding: const EdgeInsets.all(HomeBentoMetrics.heroPadding),
        child: Row(
          children: [
            BackdropCover(cover: entry.cover, kind: entry.kind, size: HomeBentoMetrics.heroCover),
            const SizedBox(width: HomeBentoMetrics.heroGap),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: HomeBentoMetrics.heroColumnSpacing,
                children: [
                  SeamText(timeAgo, style: _muted(context, text.labelSmall)),
                  VersionTitle(title: entry.title, version: note?.version, style: _strong(context, text.titleLarge), marquee: true),
                  SeamText(_subtitleOf(entry, strings), style: _muted(context, text.bodySmall)),
                  _FootnoteSlot(footnote: note),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The footnote line expands in (spatial, from the top) with a fade (effects)
/// when it arrives after the card, and shrinks out showing the last one.
class _FootnoteSlot extends StatefulWidget {
  const _FootnoteSlot({required this.footnote});
  final HeroFootnote? footnote;

  @override
  State<_FootnoteSlot> createState() => _FootnoteSlotState();
}

class _FootnoteSlotState extends State<_FootnoteSlot> with TickerProviderStateMixin {
  late HeroFootnote? _shown = widget.footnote;
  late final SpringValue _size = SpringValue(vsync: this, initial: widget.footnote == null ? 0 : 1, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: widget.footnote == null ? 0 : 1, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _size;
    _alpha;
  }

  @override
  void didUpdateWidget(_FootnoteSlot old) {
    super.didUpdateWidget(old);
    final next = widget.footnote;
    if (next != null) _shown = next;
    final target = next == null ? 0.0 : 1.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _size.snapTo(target);
      _alpha.snapTo(target);
    } else {
      _size.animateTo(target);
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shown = _shown;
    if (shown == null) return const SizedBox.shrink();
    final strings = HomeStrings.of(context);
    final style = _muted(context, Theme.of(context).textTheme.labelSmall);
    final line = SeamFade(
      fontSize: style.fontSize ?? 11,
      child: MetaLine(style: style, groups: [
        if (shown.year case final y?) MetaPlain('$y'),
        if (shown.songCount case final n?) MetaStat('$n', strings.songsUnit(n)),
        if (shown.minutes case final m?) MetaStat('$m', strings.minutesUnit(m)),
      ]),
    );
    return AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _alpha.animation]),
      child: line,
      builder: (context, child) => ClipRect(
        child: Align(
          alignment: Alignment.topLeft,
          heightFactor: _size.value.clamp(0.0, 1.0),
          child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        ),
      ),
    );
  }
}

/// Small: 48 artwork with type and time beside it; the title (2 lines) at the bottom.
class ActivitySmallCard extends StatelessWidget {
  const ActivitySmallCard({super.key, required this.entry, required this.timeAgo});

  final HomeActivityEntry entry;
  final String timeAgo;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    final titleStyle = _strong(context, text.titleSmall).copyWith(height: HomeBentoMetrics.smallTitleLineHeight / text.titleSmall!.fontSize!);
    return _BentoCard(
      entry: entry,
      artworkSide: HomeBentoMetrics.smallCover,
      child: Padding(
        padding: const EdgeInsets.all(HomeBentoMetrics.smallPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                BackdropCover(cover: entry.cover, kind: entry.kind, size: HomeBentoMetrics.smallCover),
                const SizedBox(width: HomeBentoMetrics.smallGap),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SeamText(_typeLabel(entry, strings), style: _muted(context, text.labelSmall)),
                      SeamText(timeAgo, style: _muted(context, text.labelSmall)),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: HomeBentoMetrics.smallSpacing),
            const Spacer(),
            SeamText(entry.title, style: titleStyle, maxLines: 2),
          ],
        ),
      ),
    );
  }
}

/// Wide: 80 artwork, then time-ago, the title (marquee) and the subtitle.
class ActivityWideCard extends StatelessWidget {
  const ActivityWideCard({super.key, required this.entry, required this.timeAgo});

  final HomeActivityEntry entry;
  final String timeAgo;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    return _BentoCard(
      entry: entry,
      artworkSide: HomeBentoMetrics.wideCover,
      child: Padding(
        padding: const EdgeInsets.all(HomeBentoMetrics.widePadding),
        child: Row(
          children: [
            BackdropCover(cover: entry.cover, kind: entry.kind, size: HomeBentoMetrics.wideCover),
            const SizedBox(width: HomeBentoMetrics.wideGap),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: HomeBentoMetrics.wideColumnSpacing,
                children: [
                  SeamText(timeAgo, style: _muted(context, text.labelSmall)),
                  SeamFade(
                    fontSize: text.titleMedium!.fontSize!,
                    child: MarqueeText(entry.title, style: _strong(context, text.titleMedium)),
                  ),
                  SeamText(_subtitleOf(entry, strings), style: _muted(context, text.bodySmall)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Strip: a pill with the title, the subtitle at 60 % and the time-ago at the end.
class ActivityStripCard extends StatelessWidget {
  const ActivityStripCard({super.key, required this.entry, required this.timeAgo});

  final HomeActivityEntry entry;
  final String timeAgo;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    final onSurface = Theme.of(context).colorScheme.onSurface;
    final body = text.bodyMedium!;
    return _BentoCard(
      entry: entry,
      artworkSide: HomeBentoMetrics.smallCover,
      shape: const StadiumBorder(),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeBentoMetrics.stripPaddingH, vertical: HomeBentoMetrics.stripPaddingV),
        child: Row(
          children: [
            Expanded(
              child: Row(
                children: [
                  Flexible(child: SeamText(entry.title, style: body.copyWith(fontWeight: FontWeight.w600, color: onSurface))),
                  const SizedBox(width: HomeBentoMetrics.stripSubtitleGap),
                  Flexible(
                    child: SeamText(
                      _subtitleOf(entry, strings),
                      style: body.copyWith(color: onSurface.withValues(alpha: HomeBentoMetrics.stripSubtitleAlpha)),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: HomeBentoMetrics.stripGap),
            SeamText(timeAgo, style: _muted(context, text.labelSmall)),
          ],
        ),
      ),
    );
  }
}

/// ExpressiveSectionPanel holding one title: continuous 20 corners,
/// surfaceContainerLow at tonal elevation 1, padding 18.
class HomeEmptyCard extends StatelessWidget {
  const HomeEmptyCard({super.key, required this.title});
  final String title;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.titleLarge!.copyWith(color: colors.onSurface);
    return SeamSurface(
      color: ElevationOverlay.applySurfaceTint(colors.surfaceContainerLow, colors.surfaceTint, HomeBackdropMetrics.tonalElevation),
      shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
      child: Padding(
        padding: const EdgeInsets.all(HomeEmptyCardMetrics.padding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: HomeEmptyCardMetrics.spacing,
          children: [SeamText(title, style: style, maxLines: 2)],
        ),
      ),
    );
  }
}
