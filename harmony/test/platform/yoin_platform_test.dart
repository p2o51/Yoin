import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/applemusic/apple_music_auth.dart';
import 'package:yoin/platform/audio/audio_host.dart';
import 'package:yoin/platform/browser/url_opener.dart';
import 'package:yoin/platform/motion/system_motion.dart';
import 'package:yoin/platform/cards/cards_channel.dart';
import 'package:yoin/platform/playback/channel_playback_engine.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/posture/display_posture.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/platform/storage/channel_stores.dart';
import 'package:yoin/platform/sync/cloud_sync_host.dart';
import 'package:yoin/platform/window/window_caption.dart';
import 'package:yoin/platform/windows/windows_platform.dart';
import 'package:yoin/platform/yoin_platform.dart';

void main() {
  group('YoinPlatform.forHost', () {
    test('should_useArkTsChannels_when_hostIsOhos', () {
      final p = YoinPlatform.forHost('ohos');
      expect(p.host, YoinHost.ohos);
      expect(p.keyValueStore, isA<ChannelKeyValueStore>());
      expect(p.secretStore, isA<ChannelSecretStore>());
      expect(p.playbackEngine, isA<ChannelPlaybackEngine>());
      expect(p.cardsHost, isA<ChannelCardsHost>());
      expect(p.windowCaption, isA<ChannelWindowCaptionHost>());
      expect(p.textShare, isA<ChannelTextShare>());
      expect(p.urlOpener, isA<BrowserUrlOpener>());
      expect(p.displayPosture, isA<ChannelDisplayPostureHost>());
      expect(p.audio, isA<ChannelAudioHost>());
      expect(p.volumeKeys, isA<ChannelVolumeKeyHost>());
      expect(p.systemMotion, isA<ChannelSystemMotionHost>());
    });

    test('should_usePureDartStandIns_when_hostIsWindows', () {
      final p = YoinPlatform.forHost('windows');
      expect(p.host, YoinHost.windows);
      expect(p.keyValueStore, isA<FileKeyValueStore>());
      expect(p.secretStore, isA<SessionSecretStore>());
      expect(p.playbackEngine, isA<UnsupportedPlaybackEngine>());
      expect(p.cardsHost, isA<NoCardsHost>());
      expect(p.windowCaption, isA<SystemTitleBarHost>());
      expect(p.textShare, isA<NoTextShare>());
      expect(p.audio, isA<FixedAudioHost>());
      expect(p.audio.canChooseSpatialization, isFalse);
      expect(p.volumeKeys, isA<NoVolumeKeyHost>());
      expect(p.urlOpener, isA<BrowserUrlOpener>());
      expect(p.displayPosture, isA<NoDisplayPostureHost>());
      expect(p.systemMotion, isA<NoSystemMotionHost>());
    });
  });

  group('text share and browser', () {
    TestWidgetsFlutterBinding.ensureInitialized();

    test('should_handTextToShareSheet_when_channelShares', () async {
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('yoin/share'),
        (call) async {
          calls.add(call);
          return true;
        },
      );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          const MethodChannel('yoin/share'),
          null,
        ),
      );
      expect(await const ChannelTextShare().shareText('Blue\nhttps://s/1', title: 'Blue'), isTrue);
      expect(calls.single.method, 'shareText');
      expect(calls.single.arguments, {'text': 'Blue\nhttps://s/1', 'title': 'Blue'});
    });

    test('should_reportNoShare_when_hostHasNoSheet', () async {
      expect(await const NoTextShare().shareText('x'), isFalse);
    });

    test('should_openOnlyWebPages_when_askedToOpenALink', () async {
      final host = _Browser();
      final opener = BrowserUrlOpener(host);
      expect(await opener.open(Uri.parse('https://yoin.gojyuplus.com/')), isTrue);
      expect(await opener.open(Uri.parse('file:///etc/passwd')), isFalse);
      expect(host.opened, [Uri.parse('https://yoin.gojyuplus.com/')]);
    });
  });

  group('desktopAppDataDirectory', () {
    test('should_useAppData_when_onWindows', () {
      final dir = desktopAppDataDirectory(environment: {'APPDATA': r'C:\Users\a\AppData\Roaming'}, windows: true);
      expect(dir.path, r'C:\Users\a\AppData\Roaming\Yoin');
    });

    test('should_useXdgDataHome_when_notWindows', () {
      final dir = desktopAppDataDirectory(environment: {'XDG_DATA_HOME': '/home/a/.data'}, windows: false);
      expect(dir.path, '/home/a/.data/yoin');
    });
  });

  group('FileKeyValueStore', () {
    late Directory temp;
    setUp(() => temp = Directory.systemTemp.createTempSync('yoin_kv'));
    tearDown(() => temp.deleteSync(recursive: true));

    test('should_readBackValues_when_reopened', () async {
      final store = FileKeyValueStore(temp);
      await store.write('a', '1');
      await store.write('b', '2');
      await store.write('a', null);
      final reopened = FileKeyValueStore(temp);
      expect(await reopened.read('a'), isNull);
      expect(await reopened.read('b'), '2');
    });

    test('should_startEmpty_when_fileIsDamaged', () async {
      File('${temp.path}${Platform.pathSeparator}prefs.json').writeAsStringSync('{not json');
      final store = FileKeyValueStore(temp);
      expect(await store.read('a'), isNull);
      await store.write('a', '1');
      expect(await FileKeyValueStore(temp).read('a'), '1');
    });
  });

  group('Windows stand-ins', () {
    test('should_throwUnsupported_when_queueStarts', () async {
      const engine = UnsupportedPlaybackEngine();
      expect((await engine.states.first).phase, PlaybackPhase.idle);
      await expectLater(engine.startQueue(const [], revision: 1), throwsUnsupportedError);
      await engine.stop(); // housekeeping stays harmless
      expect(await engine.snapshot(), isNull);
    });

    test('should_reportUnavailable_when_appleMusicSignInStarts', () async {
      await expectLater(
        const UnsupportedAppleMusicAuth().authorize('https://example.invalid'),
        throwsA(isA<AppleMusicAuthException>().having((e) => e.failure, 'failure', AppleMusicAuthFailure.unavailable)),
      );
    });
  });

  testWidgets('should_leaveLayoutAlone_when_systemDrawsTitleBar', (tester) async {
    var top = -1.0;
    await tester.pumpWidget(
      MediaQuery(
        data: const MediaQueryData(size: Size(1000, 700)),
        child: Directionality(
          textDirection: TextDirection.ltr,
          child: YoinWindowCaption(
            host: const SystemTitleBarHost(),
            child: Builder(
              builder: (context) {
                top = MediaQuery.paddingOf(context).top;
                return const SizedBox.expand();
              },
            ),
          ),
        ),
      ),
    );
    await tester.pump();
    expect(top, 0);
  });
}

/// A browser that opens everything it is handed.
final class _Browser implements CloudSyncHost {
  final opened = <Uri>[];

  @override
  Future<bool> openBrowser(Uri url) async {
    opened.add(url);
    return true;
  }

  @override
  Future<void> holdWhileAway() async {}
  @override
  Future<void> releaseHold() async {}
  @override
  Future<String> deviceName() async => 'test';
  @override
  Future<Directory?> filesDirectory() async => null;
}
