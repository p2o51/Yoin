import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/audio/audio_host.dart';
import 'package:yoin/platform/playback/playback_engine.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  Future<void> push(String channel, String method, Object? arguments) => messenger.handlePlatformMessage(
    channel,
    const StandardMethodCodec().encodeMethodCall(MethodCall(method, arguments)),
    (_) {},
  );

  group('audio host', () {
    const channel = MethodChannel('yoin/audio');
    late List<MethodCall> calls;

    setUp(() {
      calls = [];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return switch (call.method) {
          'output' => 'bluetooth',
          'spatial' => {'supported': true, 'outputSupported': false, 'enabled': false, 'headTracking': false},
          'openSoundSettings' => true,
          _ => null,
        };
      });
    });
    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('should_readThenFollowPushes_when_outputAsked', () async {
      final host = ChannelAudioHost();
      expect(await host.currentOutput(), AudioOutputKind.bluetooth);
      final changes = <AudioOutputKind>[];
      host.outputChanges.listen(changes.add);
      await push('yoin/audio', 'outputChanged', 'wired');
      expect(changes, [AudioOutputKind.wired]);
      expect(await host.currentOutput(), AudioOutputKind.wired);
    });

    test('should_readThenFollowPushes_when_spatialAsked', () async {
      final host = ChannelAudioHost();
      expect(await host.spatial(), const SpatialAudioCapability(supported: true));
      final changes = <SpatialAudioCapability>[];
      host.spatialChanges.listen(changes.add);
      await push('yoin/audio', 'spatialChanged', {'supported': true, 'outputSupported': true, 'enabled': true});
      expect(changes.single, const SpatialAudioCapability(supported: true, outputSupported: true, enabled: true));
    });

    test('should_sendTheSwitchAndOpenSettings_when_asked', () async {
      final host = ChannelAudioHost();
      await host.setSpatialDownmix(true);
      expect(await host.openSoundSettings(), isTrue);
      expect(calls.map((c) => (c.method, c.arguments)), [('setSpatialDownmix', true), ('openSoundSettings', null)]);
    });

    test('should_sayASpeakerAndNoSpatialAudio_when_pluginMissing', () async {
      final host = ChannelAudioHost(channel: const MethodChannel('yoin/audio-missing'));
      expect(await host.currentOutput(), AudioOutputKind.speaker);
      expect(await host.spatial(), SpatialAudioCapability.none);
      expect(await host.openSoundSettings(), isFalse);
    });

    test('should_treatUnknownNamesAsSpeaker_when_parsing', () {
      expect(AudioOutputKind.fromName('hdmi'), AudioOutputKind.speaker);
      expect(SpatialAudioCapability.fromMap('nope'), SpatialAudioCapability.none);
    });
  });

  group('volume keys', () {
    const channel = MethodChannel('yoin/volume_keys');

    test('should_askAndRelayPresses_when_intercepting', () async {
      final asked = <Object?>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        asked.add(call.arguments);
        return null;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final keys = ChannelVolumeKeyHost();
      final presses = <int>[];
      keys.presses.listen(presses.add);
      await keys.setIntercepting(true);
      await push('yoin/volume_keys', 'volumeKey', 1);
      await push('yoin/volume_keys', 'volumeKey', -1);
      await keys.setIntercepting(false);
      expect(asked, [true, false]);
      expect(presses, [1, -1]);
    });
  });

  group('engine format', () {
    test('should_parseTheDecodersReport_when_playingFormatArrives', () {
      final event = EngineEvent.fromMap({
        'type': 'playingFormat',
        'id': 'subsonic:s1',
        'entryId': 'e1',
        'fromFile': true,
        'mime': 'audio/flac',
        'channels': 6,
        'sampleRateHz': 48000,
        'bitDepth': 24,
        'bitRate': 2304000,
      });
      expect(event, isA<PlayingFormatEvent>());
      final format = event! as PlayingFormatEvent;
      expect(format.entryId, 'e1');
      expect(format.fromFile, isTrue);
      expect(format.decoded.codec, 'flac');
      expect(format.decoded.channels, 6);
      expect(format.decoded.bitRateKbps, 2304);
      final item = EngineItem(
        id: 'subsonic:s1',
        uri: 'u',
        title: 't',
        delivery: const StreamDelivery(maxBitRateKbps: 320, format: 'mp3'),
        fileDelivery: StreamDelivery.original,
      );
      expect(format.deliveryOf(item), StreamDelivery.original);
    });

    test('should_leaveUnknownsNull_when_decoderSaysZero', () {
      final format =
          EngineEvent.fromMap({'type': 'playingFormat', 'id': 'x', 'channels': 0, 'bitRate': 0})! as PlayingFormatEvent;
      expect(format.decoded.channels, isNull);
      expect(format.decoded.bitRateKbps, isNull);
      expect(format.decoded.codec, isNull);
      expect(format.fromFile, isFalse);
    });

    test('should_roundTripDeliveries_when_itemEchoed', () {
      const item = EngineItem(
        id: 'subsonic:s1',
        uri: 'u',
        title: 't',
        delivery: StreamDelivery(maxBitRateKbps: 192, format: 'mp3'),
        fileDelivery: StreamDelivery.original,
      );
      final back = EngineItem.fromMap(item.toMap());
      expect(back.delivery, const StreamDelivery(maxBitRateKbps: 192, format: 'mp3'));
      expect(back.fileDelivery, StreamDelivery.original);
      final plain = EngineItem.fromMap(const EngineItem(id: 'a', uri: 'u', title: 't').toMap());
      expect(plain.delivery, isNull);
      expect(plain.fileDelivery, isNull);
    });
  });
}
