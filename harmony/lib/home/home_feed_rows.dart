/// Two-column Home (parity p4 C2; Android HomeFeedRows.kt, HomeGridUnits.kt, HomeShelfPager.kt; iOS
/// HomeFeedRows.swift; design.md §主页「两栏」). When the feed is a Wide frame whose container is at least
/// 840 × 480dp and the device isn't in a tabletop posture ([homeFeedTwoColumn]: MatePad landscape, a Mate XT
/// fully open, a PC window), the feed lays out in rows of one full section or two halves 20dp apart. Every
/// section is then a whole number of grid units tall, so the two halves of a row always line up: height =
/// 40 + n·150 + (n − 1)·14 ([homeGridHeightDp]). Each cell reads its own width ([homeColumnDensity], Android
/// `forHomeColumn`): a half lays its section out for half the content, a full row for all of it. The arithmetic
/// is the shared core's (lib/data/home/home_grid_rules.dart, replayed against home-grid.json); this file is the
/// Flutter side: the cell frame, the exact-height box, the title row, packed grid cells and the shelf that pages
/// by whole cards in a half.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/home/home_grid_rules.dart';
import '../theme/motion.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'home_edit.dart' show HomeEditShelfClip;
import 'home_feed_density.dart';
import 'home_header.dart' show HomeSectionTitle;
import 'home_selection.dart';
import 'home_strings.dart';

/// Numbers of the two-column feed's own drawing (dp).
abstract final class HomeColumnTokens {
  /// Between the two halves of a row (shared `HomeSpanRules.columnGap`).
  static const double rowGap = 20;

  /// A paged shelf's dots: size and gap; its ‹ › buttons' touch box and glyph.
  static const double dot = 6;
  static const double dotGap = 6;
  static const double pageButton = 32;
  static const double pageGlyph = 20;

  /// Disabled ‹ › alpha.
  static const double pageButtonDisabledAlpha = .38;

  /// The span drag's dashed outline: stroke, dash, gap, corner.
  static const double outlineStroke = 2;
  static const double outlineDash = 8;
  static const double outlineGap = 6;
  static const double outlineCorner = 20;
}

/// The feed lays out in two columns (Android `homeTwoColumn(info)`): a Wide frame (W ≥ 840, height ≥ 480,
/// never tabletop) and the shared tier agreeing on the container's own size. A Mate X inner screen (Medium),
/// a tabletop half fold and a landscape handset stay one column.
bool homeFeedTwoColumn(YoinWindowInfo info) =>
    feedFrameClassOf(info) == FeedFrameClass.wide && homeTwoColumn(info.width, info.height, info.isTabletop);

/// The density a section in a [contentWidth]-wide column reads (Android `forHomeColumn`): its own feed units
/// and cover columns, the Wide recipe from 776dp across (the narrowest Wide content), the Medium one below.
HomeFeedDensity homeColumnDensity(double contentWidth) {
  final wide = contentWidth >= HomeTwoColumnTokens.minContent;
  return HomeFeedDensity(
    frameClass: FeedFrameClass.wide,
    units: feedUnitsFor(contentWidth, wide: wide),
    coverColumns: feedCoverColumns(contentWidth),
  );
}

/// A cell's frame: [content] wide between its own [start] and [end] reach (the page margin on the outer side,
/// half the row gap on the inner one), its density read from [content].
HomeFeedFrame homeCellFrame({required double start, required double content, required double end}) =>
    HomeFeedFrame(start: start, content: content, end: end, density: homeColumnDensity(content));

/// What a section lays out for in two columns (Android `LocalHomeGridHeight` + `LocalHomeColumn`): its row's
/// units, whether it is half a row (a half pages its shelf inside the column instead of bleeding past it) and
/// the box's live height (a unit drag in flight; else exactly its units' grid height).
@immutable
final class HomeColumnCell {
  const HomeColumnCell({required this.units, required this.half, double? height}) : _height = height;

  final int units;
  final bool half;
  final double? _height;

  /// The box's height: the live one while a unit drag runs, else T + n·U + (n − 1)·G.
  double get height => _height ?? homeGridHeightDp(units);

  /// Under the title row, at [units].
  double get content => homeGridContentDp(units);

  @override
  bool operator ==(Object other) =>
      other is HomeColumnCell && other.units == units && other.half == half && other._height == _height;

  @override
  int get hashCode => Object.hash(units, half, _height);
}

/// Two columns: the section exactly [height] tall, its content top-aligned and cut off at the bottom (a section
/// lays out for its units; anything past them waits for a larger count).
class HomeGridBox extends StatelessWidget {
  const HomeGridBox({super.key, required this.height, required this.child});

  final double height;
  final Widget child;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: height,
    width: double.infinity,
    child: ClipRect(
      child: OverflowBox(
        alignment: AlignmentDirectional.topStart,
        minHeight: 0,
        maxHeight: double.infinity,
        child: child,
      ),
    ),
  );
}

/// A card in a fixed grid box: laid out at its own height from the top, cut off past the box (a long caption
/// never overflows the unit).
class HomeUnitClip extends StatelessWidget {
  const HomeUnitClip({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => ClipRect(
    child: OverflowBox(
      alignment: AlignmentDirectional.topStart,
      minHeight: 0,
      maxHeight: double.infinity,
      child: child,
    ),
  );
}

/// The 40dp title row every section has on the grid: the title on its centre line, then [trailing] (a paged
/// shelf's dots and ‹ ›) at its end, inside the cell's [frame] margins.
class HomeGridTitleRow extends StatelessWidget {
  const HomeGridTitleRow({super.key, required this.title, required this.frame, this.trailing});

  final String title;
  final HomeFeedFrame frame;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
    child: SizedBox(
      height: HomeGridTokens.titleRow,
      child: Row(
        children: [
          Expanded(child: HomeSectionTitle(title)),
          ?trailing,
        ],
      ),
    ),
  );
}

/// Cards on the grid (Android HomeGridCells): each [slots] entry's child exactly [rowHeight] tall, rows [rowGap]
/// apart, [columns] equal columns [gap] apart across the width, snapped to whole px ([homeGridCellX]);
/// [stretchLast]: the last card takes what is left of its row.
class HomeGridCells extends StatelessWidget {
  const HomeGridCells({
    super.key,
    required this.slots,
    required this.columns,
    required this.children,
    this.gap = HomeGridTokens.columnGap,
    this.rowHeight = HomeGridTokens.unit,
    this.rowGap = HomeGridTokens.gap,
    this.stretchLast = false,
  });

  final List<HomeGridCell> slots;
  final int columns;

  /// One per slot, in order.
  final List<Widget> children;
  final double gap;
  final double rowHeight;
  final double rowGap;
  final bool stretchLast;

  @override
  Widget build(BuildContext context) {
    final rows = slots.isEmpty ? 0 : slots.map((s) => s.row).reduce(math.max) + 1;
    final height = rows <= 0 ? 0.0 : rows * rowHeight + (rows - 1) * rowGap;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth.isFinite ? constraints.maxWidth : 0.0;
        final rtl = Directionality.of(context) == TextDirection.rtl;
        return SizedBox(
          width: width,
          height: height,
          child: Stack(
            children: [
              for (var i = 0; i < slots.length && i < children.length; i++)
                () {
                  final slot = slots[i];
                  final span = stretchLast && i == slots.length - 1 ? columns - slot.column : slot.span;
                  final (left, w) = homeGridCellX(
                    slot.column,
                    span.clamp(1, math.max(1, columns - slot.column)),
                    columns,
                    width.floor(),
                    gap.round(),
                  );
                  return Positioned(
                    left: rtl ? width - left - w : left.toDouble(),
                    top: slot.row * (rowHeight + rowGap),
                    width: w.toDouble(),
                    height: rowHeight,
                    child: HomeUnitClip(child: children[i]),
                  );
                }(),
            ],
          ),
        );
      },
    );
  }
}

/// A titled shelf on the grid (Android HomeShelf on the grid, HomeShelfPager.kt): in a full row the free
/// shelf, bleeding to the window's edge; in half a row ([half]) it pages: each page a whole number of cards,
/// the gaps stretched so they meet the column's edges, a swipe turns one page and snaps (past 400dp/s, else to
/// the nearer page, never more than one), and the title row carries the page dots and ‹ ›. Every card is
/// [cardWidth] wide and [height] tall; [cardCount] cards (columns) come from [cardBuilder]. [lead]: one item a
/// whole page wide first (Recently Added's track grid), built for a width. [scrollable] false (edit mode): the
/// shelf stays where it is.
class HomeGridShelf extends StatefulWidget {
  const HomeGridShelf({
    super.key,
    required this.title,
    required this.frame,
    required this.half,
    required this.cardWidth,
    required this.height,
    required this.cardCount,
    required this.cardBuilder,
    this.lead,
    this.fullGap = HomeShelfPagerRules.minGap,
    this.minGap = HomeGridTokens.columnGap,
    this.scrollable = true,
  });

  final String title;
  final HomeFeedFrame frame;
  final bool half;
  final double cardWidth;
  final double height;
  final int cardCount;
  final IndexedWidgetBuilder cardBuilder;
  final Widget Function(BuildContext context, double width)? lead;
  final double fullGap;
  final double minGap;
  final bool scrollable;

  @override
  State<HomeGridShelf> createState() => _HomeGridShelfState();
}

class _HomeGridShelfState extends State<HomeGridShelf> {
  final ScrollController _scroll = ScrollController();
  final _ShelfPager _pager = _ShelfPager();

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  void _onScroll() {
    final stride = _pager.stride;
    if (stride <= 0 || !_scroll.hasClients) return;
    final now = (_scroll.offset / stride).round().clamp(0, math.max(0, _pager.pages - 1)).toInt();
    if (now != _pager.page) setState(() => _pager.page = now);
  }

  @override
  void didUpdateWidget(HomeGridShelf old) {
    super.didUpdateWidget(old);
    // A resize (the column, a span change) keeps the page it rested on, re-seated at once.
    if (old.frame.content != widget.frame.content || old.cardWidth != widget.cardWidth || old.half != widget.half) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || !_scroll.hasClients || !widget.half) return;
        final page = math.min(_pager.rest, math.max(_pager.pages - 1, 0));
        _pager.rest = page;
        final to = (page * _pager.stride).clamp(0.0, _scroll.position.maxScrollExtent);
        _scroll.jumpTo(to);
      });
    }
  }

  void _turn(int page) {
    if (!_scroll.hasClients) return;
    final target = page.clamp(0, math.max(0, _pager.pages - 1)).toInt();
    _pager.rest = target;
    final to = (target * _pager.stride).clamp(0.0, _scroll.position.maxScrollExtent);
    if (MediaQuery.disableAnimationsOf(context)) {
      _scroll.jumpTo(to);
    } else {
      _scroll.position.animateTo(to, duration: const Duration(milliseconds: 420), curve: Curves.easeOutCubic);
    }
  }

  @override
  Widget build(BuildContext context) => widget.half ? _paged(context) : _free(context);

  Widget _free(BuildContext context) {
    final frame = widget.frame;
    final hasLead = widget.lead != null;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeGridTitleRow(title: widget.title, frame: frame),
        // Edit mode: the bleeding shelf is cut back to its plate.
        HomeEditShelfClip(
          escapeStart: frame.start,
          escapeEnd: frame.end,
          child: SizedBox(
            height: widget.height,
            child: ListView.separated(
              controller: _scroll,
              scrollDirection: Axis.horizontal,
              physics: widget.scrollable ? null : const NeverScrollableScrollPhysics(),
              padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
              itemCount: widget.cardCount + (hasLead ? 1 : 0),
              separatorBuilder: (_, _) => SizedBox(width: widget.fullGap),
              itemBuilder: (context, i) {
                if (hasLead && i == 0) return SizedBox(width: frame.content, child: widget.lead!(context, frame.content));
                return SizedBox(width: widget.cardWidth, child: widget.cardBuilder(context, i - (hasLead ? 1 : 0)));
              },
            ),
          ),
        ),
      ],
    );
  }

  Widget _paged(BuildContext context) {
    final frame = widget.frame;
    final g = shelfPageGeometry(frame.content, widget.cardWidth, widget.minGap);
    final hasLead = widget.lead != null;
    final pages = shelfPageCount(widget.cardCount, g.perPage, hasLead ? 1 : 0);
    final filler = shelfFillerPx(widget.cardCount, g);
    _pager
      ..stride = g.stridePx
      ..pages = pages;
    final current = _pager.page.clamp(0, math.max(pages - 1, 0)).toInt();
    final children = <Widget>[
      if (hasLead) SizedBox(width: g.pagePx, child: widget.lead!(context, g.pagePx)),
      for (var i = 0; i < widget.cardCount; i++)
        SizedBox(width: g.cardPx, child: widget.cardBuilder(context, i)),
      if (filler > 0) SizedBox(width: filler),
    ];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        HomeGridTitleRow(
          title: widget.title,
          frame: frame,
          trailing: pages > 1 && widget.scrollable
              ? HomeShelfPageControls(pages: pages, current: current, onTurn: _turn)
              : null,
        ),
        Padding(
          padding: EdgeInsetsDirectional.only(start: frame.start, end: frame.end),
          child: SizedBox(
            height: widget.height,
            child: ClipRect(
              child: ListView.separated(
                controller: _scroll,
                scrollDirection: Axis.horizontal,
                physics: widget.scrollable
                    ? _ShelfPagePhysics(pager: _pager, reduced: MediaQuery.disableAnimationsOf(context))
                    : const NeverScrollableScrollPhysics(),
                itemCount: children.length,
                separatorBuilder: (_, _) => SizedBox(width: g.gapPx),
                itemBuilder: (_, i) => children[i],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// Where a paged shelf rests and is (read by its physics and its dots).
class _ShelfPager {
  /// The page the dots show (the nearest to where it is).
  int page = 0;

  /// The page it last settled on: a swipe moves one page from here.
  int rest = 0;
  double stride = 0;
  int pages = 1;
}

/// A paged shelf's release (Android ShelfPageFling, [shelfReleasePage]): one page from where it rested, in the
/// swipe's direction past 400dp/s, else to the nearer page; never more than one page away.
class _ShelfPagePhysics extends ScrollPhysics {
  const _ShelfPagePhysics({required this.pager, required this.reduced, super.parent});

  final _ShelfPager pager;
  final bool reduced;

  @override
  _ShelfPagePhysics applyTo(ScrollPhysics? ancestor) =>
      _ShelfPagePhysics(pager: pager, reduced: reduced, parent: buildParent(ancestor));

  @override
  Simulation? createBallisticSimulation(ScrollMetrics position, double velocity) {
    if (position.outOfRange || pager.stride <= 0 || pager.pages <= 1) {
      return super.createBallisticSimulation(position, velocity);
    }
    final page = shelfReleasePage(
      position.pixels,
      velocity,
      pager.stride,
      pager.rest,
      pager.pages,
      HomeShelfPagerRules.flingVelocity,
    );
    pager.rest = page;
    final to = (page * pager.stride).clamp(position.minScrollExtent, position.maxScrollExtent);
    final tolerance = toleranceFor(position);
    if ((to - position.pixels).abs() < tolerance.distance) return null;
    if (reduced) return ScrollSpringSimulation(YoinSprings.fastEffects, position.pixels, to, velocity, tolerance: tolerance);
    return ScrollSpringSimulation(YoinSprings.defaultSpatial, position.pixels, to, velocity, tolerance: tolerance);
  }

  @override
  bool get allowImplicitScrolling => false;
}

/// A paged shelf's dots and ‹ › at the end of its title row (Android ShelfPageDots / ShelfPageButton).
class HomeShelfPageControls extends StatelessWidget {
  const HomeShelfPageControls({super.key, required this.pages, required this.current, required this.onTurn});

  final int pages;
  final int current;
  final ValueChanged<int> onTurn;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = HomeStrings.of(context);
    Widget button(IconData icon, String label, bool enabled, VoidCallback onTap) => Semantics(
      button: true,
      enabled: enabled,
      label: label,
      excludeSemantics: true,
      child: Opacity(
        opacity: enabled ? 1 : HomeColumnTokens.pageButtonDisabledAlpha,
        child: InkResponse(
          onTap: enabled ? onTap : null,
          radius: HomeColumnTokens.pageButton / 2,
          child: SizedBox.square(
            dimension: HomeColumnTokens.pageButton,
            child: Icon(icon, size: HomeColumnTokens.pageGlyph, color: colors.onSurfaceVariant),
          ),
        ),
      ),
    );
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ExcludeSemantics(
          child: Row(
            mainAxisSize: MainAxisSize.min,
            spacing: HomeColumnTokens.dotGap,
            children: [
              for (var i = 0; i < pages; i++)
                AnimatedContainer(
                  duration: const Duration(milliseconds: 200),
                  width: HomeColumnTokens.dot,
                  height: HomeColumnTokens.dot,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: i == current ? colors.primary : colors.outlineVariant,
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(width: 8),
        button(YoinSymbols.chevronLeft, strings.shelfPreviousPage, current > 0, () => onTurn(current - 1)),
        button(YoinSymbols.chevronRight, strings.shelfNextPage, current < pages - 1, () => onTurn(current + 1)),
      ],
    );
  }
}

/// The span drag's dashed outline (Android HomeFeedRowLayout's): the width the dragged block will snap to, from
/// the row's leading edge (a right half's from its trailing one), at full strength while the finger is down.
class HomeSpanOutlinePainter extends CustomPainter {
  HomeSpanOutlinePainter({
    required this.color,
    required this.left,
    required this.width,
    required this.top,
    required this.bottom,
    required this.strong,
  });

  final Color color;
  final double left;
  final double width;
  final double top;
  final double bottom;
  final bool strong;

  @override
  void paint(Canvas canvas, Size size) {
    if (width <= 0) return;
    const stroke = HomeColumnTokens.outlineStroke;
    final rect = Rect.fromLTRB(left + stroke / 2, top + stroke / 2, left + width - stroke / 2, size.height - bottom - stroke / 2);
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(rect, const Radius.circular(HomeColumnTokens.outlineCorner)));
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..color = color.withValues(alpha: color.a * (strong ? 1 : .5));
    for (final metric in path.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        final end = math.min(d + HomeColumnTokens.outlineDash, metric.length);
        canvas.drawPath(metric.extractPath(d, end), paint);
        d = end + HomeColumnTokens.outlineGap;
      }
    }
  }

  @override
  bool shouldRepaint(HomeSpanOutlinePainter old) =>
      old.color != color ||
      old.left != left ||
      old.width != width ||
      old.top != top ||
      old.bottom != bottom ||
      old.strong != strong;
}
