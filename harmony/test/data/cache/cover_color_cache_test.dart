import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/cover_color_cache.dart';
import 'package:yoin/detail/detail_palette.dart';
import 'package:yoin/home/widgets/cover_palette.dart';

void main() {
  test('should_persistAndReloadColorsAcrossSubsonicSaltRotations', () async {
    final dir = await Directory.systemTemp.createTemp('yoin_cover_colors_test_');
    addTearDown(() => dir.delete(recursive: true));

    const url1 = 'https://music.example.com/rest/getCoverArt?u=me&t=aaa&s=111&v=1.16.1&c=yoin&f=json&id=al-42&size=300';
    const url2 = 'https://music.example.com/rest/getCoverArt?u=me&t=bbb&s=222&v=1.16.1&c=yoin&f=json&id=al-42&size=300';

    final writer = CoverColorStore(directory: () async => dir, flushDelay: const Duration(milliseconds: 10));
    await writer.load();
    writer.putBackdrop(url1, 0xFF112233, 0xFF445566);
    writer.putSeed(url1, 0xFF778899);
    await writer.flushNow();

    final reader = CoverColorStore(directory: () async => dir);
    await reader.load();
    final stored = reader.get(url2);
    expect(stored, isNotNull);
    expect(stored!.baseArgb, 0xFF112233);
    expect(stored.accentArgb, 0xFF445566);
    expect(stored.seedArgb, 0xFF778899);

    final paletteCache = CoverPaletteCache(persistent: reader);
    expect(paletteCache.contains(url2), isTrue);
    expect(paletteCache.peek(url2), const BackdropColors(Color(0xFF112233), Color(0xFF445566)));
  });

  test('should_notPolluteCacheWithNull_when_peekingMissingKey', () {
    final cache = CoverPaletteCache();
    expect(cache.peek('https://example.com/missing.jpg'), isNull);
    expect(cache.contains('https://example.com/missing.jpg'), isFalse);
  });

  testWidgets('should_applyCachedSeedOnFirstFrame_when_openingDetailPalette', (tester) async {
    CoverColorStore.debugReset();
    const url1 = 'https://music.example.com/rest/getCoverArt?u=me&t=aaa&s=111&v=1.16.1&c=yoin&f=json&id=al-99&size=96';
    const url2 = 'https://music.example.com/rest/getCoverArt?u=me&t=ccc&s=333&v=1.16.1&c=yoin&f=json&id=al-99&size=96';
    rememberCoverSeed(url1, const Color(0xFFD32F2F));

    ColorScheme? firstFrameScheme;
    await tester.pumpWidget(
      MaterialApp(
        home: DetailPalette(
          coverUrl: url2,
          child: Builder(
            builder: (context) {
              firstFrameScheme ??= DetailPaletteScope.maybeOf(context)?.scheme;
              return const SizedBox.shrink();
            },
          ),
        ),
      ),
    );
    expect(firstFrameScheme, isNotNull, reason: 'cached cover seed must apply synchronously on Frame 1');
  });

  test('should_flushColorsAddedBeforeLoadCompletes_when_loadFinishes', () async {
    final dir = await Directory.systemTemp.createTemp('yoin_cover_color_preload_');
    addTearDown(() => dir.delete(recursive: true));

    final store = CoverColorStore(directory: () async => dir, flushDelay: const Duration(milliseconds: 10));
    const url = 'https://music.example/rest/getCoverArt.view?id=al-preload&size=400&t=x&s=y';
    // Computed before load() resolves (_file is still null).
    store.putSeed(url, 0xFF3366CC);
    await store.load();
    await Future<void>.delayed(const Duration(milliseconds: 50));

    final reloaded = CoverColorStore(directory: () async => dir);
    await reloaded.load();
    expect(reloaded.get(url)?.seedArgb, 0xFF3366CC);
  });
}
