import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/library/library_filter_row.dart';
import 'package:yoin/library/library_playlists_by_you.dart';
import 'package:yoin/library/library_sort.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

Playlist _p(String id, bool? mine) => Playlist(id: MediaId.subsonic(id), name: 'List $id', ownedByMe: mine);

Future<void> _pump([int times = 30]) async {
  for (var i = 0; i < times; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  group('ownership', () {
    test('should_beMixed_when_someAreMineAndSomeAreNot', () {
      expect(hasMixedOwnership([_p('a', true), _p('b', false)]), isTrue);
      expect(hasMixedOwnership([_p('a', true), _p('b', null)]), isFalse);
      expect(hasMixedOwnership([_p('a', true), _p('b', true)]), isFalse);
    });

    test('should_keepOnlyMine_when_narrowedByYou', () {
      expect(playlistsByYou([_p('a', true), _p('b', false), _p('c', null)]).map((p) => p.id.rawId), ['a']);
    });

    test('should_sortTheUsersOwn_when_playlistsAreMixed', () {
      final sorted = sortLibrary(
        LibraryListInputs(playlists: [_p('b', true), _p('a', false), _p('c', true)], nowMs: 0),
      );
      expect(sorted.playlists!.items, hasLength(3));
      expect(sorted.playlistsByYou!.items.map((i) => i.id.rawId), unorderedEquals(['b', 'c']));
      final single = sortLibrary(LibraryListInputs(playlists: [_p('a', true)], nowMs: 0));
      expect(single.playlistsByYou, isNull);
    });
  });

  group('view model', () {
    Future<ProviderContainer> library(List<Playlist> playlists) async {
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(playlists: playlists)),
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
          activityLogProvider.overrideWith((ref) async => null),
        ],
      );
      addTearDown(c.dispose);
      c.listen(libraryViewModelProvider, (_, _) {});
      await _pump(60);
      await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.playlists);
      await _pump(60);
      return c;
    }

    LibraryContent content(ProviderContainer c) => c.read(libraryViewModelProvider) as LibraryContent;

    test('should_narrowToMine_when_byYouTurnedOn', () async {
      final c = await library([_p('a', true), _p('b', false)]);
      expect(content(c).playlistsByYou.available, isTrue);
      expect(content(c).shownPlaylists!.items, hasLength(2));
      c.read(libraryViewModelProvider.notifier).selectPlaylistsByYou(true);
      expect(content(c).playlistsByYou.filtering, isTrue);
      expect(content(c).shownPlaylists!.items.map((i) => i.id.rawId), ['a']);
      // Kept through another chip and back.
      await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.artists);
      await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.playlists);
      await _pump();
      expect(content(c).playlistsByYou.selected, isTrue);
    });

    test('should_refuseByYou_when_playlistsAreOnePersons', () async {
      final c = await library([_p('a', true), _p('b', true)]);
      c.read(libraryViewModelProvider.notifier).selectPlaylistsByYou(true);
      expect(content(c).playlistsByYou, const PlaylistsByYou());
      expect(content(c).shownPlaylists!.items, hasLength(2));
    });
  });

  group('chip row', () {
    Future<void> pump(
      WidgetTester tester, {
      required LibraryTab selected,
      required PlaylistsByYou byYou,
      ValueChanged<bool>? onByYou,
    }) => tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: LibraryFilterRow(
            tabs: const [LibraryTab.playlists, LibraryTab.artists, LibraryTab.albums],
            selected: selected,
            onSelect: (_) {},
            byYou: byYou,
            onByYouChange: onByYou,
          ),
        ),
      ),
    );

    testWidgets('should_unfoldByYou_when_playlistsOnAndMixed', (tester) async {
      final toggles = <bool>[];
      await pump(
        tester,
        selected: LibraryTab.playlists,
        byYou: const PlaylistsByYou(available: true),
        onByYou: toggles.add,
      );
      await tester.pumpAndSettle();
      expect(find.text('By You'), findsOneWidget);
      await tester.tap(find.text('By You'));
      expect(toggles, [true]);
    });

    testWidgets('should_foldByYouAway_when_anotherChipIsOn', (tester) async {
      await pump(tester, selected: LibraryTab.playlists, byYou: const PlaylistsByYou(available: true, selected: true));
      await tester.pumpAndSettle();
      expect(tester.getSize(find.text('By You')).width, greaterThan(0));
      await pump(tester, selected: LibraryTab.artists, byYou: const PlaylistsByYou(available: true, selected: true));
      await tester.pumpAndSettle();
      expect(find.text('By You'), findsNothing);
    });

    testWidgets('should_offerNoByYou_when_playlistsAreNotMixed', (tester) async {
      await pump(tester, selected: LibraryTab.playlists, byYou: const PlaylistsByYou());
      await tester.pumpAndSettle();
      expect(find.text('By You'), findsNothing);
    });
  });
}
