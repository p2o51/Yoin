/// Where a cover url's pixels come from. Every cover is a network image read
/// through the app's one cover disk cache ([ArtworkImages.cache], Android's
/// Coil loader with its disk cache), except the tip pages' made-up covers
/// ([DemoArtwork], Android's tip_demo_cover_* drawables): those are painted
/// here, so the demo phone's real components have something to show without a
/// server.
library;

import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';

import '../../data/cache/cover_cache.dart';

/// The image behind [url], decoded [cacheWidth] × [cacheHeight] pixels when
/// given (one of them keeps the aspect ratio), never upscaled.
ImageProvider artworkImageProvider(String url, {int? cacheWidth, int? cacheHeight}) {
  final demo = DemoArtwork.parse(url);
  if (demo != null) return ResizeImage.resizeIfNeeded(cacheWidth, cacheHeight, demo);
  return CachedArtworkImage(url, width: cacheWidth, height: cacheHeight);
}

/// The app's cover disk cache, chosen once in main.dart ([install]); until
/// then (tests, previews) covers come straight off the network.
abstract final class ArtworkImages {
  static CoverDiskCache _cache = CoverDiskCache();

  static CoverDiskCache get cache => _cache;

  /// Makes [cache] the one every cover reads through, and opens it.
  static void install(CoverDiskCache cache) {
    _cache = cache;
    unawaited(cache.start());
  }

  @visibleForTesting
  static void debugReset() => _cache = CoverDiskCache();
}

/// [url]'s encoded cover bytes through [ArtworkImages.cache] (the service
/// cards, Android WidgetArt through the app's Coil loader): stored covers come
/// back offline. Null when it can't be had within [timeout] or is over [maxBytes].
Future<Uint8List?> loadArtworkBytes(
  String url, {
  Duration timeout = const Duration(seconds: 15),
  int maxBytes = 4 * 1024 * 1024,
}) async {
  try {
    final bytes = await ArtworkImages.cache.load(url).timeout(timeout);
    return bytes.length > maxBytes ? null : bytes;
  } catch (_) {
    return null;
  }
}

/// A network cover read through [ArtworkImages.cache] and decoded at [width] ×
/// [height] (ResizeImage's exact policy without upscaling).
///
/// Its in-memory key is the cover's [coverCacheKey] and decode size, so the
/// same cover signed with another salt is the same image. A failed load leaves
/// Flutter's image cache at once (like NetworkImage), so the next request, a
/// retry's or another screen's, loads it again; bytes that made no image also
/// leave the disk cache (Android ImageDiskCacheInterceptor).
@immutable
final class CachedArtworkImage extends ImageProvider<CachedArtworkImage> {
  CachedArtworkImage(this.url, {this.width, this.height}) : cacheKey = coverCacheKey(url);

  final String url;
  final String cacheKey;
  final int? width;
  final int? height;

  @override
  Future<CachedArtworkImage> obtainKey(ImageConfiguration configuration) => SynchronousFuture(this);

  @override
  ImageStreamCompleter loadImage(CachedArtworkImage key, ImageDecoderCallback decode) => MultiFrameImageStreamCompleter(
    codec: _load(key, decode),
    scale: 1,
    debugLabel: cacheKey,
    informationCollector: () => [DiagnosticsProperty<ImageProvider>('Image provider', this)],
  );

  Future<ui.Codec> _load(CachedArtworkImage key, ImageDecoderCallback decode) async {
    final cache = ArtworkImages.cache;
    try {
      final bytes = await cache.load(url);
      try {
        final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
        return await decode(buffer, getTargetSize: width == null && height == null ? null : _targetSize);
      } catch (error) {
        // Stored, these bytes would fail to decode on every retry.
        await cache.remove(url);
        throw ArtworkUndecodableException(error);
      }
    } catch (_) {
      scheduleMicrotask(() => PaintingBinding.instance.imageCache.evict(key));
      rethrow;
    }
  }

  ui.TargetImageSize _targetSize(int intrinsicWidth, int intrinsicHeight) {
    var w = width;
    var h = height;
    if (w != null && w > intrinsicWidth) w = intrinsicWidth;
    if (h != null && h > intrinsicHeight) h = intrinsicHeight;
    return ui.TargetImageSize(width: w, height: h);
  }

  @override
  bool operator ==(Object other) =>
      other is CachedArtworkImage && other.cacheKey == cacheKey && other.width == width && other.height == height;

  @override
  int get hashCode => Object.hash(cacheKey, width, height);

  @override
  String toString() => 'CachedArtworkImage($cacheKey, $width × $height)';
}

/// A made-up cover: `yoin-demo:cover/N` (N 0–5), painted the way Android's
/// tip_demo_cover_(N+1).xml draws it on its 100×100 viewport.
@immutable
final class DemoArtwork extends ImageProvider<DemoArtwork> {
  const DemoArtwork(this.index);

  static const String scheme = 'yoin-demo';
  static const int count = 6;

  /// The url of cover [index].
  static String url(int index) => '$scheme:cover/${index % count}';

  /// The demo cover [url] names, or null for any other url.
  static DemoArtwork? parse(String url) {
    const prefix = '$scheme:cover/';
    if (!url.startsWith(prefix)) return null;
    final index = int.tryParse(url.substring(prefix.length));
    return index == null ? null : DemoArtwork(index % count);
  }

  final int index;

  /// The edge the cover is painted at, in pixels.
  static const int _edge = 256;

  @override
  Future<DemoArtwork> obtainKey(ImageConfiguration configuration) => SynchronousFuture(this);

  @override
  ImageStreamCompleter loadImage(DemoArtwork key, ImageDecoderCallback decode) =>
      OneFrameImageStreamCompleter(_paint().then((image) => ImageInfo(image: image)));

  Future<ui.Image> _paint() {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder)..scale(_edge / 100);
    paintDemoCover(canvas, index);
    return recorder.endRecording().toImage(_edge, _edge);
  }

  @override
  bool operator ==(Object other) => other is DemoArtwork && other.index == index;

  @override
  int get hashCode => Object.hash(DemoArtwork, index);
}

/// Cover [index] on a 100×100 canvas (tip_demo_cover_1..6.xml).
void paintDemoCover(Canvas canvas, int index) {
  Paint fill(int argb) => Paint()..color = Color(argb);
  void circle(double cx, double cy, double r, int argb) => canvas.drawCircle(Offset(cx, cy), r, fill(argb));
  void rect(double l, double t, double w, double h, int argb) => canvas.drawRect(Rect.fromLTWH(l, t, w, h), fill(argb));
  switch (index % DemoArtwork.count) {
    case 0:
      rect(0, 0, 100, 100, 0xFF2F4A7A);
      circle(50, 46, 24, 0xFFF2B04C);
      rect(0, 66, 100, 8, 0xFFE8E1D0);
    case 1:
      rect(0, 0, 100, 100, 0xFFB8432F);
      canvas.drawPath(
        Path()
          ..moveTo(0, 100)
          ..lineTo(100, 0)
          ..lineTo(100, 100)
          ..close(),
        fill(0xFF3C2A4D),
      );
      circle(30, 30, 14, 0xFFF4C9A8);
    case 2:
      rect(0, 0, 100, 100, 0xFF5E8C6A);
      Path wave(double y) => Path()
        ..moveTo(0, y)
        ..quadraticBezierTo(25, y - 20, 50, y)
        ..quadraticBezierTo(75, y + 20, 100, y)
        ..lineTo(100, 100)
        ..lineTo(0, 100)
        ..close();
      canvas.drawPath(wave(60), fill(0xFFEAD9A0));
      canvas.drawPath(wave(78), fill(0xFF23372B));
    case 3:
      rect(0, 0, 100, 100, 0xFF6B4FA3);
      circle(50, 50, 36, 0xFFE38BB5);
      circle(50, 50, 12, 0xFF1F1638);
    case 4:
      rect(0, 0, 100, 100, 0xFFD9A441);
      rect(18, 18, 28, 64, 0xFF2B5C8A);
      rect(54, 40, 28, 42, 0xFFF6EFD9);
    default:
      rect(0, 0, 100, 100, 0xFF2E6E73);
      rect(0, 0, 100, 36, 0xFFE4664A);
      circle(64, 62, 20, 0xFFCFE6E2);
  }
}
