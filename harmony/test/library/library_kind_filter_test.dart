// Albums' kind filter (Android PR #343, "EP 和专辑能不能试着分开？") and the artist page's, both on the shared
// ReleaseKindFilter: offered only with two or more kinds, an unknown kind only under All.
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/release/release_marks.dart';
import 'package:yoin/detail/artist_detail_view_model.dart';
import 'package:yoin/library/library_sort.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

Album _a(String id, ReleaseType? type) => Album(id: MediaId.subsonic(id), name: 'Album $id', releaseType: type);

Future<void> _pump([int times = 60]) async {
  for (var i = 0; i < times; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  group('Library Albums', () {
    LibraryContent content(List<Album> albums) => LibraryContent(
      albums: albums,
      sorted: sortLibrary(LibraryListInputs(albums: albums, nowMs: 0)),
    );

    test('should_offerNoFilter_when_theShelfHoldsOneKind', () {
      expect(content([_a('1', ReleaseType.album), _a('2', null)]).albumKindFilters, isEmpty);
    });

    test('should_offerAllThenTheKindsPresent_when_theShelfHoldsTwoKinds', () {
      final c = content([_a('1', ReleaseType.album), _a('2', ReleaseType.ep), _a('3', null)]);
      expect(c.albumKindFilters, [ReleaseKindFilter.all, ReleaseKindFilter.albums, ReleaseKindFilter.singlesAndEps]);
    });

    test('should_listUnknownKindsOnlyUnderAll_when_filtered', () {
      final albums = [_a('1', ReleaseType.album), _a('2', ReleaseType.ep), _a('3', ReleaseType.single), _a('4', null)];
      final c = content(albums);
      expect(c.shownAlbums!.items, hasLength(4));
      final eps = LibraryContent(albums: albums, sorted: c.sorted, albumKindFilter: ReleaseKindFilter.singlesAndEps);
      expect(eps.shownAlbums!.items.map((i) => i.id.rawId), unorderedEquals(['2', '3']));
      expect(eps.shownAlbums!.sections, isEmpty, reason: 'a filtered shelf has the bare scroller');
      final albumsOnly = LibraryContent(albums: albums, sorted: c.sorted, albumKindFilter: ReleaseKindFilter.albums);
      expect(albumsOnly.shownAlbums!.items.map((i) => i.id.rawId), ['1']);
    });

    test('should_fallBackToAll_when_thePickedKindIsNoLongerOffered', () {
      final albums = [_a('1', ReleaseType.album), _a('2', null)];
      final c = LibraryContent(
        albums: albums,
        sorted: sortLibrary(LibraryListInputs(albums: albums, nowMs: 0)),
        albumKindFilter: ReleaseKindFilter.compilations,
      );
      expect(c.activeAlbumKindFilter, ReleaseKindFilter.all);
      expect(c.shownAlbums!.items, hasLength(2));
    });

    test('should_narrowTheShelf_when_aKindIsPicked', () async {
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith(
            (ref) async => LibraryFakeSource(
              albums: [_a('1', ReleaseType.album), _a('2', ReleaseType.ep), _a('3', ReleaseType.compilation)],
            ),
          ),
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
          activityLogProvider.overrideWith((ref) async => null),
        ],
      );
      addTearDown(c.dispose);
      c.listen(libraryViewModelProvider, (_, _) {});
      await _pump();
      final vm = c.read(libraryViewModelProvider.notifier);
      await vm.selectTab(LibraryTab.albums);
      await _pump();
      LibraryContent state() => c.read(libraryViewModelProvider) as LibraryContent;
      expect(state().albumKindFilters, hasLength(4));
      vm.selectAlbumKindFilter(ReleaseKindFilter.compilations);
      expect(state().shownAlbums!.items.map((i) => i.id.rawId), ['3']);
      vm.selectAlbumKindFilter(ReleaseKindFilter.all);
      expect(state().shownAlbums!.items, hasLength(3));
    });
  });

  group('artist discography', () {
    ArtistDetailContent artist(List<Album> releases, [ReleaseKindFilter filter = ReleaseKindFilter.all]) =>
        ArtistDetailContent(
          artist: ArtistDetail(id: MediaId.subsonic('ar'), name: 'Joni', albums: releases),
          releases: releases,
          filter: filter,
        );

    test('should_keepAnUnknownKindOnlyUnderAll_when_theFilterNarrows', () {
      final releases = [_a('1', ReleaseType.album), _a('2', ReleaseType.single), _a('3', null)];
      expect(artist(releases).filters, [
        ReleaseKindFilter.all,
        ReleaseKindFilter.albums,
        ReleaseKindFilter.singlesAndEps,
      ]);
      expect(artist(releases).filteredReleases, hasLength(3));
      expect(artist(releases, ReleaseKindFilter.albums).filteredReleases.map((a) => a.id.rawId), ['1']);
    });

    test('should_offerNoChips_when_everyReleaseIsOneKindOrUnknown', () {
      expect(artist([_a('1', null), _a('2', ReleaseType.album)]).filters, isEmpty);
    });
  });
}
