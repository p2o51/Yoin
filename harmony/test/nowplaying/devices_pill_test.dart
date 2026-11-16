import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/devices/devices_pill.dart';
import 'package:yoin/nowplaying/devices/devices_sheet.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';

Future<NowPlayingOverlayController> _pump(WidgetTester tester, Size size, {bool settle = true}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final tracks = [
    for (var i = 0; i < 2; i++)
      Track(id: MediaId.subsonic('t$i'), title: 'Song $i', artist: 'Artist $i', album: 'Album', durationSec: 200),
  ];
  final controller = NowPlayingOverlayController(vsync: tester);
  addTearDown(controller.dispose);
  controller.window = YoinWindowInfo(size.width, size.height);
  controller.height = size.height;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        ...nowPlayingOverrides(controller: NowPlayingFakeController(playingState(tracks))),
        upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
      ],
      child: MaterialApp(
        home: Material(
          child: NowPlayingOverlayScope(
            controller: controller,
            child: NowPlayingScreen(controller: controller),
          ),
        ),
      ),
    ),
  );
  controller.openPlayer();
  if (settle) {
    await tester.pumpAndSettle();
  } else {
    // A title that marquees never settles: let the opening spring land frame by frame.
    for (var i = 0; i < 40; i++) {
      await tester.pump(const Duration(milliseconds: 100));
    }
  }
  return controller;
}

final _devicesPill = find.byKey(const ValueKey('np-pill-devices'));

void main() {
  testWidgets('should_showQueueDevicesAndWriteWithLabels_when_phonePortrait', (tester) async {
    // The test font sets every glyph a full em wide: this phone is wide enough for its labels.
    await _pump(tester, const Size(440, 900));
    expect(find.byKey(const ValueKey('np-pill-queue')), findsOneWidget);
    expect(_devicesPill, findsOneWidget);
    expect(find.byType(NowPlayingWritePill), findsOneWidget);
    final pill = tester.widget<NowPlayingPillButton>(_devicesPill);
    expect(pill.showLabel, isTrue);
    // Queue, Devices, Write: left to right.
    final queueX = tester.getCenter(find.byKey(const ValueKey('np-pill-queue'))).dx;
    final devicesX = tester.getCenter(_devicesPill).dx;
    final writeX = tester.getCenter(find.byType(NowPlayingWritePill)).dx;
    expect(queueX < devicesX && devicesX < writeX, isTrue);
  });

  testWidgets('should_openTheDevicesSheet_when_theDevicesPillIsTapped', (tester) async {
    await _pump(tester, const Size(412, 892));
    await tester.tap(_devicesPill);
    await tester.pumpAndSettle();
    expect(find.byType(DevicesSheet), findsOneWidget);
    expect(find.text('This device'), findsOneWidget);
  });

  testWidgets('should_foldEveryPillToItsIcon_when_theWindowIsNarrow', (tester) async {
    await _pump(tester, const Size(360, 780), settle: false);
    final pill = tester.widget<NowPlayingPillButton>(_devicesPill);
    expect(pill.showLabel, isFalse);
    expect(tester.takeException(), isNull);
    // The marquee never rests: take the screen down so its ticker goes with it.
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('should_offerDevicesInTheTopBar_when_dualPane', (tester) async {
    final c = await _pump(tester, const Size(1280, 800));
    if (!c.fullscreen) {
      c.toggleFullscreen();
      await tester.pumpAndSettle();
    }
    final button = find.byKey(const ValueKey('np-topbar-devices'));
    expect(button, findsOneWidget);
    await tester.tap(button);
    await tester.pumpAndSettle();
    expect(find.byType(DevicesSheet), findsOneWidget);
  });

  testWidgets('should_offerDevicesInTheHeader_when_landscape', (tester) async {
    await _pump(tester, const Size(892, 412));
    expect(find.byKey(const ValueKey('np-topbar-devices')), findsOneWidget);
  });
}
