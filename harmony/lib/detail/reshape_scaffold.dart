import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart' show Drag;
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/services.dart' show SystemUiOverlayStyle;
import 'package:yoin_symbols/yoin_symbols.dart';

import '../theme/motion.dart';
import '../theme/system_bars.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import 'album_scrapbook_page.dart' show AlbumPageDots, AlbumPageDotsTokens;
import 'album_spectrum_layer.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'pull_up_reveal.dart';

/// What a loaded Album / Playlist page puts in the reshape shell.
final class ReshapeContent {
  const ReshapeContent({
    required this.coverUrl,
    required this.heroBelow,
    required this.listChildren,
    this.backdrop,
    this.fallbackIcon = YoinSymbols.album,
    this.secondPage,
  });

  /// The hero cover (decoded at 640 px), flying into the stamp on pull-up.
  final String? coverUrl;
  final IconData fallbackIcon;

  /// Under the hero slot: track count + flowing titles (links live while [linksEnabled]).
  final Widget Function(bool linksEnabled) heroBelow;

  /// Drawn behind the cover inside the hero slot (Album butterfly, Playlist sleeves), given the cover
  /// rect in slot space and how far it has left with the pull-up (0 = whole, 1 = gone).
  final Widget Function(Rect coverInSlot, double leaving)? backdrop;

  /// The pulled-up track list.
  final List<Widget> listChildren;

  /// Page 2 (the Album's scrapbook), beside the reshape in a horizontal
  /// pager with page dots under the header; given the pager position
  /// (0 = overview, 1 = page 2). Page 2 is an in-page state, not a place:
  /// back leaves the page from either page, so no back handler is added.
  final Widget Function(BuildContext context, ValueListenable<double> page)? secondPage;
}

/// The reshape's pager, for a page-1 control that opens page 2 (Last Play).
class ReshapePager extends InheritedWidget {
  const ReshapePager({super.key, required this.openPage, required super.child});

  final ValueChanged<int> openPage;

  static ReshapePager? maybeOf(BuildContext context) => context.dependOnInheritedWidgetOfExactType<ReshapePager>();

  /// [maybeOf] from a callback (a tap, a sheet's row): no dependency.
  static ReshapePager? read(BuildContext context) => context.getInheritedWidgetOfExactType<ReshapePager>();

  @override
  bool updateShouldNotify(ReshapePager old) => old.openPage != openPage;
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
  final _pager = PageController();

  /// The pager position, 0 = overview, 1 = page 2.
  final _page = ValueNotifier<double>(0);

  /// A dot tap's (or Last Play's) page change, on the spatial spring.
  late final AnimationController _pageSpring = AnimationController.unbounded(vsync: this)
    ..addListener(() {
      if (_pager.hasClients) _pager.position.jumpTo(_pageSpring.value * _pager.position.viewportDimension);
    });

  @override
  void initState() {
    super.initState();
    _pager.addListener(() {
      if (_pager.hasClients && _pager.position.haveDimensions) _page.value = _pager.page ?? 0;
    });
  }

  @override
  void dispose() {
    _reveal.dispose();
    _pageSpring.dispose();
    _pager.dispose();
    _page.dispose();
    super.dispose();
  }

  Drag? _pageDrag;

  void _pageDragStart(DragStartDetails details) {
    if (!_pager.hasClients) return;
    _pageSpring.stop();
    _pageDrag = _pager.position.drag(details, () => _pageDrag = null);
  }

  void _openPage(int page) {
    if (!_pager.hasClients) return;
    if (MediaQuery.disableAnimationsOf(context)) {
      _pager.jumpToPage(page);
      return;
    }
    _pageSpring
      ..value = _page.value
      ..animateWith(SpringSimulation(YoinSprings.defaultSpatial, _page.value, page.toDouble(), 0));
  }

  @override
  Widget build(BuildContext context) {
    // A landscape handset: the page starts past the edge capsules, its wash under the band (detailChromeBand).
    return ShellChromeBand(
      background: const DetailPageBackground(),
      child: Builder(builder: _buildPage),
    );
  }

  Widget _buildPage(BuildContext context) {
    final media = MediaQuery.of(context);
    final reduce = media.disableAnimations;
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        final h = constraints.maxHeight;
        final topInset = media.padding.top;
        final content = widget.content;
        final paged = content?.secondPage != null;
        // Two pages: the dots take a row of their own under the header's meta (Compact).
        final headerBottom = topInset + DetailHeaderMetrics.bandHeight + (paged ? AlbumPageDotsTokens.cellHeight : 0);
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
        final body = content == null
            ? null
            : _Body(
                reveal: _reveal,
                physics: _physics,
                content: content,
                slotHeight: side + PullUpTokens.heroBand,
                coverInSlot: Rect.fromLTWH((w - side) / 2, DetailTokens.albumCoverTopGap, side, side),
              );
        // Page 0's flying pieces (the spectrum, the cover) go with page 0 as the pager drags.
        double shift() => paged ? -_page.value * w : 0;
        return _BarStatusIcons(
          reveal: _reveal,
          page: _page,
          enabled: content != null,
          child: Stack(
            children: [
              const Positioned.fill(child: DetailPageBackground()),
              // The cover → spectrum bar (AlbumSpectrumBar.kt): as the cover leaves, a copy of it breaks
              // into strips that sort themselves into the cover's tone-locked bands, filling the header.
              if (content != null)
                Positioned.fill(
                  child: AnimatedBuilder(
                    animation: _page,
                    builder: (context, child) => Transform.translate(offset: Offset(shift(), 0), child: child),
                    child: AlbumSpectrumLayer(
                      reveal: _reveal,
                      coverUrl: content.coverUrl,
                      cover: restCover,
                      bar: Rect.fromLTWH(0, 0, w, headerBottom),
                      reduce: reduce,
                    ),
                  ),
                ),
              Positioned.fill(
                top: headerBottom,
                child: body == null
                    ? (widget.stateView ?? const SizedBox.shrink())
                    : !paged
                    ? body
                    : ReshapePager(
                        openPage: _openPage,
                        child: Listener(
                          // A finger on the pager takes over from a dot's spring.
                          onPointerDown: (_) => _pageSpring.stop(),
                          child: PageView(
                            controller: _pager,
                            children: [
                              body,
                              Builder(builder: (context) => content!.secondPage!(context, _page)),
                            ],
                          ),
                        ),
                      ),
              ),
              Positioned(
                left: 0,
                right: 0,
                top: topInset,
                height: DetailHeaderMetrics.bandHeight,
                child: _Header(
                  reveal: _reveal,
                  page: _page,
                  docks: content != null,
                  reduce: reduce,
                  title: widget.title,
                  titleTrailing: widget.titleTrailing,
                  meta: widget.meta,
                  endPadding: widget.headerEndPadding,
                ),
              ),
              if (paged)
                Positioned(
                  top: topInset + DetailHeaderMetrics.bandHeight,
                  height: AlbumPageDotsTokens.cellHeight,
                  left: 0,
                  right: 0,
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Padding(
                      // Under the title column: past the back button and its gap (less the cell's own inset).
                      padding: const EdgeInsetsDirectional.only(
                        start:
                            DetailHeaderMetrics.paddingStart +
                            48 +
                            DetailHeaderMetrics.titleGap -
                            4 -
                            (AlbumPageDotsTokens.cellWidth / 2 - AlbumPageDotsTokens.dot),
                      ),
                      child: DetailColorsBuilder(
                        builder: (context, colors) => AnimatedBuilder(
                          animation: Listenable.merge([_reveal, _page]),
                          builder: (context, _) {
                            final ink = PullUpMath.textProgress(_reveal.expand) * (1 - _page.value).clamp(0.0, 1.0);
                            // One palette, the cover scheme's (AlbumPageDots): its secondary for the page
                            // shown, its onSurfaceVariant at 0.3 for the other; white twins over the bar.
                            return AlbumPageDots(
                              page: _page,
                              active: Color.lerp(colors.secondary, Colors.white, ink)!,
                              inactive: Color.lerp(
                                colors.muted.withValues(alpha: AlbumPageDotsTokens.inactiveAlpha),
                                Colors.white.withValues(alpha: AlbumPageDotsTokens.inactiveOnBarAlpha),
                                ink,
                              )!,
                              onPage: _openPage,
                            );
                          },
                        ),
                      ),
                    ),
                  ),
                ),
              if (content != null)
                AnimatedBuilder(
                  animation: Listenable.merge([_reveal, _page]),
                  builder: (context, _) {
                    final expand = _reveal.expand;
                    final rect = (reduce ? restCover : PullUpMath.coverRect(restCover, stamp, expand)).shift(
                      Offset(shift(), 0),
                    );
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
                          // The cover sits over the pager: a sideways drag on it still turns the page.
                          onHorizontalDragStart: paged ? _pageDragStart : null,
                          onHorizontalDragUpdate: paged ? (d) => _pageDrag?.update(d) : null,
                          onHorizontalDragEnd: paged ? (d) => _pageDrag?.end(d) : null,
                          onHorizontalDragCancel: paged ? () => _pageDrag?.cancel() : null,
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
          ),
        );
      },
    );
  }
}

/// Status-bar icons follow the bar (AlbumBarStatusBarEffect): light over the
/// formed spectrum (it sits under the status bar), the theme's default
/// otherwise — and again once the pager has carried the bar away with page 0.
/// Published through the window's status-bar style (lib/theme/system_bars.dart);
/// rebuilds only when the docked state flips.
class _BarStatusIcons extends StatefulWidget {
  const _BarStatusIcons({required this.reveal, required this.page, required this.enabled, required this.child});

  final RevealController reveal;
  final ValueListenable<double> page;
  final bool enabled;
  final Widget child;

  @override
  State<_BarStatusIcons> createState() => _BarStatusIconsState();
}

class _BarStatusIconsState extends State<_BarStatusIcons> {
  late bool _docked = _isDocked();

  bool _isDocked() =>
      widget.enabled &&
      widget.reveal.expand > PullUpTokens.barDockedThreshold &&
      widget.page.value < PullUpTokens.barDockedThreshold;

  void _sync() {
    final docked = _isDocked();
    if (docked != _docked) setState(() => _docked = docked);
  }

  @override
  void initState() {
    super.initState();
    widget.reveal.addListener(_sync);
    widget.page.addListener(_sync);
  }

  @override
  void didUpdateWidget(_BarStatusIcons old) {
    super.didUpdateWidget(old);
    if (old.reveal != widget.reveal) {
      old.reveal.removeListener(_sync);
      widget.reveal.addListener(_sync);
    }
    if (old.page != widget.page) {
      old.page.removeListener(_sync);
      widget.page.addListener(_sync);
    }
    _docked = _isDocked();
  }

  @override
  void dispose() {
    widget.reveal.removeListener(_sync);
    widget.page.removeListener(_sync);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnnotatedRegion<SystemUiOverlayStyle>(
    // Every spectrum band is dark enough for white (≥ 7:1): the bar reads as a dark surface.
    value: yoinStatusBarStyle(_docked ? Brightness.dark : Theme.of(context).brightness),
    child: widget.child,
  );
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
    final colors = Theme.of(context).colorScheme;
    // The seam is the list's top edge (the docked band's lower edge): rows go
    // under it in the user's Scroll edge style instead of being cut.
    final list = DetailColorsBuilder(
      builder: (context, page) => ChromeSeam(
        background: SeamBackground([page.wash, colors.surface, colors.surfaceContainerLow]),
        child: ListView(
          physics: physics,
          padding: EdgeInsets.fromLTRB(16, 4, 16, 112 + bottomInset),
          children: content.listChildren,
        ),
      ),
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
                    child: content.backdrop == null
                        ? null
                        : AnimatedBuilder(
                            animation: reveal,
                            builder: (context, _) =>
                                content.backdrop!(coverInSlot, PullUpMath.smoothStep(0, 0.7, reveal.expand)),
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
                // The drag's node spans the whole hero: its texts keep nodes of their own instead of
                // merging into it (one "1/9 rated" node the size of the page).
                child: Semantics(
                  explicitChildNodes: true,
                  child: Opacity(
                    opacity: PullUpMath.heroAlpha(expand),
                    child: Transform.translate(offset: Offset(0, PullUpMath.heroShift(expand)), child: child),
                  ),
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
    required this.page,
    required this.docks,
    required this.reduce,
    required this.title,
    required this.titleTrailing,
    required this.meta,
    required this.endPadding,
  });

  final RevealController reveal;

  /// The pager: off page 0 the bar slides away with it, so the ink goes back.
  final ValueListenable<double> page;
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
        animation: Listenable.merge([reveal, this.page]),
        builder: (context, _) {
          final expand = docks ? reveal.expand : 0.0;
          final p = reduce ? 0.0 : PullUpMath.smoothStep(0, 1, expand);
          // Over the formed spectrum the header turns white (AlbumBarHeader: every band holds ≥ 7:1).
          final ink = PullUpMath.textProgress(expand) * (1 - this.page.value).clamp(0.0, 1.0);
          const onBar = Colors.white;
          final titleColor = Color.lerp(page.accent, onBar, ink)!;
          final metaColor = Color.lerp(colors.onSurfaceVariant, onBar.withValues(alpha: 0.82), ink)!;
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
                  container: Color.lerp(colors.surfaceContainer, onBar.withValues(alpha: 0.2), ink),
                  content: Color.lerp(colors.onSurface, onBar, ink),
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
                                    MetaKind(text, color: Color.lerp(page.accent, onBar.withValues(alpha: 0.88), ink))
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
                SizedBox(
                  width:
                      (DetailTokens.albumStamp + DetailTokens.albumBarTitleGap) *
                      (docks ? p : 0) *
                      (1 - this.page.value).clamp(0.0, 1.0),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
