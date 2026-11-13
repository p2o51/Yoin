import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/tokens.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'pull_up_reveal.dart';

/// What a loaded Album / Playlist page puts in the reshape shell.
final class ReshapeContent {
  const ReshapeContent({
    required this.coverUrl,
    required this.heroBelow,
    required this.listChildren,
    this.sleeves,
    this.fallbackIcon = YoinSymbols.album,
  });

  /// The hero cover (decoded at 640 px), flying into the stamp on pull-up.
  final String? coverUrl;
  final IconData fallbackIcon;

  /// Under the hero slot: track count + flowing titles (links live while [linksEnabled]).
  final Widget Function(bool linksEnabled) heroBelow;

  /// Drawn behind the cover inside the hero slot (Playlist sleeves), given the cover rect in slot space.
  final Widget Function(Rect coverInSlot, double fade)? sleeves;

  /// The pulled-up track list.
  final List<Widget> listChildren;
}

/// The Album / Playlist page on Compact (D1 + D3): header, page wash, and —
/// once loaded — the hero ⇄ track-list reshape on one [RevealController].
/// While not loaded [stateView] (loading / error) fills the body under the
/// same header.
class ReshapeDetailScaffold extends StatefulWidget {
  const ReshapeDetailScaffold({
    super.key,
    required this.title,
    required this.meta,
    this.titleTrailing,
    this.content,
    this.stateView,
    this.headerEndPadding = DetailHeaderMetrics.paddingEndAlbum,
  });

  final String title;
  final List<MetaGroup> meta;
  final Widget? titleTrailing;
  final ReshapeContent? content;
  final Widget? stateView;
  final double headerEndPadding;

  @override
  State<ReshapeDetailScaffold> createState() => _ReshapeDetailScaffoldState();
}

class _ReshapeDetailScaffoldState extends State<ReshapeDetailScaffold> with TickerProviderStateMixin {
  late final RevealController _reveal = RevealController(vsync: this);
  late final RevealScrollPhysics _physics = RevealScrollPhysics(_reveal);

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final media = MediaQuery.of(context);
    final reduce = media.disableAnimations;
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        final h = constraints.maxHeight;
        final topInset = media.padding.top;
        final headerBottom = topInset + DetailHeaderMetrics.bandHeight;
        final side = PullUpMath.heroCoverSide(
          w,
          fraction: DetailTokens.albumCoverWidthFraction,
          max: DetailTokens.albumCoverMax,
        );
        final restCover = Rect.fromLTWH((w - side) / 2, headerBottom + DetailTokens.albumCoverTopGap, side, side);
        final stamp = Rect.fromLTWH(
          w - DetailTokens.albumStampEndInset - DetailTokens.albumStamp,
          topInset +
              DetailHeaderMetrics.paddingTop +
              (DetailHeaderMetrics.rowHeight - DetailTokens.albumStamp) / 2 -
              PullUpTokens.titleLift,
          DetailTokens.albumStamp,
          DetailTokens.albumStamp,
        );
        _reveal.travelPx = PullUpMath.travel(h);
        final content = widget.content;
        return Stack(
          children: [
            const Positioned.fill(child: DetailPageBackground()),
            // Docked: the header band fills with the accent (v1 stand-in for the spectrum bar).
            Positioned(
              left: 0,
              right: 0,
              top: 0,
              height: headerBottom,
              child: DetailColorsBuilder(
                builder: (context, page) => AnimatedBuilder(
                  animation: _reveal,
                  builder: (context, _) {
                    final t = content == null ? 0.0 : PullUpMath.textProgress(_reveal.expand);
                    return ColoredBox(color: page.accent.withValues(alpha: t));
                  },
                ),
              ),
            ),
            Positioned.fill(
              top: headerBottom,
              child: content == null
                  ? (widget.stateView ?? const SizedBox.shrink())
                  : _Body(
                      reveal: _reveal,
                      physics: _physics,
                      content: content,
                      slotHeight: side + PullUpTokens.heroBand,
                      coverInSlot: Rect.fromLTWH((w - side) / 2, DetailTokens.albumCoverTopGap, side, side),
                    ),
            ),
            Positioned(
              left: 0,
              right: 0,
              top: topInset,
              height: DetailHeaderMetrics.bandHeight,
              child: _Header(
                reveal: _reveal,
                docks: content != null,
                reduce: reduce,
                title: widget.title,
                titleTrailing: widget.titleTrailing,
                meta: widget.meta,
                endPadding: widget.headerEndPadding,
              ),
            ),
            if (content != null)
              AnimatedBuilder(
                animation: _reveal,
                builder: (context, _) {
                  final expand = _reveal.expand;
                  final rect = reduce ? restCover : PullUpMath.coverRect(restCover, stamp, expand);
                  final alpha = reduce ? PullUpMath.heroAlpha(expand) : 1.0;
                  final docked = expand >= 0.5;
                  return Positioned.fromRect(
                    rect: rect,
                    child: Opacity(
                      opacity: alpha,
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: docked ? () => _reveal.setExpanded(false) : null,
                        onVerticalDragUpdate: docked ? null : (d) => _reveal.dragBy(d.delta.dy),
                        onVerticalDragEnd: docked ? null : (d) => _reveal.release(d.velocity.pixelsPerSecond.dy),
                        child: Material(
                          type: MaterialType.transparency,
                          elevation: 0,
                          child: MediaArtwork(
                            url: content.coverUrl,
                            fallbackIcon: content.fallbackIcon,
                            radius: PullUpMath.coverCorner(expand),
                            decodeWidth: 640,
                            fallbackIconSize: lerpDouble(48, 24, expand)!,
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
          ],
        );
      },
    );
  }
}

class _Body extends StatelessWidget {
  const _Body({
    required this.reveal,
    required this.physics,
    required this.content,
    required this.slotHeight,
    required this.coverInSlot,
  });

  final RevealController reveal;
  final RevealScrollPhysics physics;
  final ReshapeContent content;
  final double slotHeight;
  final Rect coverInSlot;

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final list = ListView(
      physics: physics,
      padding: EdgeInsets.fromLTRB(16, 4, 16, 112 + bottomInset),
      children: content.listChildren,
    );
    return Stack(
      fit: StackFit.expand,
      children: [
        // The list layer: composed only once it shows, interactive from 0.5.
        AnimatedBuilder(
          animation: reveal,
          child: list,
          builder: (context, child) {
            final expand = reveal.expand;
            if (expand <= 0.001) return const SizedBox.shrink();
            return IgnorePointer(
              ignoring: expand < 0.5,
              child: Opacity(
                opacity: PullUpMath.listAlpha(expand),
                child: Transform.translate(offset: Offset(0, PullUpMath.listShift(expand)), child: child),
              ),
            );
          },
        ),
        // The hero layer: dominant until 0.5; dragging it is the pull-up.
        AnimatedBuilder(
          animation: reveal,
          child: ClipRect(
            child: OverflowBox(
              alignment: Alignment.topCenter,
              minHeight: 0,
              maxHeight: double.infinity,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(
                    height: slotHeight,
                    child: content.sleeves == null
                        ? null
                        : AnimatedBuilder(
                            animation: reveal,
                            builder: (context, _) =>
                                content.sleeves!(coverInSlot, PullUpMath.smoothStep(0, 0.7, reveal.expand)),
                          ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: _HeroBelow(reveal: reveal, builder: content.heroBelow),
                  ),
                ],
              ),
            ),
          ),
          builder: (context, child) {
            final expand = reveal.expand;
            if (expand >= 0.999) return const SizedBox.shrink();
            return IgnorePointer(
              ignoring: expand >= 0.5,
              child: GestureDetector(
                behavior: HitTestBehavior.translucent,
                onVerticalDragUpdate: reveal.expanded ? null : (d) => reveal.dragBy(d.delta.dy),
                onVerticalDragEnd: reveal.expanded ? null : (d) => reveal.release(d.velocity.pixelsPerSecond.dy),
                child: Opacity(
                  opacity: PullUpMath.heroAlpha(expand),
                  child: Transform.translate(offset: Offset(0, PullUpMath.heroShift(expand)), child: child),
                ),
              ),
            );
          },
        ),
      ],
    );
  }
}

/// Rebuilds the flowing titles only when the links switch, never per frame.
class _HeroBelow extends StatefulWidget {
  const _HeroBelow({required this.reveal, required this.builder});

  final RevealController reveal;
  final Widget Function(bool linksEnabled) builder;

  @override
  State<_HeroBelow> createState() => _HeroBelowState();
}

class _HeroBelowState extends State<_HeroBelow> {
  late bool _links = widget.reveal.expand < 0.5;

  @override
  void initState() {
    super.initState();
    widget.reveal.addListener(_sync);
  }

  @override
  void dispose() {
    widget.reveal.removeListener(_sync);
    super.dispose();
  }

  void _sync() {
    final links = widget.reveal.expand < 0.5;
    if (links != _links) setState(() => _links = links);
  }

  @override
  Widget build(BuildContext context) => widget.builder(_links);
}

class _Header extends StatelessWidget {
  const _Header({
    required this.reveal,
    required this.docks,
    required this.reduce,
    required this.title,
    required this.titleTrailing,
    required this.meta,
    required this.endPadding,
  });

  final RevealController reveal;
  final bool docks;
  final bool reduce;
  final String title;
  final Widget? titleTrailing;
  final List<MetaGroup> meta;
  final double endPadding;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DetailColorsBuilder(
      builder: (context, page) => AnimatedBuilder(
        animation: reveal,
        builder: (context, _) {
          final expand = docks ? reveal.expand : 0.0;
          final p = reduce ? 0.0 : PullUpMath.smoothStep(0, 1, expand);
          final ink = PullUpMath.textProgress(expand);
          final titleColor = Color.lerp(page.accent, page.onAccent, ink)!;
          final metaColor = Color.lerp(colors.onSurfaceVariant, page.onAccent, ink)!;
          return Padding(
            padding: EdgeInsetsDirectional.only(
              start: DetailHeaderMetrics.paddingStart,
              end: endPadding,
              top: DetailHeaderMetrics.paddingTop,
              bottom: DetailHeaderMetrics.paddingBottom,
            ),
            child: Row(
              children: [
                DetailBackButton(
                  container: Color.lerp(colors.surfaceContainer, page.onAccent.withValues(alpha: 0.2), ink),
                  content: Color.lerp(colors.onSurface, page.onAccent, ink),
                ),
                const SizedBox(width: DetailHeaderMetrics.titleGap - 4),
                Expanded(
                  child: Transform.translate(
                    offset: Offset(0, -PullUpTokens.titleLift * p),
                    child: Transform.scale(
                      alignment: AlignmentDirectional.topStart.resolve(Directionality.of(context)),
                      scale: lerpDouble(1, PullUpTokens.titleScale, p)!,
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          DetailTitle(title, trailing: titleTrailing, color: titleColor),
                          if (meta.isNotEmpty)
                            MetaLine(
                              groups: [
                                for (final g in meta)
                                  if (g case MetaKind(:final text, color: null))
                                    MetaKind(text, color: Color.lerp(page.accent, page.onAccent, ink))
                                  else
                                    g,
                              ],
                              style: Theme.of(context).textTheme.bodyMedium,
                              color: metaColor,
                            ),
                        ],
                      ),
                    ),
                  ),
                ),
                SizedBox(width: (DetailTokens.albumStamp + DetailTokens.albumBarTitleGap) * (docks ? p : 0)),
              ],
            ),
          );
        },
      ),
    );
  }
}
