import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/library/library_screen.dart';
import 'package:yoin/main.dart';
import 'package:yoin/platform/storage/stores.dart';

import 'support/fake_music_source.dart';
import 'support/fake_playback_engine.dart';

/// The page's shown alpha: every Opacity above it (the shell's crossfade and Memories' Home-behind) multiplied.
double _pageOpacity(WidgetTester tester, Type page) => tester
    .widgetList<Opacity>(find.ancestor(of: find.byType(page), matching: find.byType(Opacity)))
    .fold(1.0, (a, o) => a * o.opacity);

void main() {
  testWidgets('should_showLibrary_when_libraryHalfTapped', (tester) async {
    final store = InMemoryStore();
    await tester.pumpWidget(ProviderScope(
      overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
        playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
      ],
      child: const YoinApp(),
    ));
    expect(_pageOpacity(tester, HomeScreen), 1);
    expect(_pageOpacity(tester, LibraryScreen), 0);

    await tester.tap(find.bySemanticsLabel('Library'));
    await tester.pumpAndSettle();

    expect(_pageOpacity(tester, HomeScreen), closeTo(0, 0.01));
    expect(_pageOpacity(tester, LibraryScreen), closeTo(1, 0.01));
    // No account yet: the Library offers to connect one.
    expect(find.text('Connect'), findsOneWidget);
  });
}
