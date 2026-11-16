/// Where a cover url's pixels come from. Every cover is a network image,
/// except the tip pages' made-up covers ([DemoArtwork], Android's
/// tip_demo_cover_* drawables): those are painted here, so the demo phone's
/// real components have something to show without a server.
library;

import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';

/// The image behind [url], decoded [cacheWidth] pixels wide when given.
ImageProvider artworkImageProvider(String url, {int? cacheWidth}) {
  final demo = DemoArtwork.parse(url);
  final ImageProvider base = demo ?? NetworkImage(url);
  return ResizeImage.resizeIfNeeded(cacheWidth, null, base);
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
