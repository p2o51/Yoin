import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/haptics.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel(ChannelHapticsHost.channelName);
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
    YoinHaptics.host = const NoHapticsHost();
  });

  group('ChannelHapticsHost', () {
    test('should_sendNamedHaptic_when_performed', () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return null;
      });
      ChannelHapticsHost().perform(YoinHaptic.segmentTick);
      await Future<void>.delayed(Duration.zero);
      expect(calls.single.method, 'perform');
      expect(calls.single.arguments, {'name': 'segmentTick'});
    });

    test('should_supportPatterns_when_motorSaysSo', () async {
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => call.method == 'capabilities' ? {'patterns': true} : null,
      );
      final host = ChannelHapticsHost();
      expect(host.supportsPatterns, isFalse); // until it answers
      await host.init();
      expect(host.supportsPatterns, isTrue);
    });

    test('should_stayQuiet_when_pluginMissing', () async {
      final host = ChannelHapticsHost();
      await host.init();
      expect(host.supportsPatterns, isFalse);
      host
        ..perform(YoinHaptic.click)
        ..playPattern(const [HapticPulse(atMs: 0, intensity: 1, frequency: 0.5)])
        ..cancelPattern();
      await Future<void>.delayed(Duration.zero);
    });

    test('should_sendPulsesAsPercent_when_patternPlayed', () async {
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return null;
      });
      ChannelHapticsHost().playPattern(const [
        HapticPulse(atMs: 12, intensity: 0.35, frequency: 0.85),
        HapticPulse(atMs: 80, intensity: 1.4, frequency: 0.7, durationMs: 90, rise: true),
      ]);
      await Future<void>.delayed(Duration.zero);
      expect(calls.single.method, 'pattern');
      expect((calls.single.arguments as Map)['pulses'], [
        {'at': 12, 'intensity': 35, 'frequency': 85, 'duration': 0, 'rise': false},
        {'at': 80, 'intensity': 100, 'frequency': 70, 'duration': 90, 'rise': true},
      ]);
    });
  });

  group('YoinHaptics', () {
    test('should_playEachNamedHaptic_when_called', () {
      final host = RecordingHapticsHost();
      YoinHaptics.host = host;
      YoinHaptics.click();
      YoinHaptics.contextClick();
      YoinHaptics.tick();
      YoinHaptics.lightTick();
      YoinHaptics.confirm();
      YoinHaptics.reject();
      YoinHaptics.longPress();
      YoinHaptics.dragStart();
      YoinHaptics.segmentTick();
      YoinHaptics.frequentTick();
      YoinHaptics.threshold();
      YoinHaptics.toggle(on: true);
      YoinHaptics.toggle(on: false);
      expect(host.performed, [
        YoinHaptic.click,
        YoinHaptic.contextClick,
        YoinHaptic.tick,
        YoinHaptic.lightTick,
        YoinHaptic.confirm,
        YoinHaptic.reject,
        YoinHaptic.longPress,
        YoinHaptic.dragStart,
        YoinHaptic.segmentTick,
        YoinHaptic.frequentTick,
        YoinHaptic.threshold,
        YoinHaptic.toggleOn,
        YoinHaptic.toggleOff,
      ]);
    });
  });
}
