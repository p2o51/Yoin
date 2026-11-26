/// Home edit mode's compact list (Android ui/home/edit/HomeEditList.kt; owner 2026-11-22, design.md
/// §主页 › 就地编辑 › 捏合列表): a two-finger pinch in folds the feed into one 64dp strip per section — three
/// of its own covers, its title, Hide and the drag handle — and a pinch out unfolds it, the new order already
/// in place. The strips reorder by dragging exactly as the feed's blocks do (the same [HomeDragEngine], with
/// this list as its host), a finger near the top or bottom scrolls the list, and the hidden sections and Reset
/// follow under the strips as in the feed. The header's ViewCards icon above the strips goes back to the full
/// layout with one hand. Reduced motion: the list only fades.
library;

import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show CustomSemanticsAction;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/home/home_drag_core.dart';
import '../data/model/model.dart';
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import 'home_drag_engine.dart';
import 'home_edit_gestures.dart';
import 'home_edit_lift.dart';
import 'home_metrics.dart';
import 'home_section.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/backdrop_shapes.dart';
import 'widgets/cover_palette.dart';
import 'widgets/home_artwork.dart' show HomeCoverRequest;

/// One strip cover: the section's own artwork in its entity backdrop shape ([kind]; null = the 4 dp thumb a
/// track wears). [requestSize] is the size its card asks the server for, so the palette its card already
/// resolved is found: no new request, a miss leaves the cover its plate colour.
@immutable
final class StripCover {
  const StripCover(this.cover, this.kind, {this.requestSize = HomeCoverRequest.standard});

  final CoverRef? cover;
  final HomeCardKind? kind;
  final int requestSize;

  @override
  bool operator ==(Object other) =>
      other is StripCover && other.cover == cover && other.kind == kind && other.requestSize == requestSize;

  @override
  int get hashCode => Object.hash(cover, kind, requestSize);
}

/// The header's one-hand switch between the full layout and the list (HomeEditViewToggle; the pinch stays
/// the main way): it shows where a tap goes, the list over the full layout and the cards over the list.
class HomeEditViewToggle extends StatelessWidget {
  const HomeEditViewToggle({super.key, required this.engine, required this.enabled});

  final HomeDragEngine engine;
  final bool enabled;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: engine.list,
    builder: (context, _) {
      final strings = HomeStrings.of(context);
      final on = engine.list.on;
      final label = on ? strings.editViewFull : strings.editViewList;
      return Semantics(
        container: true,
        button: true,
        label: label,
        onTap: enabled ? engine.toggleList : null,
        excludeSemantics: true,
        child: IconButton(
          tooltip: label,
          onPressed: enabled ? engine.toggleList : null,
          icon: Icon(
            on ? YoinSymbols.viewCards : YoinSymbols.viewList,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      );
    },
  );
}

/// Configures the list's own edit-gesture recognizer (Home's executor, the edit flags) around the list's
/// [hitTest] and [flinging].
typedef HomeEditListGestures =
    void Function(HomeEditGestureRecognizer recognizer, HomeEditHit Function(Offset) hitTest, bool Function() flinging);

/// The compact list over the feed while [HomeEditListMode.shown]: the page's own ground ([background])
/// fading in over the feed, the switch, one strip per section in [order], then [tray]. Put it in Home's page
/// stack at the page's origin, above the feed; it builds nothing while the list is off.
class HomeEditList extends ConsumerStatefulWidget {
  const HomeEditList({
    super.key,
    required this.engine,
    required this.order,
    required this.covers,
    required this.background,
    required this.padding,
    required this.contentStart,
    required this.contentEnd,
    required this.scrollArea,
    required this.toPage,
    required this.gestures,
    required this.onHide,
    required this.onMove,
    required this.tray,
  });

  final HomeDragEngine engine;

  /// The sections the feed draws, in order.
  final List<HomeSection> order;

  /// A section's covers; at most [HomeCarryTokens.stripCoverCount] are shown.
  final List<StripCover> Function(HomeSection section) covers;
  final List<Color> background;

  /// Room above the strips (the status bar and the header) and below them (the bar).
  final EdgeInsets padding;

  /// The page margins the strips sit within.
  final double contentStart;
  final double contentEnd;

  /// The band between the status tide and the bar, page px: its ends scroll a held strip.
  final (double, double) Function() scrollArea;

  /// Global → page px.
  final Offset Function(Offset global) toPage;
  final HomeEditListGestures gestures;
  final void Function(HomeSection section) onHide;

  /// TalkBack's Move up / Move down: [section] to [to] in [order].
  final void Function(HomeSection section, int to) onMove;
  final Widget tray;

  @override
  ConsumerState<HomeEditList> createState() => _HomeEditListState();
}

class _HomeEditListState extends ConsumerState<HomeEditList> implements HomeDragHost {
  final ScrollController _scroll = ScrollController();
  final GlobalKey _headerKey = GlobalKey();
  final GlobalKey _trayKey = GlobalKey();
  final Map<HomeSection, GlobalKey> _itemKeys = {};
  final Map<HomeSection, GlobalKey> _handleKeys = {};
  final Map<HomeSection, GlobalKey> _hideKeys = {};
  final Stopwatch _clock = Stopwatch()..start();
  int _lastScrollMs = -100000;

  HomeDragEngine get _engine => widget.engine;

  @override
  void initState() {
    super.initState();
    _engine.listHost = this;
  }

  @override
  void didUpdateWidget(HomeEditList old) {
    super.didUpdateWidget(old);
    if (!identical(old.engine, widget.engine)) {
      if (identical(old.engine.listHost, this)) old.engine.listHost = null;
      widget.engine.listHost = this;
    }
  }

  @override
  void dispose() {
    if (identical(_engine.listHost, this)) _engine.listHost = null;
    _scroll.dispose();
    super.dispose();
  }

  // ── HomeDragHost ──────────────────────────────────────────────────────

  @override
  bool get isList => true;

  @override
  List<HomeSection> order() => widget.order;

  Rect? _pageRectOf(GlobalKey? key) {
    final box = key?.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !homeBoxPlaced(box)) return null;
    final topLeft = widget.toPage(box.localToGlobal(Offset.zero));
    return topLeft & box.size;
  }

  @override
  List<DragItemBand> visibleRows() {
    final live = _engine.activeList ? _engine.arrangement?.ids : null;
    final out = <DragItemBand>[];
    for (final section in live ?? widget.order) {
      final rect = _pageRectOf(_itemKeys[section]);
      if (rect == null) continue;
      out.add(DragItemBand(section, rect.top + HomeEditListTokens.rowGap, rect.bottom));
    }
    out.sort((a, b) => a.top.compareTo(b.top));
    return out;
  }

  @override
  double? extentOf(HomeSection section) => _pageRectOf(_itemKeys[section])?.height;

  @override
  double contentLeft() {
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return rtl ? widget.contentEnd : widget.contentStart;
  }

  @override
  double contentWidth() {
    final box = context.findRenderObject() as RenderBox?;
    final width = box != null && box.hasSize ? box.size.width : 0.0;
    return math.max(0, width - widget.contentStart - widget.contentEnd);
  }

  @override
  (double, double) scrollArea() => widget.scrollArea();

  @override
  double scrollBy(double px) {
    if (!_scroll.hasClients) return 0;
    final position = _scroll.position;
    final to = (position.pixels + px).clamp(position.minScrollExtent, position.maxScrollExtent);
    final moved = to - position.pixels;
    if (moved != 0) position.jumpTo(to);
    return moved;
  }

  // ── Hit testing ───────────────────────────────────────────────────────

  HomeEditHit _hitTest(Offset p) {
    if (_pageRectOf(_headerKey)?.contains(p) ?? false) return const HomeEditHitExcluded();
    if (_pageRectOf(_trayKey)?.contains(p) ?? false) return const HomeEditHitExcluded();
    if (_hideKeys.values.any((k) => _pageRectOf(k)?.contains(p) ?? false)) return const HomeEditHitExcluded();
    HomeSection? handle;
    for (final entry in _handleKeys.entries) {
      if (_pageRectOf(entry.value)?.contains(p) ?? false) handle = entry.key;
    }
    final bands = [for (final band in visibleRows()) SectionBand(band.key, band.top, band.bottom)];
    final left = contentLeft();
    return resolveHomeEditHit(
      position: p,
      bands: bands,
      contentLeft: left,
      contentRight: left + contentWidth(),
      outset: Size.zero,
      editing: true,
      handle: handle,
    );
  }

  bool _flinging() {
    if (_scroll.hasClients && _scroll.position.isScrollingNotifier.value) return true;
    return _clock.elapsedMilliseconds - _lastScrollMs < HomeCarryTokens.flingStopMs;
  }

  // ── Build ─────────────────────────────────────────────────────────────

  @override
  Widget build(BuildContext context) {
    final engine = _engine;
    return ListenableBuilder(
      listenable: engine,
      builder: (context, _) {
        final list = engine.list;
        if (!list.shown) return const SizedBox.shrink();
        final reduced = MediaQuery.disableAnimationsOf(context);
        final source = ref.watch(activeSourceProvider).value;
        final live = engine.activeList ? engine.arrangement?.ids : null;
        final order = live ?? widget.order;
        final colors = Theme.of(context).colorScheme;
        final strings = HomeStrings.of(context);
        final locked = list.pinching || engine.isCarrying;
        List<_ResolvedCover> coversOf(HomeSection section) => [
          for (final c in widget.covers(section).take(HomeCarryTokens.stripCoverCount))
            () {
              final cover = c.cover;
              final url = cover == null ? null : source?.resolveCoverUrl(cover, size: c.requestSize);
              return _ResolvedCover(url, c.kind, url == null ? null : CoverPaletteCache.instance.peek(url)?.base);
            }(),
        ];
        // [keyed]: the strip in the list, which the hit test finds; the copy under the finger has no keys.
        Widget strip(HomeSection section, int index, {bool keyed = true}) => _HomeEditStrip(
          engine: engine,
          section: section,
          covers: coversOf(section),
          handleKey: keyed ? _handleKeys.putIfAbsent(section, GlobalKey.new) : null,
          hideKey: keyed ? _hideKeys.putIfAbsent(section, GlobalKey.new) : null,
          index: index,
          count: order.length,
          onHide: () => widget.onHide(section),
          onMoveUp: index > 0 ? () => widget.onMove(section, index - 1) : null,
          onMoveDown: index < order.length - 1 ? () => widget.onMove(section, index + 1) : null,
        );
        final scrollView = NotificationListener<ScrollUpdateNotification>(
          onNotification: (n) {
            if (n.depth == 0) _lastScrollMs = _clock.elapsedMilliseconds;
            return false;
          },
          child: CustomScrollView(
            controller: _scroll,
            physics: locked ? const NeverScrollableScrollPhysics() : null,
            slivers: [
              SliverPadding(
                padding: EdgeInsetsDirectional.only(
                  start: widget.contentStart,
                  end: widget.contentEnd,
                  top: widget.padding.top,
                ),
                sliver: SliverToBoxAdapter(
                  child: KeyedSubtree(
                    key: _headerKey,
                    child: ConstrainedBox(
                      constraints: const BoxConstraints(minHeight: HomeEditListTokens.headerHeight),
                      child: Align(
                        alignment: AlignmentDirectional.centerEnd,
                        child: HomeEditViewToggle(engine: engine, enabled: list.on),
                      ),
                    ),
                  ),
                ),
              ),
              SliverPadding(
                padding: EdgeInsetsDirectional.only(start: widget.contentStart, end: widget.contentEnd),
                sliver: SliverList.list(
                  children: [
                    for (var i = 0; i < order.length; i++)
                      Padding(
                        key: _itemKeys.putIfAbsent(order[i], GlobalKey.new),
                        padding: const EdgeInsets.only(top: HomeEditListTokens.rowGap),
                        child: AnimatedBuilder(
                          animation: engine.paint,
                          builder: (context, child) {
                            final slot = engine.slotShown(order[i], inList: true);
                            return Transform.translate(
                              offset: Offset(0, engine.makeWay(order[i], inList: true)),
                              child: slot
                                  ? CustomPaint(
                                      painter: HomeDropSlotPainter(
                                        fill: colors.surfaceContainerHighest,
                                        stroke: colors.outlineVariant,
                                      ),
                                      child: Opacity(opacity: 0, child: child),
                                    )
                                  : child,
                            );
                          },
                          child: strip(order[i], i),
                        ),
                      ),
                  ],
                ),
              ),
              SliverPadding(
                padding: EdgeInsetsDirectional.only(
                  start: widget.contentStart,
                  end: widget.contentEnd,
                  top: HomeEditListTokens.headerHeight / 2,
                  bottom: widget.padding.bottom,
                ),
                sliver: SliverToBoxAdapter(
                  child: KeyedSubtree(key: _trayKey, child: widget.tray),
                ),
              ),
            ],
          ),
        );
        // The dragged (or gliding) strip, over every row, under the finger.
        final carried = engine.dragging ?? engine.settling;
        final page = AnimatedBuilder(
          animation: Listenable.merge([list.progress.listenable, engine.paint]),
          builder: (context, child) {
            final p = list.progress.value.clamp(0.0, 1.0);
            final scale = reduced ? 1.0 : HomeEditListTokens.enterScale + (1 - HomeEditListTokens.enterScale) * p;
            final at = carried == null ? null : engine.drawnTopLeft(carried, inList: true);
            final box = context.findRenderObject() as RenderBox?;
            final origin = box != null && box.attached ? widget.toPage(box.localToGlobal(Offset.zero)) : Offset.zero;
            return Stack(
              fit: StackFit.expand,
              children: [
                // The page's own ground washes over the feed as the list comes in.
                IgnorePointer(
                  child: Opacity(
                    opacity: p,
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: widget.background,
                        ),
                      ),
                    ),
                  ),
                ),
                Opacity(
                  opacity: p,
                  child: Transform.scale(scale: scale, alignment: Alignment.topCenter, child: child),
                ),
                if (carried != null && at != null)
                  Positioned(
                    left: at.dx - origin.dx,
                    top: at.dy - origin.dy,
                    width: contentWidth(),
                    child: IgnorePointer(child: strip(carried, order.indexOf(carried), keyed: false)),
                  ),
              ],
            );
          },
          child: scrollView,
        );
        return IgnorePointer(
          ignoring: !list.on,
          child: Semantics(
            // The list stands in for the feed while on.
            explicitChildNodes: true,
            child: RawGestureDetector(
              behavior: HitTestBehavior.opaque,
              gestures: {
                HomeEditGestureRecognizer: GestureRecognizerFactoryWithHandlers<HomeEditGestureRecognizer>(
                  () => HomeEditGestureRecognizer(debugOwner: this),
                  (r) {
                    widget.gestures(r, _hitTest, _flinging);
                    r.slop = MediaQuery.gestureSettingsOf(context).touchSlop ?? kTouchSlop;
                  },
                ),
              },
              child: Semantics(label: strings.editViewList, container: true, child: page),
            ),
          ),
        );
      },
    );
  }
}

/// Rule 4: a light frame where the dragged block will land, at its slot's size and place (the lifted plate
/// colour at [HomeCarryTokens.slotFillAlpha] and a [HomeCarryTokens.slotStroke] outline), while the block
/// itself follows the finger. [rect] is the plate's box within the painter's (null: the whole box).
class HomeDropSlotPainter extends CustomPainter {
  HomeDropSlotPainter({required this.fill, required this.stroke, this.inset = EdgeInsets.zero});

  final Color fill;
  final Color stroke;

  /// How far the plate reaches past the painter's box (negative insets reach out).
  final EdgeInsets inset;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = inset.deflateRect(Offset.zero & size);
    const radius = Radius.circular(HomeCarryTokens.plateRadius);
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndRadius(rect, radius),
      Paint()..color = fill.withValues(alpha: fill.a * HomeCarryTokens.slotFillAlpha),
    );
    const w = HomeCarryTokens.slotStroke;
    canvas.drawRSuperellipse(
      RSuperellipse.fromRectAndRadius(rect.deflate(w / 2), const Radius.circular(HomeCarryTokens.plateRadius - w / 2)),
      Paint()
        ..color = stroke
        ..style = PaintingStyle.stroke
        ..strokeWidth = w,
    );
  }

  @override
  bool shouldRepaint(HomeDropSlotPainter old) => old.fill != fill || old.stroke != stroke || old.inset != inset;
}

/// A strip cover resolved for drawing: its URL and the palette base its card already read.
@immutable
final class _ResolvedCover {
  const _ResolvedCover(this.url, this.kind, this.base);
  final String? url;
  final HomeCardKind? kind;
  final Color? base;
}

/// One section as a strip: its covers, its title, Hide and the drag handle. It lifts as the feed's blocks do
/// (scale, shadow, plate tint). One stop for a screen reader: "Section j of N", Move up / Move down, Hide.
class _HomeEditStrip extends StatelessWidget {
  const _HomeEditStrip({
    required this.engine,
    required this.section,
    required this.covers,
    required this.handleKey,
    required this.hideKey,
    required this.index,
    required this.count,
    required this.onHide,
    required this.onMoveUp,
    required this.onMoveDown,
  });

  final HomeDragEngine engine;
  final HomeSection section;
  final List<_ResolvedCover> covers;
  final GlobalKey? handleKey;
  final GlobalKey? hideKey;
  final int index;
  final int count;
  final VoidCallback onHide;
  final VoidCallback? onMoveUp;
  final VoidCallback? onMoveDown;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = HomeStrings.of(context);
    final title = strings.sectionTitle(section);
    final fontScale = math.max(1.0, MediaQuery.textScalerOf(context).scale(1));
    final coverSize = (HomeEditListTokens.rowHeight - HomeCarryTokens.stripCoverInset).clamp(
      HomeCarryTokens.stripCoverMin,
      HomeCarryTokens.stripCoverMax,
    );
    return Semantics(
      container: true,
      label: '$title, ${strings.sectionIndexCd(index + 1, count)}',
      customSemanticsActions: {
        CustomSemanticsAction(label: strings.editMoveUp): ?onMoveUp,
        CustomSemanticsAction(label: strings.editMoveDown): ?onMoveDown,
        CustomSemanticsAction(label: strings.editHide): onHide,
      },
      child: AnimatedBuilder(
        animation: Listenable.merge([engine.lift.listenable, engine.liftTint.listenable]),
        builder: (context, child) {
          final lifted = engine.liftSection == section;
          final lift = lifted ? engine.lift.value.clamp(0.0, 1.0) : 0.0;
          final tint = lifted ? engine.liftTint.value.clamp(0.0, 1.0) : 0.0;
          return Transform.scale(
            scale: liftScale(lift),
            child: DecoratedBox(
              decoration: ShapeDecoration(
                color: Color.lerp(colors.surfaceContainerHigh, colors.surfaceContainerHighest, tint),
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(HomeCarryTokens.plateRadius)),
                shadows: lift <= 0
                    ? null
                    : [
                        BoxShadow(
                          color: colors.shadow.withValues(alpha: HomeEditMetrics.liftShadowAlpha * lift),
                          blurRadius: HomeEditMetrics.liftShadow * 2 * lift,
                          offset: Offset(0, HomeEditMetrics.liftShadow / 2 * lift),
                        ),
                      ],
              ),
              child: child,
            ),
          );
        },
        child: SizedBox(
          height: HomeEditListTokens.rowHeight * fontScale,
          child: Padding(
            padding: const EdgeInsetsDirectional.only(
              start: HomeCarryTokens.stripPadding,
              end: HomeEditListTokens.stripEndPadding,
            ),
            child: Row(
              spacing: HomeCarryTokens.stripContentGap,
              children: [
                if (covers.isNotEmpty) _StripCovers(covers: covers, size: coverSize),
                Expanded(
                  child: ExcludeSemantics(
                    child: Text(
                      title,
                      maxLines: 1,
                      softWrap: false,
                      overflow: TextOverflow.ellipsis,
                      style: text.titleMedium!.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
                    ),
                  ),
                ),
                KeyedSubtree(
                  key: hideKey,
                  child: Semantics(
                    container: true,
                    button: true,
                    label: strings.hideSectionCd(title),
                    onTap: onHide,
                    excludeSemantics: true,
                    child: SizedBox.square(
                      dimension: HomeEditListTokens.hideButtonSize,
                      child: IconButton.filledTonal(
                        padding: EdgeInsets.zero,
                        tooltip: strings.hideSectionCd(title),
                        style: IconButton.styleFrom(
                          backgroundColor: colors.surfaceContainerHighest,
                          foregroundColor: colors.onSurface,
                          minimumSize: const Size.square(HomeEditListTokens.hideButtonSize),
                          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        ),
                        onPressed: onHide,
                        icon: const Icon(YoinSymbols.visibilityOff, size: HomeEditListTokens.hideIconSize),
                      ),
                    ),
                  ),
                ),
                ExcludeSemantics(
                  child: SizedBox.square(
                    key: handleKey,
                    dimension: HomeEditListTokens.handleTouch,
                    child: Icon(
                      YoinSymbols.dragHandle,
                      size: HomeCarryTokens.stripHandleSize,
                      color: colors.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Up to three covers, each overlapping the one before by 30 %; the first on top. No borders.
class _StripCovers extends StatelessWidget {
  const _StripCovers({required this.covers, required this.size});

  final List<_ResolvedCover> covers;
  final double size;

  @override
  Widget build(BuildContext context) {
    final step = size * (1 - HomeCarryTokens.stripCoverOverlap);
    final fallback = Theme.of(context).colorScheme.surfaceContainerHighest;
    return SizedBox(
      width: size + step * (covers.length - 1),
      height: size,
      child: Stack(
        children: [
          // The last first: the first cover on top.
          for (var i = covers.length - 1; i >= 0; i--)
            PositionedDirectional(
              start: step * i,
              top: 0,
              width: size,
              height: size,
              child: _StripCoverArt(cover: covers[i], fallback: fallback, size: size),
            ),
        ],
      ),
    );
  }
}

/// One cover in its shape, painted (no clip layer: a clipped round over other content draws black wedges on
/// HarmonyOS): the shape in the palette base, the image through it as a shader once it has decoded.
class _StripCoverArt extends StatefulWidget {
  const _StripCoverArt({required this.cover, required this.fallback, required this.size});

  final _ResolvedCover cover;
  final Color fallback;
  final double size;

  @override
  State<_StripCoverArt> createState() => _StripCoverArtState();
}

class _StripCoverArtState extends State<_StripCoverArt> {
  ImageStream? _stream;
  ImageStreamListener? _listener;
  String? _request;
  ui.Image? _image;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _resolve();
  }

  @override
  void didUpdateWidget(_StripCoverArt old) {
    super.didUpdateWidget(old);
    if (old.cover.url != widget.cover.url || old.size != widget.size) _resolve();
  }

  void _resolve() {
    final url = widget.cover.url;
    if (url == null) {
      _stop();
      _setImage(null);
      return;
    }
    final px = (widget.size * MediaQuery.devicePixelRatioOf(context)).round().clamp(1, 4096);
    final request = '$url@$px';
    if (request == _request && _stream != null) return;
    _stop();
    _request = request;
    final stream = artworkImageProvider(url, cacheWidth: px).resolve(createLocalImageConfiguration(context));
    final listener = ImageStreamListener((info, _) => _setImage(info.image), onError: (_, _) {});
    _stream = stream..addListener(listener);
    _listener = listener;
  }

  void _setImage(ui.Image? image) {
    if (!mounted) {
      image?.dispose();
      return;
    }
    setState(() {
      _image?.dispose();
      _image = image;
    });
  }

  void _stop() {
    final listener = _listener;
    if (listener != null) _stream?.removeListener(listener);
    _stream = null;
    _listener = null;
    _request = null;
  }

  @override
  void dispose() {
    _stop();
    _image?.dispose();
    _image = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CustomPaint(
    size: Size.square(widget.size),
    painter: _CoverPainter(kind: widget.cover.kind, fill: widget.cover.base ?? widget.fallback, image: _image),
  );
}

class _CoverPainter extends CustomPainter {
  _CoverPainter({required this.kind, required this.fill, required this.image});

  final HomeCardKind? kind;
  final Color fill;
  final ui.Image? image;

  @override
  void paint(Canvas canvas, Size size) {
    final kind = this.kind;
    final path = kind == null
        ? (Path()..addRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(ShapeTokens.thumb))))
        : BackdropShapes.pressedPath(kind, 0, size);
    canvas.drawPath(
      path,
      Paint()
        ..color = fill
        ..isAntiAlias = true,
    );
    final image = this.image;
    if (image == null) return;
    // Crop to fill, centred.
    final scale = size.width / image.width > size.height / image.height
        ? size.width / image.width
        : size.height / image.height;
    final dx = (size.width - image.width * scale) / 2;
    final dy = (size.height - image.height * scale) / 2;
    final matrix = Matrix4.identity()
      ..translateByDouble(dx, dy, 0, 1)
      ..scaleByDouble(scale, scale, 1, 1);
    canvas.drawPath(
      path,
      Paint()
        ..isAntiAlias = true
        ..shader = ImageShader(
          image,
          TileMode.clamp,
          TileMode.clamp,
          matrix.storage,
          filterQuality: FilterQuality.medium,
        ),
    );
  }

  @override
  bool shouldRepaint(_CoverPainter old) => old.kind != kind || old.fill != fill || old.image != image;
}
