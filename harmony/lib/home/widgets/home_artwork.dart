import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../app/providers.dart';
import '../../data/model/model.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import '../../ui/component/seam/seam_dissolve.dart' show SeamDissolve, SeamPrint;
import '../home_album_memory_mark.dart';
import '../home_metrics.dart';
import '../../ui/component/artwork_image.dart';
import '../../ui/component/artwork_swap.dart';
import '../../ui/component/cover_band_badges.dart';
import '../home_ui_state.dart';
import 'backdrop_shapes.dart';
import 'cover_palette.dart';
import 'elastic_press.dart';

/// Server request sizes (px): Jump Back In asks for 480, everything else 320.
abstract final class HomeCoverRequest {
  static const int jbi = 480;
  static const int standard = 320;
}

/// A cover's URL and the decoded image a card shows (shared with the palette).
@immutable
final class HomeCoverImage {
  const HomeCoverImage(this.url, this.image, {this.decodeWidth});
  final String url;
  final ImageProvider image;

  /// The px [image] is decoded at.
  final int? decodeWidth;
}

/// Resolves [cover] through the active source; decodes at [side] × dpr.
HomeCoverImage? resolveHomeCover(WidgetRef ref, BuildContext context, CoverRef? cover, {required int requestSize, required double side}) {
  if (cover == null) return null;
  // The tip pages' made-up covers need no source.
  final url = cover is CoverUrl && DemoArtwork.parse(cover.url) != null
      ? cover.url
      : ref.watch(activeSourceProvider).value?.resolveCoverUrl(cover, size: requestSize);
  if (url == null) return null;
  final px = (side * MediaQuery.devicePixelRatioOf(context)).round().clamp(1, requestSize);
  return HomeCoverImage(url, artworkImageProvider(url, cacheWidth: px), decodeWidth: px);
}

IconData homeFallbackIcon(HomeCardKind kind) => switch (kind) {
      HomeCardKind.album => YoinSymbols.album,
      HomeCardKind.song => YoinSymbols.musicNote,
      HomeCardKind.playlist => YoinSymbols.playlist,
      HomeCardKind.artist => YoinSymbols.artist,
    };

/// ExpressiveMediaArtwork: the type icon on surfaceContainerHighest (tonal
/// elevation 1), the image coming in over it on the effects spring. A failed
/// cover shows the icon while it retries ([ArtworkContent]). No border.
class HomeArtwork extends StatelessWidget {
  const HomeArtwork({super.key, required this.image, required this.kind, required this.size, this.radius = ShapeTokens.thumb, this.circle = false});

  final HomeCoverImage? image;
  final HomeCardKind kind;
  final double size;
  final double radius;
  final bool circle;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final surface = ElevationOverlay.applySurfaceTint(colors.surfaceContainerHighest, colors.surfaceTint, HomeBackdropMetrics.tonalElevation);
    final content = ColoredBox(
      color: surface,
      child: ArtworkContent(
        url: image?.url,
        decodeWidth: image?.decodeWidth,
        fallback: ArtworkFallbackIcon(homeFallbackIcon(kind), size: HomeBackdropMetrics.fallbackIcon),
      ),
    );
    return SizedBox.square(
      dimension: size,
      // Near the bar the cover breaks into the bottom field (seamDissolve).
      child: SeamDissolve(
        round: circle,
        child: circle
            ? ClipOval(child: content)
            : ClipRSuperellipse(borderRadius: BorderRadius.circular(radius), child: content),
      ),
    );
  }
}

/// WidgetBackdropArtwork: the entity shape filled with the cover's palette
/// base (fallback secondaryContainer), the cover at 72 % bottom-end on top.
/// Pressing (an enclosing [ElasticPress]) morphs the shape toward the rounded
/// Triangle on the Expressive fast spatial spring and presses the cover 0.97.
/// Artists draw a bare full-size circle cover.
///
/// An album's kind tag ([kindTag], "EP" / "Single"), its [version] tag and its Explicit "E" ([explicit])
/// sit on the shape's band above the cover, never on the art ([CoverBandBadges], PR #343).
class BackdropCover extends ConsumerWidget {
  const BackdropCover({
    super.key,
    required this.cover,
    required this.kind,
    required this.size,
    this.requestSize = HomeCoverRequest.standard,
    this.markAlbum,
    this.mark,
    this.version,
    this.explicit = false,
    this.kindTag,
    this.pressMorph = true,
  });

  /// The album's version, a tag on the band; null = none.
  final String? version;

  /// The release (or song) is marked explicit: the "E" on the band.
  final bool explicit;

  /// The release's kind worth a tag ("EP", "Single"; [coverKindTag]), first on the band.
  final String? kindTag;

  /// The shape morphs toward the Triangle while pressed. Off for Library's grid: one static shape per
  /// cover (Android pressMorph = false), a grid holds dozens of them.
  final bool pressMorph;

  final CoverRef? cover;
  final HomeCardKind kind;
  final double size;
  final int requestSize;

  /// The album whose memory mark the cover wears (home_album_memory_mark.dart),
  /// on the cover's own footprint, pressed with it; null = a bare cover.
  final MediaId? markAlbum;

  /// A mark given outright instead of Home's ([markAlbum]): Library search's,
  /// read from the memory ledger. Null with [markAlbum] null = a bare cover.
  final HomeAlbumMemoryMark? mark;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (kind == HomeCardKind.artist) {
      final image = resolveHomeCover(ref, context, cover, requestSize: requestSize, side: size);
      return HomeArtwork(image: image, kind: kind, size: size, circle: true);
    }
    final artworkSide = size * HomeBackdropMetrics.artworkFraction;
    final image = resolveHomeCover(ref, context, cover, requestSize: requestSize, side: artworkSide);
    final fallback = Theme.of(context).colorScheme.secondaryContainer;
    // The shape and its cover break up as one print (a card around it takes them into its own).
    return SeamPrint(
      child: SizedBox.square(
        dimension: size,
        child: Stack(
          children: [
            Positioned.fill(
              child: SeamDissolve(
                child: BackdropColorBuilder(
                  cacheKey: image?.url,
                  image: image?.image,
                  fallback: fallback,
                  builder: (context, base, _) => pressMorph
                      ? _MorphingShape(kind: kind, color: base)
                      : CustomPaint(painter: BackdropShapePainter(kind, base, kAlwaysDismissedAnimation)),
                ),
              ),
            ),
            if (version != null || explicit || kindTag != null)
              Positioned.fill(
                child: CoverBandBadges(
                  coverFraction: HomeBackdropMetrics.artworkFraction,
                  kind: kindTag,
                  version: version,
                  explicit: explicit,
                  stickerRoom: markAlbum != null || mark != null,
                ),
              ),
            Align(
              alignment: AlignmentDirectional.bottomEnd,
              child: _PressedArtwork(
                child: markAlbum == null && mark == null
                    ? HomeArtwork(image: image, kind: kind, size: artworkSide)
                    : Stack(
                        clipBehavior: Clip.none,
                        children: [
                          HomeArtwork(image: image, kind: kind, size: artworkSide),
                          // Its disc half over the cover's top edge at the end corner: inside the backdrop.
                          PositionedDirectional(
                            top: -HomeTokens.memoryMarkLift,
                            end: 0,
                            child: markAlbum == null
                                ? AlbumMemoryMarkSticker(mark: mark, coverUrl: image?.url, coverImage: image?.image)
                                : AlbumMemoryMarkSlot(
                                    album: markAlbum!,
                                    coverUrl: image?.url,
                                    coverImage: image?.image,
                                  ),
                          ),
                        ],
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _MorphingShape extends StatefulWidget {
  const _MorphingShape({required this.kind, required this.color});
  final HomeCardKind kind;
  final Color color;

  @override
  State<_MorphingShape> createState() => _MorphingShapeState();
}

class _MorphingShapeState extends State<_MorphingShape> with SingleTickerProviderStateMixin {
  late final SpringValue _morph = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _morph;
  }
  ValueListenable<bool>? _pressed;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final pressed = PressedScope.maybeOf(context);
    if (!identical(pressed, _pressed)) {
      _pressed?.removeListener(_onPressed);
      _pressed = pressed?..addListener(_onPressed);
    }
  }

  void _onPressed() {
    if (MediaQuery.disableAnimationsOf(context)) return;
    _morph.animateTo(_pressed!.value ? 1 : 0);
  }

  @override
  void dispose() {
    _pressed?.removeListener(_onPressed);
    _morph.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CustomPaint(
        painter: BackdropShapePainter(widget.kind, widget.color, _morph.animation),
      );
}

/// Paints [kind]'s backdrop in [color], pressed [morph] of the way to the
/// Triangle (the outline is re-resolved per frame; nothing rebuilds).
class BackdropShapePainter extends CustomPainter {
  BackdropShapePainter(this.kind, this.color, this.morph) : super(repaint: morph);

  final HomeCardKind kind;
  final Color color;
  final Animation<double> morph;

  @override
  void paint(Canvas canvas, Size size) {
    final path = BackdropShapes.pressedPath(kind, morph.value, size);
    canvas.drawPath(path, Paint()..color = color..isAntiAlias = true);
  }

  @override
  bool shouldRepaint(BackdropShapePainter old) => old.kind != kind || old.color != color || old.morph != morph;
}

/// The inner cover's own press (0.97 inside the card's 0.97).
class _PressedArtwork extends StatefulWidget {
  const _PressedArtwork({required this.child});
  final Widget child;

  @override
  State<_PressedArtwork> createState() => _PressedArtworkState();
}

class _PressedArtworkState extends State<_PressedArtwork> with SingleTickerProviderStateMixin {
  late final SpringValue _scale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _scale;
  }
  ValueListenable<bool>? _pressed;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final pressed = PressedScope.maybeOf(context);
    if (!identical(pressed, _pressed)) {
      _pressed?.removeListener(_onPressed);
      _pressed = pressed?..addListener(_onPressed);
    }
  }

  void _onPressed() {
    if (MediaQuery.disableAnimationsOf(context)) return;
    _scale.animateTo(_pressed!.value ? HomePressMetrics.pressedScale : 1);
  }

  @override
  void dispose() {
    _pressed?.removeListener(_onPressed);
    _scale.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      ScaleTransition(scale: _scale.animation, alignment: Alignment.bottomRight, child: widget.child);
}
