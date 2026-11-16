import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/artwork_halftone.dart';

void main() {
  const size = Size(300, 300);

  test('should_showNothing_when_revealHasNotStarted', () {
    final h = ArtworkHalftone()..layout(size, 0, 1, pitch: 6);
    expect(h.mask.isEmpty, isTrue);
    expect(h.strips, isEmpty);
  });

  test('should_coverWholeArtwork_when_revealDone', () {
    final h = ArtworkHalftone()..layout(size, 1, 1, pitch: 6);
    expect(h.ink.isEmpty, isTrue, reason: 'no moving band left');
    final top = h.strips.map((r) => r.top).reduce((a, b) => a < b ? a : b);
    final bottom = h.strips.map((r) => r.bottom).reduce((a, b) => a > b ? a : b);
    expect(top, lessThanOrEqualTo(0));
    expect(bottom, greaterThanOrEqualTo(size.height));
    for (final strip in h.strips) {
      expect(strip.left, lessThanOrEqualTo(0));
      expect(strip.right, size.width);
    }
  });

  test('should_enterFromTheRight_when_skipForward', () {
    final h = ArtworkHalftone()..layout(size, .55, 1, pitch: 6);
    expect(h.mask.isEmpty, isFalse);
    expect(h.ink.isEmpty, isFalse, reason: 'the band is moving');
    for (final strip in h.strips) {
      expect(strip.right, size.width, reason: 'settled strips hang off the right edge');
    }
    final xs = [for (var i = 0; i < h.ink.length; i++) h.ink.centerAt(i).dx];
    expect(xs.reduce((a, b) => a + b) / xs.length, greaterThan(size.width * .3));
  });

  test('should_enterFromTheLeft_when_skipBack', () {
    final h = ArtworkHalftone()..layout(size, .55, -1, pitch: 6);
    for (final strip in h.strips) {
      expect(strip.left, 0, reason: 'settled strips hang off the left edge');
    }
  });

  test('should_developOpacity_when_progressMoves', () {
    expect(ArtworkHalftone.opacity(0), closeTo(.62, 1e-9));
    expect(ArtworkHalftone.opacity(1), closeTo(1, 1e-9));
    expect(ArtworkHalftone.opacity(.5), greaterThan(.62));
  });

  test('should_growDotStorage_when_manyDots', () {
    final dots = HalftoneDots();
    for (var i = 0; i < 1000; i++) {
      dots.add(i.toDouble(), 0, 1);
    }
    dots.add(0, 0, 0);
    expect(dots.length, 1000, reason: 'a zero radius adds nothing');
    expect(dots.centerAt(999).dx, 999);
  });
}
