import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/artwork_swap.dart';

void main() {
  test('should_keepOldUnderNew_when_songChanges', () {
    final s = ArtworkSwapLayers();
    final a = s.push('a').layer!;
    expect(s.loaded(a.id), isFalse, reason: 'nothing under the first image');
    final b = s.push('b').layer!;
    expect(s.loaded(b.id), isTrue);
    expect([for (final l in s.layers) l.key], ['a', 'b'], reason: 'old drawn under new');
  });

  test('should_dropLowerLayers_when_topFullyRevealed', () {
    final s = ArtworkSwapLayers();
    s.loaded(s.push('a').layer!.id);
    final b = s.push('b').layer!;
    s.loaded(b.id);
    final dropped = s.revealed(b.id);
    expect([for (final l in dropped) l.key], ['a']);
    expect([for (final l in s.layers) l.key], ['b']);
  });

  test('should_stackOverFrozenLayer_when_skipInterruptsReveal', () {
    final s = ArtworkSwapLayers();
    s.loaded(s.push('a').layer!.id);
    final b = s.push('b').layer!;
    s.loaded(b.id);
    s.loaded(s.push('c').layer!.id);
    expect([for (final l in s.layers) l.key], ['a', 'b', 'c'], reason: 'half-revealed b stays');
    final pushed = s.push('d');
    expect(s.layers, hasLength(ArtworkSwapLayers.maxLayers));
    expect(s.layers.first.id, b.id);
    expect([for (final l in pushed.dropped) l.key], ['a']);
  });

  test('should_dropStillLoadingLayer_when_anotherSkipArrives', () {
    final s = ArtworkSwapLayers();
    s.loaded(s.push('a').layer!.id);
    s.push('b');
    final pushed = s.push('c');
    expect([for (final l in pushed.dropped) l.key], ['b'], reason: 'nothing of b to freeze');
    expect([for (final l in s.layers) l.key], ['a', 'c']);
  });

  test('should_keepSettledCover_when_returningToItBeforeTheNextLoads', () {
    final s = ArtworkSwapLayers();
    s.loaded(s.push('a').layer!.id);
    s.push('b');
    final back = s.push('a');
    expect(back.layer, isNull);
    expect([for (final l in s.layers) l.key], ['a']);
  });

  test('should_ignorePush_when_sameUrl', () {
    final s = ArtworkSwapLayers();
    s.push('a');
    expect(s.push('a').layer, isNull);
    expect(s.layers, hasLength(1));
  });

  test('should_snapFirstImage_when_nothingUnderIt', () {
    expect(
      ArtworkSwapLayers.revealsInstantly(reveal: ArtworkReveal.dotDissolve, synchronous: false, hasBase: false),
      isTrue,
      reason: 'no base: snaps',
    );
    expect(
      ArtworkSwapLayers.revealsInstantly(reveal: ArtworkReveal.dotDissolve, synchronous: false, hasBase: true),
      isFalse,
      reason: 'dissolves over a',
    );
  });

  test('should_snapMemoryHit_when_crossfadeOnly', () {
    expect(
      ArtworkSwapLayers.revealsInstantly(reveal: ArtworkReveal.crossfade, synchronous: true, hasBase: true),
      isTrue,
    );
    expect(
      ArtworkSwapLayers.revealsInstantly(reveal: ArtworkReveal.dotDissolve, synchronous: true, hasBase: true),
      isFalse,
      reason: 'ArtworkSwap.kt: only a crossfaded thumbnail skips its reveal',
    );
  });

  test('should_revealFirstImage_when_comingInOverTheFallback', () {
    expect(
      ArtworkSwapLayers.revealsInstantly(
        reveal: ArtworkReveal.crossfade,
        synchronous: true,
        hasBase: false,
        revealFirstLoad: true,
      ),
      isFalse,
    );
  });
}
