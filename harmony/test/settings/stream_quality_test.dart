import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/subsonic/subsonic_music_source.dart';
import 'package:yoin/platform/audio/audio_host.dart';
import 'package:yoin/platform/network/network_bearer.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/spatial_audio.dart';
import 'package:yoin/settings/stream_quality.dart';
import 'package:yoin/settings/stream_quality_screen.dart';

import '../support/fake_audio_host.dart';
import '../support/fake_music_source.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');

Track _track({int? kbps}) =>
    Track(id: MediaId.subsonic('s1'), audioFormat: kbps == null ? null : AudioFormat(bitRateKbps: kbps));

final _plannerProvider = Provider<SubsonicStreamPlanner>((ref) => subsonicStreamPlanner(ref));

void main() {
  late InMemoryStore store;
  late ProfileRepository repo;

  var bearer = NetworkBearer.wifi;
  late FakeAudioHost audio;

  List<Override> overrides() => [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
        networkBearerProvider.overrideWithValue(_FixedBearer(() => bearer)),
        audioHostProvider.overrideWithValue(audio),
      ];

  setUp(() {
    store = InMemoryStore();
    repo = ProfileRepository(store, store);
    bearer = NetworkBearer.wifi;
    audio = FakeAudioHost();
  });

  group('auto', () {
    test('should_followTheOutput_when_auto', () {
      expect(StreamQuality.auto.forOutput(AudioOutputKind.wired), StreamQuality.original);
      expect(StreamQuality.auto.forOutput(AudioOutputKind.bluetooth), StreamQuality.kbps320);
      expect(StreamQuality.auto.forOutput(AudioOutputKind.speaker), StreamQuality.kbps192);
    });

    test('should_keepFixedChoice_when_notAuto', () {
      for (final output in AudioOutputKind.values) {
        expect(StreamQuality.kbps128.forOutput(output), StreamQuality.kbps128);
        expect(StreamQuality.original.forOutput(output), StreamQuality.original);
      }
    });

    test('should_readAndroidKey_when_autoStored', () async {
      expect(StreamQuality.fromKey('auto'), StreamQuality.auto);
      await store.write(StreamQualityStore.key('a', StreamNetwork.mobile), 'auto');
      expect((await StreamQualityStore(store).read('a')).mobile, StreamQuality.auto);
    });

    test('should_keepMobileDefaultAt320_when_ownerHasNotDecided', () {
      // Android defaults mobile data to Auto; Harmony's default is pending the owner.
      expect(StreamQualitySettings.defaultMobile, StreamQuality.kbps320);
    });

    test('should_planForTheOutput_when_autoOnMobile', () async {
      final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann', credentials: _home);
      await StreamQualityStore(store).write(profile.id, StreamNetwork.mobile, StreamQuality.auto);
      bearer = NetworkBearer.cellular;
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      audio.output = AudioOutputKind.speaker;
      expect((await c.read(_plannerProvider)(_track(kbps: 900)))?.maxBitRateKbps, 192);
      audio.output = AudioOutputKind.bluetooth;
      expect((await c.read(_plannerProvider)(_track(kbps: 900)))?.maxBitRateKbps, 320);
      audio.output = AudioOutputKind.wired;
      expect(await c.read(_plannerProvider)(_track(kbps: 900)), isNull);
    });

    test('should_notAskTheOutput_when_choiceIsFixed', () async {
      final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann', credentials: _home);
      await StreamQualityStore(store).write(profile.id, StreamNetwork.mobile, StreamQuality.kbps128);
      bearer = NetworkBearer.cellular;
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      expect((await c.read(_plannerProvider)(_track(kbps: 900)))?.maxBitRateKbps, 128);
      expect(audio.outputReads, 0);
    });
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
    test('should_keepTwoChoicesPerAccount_when_written', () async {
      final s = StreamQualityStore(store);
      await s.write('a', StreamNetwork.mobile, StreamQuality.kbps192);
      expect(await s.read('a'), const StreamQualitySettings(wifi: StreamQuality.original, mobile: StreamQuality.kbps192));
      expect(await s.read('b'), const StreamQualitySettings());
    });

    test('should_defaultToOriginalOnWifiAnd320OnMobile_when_nothingStored', () {
      const settings = StreamQualitySettings();
      expect(settings.wifi, StreamQuality.original);
      expect(settings.mobile, StreamQuality.kbps320);
    });

    test('should_fallBackToDefault_when_storedValueUnknown', () async {
      await store.write(StreamQualityStore.key('a', StreamNetwork.wifi), 'opus');
      expect((await StreamQualityStore(store).read('a')).wifi, StreamQuality.original);
    });

    test('should_pickMobileOnlyOnCellular_when_bearerKnown', () {
      const settings = StreamQualitySettings(wifi: StreamQuality.kbps320, mobile: StreamQuality.kbps128);
      expect(settings.forBearer(NetworkBearer.cellular), StreamQuality.kbps128);
      expect(settings.forBearer(NetworkBearer.wifi), StreamQuality.kbps320);
      expect(settings.forBearer(NetworkBearer.other), StreamQuality.kbps320);
    });
  });

  group('planner', () {
    test('should_planChoiceForCurrentNetwork_when_songOpens', () async {
      final profile = await repo.add(provider: MediaId.providerSubsonic, displayName: 'ann', credentials: _home);
      await StreamQualityStore(store).write(profile.id, StreamNetwork.mobile, StreamQuality.kbps128);
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      expect(await c.read(_plannerProvider)(_track(kbps: 900)), isNull); // Wi‑Fi: original
      bearer = NetworkBearer.cellular;
      expect((await c.read(_plannerProvider)(_track(kbps: 900)))?.maxBitRateKbps, 128);
    });

    test('should_playTheFile_when_noAccount', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      expect(await c.read(_plannerProvider)(_track(kbps: 900)), isNull);
    });
  });

  group('channel', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel('yoin/network');

    test('should_readThenFollowPushes_when_bearerAsked', () async {
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async => call.method == 'bearer' ? 'cellular' : null);
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final source = ChannelNetworkBearer();
      expect(await source.current(), NetworkBearer.cellular);

      await messenger.handlePlatformMessage(
        'yoin/network',
        const StandardMethodCodec().encodeMethodCall(const MethodCall('bearerChanged', 'wifi')),
        (_) {},
      );
      expect(await source.current(), NetworkBearer.wifi);
    });

    test('should_sayOther_when_pluginMissing', () async {
      expect(await ChannelNetworkBearer(channel: const MethodChannel('yoin/network-missing')).current(), NetworkBearer.other);
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

    test('should_sayWhatArrives_when_handleBuilt', () async {
      final source = SubsonicMusicSource(
        _home,
        salt: 'salt',
        streamPlanner: (_) async => (maxBitRateKbps: 320, format: 'mp3', estimateLength: true),
      );
      addTearDown(source.dispose);
      final handle = await source.playback.handleFor(_track()) as DirectStream;
      expect(handle.delivery, const StreamDelivery(maxBitRateKbps: 320, format: 'mp3'));
      final plain = SubsonicMusicSource(_home, salt: 'salt');
      addTearDown(plain.dispose);
      expect((await plain.playback.handleFor(_track()) as DirectStream).delivery.transcoded, isFalse);
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
    testWidgets('should_saveMobileChoice_when_rowTapped', (tester) async {
      tester.view.physicalSize = const Size(800, 2000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: StreamQualityScreen(profileId: 'p1')),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Wi‑Fi'), findsOneWidget);
      expect(find.text('Mobile data'), findsOneWidget);
      expect(find.text('192 kbps'), findsNWidgets(2));

      await tester.tap(find.text('192 kbps').last);
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      final saved = await tester.runAsync(() => StreamQualityStore(store).read('p1'));
      expect(saved, const StreamQualitySettings(wifi: StreamQuality.original, mobile: StreamQuality.kbps192));
      final groups = tester.widgetList<RadioGroup<StreamQuality>>(find.byType(RadioGroup<StreamQuality>)).toList();
      expect(groups.map((g) => g.groupValue), [StreamQuality.original, StreamQuality.kbps192]);
    });

    testWidgets('should_offerAutoWithItsDetail_when_pageShown', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: StreamQualityScreen(profileId: 'p1')),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Auto'), findsNWidgets(2));
      expect(find.text('Wired Original   Bluetooth 320 kbps   Speaker 192 kbps'), findsNWidgets(2));

      await tester.tap(find.text('Auto').last);
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      final saved = await tester.runAsync(() => StreamQualityStore(store).read('p1'));
      expect(saved?.mobile, StreamQuality.auto);
    });

    testWidgets('should_sayAutoInTheSummary_when_mobileIsAuto', (tester) async {
      await tester.runAsync(() => StreamQualityStore(store).write('p1', StreamNetwork.mobile, StreamQuality.auto));
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: Material(child: StreamQualityRow(profileId: 'p1'))),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Wi‑Fi Original, mobile data Auto'), findsOneWidget);
    });

    testWidgets('should_switchSpatialAudioOff_when_switchRowTapped', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      audio.capability = const SpatialAudioCapability(supported: true, outputSupported: true, enabled: true);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: StreamQualityScreen(profileId: 'p1')),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Spatial audio'), findsOneWidget);
      expect(find.text('On. Multichannel songs are spatialized on this output.'), findsOneWidget);
      expect(find.text('System sound settings'), findsOneWidget);
      expect(tester.widget<Switch>(find.byType(Switch)).value, isTrue);
      // Screen readers hear the row as one switch, with its state.
      final semantics = tester.ensureSemantics();
      expect(
        tester.getSemantics(find.text('Spatialize 5.1 and 7.1 songs')),
        isSemantics(hasToggledState: true, isToggled: true, hasTapAction: true),
      );
      semantics.dispose();

      await tester.tap(find.text('Spatialize 5.1 and 7.1 songs'));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(tester.widget<Switch>(find.byType(Switch)).value, isFalse);
      expect(find.text('Off. Multichannel songs play folded down to stereo.'), findsOneWidget);
      expect(audio.downmix, isTrue);
      expect(await tester.runAsync(() => SpatialAudioStore(store).read()), SpatialAudioMode.off);

      await tester.tap(find.text('System sound settings'));
      await tester.pump();
      expect(audio.settingsOpened, 1);
    });

    testWidgets('should_hideSystemSettingsRow_when_deviceCantSpatialize', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: StreamQualityScreen(profileId: 'p1')),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text("This device can't spatialize. Multichannel songs play folded down to stereo."), findsOneWidget);
      expect(find.text('System sound settings'), findsNothing);
      expect(
        find.text("Only for songs that are 5.1 or 7.1 files, played as the original. Stereo songs aren't changed."),
        findsOneWidget,
      );
    });

    testWidgets('should_showBothChoices_when_rowShown', (tester) async {
      await tester.runAsync(() => StreamQualityStore(store).write('p1', StreamNetwork.wifi, StreamQuality.kbps320));
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: const MaterialApp(home: Material(child: StreamQualityRow(profileId: 'p1'))),
      ));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('Streaming quality'), findsOneWidget);
      expect(find.text('Wi‑Fi 320 kbps, mobile data 320 kbps'), findsOneWidget);
    });
  });
}

class _FixedBearer implements NetworkBearerSource {
  _FixedBearer(this.bearer);
  final NetworkBearer Function() bearer;
  @override
  Future<NetworkBearer> current() async => bearer();
}
