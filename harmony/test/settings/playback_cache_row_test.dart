import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/cache/stream_cache.dart';
import 'package:yoin/offline/stream_cache_providers.dart';
import 'package:yoin/offline/offline_strings.dart';
import 'package:yoin/settings/playback_cache_row.dart';
import 'package:yoin/settings/settings_strings.dart';

/// A cache with a size the test sets; Clear empties it.
class _Cache extends StreamCache {
  _Cache(this.size) : super(directory: () async => null);

  int size;
  int clears = 0;

  @override
  int get sizeBytes => size;

  @override
  Future<void> start() async {}

  @override
  Future<void> clear() async {
    clears++;
    size = 0;
    notifyListeners();
  }
}

void main() {
  Future<_Cache> pumpRow(WidgetTester tester, int size) async {
    final cache = _Cache(size);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [streamCacheProvider.overrideWithValue(cache)],
        child: MaterialApp(home: const Scaffold(body: PlaybackCacheRow())),
      ),
    );
    return cache;
  }

  testWidgets('should_showTheSizeAndClearIt_when_theCacheHoldsSongs', (tester) async {
    final cache = await pumpRow(tester, 52428800);
    expect(find.text('Playback cache'), findsOneWidget);
    expect(find.text('50.0 MB'), findsOneWidget);
    await tester.tap(find.text('Clear'));
    await tester.pump();
    expect(cache.clears, 1);
    expect(find.text('Empty'), findsOneWidget);
    // Nothing left to clear: the button is off.
    final button = tester.widget<TextButton>(find.byType(TextButton));
    expect(button.onPressed, isNull);
  });

  testWidgets('should_offerNoClear_when_theCacheIsEmpty', (tester) async {
    final cache = await pumpRow(tester, 0);
    expect(find.text('Empty'), findsOneWidget);
    await tester.tap(find.text('Clear'));
    await tester.pump();
    expect(cache.clears, 0);
  });

  test('should_speakChinese_when_theAppIsInChinese', () {
    const zh = SettingsStrings.forLanguage(chinese: true);
    expect(zh.storageCache, '播放缓存');
    expect(zh.storageClear, '清除');
    const offline = OfflineStrings.forLanguage(chinese: true);
    expect(offline.bytes(0), '空');
    expect(offline.bytes(1536), '1.5 KB');
    expect(offline.bytes(3 * 1073741824), '3.00 GB');
  });
}
