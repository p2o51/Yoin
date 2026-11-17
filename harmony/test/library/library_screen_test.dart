import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/artist_detail_screen.dart';
import 'package:yoin/library/library_screen.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/library/library_views.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

Future<ProviderContainer> _pumpLibrary(WidgetTester tester, LibraryFakeSource source) async {
  final container = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(container.dispose);
  container.read(shellSectionProvider.notifier).select(YoinSection.library);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: const Scaffold(body: LibraryScreen()),
      ),
    ),
  );
  for (var i = 0; i < 10; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
  return container;
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  final source = LibraryFakeSource(
    artists: [LibraryFixtures.artist('ar1', name: 'Beck', albumCount: 2)],
    albums: [LibraryFixtures.album('al1', name: 'Odelay', artist: 'Beck')],
    playlists: [Playlist(id: MediaId.subsonic('pl1'), name: 'Road trip', owner: 'me')],
  );

  testWidgets('should_showMixedGrid_when_allViewLoads', (tester) async {
    await _pumpLibrary(tester, source);
    await _settle(tester);

    expect(find.text('Search library'), findsOneWidget);
    expect(find.text('Beck'), findsWidgets);
    expect(find.text('Odelay'), findsOneWidget);
    expect(find.text('Road trip'), findsOneWidget);
    expect(find.text('Recents'), findsOneWidget, reason: 'the sort row leads the All grid');
  });

  testWidgets('should_switchToArtistsAndBack_when_chipTapped', (tester) async {
    final c = await _pumpLibrary(tester, source);
    await _settle(tester);

    await tester.tap(find.text('Artists'));
    await _settle(tester);
    expect((c.read(libraryViewModelProvider) as LibraryContent).selected, LibraryTab.artists);
    expect(find.text('Odelay'), findsNothing);
    expect(find.text('2 albums'), findsOneWidget);

    await tester.tap(find.byTooltip('Clear filter'));
    await _settle(tester);
    expect((c.read(libraryViewModelProvider) as LibraryContent).selected, LibraryTab.all);
  });

  testWidgets('should_pushArtistPage_when_artistCellTapped', (tester) async {
    await _pumpLibrary(tester, source);
    await _settle(tester);
    await tester.tap(find.text('Artists'));
    await _settle(tester);

    await tester.tap(find.text('Beck'));
    await _settle(tester);

    expect(find.byType(ArtistDetailScreen), findsOneWidget);
    expect(find.byTooltip('Back'), findsOneWidget);
  });

  group('per-view scroll position (L4)', () {
    final big = LibraryFakeSource(
      artists: [for (var i = 0; i < 60; i++) LibraryFixtures.artist('ar$i', name: 'Artist $i', albumCount: 1)],
      albums: [for (var i = 0; i < 90; i++) LibraryFixtures.album('al$i', name: 'Album $i', artist: 'Artist $i')],
    );

    ScrollPosition allPosition(WidgetTester tester) => tester
        .state<ScrollableState>(
          find.descendant(of: find.byKey(libraryScrollStorageKey(LibraryTab.all)), matching: find.byType(Scrollable)),
        )
        .position;

    testWidgets('should_restoreAllOffset_when_returningFromArtists', (tester) async {
      await _pumpLibrary(tester, big);
      await _settle(tester);

      allPosition(tester).jumpTo(1500);
      await tester.pump();

      await tester.tap(find.text('Artists'));
      await _settle(tester);
      expect(find.byKey(libraryScrollStorageKey(LibraryTab.all)), findsNothing, reason: 'All has faded out and gone');

      await tester.tap(find.byTooltip('Clear filter'));
      await _settle(tester);

      expect(allPosition(tester).pixels, 1500);
    });

    testWidgets('should_keepOneAllView_when_returningBeforeFadeOutEnds', (tester) async {
      final c = await _pumpLibrary(tester, big);
      await _settle(tester);

      allPosition(tester).jumpTo(1200);
      await tester.pump();

      final vm = c.read(libraryViewModelProvider.notifier);
      vm.selectTab(LibraryTab.artists);
      await tester.pump(const Duration(milliseconds: 32));
      expect(find.byKey(libraryScrollStorageKey(LibraryTab.all)), findsOneWidget, reason: 'All is still fading out');
      vm.selectTab(LibraryTab.artists); // the same chip again clears the filter
      await tester.pump(const Duration(milliseconds: 16));
      expect(find.byKey(libraryScrollStorageKey(LibraryTab.all)), findsOneWidget, reason: 'revived, not mounted twice');
      await _settle(tester);

      expect(find.byKey(libraryScrollStorageKey(LibraryTab.all)), findsOneWidget);
      expect(allPosition(tester).pixels, 1200);
    });
  });
}
