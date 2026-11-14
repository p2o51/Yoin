import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

Album _album(String id, int tracks) => Album(
  id: MediaId.subsonic(id),
  name: 'Album $id',
  artist: 'Artist $id',
  year: 2020,
  songCount: tracks,
  tracks: [
    for (var i = 1; i <= tracks; i++)
      Track(id: MediaId.subsonic('$id-t$i'), title: 'Song $i', trackNumber: i, albumId: MediaId.subsonic(id)),
  ],
);

void main() {
  late InMemoryStore kv;
  late ProfileRepository repo;
  late Profile profile;
  late LibraryFakeSource source;
  final now = DateTime.utc(2026, 11, 14, 12).millisecondsSinceEpoch;

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => source),
        memoriesClockProvider.overrideWithValue(() => now),
        memoriesDayOfProvider.overrideWithValue((ms) {
          final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
          return DateTime.utc(t.year, t.month, t.day);
        }),
        memoriesRandomProvider.overrideWithValue(math.Random(7)),
        memoriesAppLanguageProvider.overrideWithValue(MemoryProseLanguage.en),
      ],
    );
    addTearDown(c.dispose);
    c.listen(memoriesViewModelProvider, (_, _) {});
    return c;
  }

  Future<MemoriesUiState> settled(ProviderContainer c) async {
    for (var i = 0; i < 50; i++) {
      await pumpEventQueue();
      final s = c.read(memoriesViewModelProvider);
      if (s is! MemoriesLoading) return s;
    }
    return c.read(memoriesViewModelProvider);
  }

  setUp(() async {
    kv = InMemoryStore();
    repo = ProfileRepository(kv, InMemoryStore());
    profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    source = LibraryFakeSource(
      albumDetails: {
        for (final a in [_album('a', 5), _album('b', 6), _album('c', 2)]) a.id: a,
      },
    );
  });

  test('should_showEmpty_when_nothingWritten', () async {
    final c = container();
    expect(await settled(c), isA<MemoriesEmpty>());
  });

  test('should_dealReviewedAlbum_when_reviewSaved', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'Rain on the window. Again.', nowMs: now - 86400000);
    await store.recordAlbumPlay(MediaId.subsonic('a'), atMs: now - 3 * 86400000);
    await store.recordAlbumPlay(MediaId.subsonic('a'), atMs: now - 86400000);

    final c = container();
    final s = await settled(c) as MemoriesContent;
    final m = s.memories.single;
    expect(m.albumName, 'Album a');
    expect(m.memoryTitle, 'Album a');
    expect(m.memoryTitleKind, MemoryTitleKind.album);
    expect(m.review!.text, 'Rain on the window. Again.');
    expect(m.yoinNarration, isNull);
    expect(m.excerptCandidates.first.text, 'Rain on the window. Again.');
    expect(m.excerptCandidates.first.attribution.detail, 'Nov 13');
    expect(m.playsInYoin, 2);
    expect(m.scoreKind, MemoryScoreKind.none);
  });

  test('should_skipShortAlbum_when_onlySinglesWritten', () async {
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(MediaId.subsonic('c'), 'Two songs.', nowMs: now);
    final c = container();
    expect(await settled(c), isA<MemoriesEmpty>());
  });

  test('should_groupNotesUnderTracks_when_diaryBuilt', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    for (final (id, track, pos, at) in [('n1', 'b-t3', 90000, 2), ('n2', 'b-t3', 30000, 3), ('n3', 'b-t1', null, 1)]) {
      await store.putSongNote(
        SongNote(
          id: id,
          trackId: MediaId.subsonic(track),
          albumId: MediaId.subsonic('b'),
          content: 'note $id',
          createdAt: at,
          updatedAt: at,
          positionMs: pos,
        ),
      );
    }
    final c = container();
    final m = (await settled(c) as MemoriesContent).memories.single;
    expect(m.noteCount, 3);
    expect(m.diaryTracks.map((t) => t.track.number), [1, 3]);
    expect(m.diaryTracks.last.notes.map((n) => n.noteId), ['n2', 'n1']);
    expect(m.yoinQuestion, 'Put together, what would you say about the album?');
  });

  test('should_refreshCardInPlace_when_albumScoreSet', () async {
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(MediaId.subsonic('a'), 'Mine.', nowMs: now);
    final c = container();
    final first = (await settled(c) as MemoriesContent).memories.single;
    expect(first.score, isNull);

    final ok = await c.read(memoriesViewModelProvider.notifier).setAlbumRating(first, 8.5);
    expect(ok, isTrue);
    final m = (c.read(memoriesViewModelProvider) as MemoriesContent).memories.single;
    expect(m.scoreKind, MemoryScoreKind.albumRating);
    expect(m.scoreText, '8.5');
  });

  test('should_keepCard_when_reviewClearedWhileOpen', () async {
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(MediaId.subsonic('a'), 'Mine.', nowMs: now);
    final c = container();
    final first = (await settled(c) as MemoriesContent).memories.single;

    await c.read(memoriesViewModelProvider.notifier).saveReview(first, '');
    final s = c.read(memoriesViewModelProvider) as MemoriesContent;
    expect(s.memories.single.albumId, first.albumId);
    expect(s.memories.single.review, isNull);
    expect(s.memories.single.yoinQuestion, isNotNull);
  });

  test('should_keepDeckAndPage_when_reopened', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'A.', nowMs: now);
    await store.setAlbumReview(MediaId.subsonic('b'), 'B.', nowMs: now);
    final c = container();
    final s = await settled(c) as MemoriesContent;
    final order = s.memories.map((m) => m.sessionId).toList();
    c.read(memoriesViewModelProvider.notifier).setCurrentPage(1);

    await c.read(memoriesViewModelProvider.notifier).ensureLoaded();
    final again = c.read(memoriesViewModelProvider) as MemoriesContent;
    expect(again.memories.map((m) => m.sessionId), order);
    expect(again.currentPage, 1);
  });

  test('should_putFocusFirst_when_openedFromHome', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'A.', nowMs: now);
    await store.setAlbumReview(MediaId.subsonic('b'), 'B.', nowMs: now);
    final c = container();
    final s = await settled(c) as MemoriesContent;
    final last = s.memories.last.sessionId;

    await c.read(memoriesViewModelProvider.notifier).ensureLoaded(focus: last);
    final focused = c.read(memoriesViewModelProvider) as MemoriesContent;
    expect(focused.memories.first.sessionId, last);
    expect(focused.currentPage, 0);
  });

  test('should_showUserTitleInSerif_when_titleSaved', () async {
    await MemoryStore(kv, profileId: profile.id).setAlbumReview(MediaId.subsonic('a'), 'Mine.', nowMs: now);
    final c = container();
    final first = (await settled(c) as MemoriesContent).memories.single;
    expect(first.memoryTitleKind, MemoryTitleKind.album);

    expect(await c.read(memoriesViewModelProvider.notifier).saveTitle(first, '  The rain year '), isTrue);
    final titled = (c.read(memoriesViewModelProvider) as MemoriesContent).memories.single;
    expect(titled.memoryTitle, 'The rain year');
    expect(titled.memoryTitleKind, MemoryTitleKind.user);
    expect(titled.isTitleSerif, isTrue);
    expect(titled.albumName, 'Album a');
    expect((await MemoryStore(kv, profileId: profile.id).titleOf(first.albumId))!.title, 'The rain year');
  });

  test('should_bringAlbumNameBack_when_titleRestored', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'Mine.', nowMs: now);
    await store.setTitle(MediaId.subsonic('a'), 'Mine', nowMs: now);
    final c = container();
    final first = (await settled(c) as MemoriesContent).memories.single;
    expect(first.memoryTitle, 'Mine');

    await c.read(memoriesViewModelProvider.notifier).restoreTitle(first);
    final m = (c.read(memoriesViewModelProvider) as MemoriesContent).memories.single;
    expect(m.memoryTitle, 'Album a');
    expect(m.memoryTitleKind, MemoryTitleKind.album);
  });
}
