import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_grid.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/ledger/memory_ledger_view_model.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

void main() {
  final now = DateTime.utc(2026, 11, 14, 12).millisecondsSinceEpoch;
  late InMemoryStore kv;
  late ProfileRepository repo;
  late Profile profile;
  late LibraryFakeSource source;

  setUp(() async {
    kv = InMemoryStore();
    repo = ProfileRepository(kv, InMemoryStore());
    profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    source = LibraryFakeSource(
      albumDetails: {
        MediaId.subsonic('a'): Album(
          id: MediaId.subsonic('a'),
          name: 'Album a',
          artist: 'Artist a',
          songCount: 2,
          tracks: [
            Track(id: MediaId.subsonic('a-t1'), title: 'Song 1', albumId: MediaId.subsonic('a')),
            Track(id: MediaId.subsonic('a-t2'), title: 'Song 2', albumId: MediaId.subsonic('a')),
          ],
        ),
      },
    );
  });

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
        memoryLedgerFirstDayProvider.overrideWithValue(DateTime.monday),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('should_nameAlbumsFromDetail_when_ledgerLoaded', () async {
    final store = MemoryStore(kv, profileId: profile.id);
    await store.setAlbumReview(MediaId.subsonic('a'), 'Rain again.', nowMs: now - 86400000);
    await store.setTitle(MediaId.subsonic('a'), 'The rain year', nowMs: now);
    await store.putSongNote(
      SongNote(
        id: 'n1',
        trackId: MediaId.subsonic('a-t2'),
        content: 'the bridge',
        createdAt: now - 2 * 86400000,
        updatedAt: now - 2 * 86400000,
      ),
    );
    final c = container();
    final state = await c.read(memoryLedgerProvider.future);
    expect(state.entries.map((e) => e.kind), [
      MemoryLedgerKind.title,
      MemoryLedgerKind.review,
      MemoryLedgerKind.songNote,
    ]);
    expect(state.entries.first.album!.name, 'Album a');
    // A note written without its album finds it through the album's tracks.
    expect(state.entries.last.album!.albumId, MediaId.subsonic('a'));
    expect(state.entries.last.trackTitle, 'Song 2');
    expect(state.stats.writings, 2);
    expect(state.grid.scale, MemoryGridScale.month);
  });

  test('should_showNothing_when_nothingKept', () async {
    final c = container();
    final state = await c.read(memoryLedgerProvider.future);
    expect(state.isEmpty, isTrue);
  });
}
