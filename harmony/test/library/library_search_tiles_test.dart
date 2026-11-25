import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/search/memory_search.dart';
import 'package:yoin/library/library_search.dart';
import 'package:yoin/library/library_search_memories.dart';
import 'package:yoin/library/library_search_tiles.dart';
import 'package:yoin/library/library_search_view.dart';

import '../support/library_fake_source.dart';

class _Port implements MemorySearchPort {
  const _Port(this.snapshot);
  final MemorySearchSnapshot snapshot;
  @override
  Future<MemorySearchSnapshot> read() async => snapshot;
}

const _album = SearchLedgerAlbum(
  profileId: 'p1',
  provider: 'subsonic',
  albumId: 'al-1',
  name: 'Grey Album',
  artist: 'Kid',
);

const _ledger = MemorySearchSnapshot(
  activeProfileId: 'p1',
  entries: [
    SearchLedgerEntry(
      id: 'song-note:1',
      kind: SearchLedgerKind.songNote,
      at: 100,
      profileId: 'p1',
      album: _album,
      text: 'the bridge in the rain',
      trackTitle: 'Song t1',
      songId: 't1',
      songProvider: 'subsonic',
      positionMs: 84000,
    ),
  ],
);

void main() {
  late LibraryFakeSource source;

  Widget app(Widget child, {List overrides = const []}) => ProviderScope(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      memorySearchPortProvider.overrideWithValue(const _Port(_ledger)),
      ...overrides,
    ],
    child: MaterialApp(home: Scaffold(body: child)),
  );

  setUp(() => source = LibraryFakeSource(searchResults: SearchResults(tracks: [LibraryFixtures.track('t1')])));

  SearchTileActions actions({void Function(NoteSearchHit, Track?)? onNote}) => SearchTileActions(
    onSong: (_) {},
    onSongLongPress: null,
    onNote: onNote ?? (_, _) {},
    onAlbum: (_, _) {},
    onLedgerAlbum: (_) {},
    onArtist: (_) {},
    onPlaylist: (_) {},
    onShowAll: (_) {},
  );

  testWidgets('should_showTheNoteBesideItsSongAndPlayIt_when_aNoteMetTheQuery', (tester) async {
    final page = buildLibrarySearchPage(
      'rain',
      SearchResults(tracks: [LibraryFixtures.track('t1')]),
      _ledger.search('rain'),
      _ledger,
    );
    (NoteSearchHit, Track?)? opened;
    final scroll = ScrollController();
    addTearDown(scroll.dispose);
    await tester.pumpWidget(
      app(
        SearchAllTiles(
          page: page,
          query: 'rain',
          actions: actions(onNote: (note, track) => opened = (note, track)),
          controller: scroll,
          columns: 3,
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Your note'), findsOneWidget);
    expect(find.text('1:24'), findsOneWidget);
    expect(find.text('the bridge in the rain'), findsOneWidget);
    await tester.tap(find.text('the bridge in the rain'));
    expect(opened!.$1.entryId, 'song-note:1');
    expect(opened!.$2!.id, MediaId.subsonic('t1'));
  });

  testWidgets('should_sayNothingMatches_when_memoriesChipHasNoHits', (tester) async {
    final scroll = ScrollController();
    addTearDown(scroll.dispose);
    await tester.pumpWidget(
      app(SearchMemoriesList(page: LibrarySearchPage.empty, query: 'zzz', actions: actions(), controller: scroll)),
    );
    expect(find.text("Nothing you've written matches."), findsOneWidget);
  });

  testWidgets('should_listTheNoteUnderMemories_when_theChipIsChosen', (tester) async {
    // The container outlives the tree: closing the search clears it after the frame.
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        memorySearchPortProvider.overrideWithValue(const _Port(_ledger)),
      ],
    );
    addTearDown(c.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => openLibrarySearch(context, const Rect.fromLTWH(16, 8, 300, 56)),
                child: const Text('pill'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('pill'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'rain');
    await tester.pump(LibrarySearchNotifier.debounce);
    await tester.pumpAndSettle();

    // All: the note rides on its song's tile.
    expect(find.text('Your note'), findsOneWidget);
    await tester.tap(find.text('Memories'));
    await tester.pumpAndSettle();
    expect(find.text('the bridge in the rain'), findsOneWidget);
    expect(find.text('1:24'), findsOneWidget);
  });
}
