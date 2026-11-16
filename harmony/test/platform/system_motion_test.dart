import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/motion/system_motion.dart';
import 'package:yoin/ui/component/system_reduce_motion_scope.dart';

/// A host the test drives: each [emit] is a change the system reports.
final class _FakeMotionHost implements SystemMotionHost {
  _FakeMotionHost([this.current = SystemMotionSettings.normal]);

  final _controller = StreamController<SystemMotionSettings>.broadcast();

  @override
  SystemMotionSettings current;

  void emit(SystemMotionSettings settings) => _controller.add(current = settings);

  @override
  Future<SystemMotionSettings> read() async => current;

  @override
  Stream<SystemMotionSettings> watch() => _controller.stream;
}

/// Records what the subtree sees as `disableAnimations` on each build.
class _Probe extends StatelessWidget {
  const _Probe(this.seen);

  final List<bool> seen;

  @override
  Widget build(BuildContext context) {
    seen.add(MediaQuery.disableAnimationsOf(context));
    return const SizedBox();
  }
}

Future<void> _pushFromPlugin(Map<String, Object?> map) =>
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelSystemMotionHost.channelName,
      const StandardMethodCodec().encodeMethodCall(MethodCall('motion', map)),
      (_) {},
    );

void main() {
  group('reducesMotion', () {
    test('should_reduceMotion_when_animatorDurationScaleIsZero', () {
      expect(const SystemMotionSettings(animatorDurationScale: 0).reducesMotion, isTrue);
    });

    test('should_keepFullMotion_when_animatorDurationScaleIsSlowedOrNormal', () {
      for (final scale in [0.5, 1.0, 10.0]) {
        expect(SystemMotionSettings(animatorDurationScale: scale).reducesMotion, isFalse, reason: 'scale $scale');
      }
    });

    test('should_reduceMotion_when_accessibilityAnimationReduceIsOn', () {
      expect(const SystemMotionSettings(animationReduce: true).reducesMotion, isTrue);
      expect(const SystemMotionSettings(animationReduce: true, animatorDurationScale: 0).reducesMotion, isTrue);
      expect(SystemMotionSettings.normal.reducesMotion, isFalse);
    });

    test('should_reduceMotion_when_deviceSavesPower', () {
      expect(const SystemMotionSettings(powerSave: true).reducesMotion, isTrue);
    });
  });

  group('fromMap', () {
    test('should_readBothSettings_when_pluginReportsThem', () {
      final settings = SystemMotionSettings.fromMap({'animationReduce': true, 'animatorDurationScale': 0});
      expect(settings, const SystemMotionSettings(animationReduce: true, animatorDurationScale: 0));
      expect(SystemMotionSettings.fromMap({'animatorDurationScale': 1.5}).animatorDurationScale, 1.5);
      expect(SystemMotionSettings.fromMap({'powerSave': true}), const SystemMotionSettings(powerSave: true));
    });

    test('should_useSystemDefaults_when_valuesAreMissingOrUnreadable', () {
      expect(SystemMotionSettings.fromMap(const {}), SystemMotionSettings.normal);
      expect(
        SystemMotionSettings.fromMap({'animationReduce': 'yes', 'animatorDurationScale': -1, 'powerSave': 1}),
        SystemMotionSettings.normal,
      );
      expect(SystemMotionSettings.fromMap({'animatorDurationScale': double.nan}), SystemMotionSettings.normal);
      expect(SystemMotionSettings.fromMap({'animatorDurationScale': '0'}), SystemMotionSettings.normal);
    });
  });

  group('the channel host', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel(ChannelSystemMotionHost.channelName);
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

    tearDown(() => messenger.setMockMethodCallHandler(channel, null));

    test('should_emitReadThenPushes_when_listened', () async {
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'read');
        return {'animationReduce': false, 'animatorDurationScale': 1.0};
      });
      final host = ChannelSystemMotionHost();
      final seen = <SystemMotionSettings>[];
      final sub = host.watch().listen(seen.add);
      await Future<void>.delayed(Duration.zero);
      expect(seen.single, SystemMotionSettings.normal);
      // The user turns 减弱动画 on while Yoin runs.
      await _pushFromPlugin({'animationReduce': true, 'animatorDurationScale': 1.0});
      expect(seen.last.reducesMotion, isTrue);
      // The same reading again is not a change.
      await _pushFromPlugin({'animationReduce': true, 'animatorDurationScale': 1.0});
      expect(seen, hasLength(2));
      // And off again.
      await _pushFromPlugin({'animationReduce': false, 'animatorDurationScale': 1.0});
      expect(seen.last.reducesMotion, isFalse);
      await sub.cancel();
    });

    test('should_readAgain_when_appComesBackToFront', () async {
      var scale = 1.0;
      var reads = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        reads++;
        return {'animationReduce': false, 'animatorDurationScale': scale};
      });
      final host = ChannelSystemMotionHost();
      final seen = <SystemMotionSettings>[];
      final sub = host.watch().listen(seen.add);
      await Future<void>.delayed(Duration.zero);
      expect(reads, 1);
      // Animations turned off in Developer options while Yoin was in the background.
      scale = 0;
      final binding = TestWidgetsFlutterBinding.instance;
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
      binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await Future<void>.delayed(Duration.zero);
      expect(reads, 2);
      expect(seen.last.reducesMotion, isTrue);
      await sub.cancel();
    });

    test('should_readNormal_when_noPlugin', () async {
      final host = ChannelSystemMotionHost();
      final first = await host.watch().first.timeout(const Duration(seconds: 1));
      expect(first, SystemMotionSettings.normal);
      expect(await host.read(), SystemMotionSettings.normal);
    });

    test('should_holdReadingAsCurrent_when_readBeforeAnyoneListens', () async {
      messenger.setMockMethodCallHandler(channel, (call) async => {'animationReduce': true});
      final host = ChannelSystemMotionHost();
      expect(host.current, SystemMotionSettings.normal);
      // main.dart's read before runApp.
      expect((await host.read()).reducesMotion, isTrue);
      expect(host.current.reducesMotion, isTrue);
      // The first listener (the app root) gets it at once.
      final first = await host.watch().first;
      expect(first.reducesMotion, isTrue);
    });
  });

  test('should_reportNormal_when_hostHasNothingToRead', () async {
    expect(await const NoSystemMotionHost().watch().first, SystemMotionSettings.normal);
  });

  group('SystemReduceMotionScope', () {
    testWidgets('should_disableAnimationsBelow_when_systemReducesMotion', (tester) async {
      final host = _FakeMotionHost();
      final seen = <bool>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [systemMotionHostProvider.overrideWithValue(host)],
          child: SystemReduceMotionScope(child: _Probe(seen)),
        ),
      );
      // The stream's event, then Riverpod's rebuild a frame later.
      Future<void> report(SystemMotionSettings settings) async {
        host.emit(settings);
        await tester.pump();
        await tester.pump();
      }

      // Nothing read yet: full motion.
      expect(seen.last, isFalse);

      await report(const SystemMotionSettings(animationReduce: true));
      expect(seen.last, isTrue);

      // Turned off while the app runs: full motion again.
      await report(SystemMotionSettings.normal);
      expect(seen.last, isFalse);

      // Animations off in Developer options.
      await report(const SystemMotionSettings(animatorDurationScale: 0));
      expect(seen.last, isTrue);

      // Slowed animations are still animations.
      await report(const SystemMotionSettings(animatorDurationScale: 5));
      expect(seen.last, isFalse);
    });

    testWidgets('should_disableAnimationsOnFirstFrame_when_systemReducedMotionAtLaunch', (tester) async {
      final host = _FakeMotionHost(const SystemMotionSettings(animationReduce: true));
      final seen = <bool>[];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [systemMotionHostProvider.overrideWithValue(host)],
          child: SystemReduceMotionScope(child: _Probe(seen)),
        ),
      );
      // No full-motion frame first: decisions made once on the first frame (Home's intro) already see it.
      expect(seen, [true]);
    });

    testWidgets('should_keepPlatformFlag_when_systemSettingsAskForNothing', (tester) async {
      final seen = <bool>[];
      await tester.pumpWidget(
        MediaQuery(
          data: const MediaQueryData(disableAnimations: true),
          child: ProviderScope(child: SystemReduceMotionScope(child: _Probe(seen))),
        ),
      );
      await tester.pump();
      expect(seen.last, isTrue);
    });

    testWidgets('should_keepSubtreeState_when_settingFlips', (tester) async {
      final key = GlobalKey<State<StatefulWidget>>();
      Widget app(bool reduce) => ReduceMotionOverride(
        reduce: reduce,
        child: _Stateful(key: key),
      );
      await tester.pumpWidget(app(false));
      final before = key.currentState;
      await tester.pumpWidget(app(true));
      expect(key.currentState, same(before));
    });
  });
}

class _Stateful extends StatefulWidget {
  const _Stateful({super.key});

  @override
  State<_Stateful> createState() => _StatefulState();
}

class _StatefulState extends State<_Stateful> {
  @override
  Widget build(BuildContext context) => const SizedBox();
}
