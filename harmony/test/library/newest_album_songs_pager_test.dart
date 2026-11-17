import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/library/newest_album_songs_pager.dart';

import '../support/library_fake_source.dart';

LibraryFakeSource _source(int albumCount, {int songsPerAlbum = 2}) {
  final albums = <Album>[];
  final details = <MediaId, Album>{};
  for (var i = 0; i < albumCount; i++) {
    final tracks = [for (var s = 0; s < songsPerAlbum; s++) LibraryFixtures.track('a$i-s$s', albumId: 'a$i')];
    final a = LibraryFixtures.album('a$i', tracks: tracks);
    albums.add(LibraryFixtures.album('a$i'));
    details[a.id] = a;
  }
  return LibraryFakeSource(albums: albums, albumDetails: details);
}

void main() {
  test('should_returnSongsOfFirstTenAlbums_when_firstPage', () async {
    final source = _source(25);
    final pager = NewestAlbumSongsPager(source);

    final page = await pager.next();

    expect(page, hasLength(20));
    expect(page.first.id.rawId, 'a0-s0');
    expect(source.albumListCalls.single, (type: AlbumListType.newest, size: 10, offset: 0));
  });

  test('should_overlapFiveAlbumsAndSkipHeldOnes_when_nextPage', () async {
    final source = _source(25);
    final pager = NewestAlbumSongsPager(source);
    await pager.next();

    final page = await pager.next();

    expect(source.albumListCalls.last, (type: AlbumListType.newest, size: 15, offset: 5));
    expect(page.first.id.rawId, 'a10-s0');
    expect(page, hasLength(20));
  });

  test('should_returnEmpty_when_noMoreAlbums', () async {
    final pager = NewestAlbumSongsPager(_source(3));
    expect(await pager.next(), hasLength(6));
    expect(await pager.next(), isEmpty);
    expect(pager.ended, isTrue);
  });

  test('should_listSongOnce_when_twoAlbumsHoldIt', () async {
    final shared = LibraryFixtures.track('shared');
    final a = LibraryFixtures.album('a', tracks: [shared]);
    final b = LibraryFixtures.album('b', tracks: [shared, LibraryFixtures.track('own')]);
    final source = LibraryFakeSource(albums: [a, b], albumDetails: {a.id: a, b.id: b});

    final page = await NewestAlbumSongsPager(source).next();

    expect(page.map((t) => t.id.rawId), ['shared', 'own']);
  });

  test('should_stayInPlace_when_aReadFails', () async {
    final source = _source(12);
    source.failures['getAlbum:a3'] = const SourceNetworkException('down');
    final pager = NewestAlbumSongsPager(source);

    await expectLater(pager.next(), throwsA(isA<SourceNetworkException>()));
    source.failures.clear();
    final retry = await pager.next();

    expect(retry.first.id.rawId, 'a0-s0');
    expect(retry, hasLength(20));
  });

  test('should_dropAlbum_when_itFailsTwiceInARow', () async {
    final source = _source(4);
    source.failures['getAlbum:a1'] = const SourceNetworkException('down');
    final pager = NewestAlbumSongsPager(source);

    await expectLater(pager.next(), throwsA(isA<SourceNetworkException>()));
    final page = await pager.next();

    expect(page.map((t) => t.albumId!.rawId).toSet(), {'a0', 'a2', 'a3'});
  });

  test('should_openAtMostThreeAlbumsAtOnce_when_reading', () async {
    final source = _source(10);
    final pager = NewestAlbumSongsPager(source);
    await pager.next();
    expect(source.maxAlbumReadsInFlight, lessThanOrEqualTo(3));
  });
}
