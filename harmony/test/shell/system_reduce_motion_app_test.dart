import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/landing/landing_store.dart';
import 'package:yoin/main.dart';
import 'package:yoin/platform/motion/system_motion.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';

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

void main() {
  testWidgets('should_reduceMotionInPages_when_systemTurnsReduceMotionOnWhileAppRuns', (tester) async {
    final store = InMemoryStore()..values[KeyValueLandingStore.key] = 'true';
    final host = _FakeMotionHost();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          systemMotionHostProvider.overrideWithValue(host),
        ],
        child: const YoinApp(),
      ),
    );
    await tester.pump();
    bool homeReduced() => MediaQuery.disableAnimationsOf(tester.element(find.byType(HomeScreen)));
    expect(homeReduced(), isFalse);

    // HarmonyOS 减弱动画 turned on: every reduced path below the app root sees it.
    host.emit(const SystemMotionSettings(animationReduce: true));
    await tester.pump();
    await tester.pump();
    expect(homeReduced(), isTrue);

    // And off again.
    host.emit(SystemMotionSettings.normal);
    await tester.pump();
    await tester.pump();
    expect(homeReduced(), isFalse);
  });

  testWidgets('should_reduceMotionFromFirstFrame_when_systemReducesMotionAtColdStart', (tester) async {
    final store = InMemoryStore()..values[KeyValueLandingStore.key] = 'true';
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(store),
          secretStoreProvider.overrideWithValue(store),
          musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          // Power saving was on before Yoin started (main.dart read it before runApp).
          systemMotionHostProvider.overrideWithValue(_FakeMotionHost(const SystemMotionSettings(powerSave: true))),
        ],
        child: const YoinApp(),
      ),
    );
    // The first frame Home is on screen (localizations load first) already reduces, with nothing emitted.
    await tester.pump();
    expect(MediaQuery.disableAnimationsOf(tester.element(find.byType(HomeScreen))), isTrue);
  });
}
