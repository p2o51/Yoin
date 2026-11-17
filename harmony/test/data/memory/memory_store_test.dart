import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore kv;
  final album = MediaId.subsonic('al1');
  final song = MediaId.subsonic('s1');

  setUp(() => kv = InMemoryStore());

  test('should_keepScoreAndReviewApart_when_eachWrittenAlone', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.setAlbumRating(album, 8.5, nowMs: 10);
    await store.setAlbumReview(album, '  Late-night record.  ', nowMs: 20);

    final row = (await store.albumRating(album))!;
    expect(row.rating, 8.5);
    expect(row.review, 'Late-night record.');
    expect(row.reviewUpdatedAt, 20);
    expect(row.updatedAt, 20);

    await store.setAlbumRating(album, null, nowMs: 30);
    final cleared = (await store.albumRating(album))!;
    expect(cleared.hasScore, isFalse);
    expect(cleared.review, 'Late-night record.');
    expect(cleared.reviewUpdatedAt, 20);
  });

  test('should_dropRow_when_scoreAndReviewBothCleared', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.setAlbumRating(album, 7, nowMs: 1);
    await store.setAlbumReview(album, '   ', nowMs: 2);
    await store.setAlbumRating(album, 0, nowMs: 3);
    expect(await store.albumRating(album), isNull);
  });

  test('should_keepAccountsApart_when_twoProfilesWrite', () async {
    await MemoryStore(kv, profileId: 'p1').setAlbumRating(album, 9, nowMs: 1);
    expect(await MemoryStore(kv, profileId: 'p2').albumRating(album), isNull);
    expect((await MemoryStore(kv, profileId: 'p1').albumRating(album))!.rating, 9);
  });

  test('should_persistNoteWithMoment_when_noteAdded', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.putSongNote(
      SongNote(
        id: 'n1',
        trackId: song,
        albumId: album,
        content: 'the bridge',
        createdAt: 5,
        updatedAt: 5,
        positionMs: 141000,
      ),
    );
    final reread = await MemoryStore(kv, profileId: 'p1').songNotes();
    expect(reread.single.positionMs, 141000);
    expect(reread.single.albumId, album);
    expect(jsonDecode(kv.values[MemoryStore.songNotesKey('p1')]!), isA<List<Object?>>());
  });

  test('should_deleteNote_when_replacedWithBlank', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.putSongNote(SongNote(id: 'n1', trackId: song, content: 'x', createdAt: 1, updatedAt: 1));
    await store.putSongNote(SongNote(id: 'n1', trackId: song, content: '  ', createdAt: 1, updatedAt: 2));
    expect(await store.songNotes(), isEmpty);
  });

  test('should_countEveryStartedSong_when_albumPlayed', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.recordAlbumPlay(album, atMs: 200, albumName: 'A', artistName: 'B');
    await store.recordAlbumPlay(album, atMs: 100);
    await store.recordAlbumPlay(album, atMs: 300, albumName: ' ');
    final plays = (await MemoryStore(kv, profileId: 'p1').playsOf(album))!;
    expect(plays.playCount, 3);
    expect(plays.firstPlayedAt, 100);
    expect(plays.lastPlayedAt, 300);
    expect(plays.albumName, 'A');
  });

  test('should_announceChange_when_written', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    final changes = <MemoryStoreChange>[];
    store.changes.listen(changes.add);
    await store.setAlbumReview(album, 'x', nowMs: 1);
    await store.recordAlbumPlay(album, atMs: 1);
    await pumpEventQueue();
    expect(changes, [MemoryStoreChange.albumRatings, MemoryStoreChange.plays]);
  });

  test('should_readEmpty_when_documentCorrupt', () async {
    kv.values[MemoryStore.albumRatingsKey('p1')] = '{nope';
    expect(await MemoryStore(kv, profileId: 'p1').albumRatings(), isEmpty);
  });

  test('should_storeTitleTrimmed_when_userNamesMemory', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.setTitle(album, '  The rain year  ', nowMs: 10);
    final row = (await MemoryStore(kv, profileId: 'p1').titleOf(album))!;
    expect(row.title, 'The rain year');
    expect(row.updatedAt, 10);
    expect(kv.values[MemoryStore.titlesKey('p1')], isNotNull);
    expect(await MemoryStore(kv, profileId: 'p2').titleOf(album), isNull);
  });

  test('should_keepEditTime_when_sameTitleSavedAgain', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.setTitle(album, 'Mine', nowMs: 10);
    await store.setTitle(album, ' Mine ', nowMs: 20);
    expect((await store.titleOf(album))!.updatedAt, 10);
  });

  test('should_deleteRow_when_titleBlankOrRestored', () async {
    final store = MemoryStore(kv, profileId: 'p1');
    await store.setTitle(album, 'Mine', nowMs: 10);
    await store.setTitle(album, '   ', nowMs: 20);
    expect(await store.titleOf(album), isNull);
    await store.setTitle(album, 'Again', nowMs: 30);
    await store.setTitle(album, null, nowMs: 40);
    expect(await MemoryStore(kv, profileId: 'p1').titles(), isEmpty);
  });
}
