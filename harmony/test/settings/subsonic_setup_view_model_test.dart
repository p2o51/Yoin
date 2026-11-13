import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/subsonic_setup_view_model.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;
  late FakeMusicSource source;
  SubsonicCredentials? built;

  ProviderContainer container() {
    final c = ProviderContainer(overrides: [
      keyValueStoreProvider.overrideWithValue(store),
      secretStoreProvider.overrideWithValue(store),
      musicSourceFactoryProvider.overrideWithValue((credentials) {
        built = credentials as SubsonicCredentials;
        return source;
      }),
    ]);
    c.listen(subsonicSetupViewModelProvider, (_, _) {});
    return c;
  }

  setUp(() {
    store = InMemoryStore();
    source = FakeMusicSource();
    built = null;
  });

  test('should_listMissingFields_when_connectWithBlanks', () async {
    final c = container();
    addTearDown(c.dispose);
    await c.read(subsonicSetupViewModelProvider.notifier).connect(address: ' ', username: '', password: '');
    final state = c.read(subsonicSetupViewModelProvider) as SetupEditing;
    expect(state.missing, {SetupField.address, SetupField.username, SetupField.password});
    expect(built, isNull);
  });

  test('should_saveAndActivate_when_serverAnswers', () async {
    final c = container();
    addTearDown(c.dispose);
    await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'music.example.com/', username: ' gpo ', password: 'pw');

    expect(c.read(subsonicSetupViewModelProvider), isA<SetupConnected>());
    expect(built!.baseUrl, 'https://music.example.com');
    expect(built!.username, 'gpo');
    final profile = await c.read(profileRepositoryProvider).activeProfile();
    expect(profile!.displayName, 'music.example.com');
    expect(source.disposed, isTrue);
  });

  test('should_reportUnreachable_when_networkFails', () async {
    // A real source throws from ping() when the server can't be reached.
    source = _ThrowingPing(const SourceNetworkException('refused'));
    final c = container();
    addTearDown(c.dispose);
    await c.read(subsonicSetupViewModelProvider.notifier).connect(address: 'x.example', username: 'u', password: 'p');
    expect((c.read(subsonicSetupViewModelProvider) as SetupEditing).failure, SetupFailure.unreachable);
    expect(await c.read(profileRepositoryProvider).activeProfile(), isNull);
  });

  test('should_keepHttpScheme_when_typed', () {
    expect(SubsonicSetupViewModel.normalizeAddress('http://mini.local:4533//'), 'http://mini.local:4533');
  });
}

class _ThrowingPing extends FakeMusicSource {
  _ThrowingPing(this.error);
  final Object error;
  @override
  Future<bool> ping() async => throw error;
}
