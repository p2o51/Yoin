/// Home's Lyric Cards (Android ui/home/HomeLyricHighlight.kt, owner
/// 2026-11-08, design C): lines the user picked, each on its song's cover
/// wash, two to four cards that swipe sideways with page dots under them. A
/// tap plays the song from that line. No title on Home (owner 2026-11-09):
/// the cards say what they are; the name stays for Edit Home. The first time,
/// the section is the intro card ([LyricHighlightIntroCard]) instead.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/highlight/lyric_highlight.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'home_edit.dart';
import 'home_edit_wiggle.dart';
import 'home_metrics.dart';
import 'home_selection.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'lyric_highlight_view_model.dart';
import 'widgets/cover_palette.dart';
import 'widgets/elastic_press.dart';
import 'widgets/home_artwork.dart';

/// A card's width (LyricPageSize): the whole content width for one card,
/// else [HomeLyricCardTokens.width] leaving the next one peeking.
double lyricCardWidth(double content, {required bool single}) {
  if (content <= 0) return 1;
  final width = single ? content : math.min(HomeLyricCardTokens.width, content - HomeLyricCardTokens.peek);
  return math.max(1, width);
}

/// The page the dots mark (LyricHighlightSection): the last card counts once
/// the shelf has run out of room to scroll, otherwise the card nearest the
/// shelf's start.
int lyricShelfPage({required double pixels, required double maxScroll, required double pitch, required int count}) {
  if (count <= 0) return 0;
  if (pixels >= maxScroll - 0.5) return count - 1;
  if (pitch <= 0) return 0;
  return (pixels / pitch).round().clamp(0, count - 1);
}

/// The section: [count] of [cards] on a shelf that swipes sideways, the page
/// dots under it (none for a single card, which fills the width). [frame]
/// is the feed's, for a shelf that bleeds to the screen's edges; null (the
/// Tips demo) keeps it in the column. [controller] lets the demo drive it.
class LyricHighlightSection extends StatefulWidget {
  const LyricHighlightSection({
    super.key,
    required this.cards,
    required this.count,
    required this.frame,
    required this.onPlay,
    this.controller,
    this.scrollEnabled = true,
  });

  final List<HomeLyricHighlight> cards;
  final int count;
  final HomeFeedFrame? frame;
  final ValueChanged<HomeLyricHighlight> onPlay;
  final ScrollController? controller;
  final bool scrollEnabled;

  @override
  State<LyricHighlightSection> createState() => _LyricHighlightSectionState();
}

class _LyricHighlightSectionState extends State<LyricHighlightSection> {
  ScrollController? _own;
  ScrollController get _scroll => widget.controller ?? (_own ??= ScrollController());
  final ValueNotifier<int> _page = ValueNotifier(0);
  double _pitch = 1;
  int _shown = 0;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void didUpdateWidget(LyricHighlightSection oldWidget) {
    super.didUpdateWidget(oldWidget);
    final previous = oldWidget.controller ?? _own;
    if (!identical(previous, _scroll)) {
      previous?.removeListener(_onScroll);
      _scroll.addListener(_onScroll);
    }
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _own?.dispose();
    _page.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final p = _scroll.position;
    if (!p.hasContentDimensions) return;
    _page.value = lyricShelfPage(pixels: p.pixels, maxScroll: p.maxScrollExtent, pitch: _pitch, count: _shown);
  }

  @override
  Widget build(BuildContext context) {
    final shown = widget.cards.take(widget.count.clamp(1, lyricHighlightMaxCards)).toList();
    _shown = shown.length;
    final single = shown.length <= 1;
    final frame = widget.frame;
    return LayoutBuilder(
      builder: (context, constraints) {
        // The content's width either way (the shelf itself may bleed past it).
        final content = frame?.content ?? constraints.maxWidth;
        final width = lyricCardWidth(content, single: single);
        _pitch = width + HomeLyricCardTokens.gap;
        final padding = EdgeInsetsDirectional.only(start: frame?.start ?? 0, end: frame?.end ?? 0);
        final shelf = SingleChildScrollView(
          controller: _scroll,
          scrollDirection: Axis.horizontal,
          padding: padding,
          physics: widget.scrollEnabled && !single
              ? LyricShelfPhysics(pitch: _pitch)
              : const NeverScrollableScrollPhysics(),
          clipBehavior: Clip.none,
          // Every card stays built (four at most), as Android's pager keeps them composed.
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final (i, card) in shown.indexed) ...[
                if (i > 0) const SizedBox(width: HomeLyricCardTokens.gap),
                SizedBox(
                  key: ValueKey(card.songId),
                  width: width,
                  height: HomeLyricCardTokens.height,
                  child: HomeEditCard(
                    index: i,
                    kickKey: card.songId,
                    child: LyricHighlightCard(card: card, onPlay: () => widget.onPlay(card)),
                  ),
                ),
              ],
            ],
          ),
        );
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _onScroll();
        });
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (frame == null)
              shelf
            else
              // Edit mode: the bleeding shelf is cut back to its plate.
              HomeEditShelfClip(escapeStart: frame.start, escapeEnd: frame.end, child: shelf),
            if (!single) ...[
              const SizedBox(height: HomeLyricCardTokens.sectionSpacing),
              Center(
                child: ValueListenableBuilder<int>(
                  valueListenable: _page,
                  builder: (context, page, _) => LyricPageDots(count: shown.length, current: page),
                ),
              ),
            ],
          ],
        );
      },
    );
  }
}

/// The shelf's paging (Android's pager snapping): a release settles on the
/// nearest card's start (a fling one card on), the last stops where the
/// shelf runs out of room, on the spatial spring.
class LyricShelfPhysics extends ScrollPhysics {
  const LyricShelfPhysics({required this.pitch, super.parent});

  final double pitch;

  @override
  LyricShelfPhysics applyTo(ScrollPhysics? ancestor) => LyricShelfPhysics(pitch: pitch, parent: buildParent(ancestor));

  @override
  SpringDescription get spring => YoinSprings.defaultSpatial;

  /// Where a release at [pixels] with [velocity] settles.
  static double target({
    required double pixels,
    required double velocity,
    required double pitch,
    required double min,
    required double max,
    double minVelocity = 0,
  }) {
    var page = pixels / pitch;
    if (velocity < -minVelocity) {
      page -= 0.5;
    } else if (velocity > minVelocity) {
      page += 0.5;
    }
    return (page.roundToDouble() * pitch).clamp(min, max);
  }

  @override
  Simulation? createBallisticSimulation(ScrollMetrics position, double velocity) {
    if ((velocity <= 0 && position.pixels <= position.minScrollExtent) ||
        (velocity >= 0 && position.pixels >= position.maxScrollExtent)) {
      return super.createBallisticSimulation(position, velocity);
    }
    final tolerance = toleranceFor(position);
    final to = target(
      pixels: position.pixels,
      velocity: velocity,
      pitch: pitch,
      min: position.minScrollExtent,
      max: position.maxScrollExtent,
      minVelocity: tolerance.velocity,
    );
    if (to == position.pixels) return null;
    return ScrollSpringSimulation(spring, position.pixels, to, velocity, tolerance: tolerance);
  }

  @override
  bool get allowImplicitScrolling => false;
}

/// The page dots (LyricPageDots): the current card a short pill, the others
/// dots, the width on the spatial spring and the colour on the effects one.
class LyricPageDots extends StatelessWidget {
  const LyricPageDots({super.key, required this.count, required this.current});

  final int count;
  final int current;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      for (var i = 0; i < count; i++) ...[
        if (i > 0) const SizedBox(width: HomeLyricCardTokens.dotGap),
        _LyricDot(active: i == current),
      ],
    ],
  );
}

class _LyricDot extends StatefulWidget {
  const _LyricDot({required this.active});

  final bool active;

  @override
  State<_LyricDot> createState() => _LyricDotState();
}

class _LyricDotState extends State<_LyricDot> with TickerProviderStateMixin {
  late final SpringValue _width = SpringValue(
    vsync: this,
    initial: widget.active ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _tone = SpringValue(
    vsync: this,
    initial: widget.active ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _width;
    _tone;
  }

  @override
  void didUpdateWidget(_LyricDot oldWidget) {
    super.didUpdateWidget(oldWidget);
    final target = widget.active ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _width.snapTo(target);
      _tone.snapTo(target);
    } else {
      _width.animateTo(target);
      _tone.animateTo(target);
    }
  }

  @override
  void dispose() {
    _width.dispose();
    _tone.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return AnimatedBuilder(
      animation: Listenable.merge([_width.animation, _tone.animation]),
      builder: (context, _) => Container(
        width: HomeLyricCardTokens.dot + (HomeLyricCardTokens.dotActive - HomeLyricCardTokens.dot) * _width.value,
        height: HomeLyricCardTokens.dot,
        decoration: ShapeDecoration(
          color: Color.lerp(colors.outlineVariant, colors.onSurfaceVariant, _tone.value.clamp(0.0, 1.0)),
          shape: const StadiumBorder(),
        ),
      ),
    );
  }
}

/// One card on its song's cover wash (LyricHighlightCard): the song beside
/// its cover, title over artist; the passage; how the song came up in the
/// bottom corner. No seam print (Android: in a swiping shelf it kept a
/// recycled neighbour's text over the peeking card).
class LyricHighlightCard extends ConsumerWidget {
  const LyricHighlightCard({super.key, required this.card, required this.onPlay});

  final HomeLyricHighlight card;
  final VoidCallback onPlay;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final strings = HomeStrings.of(context);
    final dark = theme.brightness == Brightness.dark;
    final cover = card.cover;
    final image = resolveHomeCover(
      ref,
      context,
      cover,
      requestSize: HomeCoverRequest.standard,
      side: HomeLyricCardTokens.art * HomeBackdropMetrics.artworkFraction,
    );
    final kind = switch (card.kind) {
      LyricHighlightBucket.heavy => strings.lyricKindHeavy,
      LyricHighlightBucket.rediscover => strings.lyricKindRediscover,
      LyricHighlightBucket.user => strings.lyricKindUser,
      LyricHighlightBucket.fresh || null => null,
    };
    return Semantics(
      onTapHint: strings.lyricPlayFromLine,
      child: ElasticPress(
        onTap: onPlay,
        child: BackdropColorBuilder(
          cacheKey: image?.url,
          image: image?.image,
          fallback: colors.secondaryContainer,
          builder: (context, base, child) => DecoratedBox(
            decoration: ShapeDecoration(
              color: Color.lerp(colors.surfaceContainerLow, base, HomeTokens.cardWashFraction),
              shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
            ),
            child: child,
          ),
          child: BackdropColorBuilder(
            cacheKey: image?.url,
            image: image?.image,
            fallback: dark ? colors.tertiaryContainer : colors.secondaryContainer,
            accent: dark,
            builder: (context, ink, _) => Padding(
              padding: const EdgeInsets.all(HomeLyricCardTokens.padding),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // The song beside its cover, title over artist (owner 2026-11-08).
                  Row(
                    children: [
                      BackdropCover(cover: cover, kind: HomeCardKind.song, size: HomeLyricCardTokens.art),
                      const SizedBox(width: HomeLyricCardTokens.headGap),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              card.title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.labelLarge!.copyWith(color: colors.onSurface),
                            ),
                            if (card.artist.trim().isNotEmpty)
                              Text(
                                card.artist,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: HomeLyricCardTokens.cardSpacing),
                  // The song's own words, so the app's sans, not the serif (owner 2026-11-08).
                  Expanded(
                    child: Text(
                      card.text,
                      maxLines: HomeLyricCardTokens.textMaxLines,
                      overflow: TextOverflow.ellipsis,
                      style: gsf(
                        HomeLyricCardTokens.textSize,
                        weight: 500,
                        height: HomeLyricCardTokens.textLineHeight / HomeLyricCardTokens.textSize,
                      ).copyWith(color: colors.onSurface),
                    ),
                  ),
                  // How the song came up, bottom start; nothing for a song not heard yet.
                  if (kind != null) ...[
                    const SizedBox(height: HomeLyricCardTokens.cardSpacing),
                    Text(kind, maxLines: 1, style: text.labelMedium!.copyWith(color: ink)),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The section's first-use intro (LyricHighlightIntroCard, owner
/// 2026-11-08): a beta found, the lines the user shares or saves show here,
/// and a tap plays from one (HarmonyOS copy: Android's says the phone can't
/// run Gemini Nano). "Got it" puts it away for good.
class LyricHighlightIntroCard extends StatelessWidget {
  const LyricHighlightIntroCard({super.key, required this.intro, required this.onDismiss});

  final LyricHighlightIntro intro;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    final body = switch (intro) {
      LyricHighlightIntro.unsupported => [strings.lyricIntroPicks, strings.lyricIntroPlay],
    };
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerHigh,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(HomeLyricCardTokens.introPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(strings.lyricIntroBadge, style: text.labelMedium!.copyWith(color: colors.primary)),
            const SizedBox(height: HomeLyricCardTokens.introSpacing),
            Text(strings.lyricIntroTitle, style: text.titleMedium!.copyWith(color: colors.onSurface)),
            for (final paragraph in body) ...[
              const SizedBox(height: HomeLyricCardTokens.introSpacing),
              Text(paragraph, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
            ],
            const SizedBox(height: HomeLyricCardTokens.introSpacing),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                switch (intro) {
                  LyricHighlightIntro.unsupported => FilledButton(
                    onPressed: onDismiss,
                    child: Text(strings.lyricIntroGotIt),
                  ),
                },
              ],
            ),
          ],
        ),
      ),
    );
  }
}
