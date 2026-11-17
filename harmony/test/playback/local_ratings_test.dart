import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/local_ratings.dart';

import '../support/playback_fakes.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

/// Counts every push and can hold the next ones at a gate.
class _GatedSource extends PlaybackFakeSource {
  _GatedSource({super.capabilities});

  int attempts = 0;
  Completer<void>? ratingGate;

  @override
  Future<void> setRating(MediaId trackId, int rating) async {
    attempts++;
    await ratingGate?.future;
    return super.setRating(trackId, rating);
  }
}

void main() {
  late InMemoryStore values;
  late ProfileRepository repo;
  late Profile profile;
  late _GatedSource source;
  final song = MediaId.subsonic('s1');

  ProviderContainer container(MusicSource s) {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => s),
      ],
    );
    c.listen(localRatingsProvider, (_, _) {});
    return c;
  }

  Future<ProviderContainer> ready(MusicSource s) async {
    final c = container(s);
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
    return c;
  }

  setUp(() async {
    values = InMemoryStore();
    repo = ProfileRepository(values, InMemoryStore());
    profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    source = _GatedSource();
  });

  test('should_pushHalfRoundedUp_when_ratingSet', () {
    expect(serverRatingFor(7.5), 4);
    expect(serverRatingFor(5.0), 3);
    expect(serverRatingFor(0.0), 0);
    expect(serverRatingFor(10), 5);
    expect(serverRatingFor(null), 0);
  });

  test('should_storeAndPush_when_ratingSet', () async {
    final c = await ready(source);
    await c.read(localRatingsProvider.notifier).setRating(song, 7.5);

    expect(source.ratingWrites, [(song, 4)]);
    final row = c.read(localRatingsProvider)[song]!;
    expect(row.rating, 7.5);
    expect(row.needsSync, isFalse);
    final stored = jsonDecode(values.values[LocalRatingStore.keyFor(profile.id)]!) as Map;
    expect((stored['subsonic:s1'] as Map)['r'], 7.5);
    c.dispose();
  });

  test('should_keepRating_when_appRestarts', () async {
    final first = await ready(source);
    await first.read(localRatingsProvider.notifier).setRating(song, 6.3);
    first.dispose();

    final second = await ready(source);
    expect(second.read(localRatingsProvider)[song]?.rating, 6.3);
    second.dispose();
  });

  test('should_keepLocalRatingAndNeedsSync_when_pushFails', () async {
    source.writeFailure = Exception('offline');
    final c = await ready(source);
    await c.read(localRatingsProvider.notifier).setRating(song, 8);

    final row = c.read(localRatingsProvider)[song]!;
    expect(row.rating, 8, reason: 'local-first: never rolled back');
    expect(row.needsSync, isTrue);
    c.dispose();
  });

  test('should_sendPendingRatings_when_accountBecomesActive', () async {
    source.writeFailure = Exception('offline');
    final first = await ready(source);
    await first.read(localRatingsProvider.notifier).setRating(song, 9);
    first.dispose();

    source.writeFailure = null;
    final second = await ready(source);
    await pumpEventQueue();
    expect(source.ratingWrites, [(song, 5)]);
    expect(second.read(localRatingsProvider)[song]!.needsSync, isFalse);
    second.dispose();
  });

  test('should_stopRetrying_when_threeSendsFailInARow', () async {
    source.writeFailure = Exception('offline');
    final first = await ready(source);
    final ratings = first.read(localRatingsProvider.notifier);
    for (var i = 0; i < 5; i++) {
      await ratings.setRating(MediaId.subsonic('s$i'), 4);
    }
    first.dispose();

    source.attempts = 0;
    final second = await ready(source);
    await pumpEventQueue();
    expect(source.attempts, ratingRetryMaxFailures);
    second.dispose();
  });

  test('should_notClearNewerRating_when_olderPushLandsLate', () async {
    final c = await ready(source);
    final ratings = c.read(localRatingsProvider.notifier);
    final gate = source.ratingGate = Completer<void>();
    final older = ratings.setRating(song, 2);
    await pumpEventQueue();
    // A newer rating is stored while the older push is still out.
    final newer = ratings.setRating(song, 10);
    await pumpEventQueue();
    expect(c.read(localRatingsProvider)[song]!.rating, 10);

    source.ratingGate = null;
    gate.complete();
    await older;
    await newer;

    final row = c.read(localRatingsProvider)[song]!;
    expect(row.rating, 10);
    expect(row.needsSync, isFalse, reason: "the newer rating's own push landed");
    expect(source.ratingWrites.last, (song, 5));
    c.dispose();
  });

  test('should_stayLocal_when_sourceKeepsNoRatings', () async {
    final local = _GatedSource(capabilities: const {Capability.favorites});
    final c = await ready(local);
    await c.read(localRatingsProvider.notifier).setRating(song, 7);

    expect(local.attempts, 0);
    expect(c.read(localRatingsProvider)[song]!.rating, 7);
    c.dispose();
  });

  test('should_keepRatingsApart_when_accountsDiffer', () async {
    final c = await ready(source);
    await c.read(localRatingsProvider.notifier).setRating(song, 3);
    c.dispose();

    final other = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Work', credentials: _creds);
    await repo.setActive(other.id);
    final second = await ready(source);
    expect(second.read(localRatingsProvider)[song], isNull);
    second.dispose();
  });
}
