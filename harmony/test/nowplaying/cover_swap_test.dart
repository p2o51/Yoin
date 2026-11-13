import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/cover_swap.dart';

void main() {
  test('should_keepOldUnderNew_when_songChanges', () {
    final s = CoverSwapLayers();
    final a = s.push('a')!;
    expect(a.hasBase, isFalse);
    final b = s.push('b')!;
    expect(b.hasBase, isTrue);
    expect([for (final l in s.layers) l.url], ['a', 'b'], reason: 'old drawn under new');
  });

  test('should_dropLowerLayers_when_topFullyRevealed', () {
    final s = CoverSwapLayers();
    s.push('a');
    final b = s.push('b')!;
    final dropped = s.revealed(b.id);
    expect([for (final l in dropped) l.url], ['a']);
    expect([for (final l in s.layers) l.url], ['b']);
  });

  test('should_stackOverFrozenLayer_when_skipInterruptsReveal', () {
    final s = CoverSwapLayers();
    s.push('a');
    final b = s.push('b')!;
    s.push('c');
    expect([for (final l in s.layers) l.url], ['a', 'b', 'c'], reason: 'half-revealed b stays');
    s.push('d');
    expect(s.layers, hasLength(CoverSwapLayers.maxLayers));
    expect(s.layers.first.id, b.id);
  });

  test('should_ignorePush_when_sameUrl', () {
    final s = CoverSwapLayers();
    s.push('a');
    expect(s.push('a'), isNull);
    expect(s.layers, hasLength(1));
  });
  test('should_snapFirstImage_when_nothingUnderIt', () {
    final s = CoverSwapLayers();
    final first = s.push('a')!;
    final second = s.push('b')!;
    expect(CoverSwapLayers.revealsInstantly(first, synchronous: false), isTrue, reason: 'no base: snaps');
    expect(CoverSwapLayers.revealsInstantly(second, synchronous: false), isFalse, reason: 'dissolves over a');
    expect(CoverSwapLayers.revealsInstantly(second, synchronous: true), isTrue, reason: 'memory hit snaps');
  });
}
