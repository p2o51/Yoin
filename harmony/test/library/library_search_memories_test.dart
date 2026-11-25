import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/search/memory_search.dart';
import 'package:yoin/data/search/search_page.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/library_search.dart';
import 'package:yoin/library/library_search_memories.dart';
import 'package:yoin/library/library_search_tiles.dart';
import 'package:yoin/library/search_note_playback.dart';
import 'package:yoin/platform/playback/playback_engine.dart' show PlaybackPhase;
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/library_fake_source.dart';
import '../support/nowplaying_fakes.dart';

class _Port implements MemorySearchPort {
  _Port(this.snapshot);
  MemorySearchSnapshot snapshot;
  int reads = 0;

  @override
  Future<MemorySearchSnapshot> read() async {
    reads++;
    return snapshot;
  }
}

const _album = SearchLedgerAlbum(
  profileId: 'p1',
  provider: 'subsonic',
  albumId: 'al-1',
  name: 'Rain Album',
  artist: 'Kid',
);

SearchLedgerEntry _songNote(String id, String profile, String song, String text, {int? at, int? positionMs}) =>
    SearchLedgerEntry(
      id: 'song-note:$id',
      kind: SearchLedgerKind.songNote,
      at: at ?? 100,
      profileId: profile,
      album: profile == 'p1' ? _album : null,
      text: text,
      trackTitle: 'Song $song',
      songId: song,
      songProvider: 'subsonic',
      positionMs: positionMs,
    );

final _ledger = MemorySearchSnapshot(
  activeProfileId: 'p1',
  entries: [
    _songNote('1', 'p1', 't1', 'summer rain on the roof', positionMs: 84000),
    _songNote('2', 'p2', 't9', 'summer nights elsewhere', at: 90),
    const SearchLedgerEntry(
      id: 'review:a1',
      kind: SearchLedgerKind.review,
      at: 50,
      profileId: 'p1',
      album: _album,
      text: 'a summer record',
    ),
    const SearchLedgerEntry(
      id: 'album-rating:a1',
      kind: SearchLedgerKind.albumRating,
      at: 40,
      profileId: 'p1',
      album: _album,
      score: 8.5,
    ),
  ],
);

void main() {
  group('LibrarySearchNotifier', () {
    late LibraryFakeSource source;
    late _Port port;
    late ProviderContainer c;

    setUp(() {
      source = LibraryFakeSource(searchResults: SearchResults(tracks: [LibraryFixtures.track('t1')]));
      port = _Port(_ledger);
      c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => source),
          memorySearchPortProvider.overrideWithValue(port),
        ],
      );
      c.listen(librarySearchProvider, (_, _) {});
    });
    tearDown(() => c.dispose());

    Future<void> wait(int ms) => Future<void>.delayed(Duration(milliseconds: ms));

    test('should_findEveryAccountsNotes_when_searching', () async {
      await c.read(activeSourceProvider.future);
      c.read(librarySearchProvider.notifier).search('summer');
      await wait(400);
      final state = c.read(librarySearchProvider);
      expect(state.searched, 'summer');
      expect(state.memories.notes.map((n) => n.entryId), ['song-note:1', 'song-note:2']);
      expect(state.memories.notes.last.isOtherAccount, isTrue);
      expect(state.memories.memories.single.album.key, 'p1|subsonic|al-1');
      expect(state.ledger.activeProfileId, 'p1');
    });

    test('should_dropMemories_when_queryBlank', () async {
      final search = c.read(librarySearchProvider.notifier);
      search.search('summer');
      await wait(400);
      expect(c.read(librarySearchProvider).memories.isEmpty, isFalse);
      search.search(' ');
      expect(c.read(librarySearchProvider).memories.isEmpty, isTrue);
      expect(c.read(librarySearchProvider).searched, isEmpty);
    });

    test('should_offerMemoriesChipInAndroidsOrder_when_listingFilters', () {
      expect(searchFiltersFor({Capability.searchPlaylists}, LibrarySearchScope.catalog), [
        LibrarySearchFilter.all,
        LibrarySearchFilter.songs,
        LibrarySearchFilter.albums,
        LibrarySearchFilter.memories,
        LibrarySearchFilter.artists,
        LibrarySearchFilter.playlists,
      ]);
    });
  });

  group('buildLibrarySearchPage', () {
    final results = SearchResults(
      tracks: [LibraryFixtures.track('t1'), LibraryFixtures.track('t2')],
      albums: [LibraryFixtures.album('al-1', name: 'Rain Album', artist: 'Kid')],
    );

    test('should_joinTheAccountsNoteToItsSong_when_theServiceReturnedIt', () {
      final page = buildLibrarySearchPage('summer', results, _ledger.search('summer'), _ledger);
      final songs = page.sections.firstWhere((s) => s.section == SearchSection.songs);
      final tiles = [for (final r in songs.rows) ...r.tiles];
      final joined = tiles.whereType<SearchSongTile>().single;
      expect(joined.track.id, MediaId.subsonic('t1'));
      expect(joined.note!.entryId, 'song-note:1');
      expect(joined.wide, isTrue);
      // Another account's note stands alone, badged.
      final loose = tiles.whereType<SearchNoteTile>().single;
      expect(loose.note.isOtherAccount, isTrue);
      // The album's memory words met the query: a 1×2 with its mark.
      final albums = page.sections.firstWhere((s) => s.section == SearchSection.albums);
      final album = albums.rows.single.tiles.single as SearchAlbumTile;
      expect(album.hit!.field, MemoryHitField.review);
      expect(album.memory!.mark!.kind, LedgerMarkKind.albumRating);
      expect(
        page.memories.map((t) => t.key),
        containsAll(['song:subsonic:t1', 'note:song-note:2', 'album:subsonic:al-1']),
      );
    });

    test('should_leadWithTheBestMatch_when_aNameIsTypedInFull', () {
      final page = buildLibrarySearchPage('Song t2', results, MemorySearchMatches.empty, _ledger);
      expect((page.best as SearchSongTile).track.id, MediaId.subsonic('t2'));
    });

    test('should_answerFromTheLedgerAlone_when_theServiceHasNotAnswered', () {
      final page = buildLibrarySearchPage('summer', null, _ledger.search('summer'), _ledger);
      expect(page.isEmpty, isFalse);
      expect(page.best, isNull);
    });
  });

  group('searchTileColumns', () {
    test('should_keepThreeColumns_when_phoneOrPuraXOuterScreen', () {
      expect(searchTileColumns(const YoinWindowInfo(390, 844)), 3);
      // Pura X's outer screen: a ~1:1 compact window, shorter than 480.
      expect(searchTileColumns(const YoinWindowInfo(340, 340)), 3);
    });

    test('should_followTheLibraryGrid_when_wider', () {
      expect(searchTileColumns(const YoinWindowInfo(720, 800)), 6); // a foldable's inner screen
      expect(searchTileColumns(const YoinWindowInfo(844, 390)), 7); // a landscape handset
      expect(searchTileColumns(const YoinWindowInfo(1280, 800)), 8); // a MateBook window
    });
  });

  group('seekWhenCurrent', () {
    final track = LibraryFixtures.track('t1');
    late NowPlayingFakeController controller;
    late ProviderContainer c;

    setUp(() {
      controller = NowPlayingFakeController(NowPlayingState.idle);
      c = ProviderContainer(overrides: [playbackControllerProvider.overrideWith(() => controller)]);
      c.listen(playbackControllerProvider, (_, _) {});
    });
    tearDown(() => c.dispose());

    test('should_seekOnceTheSongIsPrepared_when_itBecomesCurrent', () async {
      final sought = seekWhenCurrent(c, 't1', 84000);
      // Loading with the song's own length as a hint: not yet.
      controller.emit(NowPlayingState(queue: [track], index: 0, phase: PlaybackPhase.loading, durationMs: 180000));
      await Future<void>.delayed(Duration.zero);
      expect(controller.calls, isEmpty);
      controller.emit(NowPlayingState(queue: [track], index: 0, phase: PlaybackPhase.ready, durationMs: 180000));
      expect(await sought, isTrue);
      expect(controller.calls, ['seekTo:84000']);
    });

    test('should_giveUp_when_theSongNeverBecomesCurrent', () async {
      final sought = await seekWhenCurrent(c, 't1', 84000, timeout: const Duration(milliseconds: 20));
      expect(sought, isFalse);
      expect(controller.calls, isEmpty);
    });

    test('should_notSeek_when_theNoteIsAtTheStart', () async {
      expect(await seekWhenCurrent(c, 't1', 0), isFalse);
    });
  });
}
