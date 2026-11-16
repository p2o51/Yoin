import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/nowplaying/lyrics_share.dart';
import 'package:yoin/platform/share/image_share.dart';

class _Share implements ImageShare {
  _Share({this.ok = true});

  final bool ok;

  @override
  bool get canSave => true;

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async => ok;

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async => ok;
}

/// The share sheet tells Home's Lyric Cards when the lines went out (Android onKept).
void main() {
  Future<({List<String> events})> pump(WidgetTester tester, {bool ok = true}) async {
    tester.view.physicalSize = const Size(900, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final events = <String>[];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [imageShareProvider.overrideWithValue(_Share(ok: ok))],
        child: MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: LyricsShareSheet(
                snapshot: const LyricsShareSnapshot(
                  lines: [LyricRow('a line', startMs: 4000)],
                  passageStarts: {},
                  showTranslation: false,
                  songTitle: 'Song',
                  artist: 'Artist',
                  cover: null,
                ),
                onShared: () => events.add('shared'),
                onMessage: (m) => events.add('message'),
                onKept: () => events.add('kept'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return (events: events);
  }

  Future<void> tapAndWait(WidgetTester tester, Key key) async {
    await tester.runAsync(() async {
      await tester.tap(find.byKey(key));
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    await tester.pumpAndSettle();
  }

  testWidgets('should_keepTheLines_when_saved', (tester) async {
    final p = await pump(tester);
    await tapAndWait(tester, const ValueKey('lyrics-share-save'));
    expect(p.events, ['kept']);
  });

  testWidgets('should_keepTheLinesBeforeClosing_when_shared', (tester) async {
    final p = await pump(tester);
    await tapAndWait(tester, const ValueKey('lyrics-share-share'));
    expect(p.events, ['kept', 'shared']);
  });

  testWidgets('should_keepNothing_when_shareOrSaveFailed', (tester) async {
    final p = await pump(tester, ok: false);
    await tapAndWait(tester, const ValueKey('lyrics-share-save'));
    await tapAndWait(tester, const ValueKey('lyrics-share-share'));
    expect(p.events, ['message']);
  });
}
