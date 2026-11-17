/// The strip stack (Android ui/home/edit/HomeCarryStack.kt, port sheet §4.4,
/// §4.10): while a carry runs, every section is a strip flying between its
/// feed plate and its slot — covers, title and handle on a plate tinted
/// toward its first cover, a hole where the carried strip will land. It
/// sits in Home's page stack above the feed, inside the status tide, and
/// draws nothing and takes no touches outside a carry.
library;

import 'dart:ui' as ui;

import 'package:flutter/foundation.dart' show ValueListenable, listEquals;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import 'home_carry.dart';
import 'home_section.dart';
import 'home_strings.dart';
import 'home_ui_state.dart';
import 'widgets/backdrop_shapes.dart';
import 'widgets/cover_palette.dart';
import 'widgets/home_artwork.dart' show HomeCoverRequest;

/// One strip cover: the section's own artwork in its entity backdrop shape
/// ([kind]; null = the 4 dp thumb a track wears). [requestSize] is the size
/// its card asks the server for, so the palette its card already resolved is
/// found (critique 9): no new request, a miss leaves the strip its plate colour.
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

/// A strip cover resolved for drawing: its URL and the palette base its card already read.
@immutable
final class _ResolvedCover {
  const _ResolvedCover(this.url, this.kind, this.base);
  final String? url;
  final HomeCardKind? kind;
  final Color? base;
}

/// Whether the strip labels should be built ahead of a carry (the stack's `warmCount`).
enum StripWarmth {
  /// Edit mode at rest: build them, a few frames on.
  warm,

  /// Entering or exiting: leave them as they are.
  keep,

  /// Normal mode at rest: let them go.
  cold,
}

/// [StripWarmth] from edit mode: [editing], and P [moving] (an entry or exit in flight).
StripWarmth stripWarmth({required bool editing, required bool moving}) {
  if (editing && !moving) return StripWarmth.warm;
  if (editing || moving) return StripWarmth.keep;
  return StripWarmth.cold;
}

/// The strip stack. In edit mode at rest the first [warmCount] labels of
/// [sections] are built ahead of a carry, invisible (Home raises the count a
/// label a frame): built in the fold's first frame they would stall it. A
/// carry builds whatever is missing and otherwise changes nothing here:
/// which strip is carried is read in paint only.
///
/// Z order (critique 5): the hole and the other plates, their labels, the
/// carried strip's shadow, its plate, its label. The strips never wiggle.
class HomeCarryStack extends ConsumerWidget {
  const HomeCarryStack({
    super.key,
    required this.engine,
    required this.covers,
    this.sections = const [],
    this.warmCount,
  });

  final HomeCarryEngine engine;

  /// A section's covers, at most [HomeCarryTokens.stripCoverCount] are shown.
  final List<StripCover> Function(HomeSection section) covers;

  /// The sections to build labels for; empty = the carry's own.
  final List<HomeSection> sections;
  final ValueListenable<int>? warmCount;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final source = ref.watch(activeSourceProvider).value;
    return ListenableBuilder(
      listenable: Listenable.merge([engine, ?warmCount]),
      builder: (context, _) {
        final session = engine.session;
        final ids = sections.isEmpty ? (session?.orig ?? const <HomeSection>[]) : sections;
        // All of them once a carry runs.
        final count = session != null ? ids.length : (warmCount?.value ?? 0).clamp(0, ids.length);
        if (count <= 0) return const SizedBox.shrink();
        // The strips at a carry's own size, else at the size they would have now (only the slot top differs).
        final metrics = session?.metrics ?? engine.provisionalMetrics(ids.length);
        if (metrics == null) return const SizedBox.shrink();
        final shown = ids.take(count).toList();
        final resolved = <HomeSection, List<_ResolvedCover>>{
          for (final section in shown)
            section: [
              for (final c in covers(section).take(HomeCarryTokens.stripCoverCount))
                () {
                  final cover = c.cover;
                  final url = cover == null ? null : source?.resolveCoverUrl(cover, size: c.requestSize);
                  return _ResolvedCover(url, c.kind, url == null ? null : CoverPaletteCache.instance.peek(url)?.base);
                }(),
            ],
        };
        final colors = Theme.of(context).colorScheme;
        final palette = _StripColors(
          plate: colors.surfaceContainerHigh,
          liftedPlate: colors.surfaceContainerHighest,
          hole: colors.secondaryContainer,
          shadow: colors.shadow,
          bases: {for (final e in resolved.entries) e.key: e.value.isEmpty ? null : e.value.first.base},
        );
        return IgnorePointer(
          child: ExcludeSemantics(
            child: Stack(
              fit: StackFit.expand,
              children: [
                CustomPaint(painter: _UnderPainter(engine, palette)),
                Flow(
                  delegate: _StripFlowDelegate(engine, shown, Size(metrics.width, metrics.height)),
                  children: [
                    for (final section in shown)
                      _StripLabel(
                        key: ValueKey(section),
                        section: section,
                        width: metrics.width,
                        height: metrics.height,
                        coverSize: metrics.coverSize,
                        covers: resolved[section]!,
                      ),
                    CustomPaint(painter: _CarriedPainter(engine, palette)),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

@immutable
final class _StripColors {
  const _StripColors({
    required this.plate,
    required this.liftedPlate,
    required this.hole,
    required this.shadow,
    required this.bases,
  });

  final Color plate;
  final Color liftedPlate;
  final Color hole;
  final Color shadow;
  final Map<HomeSection, Color?> bases;
}

void _drawStripPlate(Canvas canvas, StripFrame frame, Color colour) {
  final alpha = frame.plateAlpha.clamp(0.0, 1.0);
  if (alpha <= 0) return;
  canvas.drawRRect(
    RRect.fromRectAndRadius(frame.rect, Radius.circular(frame.radius)),
    Paint()..color = colour.withValues(alpha: colour.a * alpha),
  );
}

/// The hole and every strip plate but the carried one.
class _UnderPainter extends CustomPainter {
  _UnderPainter(this.engine, this.colors) : super(repaint: engine.paint);

  final HomeCarryEngine engine;
  final _StripColors colors;

  @override
  void paint(Canvas canvas, Size size) {
    final live = engine.session;
    if (live == null) return;
    final fold = engine.fold.value;
    final holeA = HomeCarryTokens.holeAlpha * holeAlpha(fold);
    if (holeA > 0) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          holeRect(live.metrics, engine.holeY.value),
          const Radius.circular(HomeCarryTokens.plateRadius),
        ),
        Paint()..color = colors.hole.withValues(alpha: colors.hole.a * holeA),
      );
    }
    for (final section in live.ids) {
      if (section == live.carried) continue;
      final frame = engine.frameFor(section);
      if (frame == null) continue;
      _drawStripPlate(
        canvas,
        frame,
        stripPlateColor(
          carried: false,
          liftTint: 0,
          plate: colors.plate,
          liftedPlate: colors.liftedPlate,
          coverBase: colors.bases[section],
          tint: frame.tint,
        ),
      );
    }
  }

  @override
  bool shouldRepaint(_UnderPainter old) => old.engine != engine || old.colors != colors;
}

/// The carried strip's shadow, lifting to 6 dp as the strip lands, then its plate.
class _CarriedPainter extends CustomPainter {
  _CarriedPainter(this.engine, this.colors) : super(repaint: engine.paint);

  final HomeCarryEngine engine;
  final _StripColors colors;

  @override
  void paint(Canvas canvas, Size size) {
    final carried = engine.session?.carried;
    if (carried == null) return;
    final frame = engine.frameFor(carried);
    if (frame == null) return;
    final elevation = HomeCarryTokens.liftShadow * frame.shadowAlpha;
    if (elevation > 0) {
      final path = Path()..addRRect(RRect.fromRectAndRadius(frame.rect, Radius.circular(frame.radius)));
      canvas.drawShadow(path, colors.shadow, elevation, false);
    }
    _drawStripPlate(
      canvas,
      frame,
      stripPlateColor(
        carried: true,
        liftTint: engine.liftTint.value,
        plate: colors.plate,
        liftedPlate: colors.liftedPlate,
        coverBase: colors.bases[carried],
        tint: frame.tint,
      ),
    );
  }

  @override
  bool shouldRepaint(_CarriedPainter old) => old.engine != engine || old.colors != colors;
}

/// Places each label at its strip (position and alpha only, in paint), the
/// carried strip's plate layer after the others, its label last.
class _StripFlowDelegate extends FlowDelegate {
  _StripFlowDelegate(this.engine, this.sections, this.labelSize) : super(repaint: engine.paint);

  final HomeCarryEngine engine;
  final List<HomeSection> sections;
  final Size labelSize;

  @override
  BoxConstraints getConstraintsForChild(int i, BoxConstraints constraints) =>
      i < sections.length ? BoxConstraints.tight(labelSize) : BoxConstraints.tight(constraints.biggest);

  @override
  void paintChildren(FlowPaintingContext context) {
    final carried = engine.session?.carried;
    void label(int i) {
      final frame = engine.frameFor(sections[i]);
      final alpha = (frame?.labelAlpha ?? 0).clamp(0.0, 1.0);
      if (frame == null || alpha <= 0) return;
      context.paintChild(
        i,
        transform: Matrix4.translationValues(frame.labelOffset.dx, frame.labelOffset.dy, 0),
        opacity: alpha,
      );
    }

    var carriedIndex = -1;
    for (var i = 0; i < sections.length; i++) {
      if (sections[i] == carried) {
        carriedIndex = i;
        continue;
      }
      label(i);
    }
    context.paintChild(sections.length);
    if (carriedIndex >= 0) label(carriedIndex);
  }

  @override
  bool shouldRepaint(_StripFlowDelegate old) =>
      old.engine != engine || !listEquals(old.sections, sections) || old.labelSize != labelSize;

  @override
  bool shouldRelayout(_StripFlowDelegate old) => old.labelSize != labelSize || old.sections.length != sections.length;
}

/// Covers, title and handle at the slot's size (w × h_s, never interpolated).
class _StripLabel extends StatelessWidget {
  const _StripLabel({
    super.key,
    required this.section,
    required this.width,
    required this.height,
    required this.coverSize,
    required this.covers,
  });

  final HomeSection section;
  final double width;
  final double height;
  final double coverSize;
  final List<_ResolvedCover> covers;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return SizedBox(
      width: width,
      height: height,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: HomeCarryTokens.stripPadding),
        child: Row(
          spacing: HomeCarryTokens.stripContentGap,
          children: [
            if (covers.isNotEmpty) _StripCovers(covers: covers, size: coverSize),
            Expanded(
              child: Text(
                HomeStrings.of(context).sectionTitle(section),
                maxLines: 1,
                softWrap: false,
                overflow: TextOverflow.ellipsis,
                style: text.titleMedium!.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
              ),
            ),
            Icon(YoinSymbols.dragHandle, size: HomeCarryTokens.stripHandleSize, color: colors.onSurfaceVariant),
          ],
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

/// One cover in its shape, painted (no clip layer: a clipped round over
/// other content draws black wedges on HarmonyOS): the shape in the palette
/// base, the image through it as a shader once it has decoded.
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
