import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/nowplaying/devices/devices_pill.dart';
import 'package:yoin/nowplaying/devices/devices_sheet.dart';
import 'package:yoin/nowplaying/devices/devices_view_model.dart';
import 'package:yoin/platform/cast/cast_host.dart';
import 'package:yoin/playback/output/dlna_client.dart';
import 'package:yoin/playback/output/output_providers.dart';
import 'package:yoin/playback/output/playback_router.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/yoin_loading_indicator.dart';

import '../playback/output/output_fakes.dart';
import '../support/fake_music_source.dart';
import '../support/nowplaying_fakes.dart';

/// An Apple Music-like source: its queue never reaches a renderer.
class _AppleSource extends FakeMusicSource {
  @override
  String get provider => MediaId.providerAppleMusic;
}

String _describe(String udn, String name) =>
    '<root><device><friendlyName>$name</friendlyName><UDN>$udn</UDN>'
    '<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>'
    '<controlURL>/av</controlURL></service></serviceList></device></root>';

void main() {
  group('rows', () {
    test('should_listThisDeviceTheCastRowAndSpeakers_when_subsonicPlaysHere', () {
      final rows = buildDevices(
        providerId: MediaId.providerSubsonic,
        route: const LocalOutput(),
        hasSystemPicker: true,
        dlnaDevices: [fakeSpeaker('uuid:a', name: 'Kitchen')],
      );
      expect(rows.map((r) => r.kind), [DeviceKind.local, DeviceKind.systemCast, DeviceKind.dlna]);
      expect(rows[0].isActive, isTrue);
      expect(rows[0].isSelectable, isFalse);
      expect(rows[1].status, DeviceStatus.notCasting);
      expect(rows[2].status, DeviceStatus.dlna);
      expect(rows[2].isSelectable, isTrue);
    });

    test('should_offerSwitchingBack_when_aSpeakerPlays', () {
      final speaker = fakeSpeaker('uuid:a');
      final rows = buildDevices(
        providerId: MediaId.providerSubsonic,
        route: DlnaOutput(speaker),
        hasSystemPicker: true,
      );
      final local = rows.firstWhere((r) => r.kind == DeviceKind.local);
      expect(local.isActive, isFalse);
      expect(local.isSelectable, isTrue);
      expect(local.status, DeviceStatus.switchBack);
      final dlna = rows.firstWhere((r) => r.kind == DeviceKind.dlna);
      expect(dlna.isActive, isTrue);
      expect(dlna.status, DeviceStatus.casting);
      // The cast panel waits until the speaker lets go.
      expect(rows.firstWhere((r) => r.kind == DeviceKind.systemCast).isSelectable, isFalse);
    });

    test('should_showTheCastDeviceAndLockSpeakers_when_theSystemSessionIsCast', () {
      final rows = buildDevices(
        providerId: MediaId.providerSubsonic,
        route: const SystemCastOutput('Vision'),
        hasSystemPicker: true,
        systemCastName: 'Vision',
        dlnaDevices: [fakeSpeaker('uuid:a')],
      );
      final cast = rows.firstWhere((r) => r.kind == DeviceKind.systemCast);
      expect(cast.name, 'Vision');
      expect(cast.isActive, isTrue);
      expect(cast.status, DeviceStatus.casting);
      expect(rows.firstWhere((r) => r.kind == DeviceKind.local).isSelectable, isFalse);
      expect(rows.firstWhere((r) => r.kind == DeviceKind.dlna).isSelectable, isFalse);
    });

    test('should_leaveSpeakersOut_when_theAccountIsNotSubsonic', () {
      final rows = buildDevices(
        providerId: MediaId.providerAppleMusic,
        route: const LocalOutput(),
        hasSystemPicker: true,
        dlnaDevices: [fakeSpeaker('uuid:a')],
      );
      expect(rows.map((r) => r.kind), [DeviceKind.local, DeviceKind.systemCast]);
    });

    test('should_keepRowsInPlace_when_aRefreshReordersThem', () {
      const a = YoinDevice(id: 'a', kind: DeviceKind.dlna, name: 'A', isActive: false);
      const b = YoinDevice(id: 'b', kind: DeviceKind.dlna, name: 'B', isActive: false);
      const c = YoinDevice(id: 'c', kind: DeviceKind.dlna, name: 'C', isActive: false);
      const bActive = YoinDevice(id: 'b', kind: DeviceKind.dlna, name: 'B', isActive: true);
      expect(mergeDevicesForStableRows([a, b], [c, bActive]), [bActive, c]);
      expect(mergeDevicesForStableRows(const [], [a]), [a]);
    });
  });

  group('pills layout', () {
    test('should_keepLabels_when_theyFit', () {
      expect(bottomPillsLayout(maxWidth: 364, labelWidths: [40, 50, 35]).labelled, isTrue);
    });

    test('should_foldToIcons_when_theLabelsDontFit', () {
      final layout = bottomPillsLayout(maxWidth: 250, labelWidths: [40, 50, 35]);
      expect(layout.labelled, isFalse);
      expect(layout.iconOnlyWidth, NowPlayingTokens.pillIconOnlyMin * 3 + NowPlayingTokens.pillGap * 2);
      expect(layout.fitsPressedLabel(40), isTrue);
      expect(bottomPillsLayout(maxWidth: 200, labelWidths: [40, 50, 35]).fitsPressedLabel(60), isFalse);
    });

    test('should_countTheCastChip_when_aCastDevicePlays', () {
      expect(bottomPillsLayout(maxWidth: 364, labelWidths: [40, 50, 35], castWidth: 140).labelled, isFalse);
    });
  });

  group('sheet', () {
    late FakeClock clock;
    late MirrorNativeEngine native;
    late Map<String, FakeRenderer> speakers;
    late FakeCastHost cast;
    late PlaybackRouter router;

    // Built inside each test body: futures made in setUp live outside the widget tester's fake clock.
    PlaybackRouter makeRouter({Future<Set<Uri>> Function()? search}) => testRouter(
      clock: clock,
      native: native,
      speakers: speakers,
      cast: cast,
      search: search ?? () async => {Uri.parse('http://10.0.0.1/d.xml')},
      describer: DlnaClient(
        http: (method, url, {headers = const {}, body}) async => (status: 200, body: _describe('uuid:k', 'Kitchen')),
      ),
    );

    setUp(() {
      clock = FakeClock();
      native = MirrorNativeEngine();
      speakers = {};
      cast = FakeCastHost();
    });

    tearDown(() => router.dispose());

    Future<void> pumpSheet(WidgetTester tester, {MusicSource? source, Locale? locale}) async {
      router = makeRouter();
      final tracks = nowPlayingTracks(2);
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: NowPlayingFakeController(playingState(tracks)), source: source),
            playbackRouterProvider.overrideWithValue(router),
          ],
          child: MaterialApp(
            locale: locale,
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(onPressed: () => showDevicesSheet(context), child: const Text('open')),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    testWidgets('should_listThisDeviceTheCastPanelAndFoundSpeakers_when_opened', (tester) async {
      await pumpSheet(tester);
      expect(find.text('Devices'), findsOneWidget);
      expect(find.text('This device'), findsOneWidget);
      expect(find.text('Cast Device'), findsOneWidget);
      expect(find.text('Not casting'), findsOneWidget);
      expect(find.text('Kitchen'), findsOneWidget);
      expect(find.text('DLNA'), findsOneWidget);
      expect(find.text('UPnP'), findsOneWidget);
      expect(
        find.textContaining('·'),
        findsNothing,
        reason: 'protocols and the subtitle are set apart by space and a line',
      );
    });

    testWidgets('should_putSameWifiOnItsOwnLine_when_theAccountIsSubsonic', (tester) async {
      await pumpSheet(tester);
      expect(find.text('This device, Cast and DLNA'), findsOneWidget);
      expect(find.text('Same Wi-Fi'), findsOneWidget);
      expect(
        tester.getTopLeft(find.text('Same Wi-Fi')).dy,
        greaterThan(tester.getBottomLeft(find.text('This device, Cast and DLNA')).dy - 1),
      );
    });

    testWidgets('should_openTheSystemPanel_when_theCastRowIsTapped', (tester) async {
      await pumpSheet(tester);
      await tester.tap(find.text('Cast Device'));
      await tester.pumpAndSettle();
      expect(cast.pickerOpens, 1);
    });

    testWidgets('should_sayPlayFirst_when_aSpeakerIsPickedWithNothingLoaded', (tester) async {
      await pumpSheet(tester);
      await tester.tap(find.text('Kitchen'));
      await tester.pumpAndSettle();
      expect(find.text('Play a Subsonic track before choosing a DLNA speaker.'), findsOneWidget);
    });

    testWidgets('should_moveThePlayingQueueToTheSpeaker_when_itIsPicked', (tester) async {
      await pumpSheet(tester);
      router.states.listen((_) {});
      await router.startQueue([streamItem(0), streamItem(1)], revision: 2);
      native.echo(index: 0, isPlaying: true, positionMs: 1000, sampledAtMs: clock.now);
      await tester.pump();
      await tester.tap(find.text('Kitchen'));
      await tester.pumpAndSettle();
      expect(router.route.value, isA<DlnaOutput>());
      expect(speakers['uuid:k']!.calls, contains('play'));
      expect(find.text('Casting'), findsOneWidget);
      expect(find.text('Switch back to this device'), findsOneWidget);
      await tester.tap(find.text('This device'));
      await tester.pumpAndSettle();
      expect(router.route.value, const LocalOutput());
      expect(native.calls.last, 'startQueue');
    });

    testWidgets('should_sayTheSearchFailed_when_discoveryFails', (tester) async {
      router = makeRouter(search: () async => throw Exception('no Wi-Fi'));
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: NowPlayingFakeController(playingState(nowPlayingTracks(2)))),
            playbackRouterProvider.overrideWithValue(router),
          ],
          child: const MaterialApp(home: Scaffold(body: DevicesSheet())),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Unable to find speakers. Connect to the same Wi-Fi network and try again.'), findsOneWidget);
    });

    testWidgets('should_hideSpeakers_when_theAccountIsNotSubsonic', (tester) async {
      await pumpSheet(tester, source: _AppleSource());
      expect(find.text('Playback devices'), findsOneWidget);
      expect(find.text('Kitchen'), findsNothing);
      expect(find.text('Same Wi-Fi'), findsNothing);
    });

    testWidgets('should_speakChinese_when_theLocaleIsChinese', (tester) async {
      await pumpSheet(tester, locale: const Locale('zh'));
      expect(find.text('此设备'), findsOneWidget);
      expect(find.text('投播设备'), findsOneWidget);
      expect(find.text('未投播'), findsOneWidget);
      expect(find.text('此设备、投播与 DLNA'), findsOneWidget);
      expect(find.text('同一 Wi-Fi'), findsOneWidget);
    });

    testWidgets('should_notOverlayPullIndicator_when_loadingWithoutPull', (tester) async {
      const device = YoinDevice(id: 'local', kind: DeviceKind.local, name: '', isActive: true, isSelectable: false);
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: DevicesSheetBody(
              state: const DevicesSheetState(
                providerId: MediaId.providerSubsonic,
                devices: [device],
                loading: true,
              ),
              onRefresh: () {},
              onSelect: (_) {},
            ),
          ),
        ),
      );
      await tester.pump(const Duration(milliseconds: 300));
      // Header refresh button shows the single spinner; pull-to-refresh overlay must not drop over "This device".
      expect(find.byType(CircularProgressIndicator), findsOneWidget);
      expect(find.byType(YoinLoadingIndicator), findsNothing);
    });

    testWidgets('should_closeTheSheet_when_theSongChanges', (tester) async {
      router = makeRouter();
      final tracks = nowPlayingTracks(2);
      final controller = NowPlayingFakeController(playingState(tracks));
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: controller),
            playbackRouterProvider.overrideWithValue(router),
          ],
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: TextButton(onPressed: () => showDevicesSheet(context), child: const Text('open')),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      expect(find.byType(DevicesSheet), findsOneWidget);
      controller.emit(playingState(tracks, index: 1));
      await tester.pumpAndSettle();
      expect(find.byType(DevicesSheet), findsNothing);
    });
  });

  test('should_readTheSystemOutput_when_nativeReportsIt', () {
    final output = CastOutput.fromMap(const {
      'state': 'connected',
      'category': 'remote',
      'name': 'Vision',
      'deviceType': 2,
    });
    expect(output.isRemote, isTrue);
    expect(output.name, 'Vision');
    expect(CastOutput.fromMap(const {'state': 'connecting', 'category': 'remote'}).isRemote, isFalse);
  });
}
