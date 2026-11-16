import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/lyrics/lyrics_repository.dart';
import 'package:yoin/data/lyrics/lyrics_search.dart';
import 'package:yoin/nowplaying/lyrics_sheets.dart';

/// The lyrics search grows out of the Search key and shrinks back into it
/// (Android's ExpandedFullScreenSearchBar morph); system back takes the same
/// way out instead of cutting it.
void main() {
  const key = Rect.fromLTWH(40, 500, 52, 52);
  final applied = <LyricsSearchHit>[];

  Future<void> pump(WidgetTester tester, {List<LyricProvider>? providers}) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    applied.clear();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          lyricsSearchProvider.overrideWithValue(
            LyricsSearch(
              registry: LyricsProviderRegistry(
                providers:
                    providers ?? [_Provider('qq', const SongMatch(songId: '1', title: 'Song', artist: 'Artist'))],
              ),
            ),
          ),
        ],
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => showLyricsSearch(
                    context,
                    from: key,
                    initialQuery: 'Song Artist',
                    onApply: (hit) async {
                      applied.add(hit);
                      return true;
                    },
                  ),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
  }

  Rect surface(WidgetTester tester) =>
      tester.getRect(find.ancestor(of: find.byType(LyricsSearchSurface), matching: find.byType(DecoratedBox)).first);

  testWidgets('should_growOutOfTheKey_when_opened', (tester) async {
    await pump(tester);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    final early = surface(tester);
    expect(early.width, lessThan(400), reason: 'starts as the key');
    expect(early.top, greaterThan(100));
    await tester.pumpAndSettle();
    expect(surface(tester), const Rect.fromLTWH(0, 0, 400, 800));
    expect(find.text('Song'), findsOneWidget);
  });

  testWidgets('should_shrinkBackIntoTheKey_when_systemBackPressed', (tester) async {
    await pump(tester);
    await tester.pumpAndSettle();
    await tester.binding.handlePopRoute();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.byType(LyricsSearchSurface), findsOneWidget, reason: 'animates out, no hard cut');
    final leaving = surface(tester);
    expect(leaving.width, lessThan(400));
    await tester.pumpAndSettle();
    expect(find.byType(LyricsSearchSurface), findsNothing);
  });

  testWidgets('should_applyAndClose_when_resultPicked', (tester) async {
    await pump(tester);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Song'));
    await tester.pumpAndSettle();
    expect(applied.single.providerName, 'qq');
    expect(applied.single.lrc, '[00:01.00]lyric');
    expect(find.byType(LyricsSearchSurface), findsNothing);
  });

  testWidgets('should_sayProviderFailed_when_itCouldNotAnswer', (tester) async {
    await pump(tester, providers: [_Provider('qq', null, fails: true), _Provider('lrclib', null)]);
    await tester.pumpAndSettle();
    expect(find.text('QQ Music is unavailable right now'), findsOneWidget);
    expect(find.text('No results'), findsOneWidget);
  });
}

class _Provider extends LyricProvider {
  _Provider(this.name, this.match, {this.fails = false});

  @override
  final String name;
  final SongMatch? match;
  final bool fails;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async => match;

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    if (fails) throw const _Unavailable();
    return [?match];
  }

  @override
  Future<String?> fetchLyric(String songId) async => '[00:01.00]lyric';
}

final class _Unavailable implements Exception {
  const _Unavailable();

  @override
  String toString() => 'QQ Music is unavailable right now';
}
