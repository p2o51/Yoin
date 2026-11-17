import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/memories/track_album_linker.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/library_fake_source.dart';

/// A server that answers single songs: none here, it only has to be asked.
class _LookupSource extends LibraryFakeSource implements MusicTrackLookup {
  @override
  Future<Track?> getTrack(MediaId id) async => null;
}

void main() {
  late InMemoryStore kv;
  late ProfileRepository repo;
  late Profile a;
  late Profile b;
  late Map<String, _LookupSource> sources;

  setUp(() async {
    kv = InMemoryStore();
    repo = ProfileRepository(kv, InMemoryStore());
    b = await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'B',
      credentials: const SubsonicCredentials(baseUrl: 'https://b.test', username: 'u', password: 'p'),
    );
    // The newest account is the one in use.
    a = await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'A',
      credentials: const SubsonicCredentials(baseUrl: 'https://a.test', username: 'u', password: 'p'),
    );
    sources = {a.id: _LookupSource(), b.id: _LookupSource()};
  });

  ProviderContainer container() {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        profileRepositoryProvider.overrideWithValue(repo),
        // Each account's own server, as the app builds it for the account in use.
        activeSourceProvider.overrideWith((ref) async => sources[(await repo.activeProfile())?.id]),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('should_neverPairOneAccountsServerWithAnothersSongs_when_switchingMidRead', () async {
    final c = container();
    final linker = c.read(trackAlbumLinkerProvider);
    await c.read(memoryStoreProvider.future);
    expect((await linker.activeAccount())!.profileId, a.id);
    // The switch lands while the linker is reading the account in use.
    await repo.setActive(b.id);
    final reading = linker.activeAccount();
    c.invalidate(activeSourceProvider);
    final account = await reading;
    // Either no account (the next kick reads the settled one) or one whose server is its own.
    if (account != null) expect(account.lookup, same(sources[account.profileId]));
    await c.read(memoryStoreProvider.future);
    final settled = await linker.activeAccount();
    expect(settled!.profileId, b.id);
    expect(settled.lookup, same(sources[b.id]));
  });

  test('should_notCountTheAccountLeft_when_switchUnderWay', () async {
    final c = container();
    final linker = c.read(trackAlbumLinkerProvider);
    await c.read(memoryStoreProvider.future);
    expect(linker.isActive(a.id), isTrue);
    await repo.setActive(b.id);
    c.invalidate(activeSourceProvider);
    expect(linker.isActive(a.id), isFalse);
    await c.read(memoryStoreProvider.future);
    expect(linker.isActive(b.id), isTrue);
    expect(linker.isActive(a.id), isFalse);
  });
}
