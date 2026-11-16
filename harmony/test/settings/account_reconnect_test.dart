import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/account_reconnect.dart';
import 'package:yoin/settings/account_switch.dart';
import 'package:yoin/settings/settings_view_model.dart';

import '../support/fake_music_source.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');

/// A source whose ping the test answers.
class _PingSource extends FakeMusicSource {
  _PingSource(this.answer);

  final Future<bool> Function() answer;

  @override
  Future<bool> ping() => answer();
}

void main() {
  late InMemoryStore values;
  late InMemoryStore secrets;
  late ProfileRepository repo;
  late List<_PingSource> built;
  late Future<bool> Function() ping;
  late ProviderContainer c;

  List<Override> overrides() => [
    keyValueStoreProvider.overrideWithValue(values),
    secretStoreProvider.overrideWithValue(secrets),
    musicSourceFactoryProvider.overrideWithValue((_) {
      final s = _PingSource(() => ping());
      built.add(s);
      return s;
    }),
    accountSwitchPrepareProvider.overrideWithValue(() async {}),
    accountSwitchPingTimeoutProvider.overrideWithValue(const Duration(milliseconds: 50)),
  ];

  setUp(() {
    values = InMemoryStore();
    secrets = InMemoryStore();
    repo = ProfileRepository(values, secrets);
    built = [];
    ping = () async => true;
    c = ProviderContainer(overrides: overrides());
    // Keep the flags alive, as Home's header does.
    c.listen(activeNeedsReconnectProvider, (_, _) {});
  });
  tearDown(() => c.dispose());

  Future<bool> needs() async {
    // Let the source settle first (a failed read: once Riverpod is retrying
    // it), then the answer read from it.
    for (var i = 0; i < 200; i++) {
      final source = c.read(activeSourceProvider);
      if (!source.isLoading || source.retrying) break;
      await Future<void>.delayed(const Duration(milliseconds: 1));
    }
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    return c.read(activeNeedsReconnectProvider.future);
  }

  /// An account whose stored credentials are gone (a restore without them).
  Future<Profile> lostAccount() async {
    final p = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home);
    await secrets.write(ProfileRepository.credentialsKey(p.id), null);
    // The app follows an added account (as the setup page does).
    c.invalidate(activeSourceProvider);
    return p;
  }

  /// A working account, the one in use.
  Future<Profile> workingAccount() async {
    final p = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann', credentials: _home);
    c.invalidate(activeSourceProvider);
    return p;
  }

  Future<void> giveCredentialsBack(Profile p) =>
      secrets.write(ProfileRepository.credentialsKey(p.id), jsonEncode(_home.toJson()));

  group('activeNeedsReconnectProvider', () {
    test('should_notNeedReconnect_when_noAccount', () async {
      expect(await needs(), isFalse);
    });

    test('should_notNeedReconnect_when_accountWorks', () async {
      await workingAccount();
      expect(await needs(), isFalse);
    });

    test('should_needReconnect_when_credentialsAreGone', () async {
      await lostAccount();
      expect(await c.read(activeSourceProvider.future), isNull);
      expect(await needs(), isTrue);
    });

    test('should_needReconnect_when_credentialsCannotBeRead', () async {
      final p = await workingAccount();
      await secrets.write(ProfileRepository.credentialsKey(p.id), '{not json');
      c.invalidate(activeSourceProvider);
      expect(await needs(), isTrue);
    });

    test('should_clear_when_credentialsAreEnteredAgain', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      // The account's page saves them (updateProfileCredentials) and the app follows.
      await giveCredentialsBack(p);
      c.invalidate(activeSourceProvider);
      expect(await needs(), isFalse);
    });
  });

  group('retryActiveSource', () {
    test('should_returnFalse_when_noAccount', () async {
      expect(await retryActiveSource(c), isFalse);
      expect(built, isEmpty);
    });

    test('should_publishPingedSource_when_credentialsReadAgain', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);

      expect(await retryActiveSource(c), isTrue);

      // The source that answered is the one in use: no second build.
      expect(built, hasLength(1));
      expect(identical(await c.read(activeSourceProvider.future), built.single), isTrue);
      expect(built.single.disposed, isFalse);
      expect(await needs(), isFalse);
    });

    test('should_stayFlagged_when_credentialsStillMissing', () async {
      await lostAccount();
      expect(await needs(), isTrue);

      expect(await retryActiveSource(c), isFalse);

      expect(built, isEmpty);
      expect(await c.read(activeSourceProvider.future), isNull);
      expect(await needs(), isTrue);
    });

    test('should_notPublish_when_serverRefusesCredentials', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);
      ping = () async => throw const SourceAuthException('Wrong username or password', code: 40);

      expect(await retryActiveSource(c), isFalse);

      expect(built.single.disposed, isTrue);
      expect(await c.read(activeSourceProvider.future), isNull);
    });

    test('should_publish_when_serverDoesNotAnswer', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);
      // Offline or slow: a cold start would get the source all the same.
      ping = () => Completer<bool>().future;

      expect(await retryActiveSource(c), isTrue);
      expect(identical(await c.read(activeSourceProvider.future), built.single), isTrue);
    });

    test('should_publish_when_serverFailsOtherwise', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);
      ping = () async => throw const SourceServerException('HTTP 502');

      expect(await retryActiveSource(c), isTrue);
      expect(built.single.disposed, isFalse);
    });

    test('should_leaveSourceAlone_when_oneIsUp', () async {
      await workingAccount();
      final up = await c.read(activeSourceProvider.future);
      built.clear();

      expect(await retryActiveSource(c), isTrue);

      expect(built, isEmpty);
      expect(identical(await c.read(activeSourceProvider.future), up), isTrue);
    });

    test('should_answerAtOnce_when_aFailedReadIsBeingRetried', () async {
      final p = await workingAccount();
      await secrets.write(ProfileRepository.credentialsKey(p.id), '{not json');
      c.invalidate(activeSourceProvider);
      expect(await needs(), isTrue);

      // Riverpod's retries of the failed read aren't waited out.
      expect(await retryActiveSource(c).timeout(const Duration(seconds: 2)), isFalse);

      // Readable again: the retry publishes its source over the failed read.
      await giveCredentialsBack(p);
      expect(await retryActiveSource(c).timeout(const Duration(seconds: 2)), isTrue);
      expect(identical(await c.read(activeSourceProvider.future), built.last), isTrue);
      expect(await needs(), isFalse);
    });

    test('should_bumpAccountsRevision_when_retried', () async {
      await lostAccount();
      final before = c.read(accountsRevisionProvider);
      await retryActiveSource(c);
      expect(c.read(accountsRevisionProvider), before + 1);
    });
  });

  group('AccountReconnector', () {
    test('should_sayFixed_when_retryWorks', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);

      final reconnecting = <bool>[];
      c.listen(accountReconnectorProvider, (_, next) => reconnecting.add(next));
      final outcome = await c.read(accountReconnectorProvider.notifier).reconnect(p.id);

      expect(outcome, AccountReconnectOutcome.fixed);
      // Under way from the tap until the retry is over.
      expect(reconnecting, [true, false]);
    });

    test('should_sayOpenPage_when_retryCannotFix', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      expect(await c.read(accountReconnectorProvider.notifier).reconnect(p.id), AccountReconnectOutcome.openPage);
    });

    test('should_notStart_when_aReconnectIsUnderWay', () async {
      final p = await lostAccount();
      expect(await needs(), isTrue);
      await giveCredentialsBack(p);
      final gate = Completer<bool>();
      ping = () => gate.future;
      final reconnector = c.read(accountReconnectorProvider.notifier);
      final first = reconnector.reconnect(p.id);
      await Future<void>.delayed(Duration.zero);
      expect(c.read(accountReconnectorProvider), isTrue);
      expect(await reconnector.reconnect(p.id), isNull);
      gate.complete(true);
      expect(await first, AccountReconnectOutcome.fixed);
      expect(built, hasLength(1));
    });

    test('should_sayNothing_when_anotherAccountIsInUseAfterwards', () async {
      final lost = await lostAccount();
      final other = await lostAccount();
      // Settings switched away meanwhile: the account the card offered isn't the one in use.
      await repo.setActive(other.id);
      c.invalidate(activeSourceProvider);
      expect(await needs(), isTrue);
      expect(await c.read(accountReconnectorProvider.notifier).reconnect(lost.id), isNull);
    });
  });
}
