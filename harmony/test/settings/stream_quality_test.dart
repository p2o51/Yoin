import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/stream_quality.dart';
import 'package:yoin/settings/stream_quality_screen.dart';

import '../support/fake_music_source.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');

Track _track({int? kbps}) =>
    Track(id: MediaId.subsonic('s1'), audioFormat: kbps == null ? null : AudioFormat(bitRateKbps: kbps));

final _plannerProvider = Provider<SubsonicStreamPlanner>((ref) => subsonicStreamPlanner(ref));

void main() {
  late InMemoryStore store;
  late ProfileRepository repo;

  List<Override> overrides() => [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      ];

  setUp(() {
    store = InMemoryStore();
    repo = ProfileRepository(store, store);
  });

  group('plan', () {
    test('should_playTheFile_when_original', () {
      expect(planStream(StreamQuality.original, 1411), isNull);
    });

    test('should_transcodeToMp3_when_fileAboveCap', () {
      final plan = planStream(StreamQuality.kbps320, 1000)!;
      expect(plan.maxBitRateKbps, 320);
      expect(plan.format, 'mp3');
      expect(plan.estimateLength, isTrue);
    });

    test('should_playTheFile_when_fileAlreadyUnderCap', () {
      expect(planStream(StreamQuality.kbps192, 128), isNull);
    });

    test('should_notEstimateLength_when_bitRateUnknown', () {
      expect(planStream(StreamQuality.kbps128, null)!.estimateLength, isFalse);
      expect(planStream(StreamQuality.kbps128, 0)!.estimateLength, isFalse);
    });
  });

  group('store', () {
    test('should_keepOneChoicePerAccount_when_written', () async {
      final s = StreamQualityStore(store);
      await s.write('a', StreamQuality.kbps192);
      expect(await s.read('a'), StreamQuality.kbps192);
      expect(await s.read('b'), StreamQuality.original);
    });

    test('should_fallBackToOriginal_when_storedValueUnknown', () async {
      await store.write(StreamQualityStore.key('a'), 'opus');
      expect(await StreamQualityStore(store).read('a'), StreamQuality.original);
    });
  });

  group('planner', () {
    test('should_planActiveAccountsChoice_when_songOpens', () async {
      final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann', credentials: _home);
      await StreamQualityStore(store).write(profile.id, StreamQuality.kbps128);
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      final plan = await c.read(_plannerProvider)(_track(kbps: 900));
      expect(plan?.maxBitRateKbps, 128);
    });

    test('should_playTheFile_when_noAccount', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      expect(await c.read(_plannerProvider)(_track(kbps: 900)), isNull);
    });
  });

  group('subsonic stream url', () {
    test('should_askForTranscode_when_plannerSaysSo', () async {
      final source = SubsonicMusicSource(
        _home,
        salt: 'salt',
        streamPlanner: (_) async => (maxBitRateKbps: 192, format: 'mp3', estimateLength: true),
      );
      addTearDown(source.dispose);
      final handle = await source.playback.handleFor(_track()) as DirectStream;
      final query = Uri.parse(handle.uri).queryParameters;
      expect(query['maxBitRate'], '192');
      expect(query['format'], 'mp3');
      expect(query['estimateContentLength'], 'true');
    });

    test('should_streamTheFile_when_noPlanner', () async {
      final source = SubsonicMusicSource(_home, salt: 'salt');
      addTearDown(source.dispose);
      final handle = await source.playback.handleFor(_track()) as DirectStream;
      final query = Uri.parse(handle.uri).queryParameters;
      expect(query.containsKey('maxBitRate'), isFalse);
      expect(query.containsKey('format'), isFalse);
    });
  });

  group('screen', () {
    testWidgets('should_saveChoice_when_rowTapped', (tester) async {
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: StreamQualityScreen(profileId: 'p1')),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Original'), findsOneWidget);
      expect(find.text('320 kbps'), findsOneWidget);

      await tester.tap(find.text('192 kbps'));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(await tester.runAsync(() => StreamQualityStore(store).read('p1')), StreamQuality.kbps192);
      final group = tester.widget<RadioGroup<StreamQuality>>(find.byType(RadioGroup<StreamQuality>));
      expect(group.groupValue, StreamQuality.kbps192);
    });

    testWidgets('should_showChoiceInForce_when_rowShown', (tester) async {
      await tester.runAsync(() => StreamQualityStore(store).write('p1', StreamQuality.kbps320));
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: Material(child: StreamQualityRow(profileId: 'p1'))),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Streaming quality'), findsOneWidget);
      expect(find.text('320 kbps'), findsOneWidget);
    });
  });
}
