import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/library_search.dart';
import 'package:yoin/library/newest_album_songs_pager.dart';

import '../support/library_fake_source.dart';

/// A catalogue service (Apple Music's capabilities): its library searched on
/// its own, and songs added to it.
class _CatalogSource extends LibraryFakeSource implements MusicLibraryCollection, MusicLibraryAdds {
  _CatalogSource({super.searchResults});

  SearchResults libraryResults = const SearchResults();
  final libraryQueries = <String>[];
  final Map<MediaId, LibraryMembership> members = {};
  LibraryMembership? addAnswer = LibraryMembership.added;
  final added = <MediaId>[];
  List<Track> songs = const [];

  @override
  String get provider => MediaId.providerAppleMusic;

  @override
  Set<Capability> get capabilities => const {
    Capability.search,
    Capability.catalogSearch,
    Capability.searchPlaylists,
    Capability.libraryAdd,
    Capability.librarySongs,
    Capability.playlistsRead,
  };

  @override
  Future<SearchResults> searchLibrary(String query) async {
    libraryQueries.add(query);
    return libraryResults;
  }

  @override
  Future<List<Track>> librarySongs({int size = 100, int offset = 0}) async => songs;

  @override
  Future<LibraryMembership> libraryMembership(MediaId trackId) async => members[trackId] ?? LibraryMembership.notAdded;

  @override
  Future<LibraryMembership> addToLibrary(MediaId trackId) async {
    added.add(trackId);
    final answer = addAnswer;
    if (answer == null) throw const SourceServerException('refused');
    members[trackId] = answer;
    return answer;
  }
}

Track _song(String id, [String? title]) => Track(id: MediaId(MediaId.providerAppleMusic, id), title: title ?? id);

void main() {
  late _CatalogSource source;
  late ProviderContainer c;

  setUp(() {
    source = _CatalogSource(searchResults: SearchResults(tracks: [_song('11'), _song('12')]));
    source.libraryResults = SearchResults(tracks: [_song('i.1')]);
    c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => source)]);
    c.listen(librarySearchProvider, (_, _) {});
  });
  tearDown(() => c.dispose());

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await pumpEventQueue();
    }
  }

  Future<void> searched(String query) async {
    await c.read(activeSourceProvider.future);
    await settle();
    c.read(librarySearchProvider.notifier).search(query);
    await Future<void>.delayed(LibrarySearchNotifier.debounce + const Duration(milliseconds: 50));
    await settle();
  }

  test('should_searchTheLibrary_when_scopeIsTheDefault', () async {
    await searched('x');
    final state = c.read(librarySearchProvider);
    expect(state.scope, LibrarySearchScope.library);
    expect(state.canSearchCatalog, isTrue);
    expect(source.libraryQueries, ['x']);
    expect(source.searches, isEmpty);
    expect(state.results!.tracks.single.id.rawId, 'i.1');
  });

  test('should_searchCatalogAndAskMembership_when_scopeSwitched', () async {
    source.members[MediaId(MediaId.providerAppleMusic, '12')] = LibraryMembership.added;
    await searched('x');
    c.read(librarySearchProvider.notifier).setScope(LibrarySearchScope.catalog);
    await settle();
    final state = c.read(librarySearchProvider);
    expect(source.searches, ['x']);
    expect(state.results!.tracks, hasLength(2));
    expect(state.membership[MediaId(MediaId.providerAppleMusic, '11')], LibraryMembership.notAdded);
    expect(state.membership[MediaId(MediaId.providerAppleMusic, '12')], LibraryMembership.added);
  });

  test('should_markAdded_when_addToLibraryConfirms', () async {
    await searched('x');
    final vm = c.read(librarySearchProvider.notifier)..setScope(LibrarySearchScope.catalog);
    await settle();
    await vm.addToLibrary(_song('11'));
    final state = c.read(librarySearchProvider);
    final id = MediaId(MediaId.providerAppleMusic, '11');
    expect(state.membership[id], LibraryMembership.added);
    expect(state.feedback[id], LibraryAddFeedback.added);
    expect(state.adding, isEmpty);
    // Added stays added: a second tap sends nothing.
    await vm.addToLibrary(_song('11'));
    expect(source.added, [id]);
  });

  test('should_sayWaitingOrFailed_when_appleIsSlowOrRefuses', () async {
    await searched('x');
    final vm = c.read(librarySearchProvider.notifier)..setScope(LibrarySearchScope.catalog);
    await settle();
    source.addAnswer = LibraryMembership.pending;
    await vm.addToLibrary(_song('11'));
    source.addAnswer = null;
    await vm.addToLibrary(_song('12'));
    final state = c.read(librarySearchProvider);
    expect(state.feedback[MediaId(MediaId.providerAppleMusic, '11')], LibraryAddFeedback.pending);
    expect(state.feedback[MediaId(MediaId.providerAppleMusic, '12')], LibraryAddFeedback.failed);
  });

  test('should_offerPlaylistsOnlyWhereSearchFindsThem_when_listingFilters', () {
    const subsonic = {Capability.search, Capability.playlistsRead};
    expect(searchFiltersFor(subsonic, LibrarySearchScope.library), isNot(contains(LibrarySearchFilter.playlists)));
    expect(searchFiltersFor(source.capabilities, LibrarySearchScope.catalog), contains(LibrarySearchFilter.playlists));
    expect(
      searchFiltersFor({Capability.searchPlaylists}, LibrarySearchScope.library),
      isNot(contains(LibrarySearchFilter.playlists)),
    );
  });

  test('should_sortLibrarySongsByTitle_when_serviceListsItsOwn', () async {
    source.songs = [_song('1', 'beta'), _song('2', 'Alpha'), _song('3', '晴天'), _song('4', 'alpha')];
    final pager = ServiceLibrarySongsPager(source);
    final songs = await pager.next();
    expect([for (final s in songs) s.id.rawId], ['2', '4', '1', '3']);
    expect(pager.ended, isTrue);
    expect(await pager.next(), isEmpty);
  });

  test('should_sortHanByPinyinThenByArtist_when_titlesTie', () async {
    source.songs = [
      Track(id: MediaId(MediaId.providerAppleMusic, '1'), title: 'Home', artist: 'Zed'),
      Track(id: MediaId(MediaId.providerAppleMusic, '2'), title: '晴天', artist: 'Jay'),
      Track(id: MediaId(MediaId.providerAppleMusic, '3'), title: 'Home', artist: 'Abe'),
      Track(id: MediaId(MediaId.providerAppleMusic, '4'), title: 'Rain'),
    ];
    final asked = <String>{};
    final pager = ServiceLibrarySongsPager(
      source,
      latinNames: (names) {
        asked.addAll(names);
        return Future.value({'晴天': 'qing tian'});
      },
    );
    final songs = await pager.next();
    // 晴天 files under Q (its pinyin), between Home and Rain; the two Homes by artist.
    expect([for (final s in songs) s.id.rawId], ['3', '1', '2', '4']);
    expect(asked, containsAll(['晴天', 'Jay']));
  });
}
