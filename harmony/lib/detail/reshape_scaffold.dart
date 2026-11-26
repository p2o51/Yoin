import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart' show Drag;
import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/services.dart' show SystemUiOverlayStyle;
import 'package:yoin_symbols/yoin_symbols.dart';

import '../home/widgets/home_motion.dart' show StagedBeat, StagedReveal;
import '../theme/motion.dart';
import '../theme/system_bars.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/edge_split.dart';
import '../ui/adaptive/yoin_window_info.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/seam/seam.dart';
import '../ui/navigation/pane/detail_pane.dart';
import 'album_page_flight.dart';
import 'album_scrapbook_page.dart' show AlbumPageDots, AlbumPageDotsTokens;
import 'album_spectrum_layer.dart';
import 'detail_layout.dart';
import 'detail_page.dart';
import 'detail_palette.dart';
import 'detail_strings.dart';
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
    this.overviews,
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

  /// The layouts beyond the Compact pull-up (null: Compact in every window).
  final ReshapeOverviews? overviews;
}

/// What an Album / Playlist page shows outside the Compact pull-up
/// (AlbumDetailScreen / PlaylistDetailScreen on Android main): the Short
/// portrait and the landscape handset turn the hero sideways over the same
/// pull-up, the Medium overview is a plain list whose first item is the hero
/// row, and a Wide window sets an identity column beside the whole list.
final class ReshapeOverviews {
  const ReshapeOverviews({
    required this.meta,
    required this.titles,
    this.barLayouts = const {DetailLayout.compact},
    this.hasShort = false,
    this.coverBackdrop,
    this.huggingMark,
    this.attached,
    this.landscapeCoverStart = DetailLayoutTokens.albumLandscapeCoverStart,
    this.landscapeTop = DetailLayoutTokens.sideCoverTopGap,
    this.landscapeTitlesGap = DetailLayoutTokens.landscapeMetaGap,
    this.shortFooterFades = true,
  });

  /// The hero's meta for [layout] (Short: stacked beside the cover; Landscape
  /// beside it; Medium the hero row's; Wide under the identity column's
  /// cover), also the liner notes after a pulled-up list (asked for as
  /// [DetailLayout.compact] there: the row form). [interactive] false
  /// while it is not the page's dominant layer.
  final Widget Function(DetailLayout layout, bool interactive) meta;

  /// Under the meta in a sideways hero (Short: under the row, full width;
  /// Landscape: beside the cover): the count line and the flowing titles,
  /// their links live while [links].
  final Widget Function(DetailLayout layout, bool links) titles;

  /// The layouts whose cover flies into the bar as a stamp (Album: all but
  /// Wide; Playlist: Compact only). Elsewhere the cover sits in the page.
  final Set<DetailLayout> barLayouts;

  /// Whether the page has the Short portrait (the Album).
  final bool hasShort;

  /// Behind a cover drawn in the page (the Playlist sleeves): the cover's
  /// [side], the room beside it, and how far it has left with the pull-up.
  final Widget Function(double side, double sideRoom, double leaving)? coverBackdrop;

  /// The Album's whole mark hugging a landscape cover (filled into its
  /// albumHuggingMark box), and how far it has left with the pull-up.
  final Widget Function(double leaving)? huggingMark;

  /// Mounted with page 0 in every overview (the scrapbook focus taker); lays out as nothing.
  final Widget? attached;

  /// The landscape hero: the cover's start, the row's top inset (Album 48 / 4,
  /// Playlist 44 / 24), and the gap from the meta to the titles (16 / 20).
  final double landscapeCoverStart;
  final double landscapeTop;
  final double landscapeTitlesGap;

  /// Whether the Short liner notes fade in late (the Album's).
  final bool shortFooterFades;
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

/// The page's first-open reveal (Android rememberStagedReveal("album-…") /
/// ("playlist-…")), for what is drawn inside the hero (the playlist sleeves
/// open on the [hero] beat).
class DetailStagedRevealScope extends InheritedWidget {
  const DetailStagedRevealScope({super.key, required this.hero, required super.child});

  /// The hero cover's beat, 0 → 1.
  final Animation<double> hero;

  static Animation<double>? maybeHeroOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DetailStagedRevealScope>()?.hero;

  @override
  bool updateShouldNotify(DetailStagedRevealScope old) => old.hero != hero;
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
    this.titleExplicit = false,
    this.content,
    this.stateView,
    this.headerEndPadding = DetailHeaderMetrics.paddingEndAlbum,
  });

  final String title;
  final List<MetaGroup> meta;
  final Widget? titleTrailing;

  /// The release is marked explicit: the "E" after the title (and its tag), in the meta line's ink so it
  /// turns white with the header over the spectrum bar.
  final bool titleExplicit;
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

  /// The pager's subtree, one element whichever layout builds it: a resize or
  /// rotation that changes the layout keeps the page shown (Android's hoisted pagerState).
  final _pagerKey = GlobalKey(debugLabel: 'reshape.pager');

  /// The pager position, 0 = overview, 1 = page 2.
  final _page = ValueNotifier<double>(0);

  /// Page 2's cover flight (AlbumPageFlight): the hero cover travels to the scrapbook's with the pager.
  late final AlbumPageFlight _flight = AlbumPageFlight(page: _page);

  /// A dot tap's (or Last Play's) page change, on the spatial spring.
  late final AnimationController _pageSpring = AnimationController.unbounded(vsync: this)
    ..addListener(() {
      if (_pager.hasClients) _pager.position.jumpTo(_pageSpring.value * _pager.position.viewportDimension);
    });

  /// Staged '启幕' on the first open: the cover lands first (risen, grown
  /// in), the hero details rise a beat later. Once per page: returning to
  /// it, rotating or pulling up never replays it.
  late final StagedReveal _staged = StagedReveal.once(vsync: this);
  bool _stagedQueued = false;

  /// The Medium overview's list: its scroll carries the cover into the bar.
  final _mediumScroll = ScrollController();
  late final ScrollBarMorph _mediumMorph = ScrollBarMorph(_mediumScroll, coverSide: DetailLayoutTokens.mediumCoverSide);

  /// The Medium stamp's tap: back to the top on the spatial spring.
  late final AnimationController _scrollSpring = AnimationController.unbounded(vsync: this)
    ..addListener(() {
      // The expressive spring overshoots: never past the list's top (no gap over the hero row).
      if (_mediumScroll.hasClients) _mediumScroll.position.jumpTo(math.max(0.0, _scrollSpring.value));
    });

  /// The landscape handset's capsule band (the spectrum runs under it, edge to edge).
  EdgeInsets _bandInsets = EdgeInsets.zero;

  @override
  void initState() {
    super.initState();
    // Created now: a lazy field first read in dispose() would build a ticker on a dead element.
    _staged;
    _pager.addListener(() {
      if (_pager.hasClients && _pager.position.haveDimensions) _page.value = _pager.page ?? 0;
    });
  }

  /// Starts the reveal once the page has content (not against a loading page).
  void _stageWhenLoaded(bool loaded, bool reduce) {
    if (!loaded || _staged.played || _stagedQueued) return;
    _stagedQueued = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _staged.start(reducedMotion: reduce);
    });
  }

  @override
  void dispose() {
    _staged.dispose();
    _reveal.dispose();
    _pageSpring.dispose();
    _scrollSpring.dispose();
    _mediumMorph.dispose();
    _mediumScroll.dispose();
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

  void _scrollMediumToTop() {
    if (!_mediumScroll.hasClients) return;
    final from = _mediumScroll.position.pixels;
    if (MediaQuery.disableAnimationsOf(context)) {
      _mediumScroll.jumpTo(0);
      return;
    }
    _scrollSpring
      ..value = from
      ..animateWith(SpringSimulation(YoinSprings.defaultSpatial, from, 0, 0));
  }

  /// The layout this page takes in its container (Compact until it has content).
  DetailLayout _layoutOf(BuildContext context) {
    final overviews = widget.content?.overviews;
    if (overviews == null) return DetailLayout.compact;
    return resolveDetailLayout(
      YoinWindowInfo.of(context),
      hasShort: overviews.hasShort,
      fontScale: MediaQuery.textScalerOf(context).scale(14) / 14,
    );
  }

  @override
  Widget build(BuildContext context) {
    final window = YoinWindowInfo.of(context);
    _bandInsets = window.chromeForm == ShellChromeForm.edgeSplit ? shellChromeInsetsOf(context) : EdgeInsets.zero;
    // A landscape handset: the page starts past the edge capsules, its wash under the band (detailChromeBand).
    return ShellChromeBand(
      background: const DetailPageBackground(),
      child: Builder(builder: _buildPage),
    );
  }

  /// The page dots (AlbumPageDots) in the cover scheme, turning white over the formed bar.
  Widget _pageDots(BarMorph morph, {bool docks = true}) => DetailColorsBuilder(
    builder: (context, colors) => AnimatedBuilder(
      animation: Listenable.merge([morph, _page]),
      builder: (context, _) {
        final ink = docks ? PullUpMath.textProgress(morph.barProgress) * (1 - _page.value).clamp(0.0, 1.0) : 0.0;
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
  );

  Widget _buildPage(BuildContext context) {
    final media = MediaQuery.of(context);
    final reduce = media.disableAnimations;
    _stageWhenLoaded(widget.content != null, reduce);
    final layout = _layoutOf(context);
    if (layout != DetailLayout.compact) return _buildOverview(context, media, reduce, layout);
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
                heroBeat: _staged.beats[0].animation,
                detailsBeat: _staged.beats[1].animation,
                flight: paged ? _flight : null,
                slotHeight: side + PullUpTokens.heroBand,
                coverInSlot: Rect.fromLTWH((w - side) / 2, DetailTokens.albumCoverTopGap, side, side),
              );
        // Page 0's flying pieces (the spectrum, the cover) go with page 0 as the pager drags.
        double shift() => paged ? -_page.value * w : 0;
        // The cover flies to page 2's cover instead (not under reduced motion: it slides away there).
        final flight = paged ? _flight : null;
        _flight
          ..flies = paged && !reduce
          ..pageOrigin = Offset(0, headerBottom);
        return _BarStatusIcons(
          reveal: _reveal,
          page: _page,
          // The status bar belongs to the window, not to a page in the detail column.
          enabled: content != null && !DetailPane.isInColumn(context),
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
                        key: _pagerKey,
                        openPage: _openPage,
                        child: AlbumPageFlightScope(
                          flight: flight,
                          child: Listener(
                            // A finger on the pager takes over from a dot's spring.
                            onPointerDown: (_) => _pageSpring.stop(),
                            child: PageView(
                              controller: _pager,
                              children: [
                                body,
                                SizedBox.expand(
                                  key: _flight.pageKey,
                                  child: Builder(builder: (context) => content!.secondPage!(context, _page)),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
              ),
              Positioned(
                left: 0,
                right: 0,
                top: topInset,
                height: DetailHeaderMetrics.bandHeight,
                child: DetailBarHeader(
                  reveal: _reveal,
                  page: _page,
                  docks: content != null,
                  reduce: reduce,
                  title: widget.title,
                  titleTrailing: widget.titleTrailing,
                  titleExplicit: widget.titleExplicit,
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
                      child: _pageDots(_reveal),
                    ),
                  ),
                ),
              if (content != null)
                AnimatedBuilder(
                  animation: Listenable.merge([_reveal, _page]),
                  builder: (context, _) {
                    final expand = _reveal.expand;
                    final t = flight?.t ?? 0;
                    // From wherever the pull-up holds it (the hero's middle or the stamp) to the
                    // scrapbook's cover, with the pager; back the same way. No target: it slides with page 0.
                    final target = flight != null && flight.flies && t > 0 ? flight.coverTarget() : null;
                    final pose = AlbumFlightPose.of(
                      from: reduce ? restCover : PullUpMath.coverRect(restCover, stamp, expand),
                      target: target,
                      t: t,
                      shift: shift(),
                      targetTilt: _flight.coverTilt,
                    );
                    final toPage = target == null ? 0.0 : t;
                    // Landed: the page's own cover (tilted, under its emblem) takes over.
                    final alpha = pose.landed ? 0.0 : (reduce ? PullUpMath.heroAlpha(expand) : 1.0);
                    final docked = expand >= 0.5;
                    return Positioned.fromRect(
                      rect: pose.rect,
                      child: IgnorePointer(
                        ignoring: pose.landed,
                        child: Transform.rotate(
                          angle: pose.tiltDegrees * math.pi / 180,
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
                                // The image's own corner, no shadow: nothing behind it is clipped.
                                // It lands on its staged beat (risen, grown in) on the first open.
                                child: StagedBeat(
                                  beat: _staged.beats[0].animation,
                                  rise: DetailTokens.revealCoverRise,
                                  scaleFrom: DetailTokens.revealCoverScaleFrom,
                                  alignment: Alignment.center,
                                  child: MediaArtwork(
                                    url: content.coverUrl,
                                    fallbackIcon: content.fallbackIcon,
                                    radius: lerpDouble(PullUpMath.coverCorner(expand), _flight.coverCorner, toPage)!,
                                    decodeWidth: 640,
                                    fallbackIconSize: lerpDouble(lerpDouble(48, 24, expand)!, 24, toPage)!,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              // The scrapbook's emblem sits over its cover: while the cover flies, a copy of it rides
              // over the flying one, where the page holds it (the page's own waits until landed).
              if (content != null && flight != null)
                AnimatedBuilder(
                  animation: _page,
                  builder: (context, _) {
                    final emblem = flight.emblem;
                    final t = flight.t;
                    // Positioned either way: a plain child would size the stack.
                    if (emblem == null || t <= 0 || !flight.pageCoverHidden()) {
                      return const Positioned(left: 0, top: 0, child: SizedBox.shrink());
                    }
                    return Positioned(
                      left: 0,
                      top: 0,
                      // A stand-in: the page's own emblem keeps the taps and the reader.
                      child: IgnorePointer(
                        child: ExcludeSemantics(
                          child: CompositedTransformFollower(
                            link: flight.emblemLink,
                            showWhenUnlinked: false,
                            child: emblem,
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

  /// Short, Landscape, Medium and Wide (AlbumDetailContent's other forks):
  /// the same header, pager and page 2; the cover flies into the bar in the
  /// page's [ReshapeOverviews.barLayouts] and sits in the page elsewhere.
  Widget _buildOverview(BuildContext context, MediaQueryData media, bool reduce, DetailLayout layout) {
    final strings = DetailStrings.of(context);
    final inColumn = DetailPane.isInColumn(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth;
        final h = constraints.maxHeight;
        final topInset = media.padding.top;
        final content = widget.content!;
        final overviews = content.overviews!;
        final paged = content.secondPage != null;
        final rtl = Directionality.of(context) == TextDirection.rtl;
        // Short: the dots sit at the title row's end, giving their row back to the page.
        final dotsInRow = layout == DetailLayout.short;
        final headerBottom =
            topInset + DetailHeaderMetrics.bandHeight + (paged && !dotsInRow ? AlbumPageDotsTokens.cellHeight : 0);
        final docks = overviews.barLayouts.contains(layout);
        final geometry = docks
            ? detailSideBarGeometry(
                layout,
                width: w,
                height: h,
                topInset: topInset,
                headerBottom: headerBottom,
                rtl: rtl,
              )
            : null;
        final BarMorph morph = layout == DetailLayout.medium ? _mediumMorph : _reveal;
        if (geometry != null && layout == DetailLayout.medium) _mediumMorph.coverSide = geometry.cover.height;
        _reveal.travelPx = PullUpMath.travel(h);
        // The cover flies to page 2's with the pager from the Short hero (as from Compact's).
        final flies = paged && layout == DetailLayout.short && geometry != null;
        _flight
          ..flies = flies && !reduce
          ..pageOrigin = Offset(0, headerBottom);
        final flight = flies ? _flight : null;
        double shift() => paged ? -_page.value * w : 0;
        final bodyHeight = math.max(0.0, h - headerBottom);
        final Widget body = switch (layout) {
          DetailLayout.short || DetailLayout.landscape => _SideBody(
            layout: layout,
            reveal: _reveal,
            physics: _physics,
            content: content,
            heroBeat: _staged.beats[0].animation,
            detailsBeat: _staged.beats[1].animation,
            flight: flight,
            coverSide:
                geometry?.cover.width ??
                (layout == DetailLayout.short ? albumShortCoverSide(w) : playlistLandscapeCoverSide(bodyHeight)),
            coverInPage: geometry == null,
            pageStart: rtl ? _bandInsets.right : _bandInsets.left,
          ),
          DetailLayout.medium => Listener(
            // A finger on the list takes over from the stamp's spring.
            onPointerDown: (_) => _scrollSpring.stop(),
            child: _MediumBody(
              scroll: _mediumScroll,
              content: content,
              coverInPage: geometry == null,
              heroBeat: _staged.beats[0].animation,
            ),
          ),
          DetailLayout.wide => _WideBody(content: content, heroBeat: _staged.beats[0].animation),
          DetailLayout.compact => throw StateError('Compact has its own build'),
        };
        final page0 = overviews.attached == null
            ? body
            : Stack(fit: StackFit.expand, children: [body, overviews.attached!]);
        final band = _bandInsets;
        return _BarStatusIcons(
          reveal: morph,
          page: _page,
          enabled: docks && !inColumn,
          child: Stack(
            // The spectrum runs on under a landscape handset's capsule band, edge to edge.
            clipBehavior: Clip.none,
            children: [
              const Positioned.fill(child: DetailPageBackground()),
              if (geometry != null)
                Positioned(
                  left: -band.left,
                  right: -band.right,
                  top: 0,
                  bottom: 0,
                  child: AnimatedBuilder(
                    animation: _page,
                    builder: (context, child) => Transform.translate(offset: Offset(shift(), 0), child: child),
                    child: AlbumSpectrumLayer(
                      reveal: morph,
                      coverUrl: content.coverUrl,
                      cover: geometry.cover.translate(band.left, 0),
                      bar: Rect.fromLTWH(0, 0, w + band.horizontal, headerBottom),
                      reduce: reduce,
                    ),
                  ),
                ),
              Positioned.fill(
                top: headerBottom,
                child: !paged
                    ? page0
                    : ReshapePager(
                        key: _pagerKey,
                        openPage: _openPage,
                        child: AlbumPageFlightScope(
                          flight: flight,
                          child: Listener(
                            onPointerDown: (_) => _pageSpring.stop(),
                            child: PageView(
                              controller: _pager,
                              children: [
                                page0,
                                SizedBox.expand(
                                  key: _flight.pageKey,
                                  child: Builder(builder: (context) => content.secondPage!(context, _page)),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
              ),
              Positioned(
                left: 0,
                right: 0,
                top: topInset,
                height: DetailHeaderMetrics.bandHeight,
                child: DetailBarHeader(
                  reveal: morph,
                  page: _page,
                  docks: docks,
                  reduce: reduce,
                  title: widget.title,
                  titleTrailing: widget.titleTrailing,
                  titleExplicit: widget.titleExplicit,
                  meta: widget.meta,
                  endPadding: widget.headerEndPadding,
                  stampSide: detailStampSide(layout),
                  trailing: paged && dotsInRow
                      ? Padding(
                          padding: const EdgeInsetsDirectional.only(start: DetailLayoutTokens.footerInset),
                          child: _pageDots(morph, docks: docks),
                        )
                      : null,
                ),
              ),
              if (paged && !dotsInRow)
                Positioned(
                  top: topInset + DetailHeaderMetrics.bandHeight,
                  height: AlbumPageDotsTokens.cellHeight,
                  left: 0,
                  right: 0,
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: Padding(
                      padding: const EdgeInsetsDirectional.only(
                        start:
                            DetailHeaderMetrics.paddingStart +
                            48 +
                            DetailHeaderMetrics.titleGap -
                            4 -
                            (AlbumPageDotsTokens.cellWidth / 2 - AlbumPageDotsTokens.dot),
                      ),
                      child: _pageDots(morph, docks: docks),
                    ),
                  ),
                ),
              if (geometry != null)
                AnimatedBuilder(
                  animation: Listenable.merge([morph, _page]),
                  builder: (context, _) {
                    final progress = morph.barProgress.clamp(0.0, 1.0);
                    final rest = geometry.cover.translate(0, -morph.coverLift);
                    final t = flight?.t ?? 0;
                    final target = flight != null && flight.flies && t > 0 ? flight.coverTarget() : null;
                    final pose = AlbumFlightPose.of(
                      from: reduce ? rest : PullUpMath.coverRect(rest, geometry.stamp, progress),
                      target: target,
                      t: t,
                      shift: shift(),
                      targetTilt: _flight.coverTilt,
                    );
                    final toPage = target == null ? 0.0 : t;
                    final alpha = pose.landed ? 0.0 : (reduce ? PullUpMath.heroAlpha(progress) : 1.0);
                    final docked = progress >= PullUpTokens.barDockedThreshold;
                    final medium = layout == DetailLayout.medium;
                    final pullUp = !medium;
                    Widget cover = MediaArtwork(
                      url: content.coverUrl,
                      fallbackIcon: content.fallbackIcon,
                      radius: lerpDouble(PullUpMath.coverCorner(progress), _flight.coverCorner, toPage)!,
                      decodeWidth: 640,
                      fallbackIconSize: lerpDouble(lerpDouble(48, 24, progress)!, 24, toPage)!,
                    );
                    // The Short cover lands on its staged beat on the first open, as Compact's.
                    if (layout == DetailLayout.short) {
                      cover = StagedBeat(
                        beat: _staged.beats[0].animation,
                        rise: DetailTokens.revealCoverRise,
                        scaleFrom: DetailTokens.revealCoverScaleFrom,
                        alignment: Alignment.center,
                        child: cover,
                      );
                    }
                    return Positioned.fromRect(
                      rect: pose.rect,
                      // Medium: the resting cover is only a picture over the list's own room for it —
                      // the list under it takes the drag; only the stamp is a button.
                      child: IgnorePointer(
                        ignoring: pose.landed || (medium && !docked),
                        child: Transform.rotate(
                          angle: pose.tiltDegrees * math.pi / 180,
                          child: Opacity(
                            opacity: alpha,
                            child: Semantics(
                              button: docked,
                              onTapHint: docked ? strings.albumCdShowCover : null,
                              child: GestureDetector(
                                behavior: HitTestBehavior.opaque,
                                onTap: docked ? (medium ? _scrollMediumToTop : () => _reveal.setExpanded(false)) : null,
                                onVerticalDragUpdate: pullUp && !docked ? (d) => _reveal.dragBy(d.delta.dy) : null,
                                onVerticalDragEnd: pullUp && !docked
                                    ? (d) => _reveal.release(d.velocity.pixelsPerSecond.dy)
                                    : null,
                                onHorizontalDragStart: paged ? _pageDragStart : null,
                                onHorizontalDragUpdate: paged ? (d) => _pageDrag?.update(d) : null,
                                onHorizontalDragEnd: paged ? (d) => _pageDrag?.end(d) : null,
                                onHorizontalDragCancel: paged ? () => _pageDrag?.cancel() : null,
                                child: Material(type: MaterialType.transparency, elevation: 0, child: cover),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              if (flight != null)
                AnimatedBuilder(
                  animation: _page,
                  builder: (context, _) {
                    final emblem = flight.emblem;
                    final t = flight.t;
                    if (emblem == null || t <= 0 || !flight.pageCoverHidden()) {
                      return const Positioned(left: 0, top: 0, child: SizedBox.shrink());
                    }
                    return Positioned(
                      left: 0,
                      top: 0,
                      child: IgnorePointer(
                        child: ExcludeSemantics(
                          child: CompositedTransformFollower(
                            link: flight.emblemLink,
                            showWhenUnlinked: false,
                            child: emblem,
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

  final BarMorph reveal;
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
      widget.reveal.barProgress > PullUpTokens.barDockedThreshold &&
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
    required this.heroBeat,
    required this.detailsBeat,
    required this.flight,
    required this.slotHeight,
    required this.coverInSlot,
  });

  final RevealController reveal;
  final RevealScrollPhysics physics;
  final ReshapeContent content;

  /// The first open's beats: the cover's (the backdrop opens behind it) and
  /// the hero details' a beat later.
  final Animation<double> heroBeat;
  final Animation<double> detailsBeat;

  /// Page 2's cover flight: while the cover flies, the hero text fades out early.
  final AlbumPageFlight? flight;
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
        // Rows near the bar open into the bottom field.
        field: true,
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
                        : DetailStagedRevealScope(
                            hero: heroBeat,
                            child: AnimatedBuilder(
                              animation: reveal,
                              builder: (context, _) =>
                                  content.backdrop!(coverInSlot, PullUpMath.smoothStep(0, 0.7, reveal.expand)),
                            ),
                          ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                    child: StagedBeat(
                      beat: detailsBeat,
                      rise: DetailTokens.revealDetailsRise,
                      child: _FlightTextFade(
                        flight: flight,
                        child: _HeroBelow(reveal: reveal, builder: content.heroBelow),
                      ),
                    ),
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

/// Page 0's hero text while the cover flies to page 2 (albumHeroPageFade):
/// gone a third of the way in, so it never slides across the flying cover.
class _FlightTextFade extends StatelessWidget {
  const _FlightTextFade({required this.flight, required this.child});

  final AlbumPageFlight? flight;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final flight = this.flight;
    if (flight == null) return child;
    return ValueListenableBuilder<double>(
      valueListenable: flight.page,
      child: child,
      builder: (context, _, child) {
        final t = flight.t;
        final alpha = t <= 0 ? 1.0 : albumHeroPageFade(t, flying: flight.coverFlying());
        return Opacity(opacity: alpha, child: child);
      },
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

/// The page's header row: back, the title and its meta, and the room the
/// stamp takes as the bar forms (white over the spectrum). Public for the
/// Cover stamp tip's demo page.
class DetailBarHeader extends StatelessWidget {
  const DetailBarHeader({
    super.key,
    required this.reveal,
    required this.page,
    required this.docks,
    required this.reduce,
    required this.title,
    required this.titleTrailing,
    this.titleExplicit = false,
    required this.meta,
    required this.endPadding,
    this.stampSide = DetailTokens.albumStamp,
    this.trailing,
    this.onBack,
  });

  final BarMorph reveal;

  /// The pager: off page 0 the bar slides away with it, so the ink goes back.
  final ValueListenable<double> page;
  final bool docks;
  final bool reduce;
  final String title;
  final Widget? titleTrailing;
  final bool titleExplicit;
  final List<MetaGroup> meta;
  final double endPadding;

  /// The stamp the title gives room to as the bar forms (44 on a landscape handset).
  final double stampSide;

  /// At the title row's end, before the stamp's room (Short: the page dots).
  final Widget? trailing;

  /// In place of popping the route (a Tips demo's page goes nowhere).
  final VoidCallback? onBack;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DetailColorsBuilder(
      builder: (context, page) => AnimatedBuilder(
        animation: Listenable.merge([reveal, this.page]),
        builder: (context, _) {
          final expand = docks ? reveal.barProgress.clamp(0.0, 1.0) : 0.0;
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
                  onBack: onBack,
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
                          DetailTitle(
                            title,
                            trailing: titleTrailing,
                            color: titleColor,
                            explicit: titleExplicit,
                            badgeColor: metaColor,
                          ),
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
                ?trailing,
                SizedBox(
                  width:
                      (stampSide + DetailTokens.albumBarTitleGap) *
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

/// The list a sideways hero pulls up into, with the liner notes after it.
/// Built only once the list shows, interactive from half way (as Compact's).
class _SideBody extends StatelessWidget {
  const _SideBody({
    required this.layout,
    required this.reveal,
    required this.physics,
    required this.content,
    required this.heroBeat,
    required this.detailsBeat,
    required this.flight,
    required this.coverSide,
    required this.coverInPage,
    required this.pageStart,
  });

  final DetailLayout layout;
  final RevealController reveal;
  final RevealScrollPhysics physics;
  final ReshapeContent content;
  final Animation<double> heroBeat;
  final Animation<double> detailsBeat;
  final AlbumPageFlight? flight;

  /// The cover's side; the bar layer draws it unless [coverInPage].
  final double coverSide;
  final bool coverInPage;

  /// The capsule band before the page (the mark keeps clear of the screen edge, not the band's).
  final double pageStart;

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final colors = Theme.of(context).colorScheme;
    final overviews = content.overviews!;
    final short = layout == DetailLayout.short;
    final footerFades = short && overviews.shortFooterFades;
    // 封面 → 封底: after the tracklist, the hero's meta again as liner notes; live once the list leads.
    final footer = Padding(
      padding: const EdgeInsetsDirectional.only(
        start: DetailLayoutTokens.footerInset,
        end: DetailLayoutTokens.footerInset,
        top: DetailLayoutTokens.footerTop,
      ),
      child: AnimatedBuilder(
        animation: reveal,
        // The list's full width: the row form (Last Play with its caption | the emblem), as Android's
        // footer — never Short's narrow stacked column beside the cover.
        child: _HeroBelow(
          reveal: reveal,
          builder: (links) => overviews.meta(short ? DetailLayout.compact : layout, !links),
        ),
        builder: (context, child) => Opacity(
          // Late (the last 40 % of the reshape): mid-drag the hero's own copy is still fading out.
          opacity: footerFades
              ? ((reveal.expand - DetailLayoutTokens.footerFadeStart) / (1 - DetailLayoutTokens.footerFadeStart)).clamp(
                  0.0,
                  1.0,
                )
              : 1,
          child: child,
        ),
      ),
    );
    final list = DetailColorsBuilder(
      builder: (context, page) => ChromeSeam(
        background: SeamBackground([page.wash, colors.surface, colors.surfaceContainerLow]),
        field: true,
        child: ListView(
          physics: physics,
          padding: EdgeInsets.fromLTRB(
            DetailLayoutTokens.pageInset,
            DetailLayoutTokens.listTopPadding,
            DetailLayoutTokens.pageInset,
            DetailLayoutTokens.listBottomPadding + bottomInset,
          ),
          children: [...content.listChildren, footer],
        ),
      ),
    );
    final hero = short ? _shortHero(overviews) : _landscapeHero(overviews);
    return Stack(
      fit: StackFit.expand,
      children: [
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
        AnimatedBuilder(
          animation: reveal,
          child: ClipRect(
            child: OverflowBox(
              alignment: Alignment.topCenter,
              minHeight: 0,
              maxHeight: double.infinity,
              child: DetailStagedRevealScope(hero: heroBeat, child: hero),
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

  /// Short (AlbumShortOverview): the cover on the 16 line with the meta
  /// stacked beside it, then the count and the flowing titles full width.
  Widget _shortHero(ReshapeOverviews overviews) => Padding(
    padding: const EdgeInsetsDirectional.only(
      start: DetailLayoutTokens.shortCoverStart,
      end: DetailLayoutTokens.pageInset,
      top: DetailLayoutTokens.sideCoverTopGap,
    ),
    child: StagedBeat(
      beat: detailsBeat,
      rise: DetailTokens.revealDetailsRise,
      child: _FlightTextFade(
        flight: flight,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // The cover's place: the bar layer draws it (it flies into the stamp).
                SizedBox(width: coverSide + DetailLayoutTokens.shortHeroGap, height: coverSide),
                Expanded(
                  child: _HeroBelow(reveal: reveal, builder: (links) => overviews.meta(DetailLayout.short, links)),
                ),
              ],
            ),
            const SizedBox(height: DetailLayoutTokens.shortTitlesGap),
            _HeroBelow(reveal: reveal, builder: (links) => overviews.titles(DetailLayout.short, links)),
          ],
        ),
      ),
    ),
  );

  /// Landscape handset (AlbumLandscapeOverview / PlaylistLandscapeLayers): the
  /// cover on the left, what sits under it on a phone on the right.
  Widget _landscapeHero(ReshapeOverviews overviews) {
    final Widget slot;
    if (coverInPage) {
      slot = AnimatedBuilder(
        animation: reveal,
        builder: (context, _) => _InPageCover(
          content: content,
          side: coverSide,
          // The sleeves fan no wider than the start margin and the gutter to the meta allow.
          sideRoom: math.min(overviews.landscapeCoverStart, DetailLayoutTokens.landscapeGutter),
          leaving: PullUpMath.smoothStep(0, 0.7, reveal.expand),
          decodeWidth: 640,
        ),
      );
    } else {
      final hugging = overviews.huggingMark;
      // The whole mark hugging the cover, clear of the screen's edge (not the band's).
      final cover = Rect.fromLTWH(pageStart + overviews.landscapeCoverStart, 0, coverSide, coverSide);
      final mark = albumHuggingMark(cover).translate(-cover.left, 0);
      slot = SizedBox.square(
        dimension: coverSide,
        child: hugging == null
            ? null
            : Stack(
                clipBehavior: Clip.none,
                children: [
                  Positioned.fromRect(
                    rect: mark,
                    child: AnimatedBuilder(
                      animation: reveal,
                      builder: (context, _) => hugging(PullUpMath.smoothStep(0, 0.7, reveal.expand)),
                    ),
                  ),
                ],
              ),
      );
    }
    return Padding(
      padding: EdgeInsetsDirectional.only(
        start: overviews.landscapeCoverStart,
        top: overviews.landscapeTop,
        end: DetailLayoutTokens.landscapeHeroEnd,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          slot,
          const SizedBox(width: DetailLayoutTokens.landscapeGutter),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: DetailLayoutTokens.landscapeMetaMaxWidth),
                  child: _HeroBelow(reveal: reveal, builder: (links) => overviews.meta(DetailLayout.landscape, links)),
                ),
                SizedBox(height: overviews.landscapeTitlesGap),
                _HeroBelow(reveal: reveal, builder: (links) => overviews.titles(DetailLayout.landscape, links)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The Medium overview (AlbumMediumOverview / PlaylistMediumOverview): no
/// reveal state — one plain list, capped at 720 and centred, whose first
/// item is the hero row (cover 240 beside the meta). The Album's cover is
/// drawn by the bar layer and forms the stamp as the list scrolls; the
/// Playlist's sits in the row on its sleeves.
class _MediumBody extends StatelessWidget {
  const _MediumBody({required this.scroll, required this.content, required this.coverInPage, required this.heroBeat});

  final ScrollController scroll;
  final ReshapeContent content;
  final bool coverInPage;
  final Animation<double> heroBeat;

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final colors = Theme.of(context).colorScheme;
    final overviews = content.overviews!;
    final meta = overviews.meta(DetailLayout.medium, true);
    final Widget row = coverInPage
        ? _SleeveRoomRow(content: content, meta: meta)
        : Padding(
            padding: const EdgeInsets.only(bottom: DetailLayoutTokens.mediumHeroBottom),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // The cover's place: the bar layer draws it, flying into the header with the scroll.
                const SizedBox.square(dimension: DetailLayoutTokens.mediumCoverSide),
                const SizedBox(width: DetailLayoutTokens.mediumHeroGap),
                Expanded(child: meta),
              ],
            ),
          );
    return LayoutBuilder(
      builder: (context, constraints) {
        final inset = detailMediumInset(constraints.maxWidth);
        return DetailStagedRevealScope(
          hero: heroBeat,
          child: DetailColorsBuilder(
            builder: (context, page) => ChromeSeam(
              controller: scroll,
              background: SeamBackground([page.wash, colors.surface, colors.surfaceContainerLow]),
              field: true,
              child: ListView(
                controller: scroll,
                padding: EdgeInsets.fromLTRB(
                  inset,
                  DetailLayoutTokens.listTopPadding,
                  inset,
                  DetailLayoutTokens.listBottomPadding + bottomInset,
                ),
                children: [
                  KeyedSubtree(key: const ValueKey('detail-medium-hero'), child: row),
                  ...content.listChildren,
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The Playlist's Medium hero row: the cover steps in from the list's start
/// for its sleeves (only with sleeves; a refresh that adds or drops them
/// moves the cover on the spatial spring).
class _SleeveRoomRow extends StatefulWidget {
  const _SleeveRoomRow({required this.content, required this.meta});

  final ReshapeContent content;
  final Widget meta;

  @override
  State<_SleeveRoomRow> createState() => _SleeveRoomRowState();
}

class _SleeveRoomRowState extends State<_SleeveRoomRow> with SingleTickerProviderStateMixin {
  double get _target =>
      widget.content.overviews!.coverBackdrop == null ? 0 : DetailLayoutTokens.playlistMediumSleeveRoom;
  late final SpringValue _room = SpringValue(vsync: this, initial: _target, spring: YoinSprings.defaultSpatial);

  @override
  void didUpdateWidget(_SleeveRoomRow old) {
    super.didUpdateWidget(old);
    if (MediaQuery.disableAnimationsOf(context)) {
      _room.snapTo(_target);
    } else {
      _room.animateTo(_target);
    }
  }

  @override
  void dispose() {
    _room.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(
      top: DetailLayoutTokens.playlistMediumTop,
      bottom: DetailLayoutTokens.mediumHeroBottom,
    ),
    child: AnimatedBuilder(
      animation: _room.animation,
      builder: (context, _) {
        final room = math.max(0.0, _room.value);
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: room),
            _InPageCover(
              content: widget.content,
              side: DetailLayoutTokens.mediumCoverSide,
              sideRoom: DetailLayoutTokens.playlistMediumSleeveRoom,
              decodeWidth: 640,
              inList: true,
            ),
            SizedBox(width: DetailLayoutTokens.mediumHeroGap + room),
            Expanded(child: widget.meta),
          ],
        );
      },
    ),
  );
}

/// A Wide full window (AlbumWideOverview / PlaylistWideOverview): the
/// identity column — cover 360 in its 400 box, the meta under it, the
/// cover's width — beside the whole list, capped at 800. No bar: the cover
/// stays in its column.
class _WideBody extends StatelessWidget {
  const _WideBody({required this.content, required this.heroBeat});

  final ReshapeContent content;
  final Animation<double> heroBeat;

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final colors = Theme.of(context).colorScheme;
    final overviews = content.overviews!;
    const coverInset = (DetailLayoutTokens.wideIdentityWidth - DetailLayoutTokens.wideCoverSide) / 2;
    return DetailStagedRevealScope(
      hero: heroBeat,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: DetailLayoutTokens.wideIdentityWidth + 2 * DetailLayoutTokens.wideMargin,
            // The margins sit inside the scroll: it clips its sides, and the sleeves fan out into them.
            child: SingleChildScrollView(
              padding: EdgeInsets.fromLTRB(
                DetailLayoutTokens.wideMargin,
                DetailLayoutTokens.wideTop,
                DetailLayoutTokens.wideMargin,
                DetailLayoutTokens.wideBottom + bottomInset,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox.square(
                    dimension: DetailLayoutTokens.wideIdentityWidth,
                    child: Center(
                      child: _InPageCover(
                        content: content,
                        side: DetailLayoutTokens.wideCoverSide,
                        sideRoom: coverInset + DetailLayoutTokens.wideMargin,
                        decodeWidth: 900,
                      ),
                    ),
                  ),
                  const SizedBox(height: DetailLayoutTokens.wideMetaGap),
                  SizedBox(width: DetailLayoutTokens.wideCoverSide, child: overviews.meta(DetailLayout.wide, true)),
                ],
              ),
            ),
          ),
          Flexible(
            child: Padding(
              padding: const EdgeInsetsDirectional.only(end: DetailLayoutTokens.wideEnd),
              child: Align(
                alignment: AlignmentDirectional.topStart,
                child: ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: DetailLayoutTokens.wideListMaxWidth),
                  child: DetailColorsBuilder(
                    builder: (context, page) => ChromeSeam(
                      background: SeamBackground([page.wash, colors.surface, colors.surfaceContainerLow]),
                      field: true,
                      child: ListView(
                        padding: EdgeInsets.only(
                          top: DetailLayoutTokens.wideListTop + DetailLayoutTokens.listTopPadding,
                          bottom: DetailLayoutTokens.listBottomPadding + bottomInset,
                        ),
                        children: content.listChildren,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A cover drawn in the page (no bar to fly into): the Hero corner, and the
/// page's backdrop behind it (the Playlist sleeves, fanned into [sideRoom]
/// and leaving with [leaving]). [inList]: it scrolls in a list, so it breaks
/// into the seam's halftone with the rows.
class _InPageCover extends StatelessWidget {
  const _InPageCover({
    required this.content,
    required this.side,
    required this.sideRoom,
    required this.decodeWidth,
    this.leaving = 0,
    this.inList = false,
  });

  final ReshapeContent content;
  final double side;
  final double sideRoom;
  final int decodeWidth;
  final double leaving;
  final bool inList;

  @override
  Widget build(BuildContext context) {
    final backdrop = content.overviews?.coverBackdrop;
    Widget artwork = MediaArtwork(
      url: content.coverUrl,
      fallbackIcon: content.fallbackIcon,
      radius: PullUpTokens.heroCorner,
      decodeWidth: decodeWidth,
    );
    if (inList) artwork = SeamDissolve(child: artwork);
    return SizedBox.square(
      dimension: side,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          if (backdrop != null) Positioned.fill(child: backdrop(side, sideRoom, leaving)),
          Positioned.fill(child: artwork),
        ],
      ),
    );
  }
}
