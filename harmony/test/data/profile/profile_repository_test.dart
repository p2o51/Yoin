import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore values;
  late InMemoryStore secrets;
  late ProfileRepository repo;

  setUp(() {
    values = InMemoryStore();
    secrets = InMemoryStore();
    repo = ProfileRepository(values, secrets);
  });

  const creds = SubsonicCredentials(baseUrl: 'https://music.example.com', username: 'gpo', password: 'pw');

  test('should_keepCredentialsOutOfPlainValues_when_profileAdded', () async {
    final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home server', credentials: creds);

    expect(values.values.values.join(), isNot(contains('pw')));
    expect(secrets.values.keys, [ProfileRepository.credentialsKey(profile.id)]);
    final read = await repo.credentials(profile.id);
    expect(read, isA<SubsonicCredentials>().having((c) => c.password, 'password', 'pw'));
  });

  test('should_activateNewProfile_when_added', () async {
    final first = await repo.add(provider: 'subsonic', displayName: 'A', credentials: creds);
    final second = await repo.add(provider: 'subsonic', displayName: 'B', credentials: creds);

    expect((await repo.profiles()).map((p) => p.id), [first.id, second.id]);
    expect((await repo.activeProfile())!.id, second.id);
  });

  test('should_fallBackToRemainingProfile_when_activeRemoved', () async {
    final first = await repo.add(provider: 'subsonic', displayName: 'A', credentials: creds);
    final second = await repo.add(provider: 'subsonic', displayName: 'B', credentials: creds);

    await repo.remove(second.id);

    expect((await repo.activeProfile())!.id, first.id);
    expect(await repo.credentials(second.id), isNull);
  });

  test('should_returnNoActiveProfile_when_noneConfigured', () async {
    expect(await repo.activeProfile(), isNull);
  });
}
