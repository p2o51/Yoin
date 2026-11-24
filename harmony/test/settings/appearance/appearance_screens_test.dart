import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/platform/window/display_corners.dart';
import 'package:yoin/settings/appearance/appearance_group.dart';
import 'package:yoin/settings/appearance/screen_corner_control.dart';
import 'package:yoin/settings/appearance/screen_corners.dart';
import 'package:yoin/settings/appearance/screen_corners_screen.dart';
import 'package:yoin/settings/appearance/scroll_edge_screen.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/seam/chrome_seam.dart';
import 'package:yoin/ui/component/seam/seam.dart';
import 'package:yoin/ui/component/seam/seam_dissolve.dart';

import '../../support/fake_music_source.dart';

class _Display implements DisplayCorners {
  const _Display(this.value);

  final double? value;

  @override
  Future<double?> cornerRadius() async => value;
}

Iterable<ShaderMaskLayer> _masks(WidgetTester tester) sync* {
  Iterable<Layer> walk(Layer? l) sync* {
    for (var c = l; c != null; c = c.nextSibling) {
      yield c;
      if (c is ContainerLayer) yield* walk(c.firstChild);
    }
  }

  yield* walk(tester.layers.first).whereType<ShaderMaskLayer>();
}

void main() {
  late InMemoryStore store;

  List<Override> overrides({double? display}) => [
    keyValueStoreProvider.overrideWithValue(store),
    secretStoreProvider.overrideWithValue(store),
    musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
    displayCornersProvider.overrideWithValue(_Display(display)),
  ];

  Future<void> settle(WidgetTester tester) async {
    for (var i = 0; i < 6; i++) {
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pump();
    }
    await tester.pumpAndSettle();
  }

  Future<void> pump(WidgetTester tester, Widget home, {Size size = const Size(420, 900), double? display}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: overrides(display: display),
        child: MaterialApp(
          // Reduced motion: the previews hold still, so the page settles.
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(disableAnimations: true),
            child: YoinWindowScope.measured(child: child!),
          ),
          home: home,
        ),
      ),
    );
    await settle(tester);
  }

  setUp(() => store = InMemoryStore());

  group('settings list', () {
    testWidgets('should_showAppearanceUnderFeaturesAndAppearance_when_settingsOpen', (tester) async {
      await pump(tester, const SettingsScreen(), size: const Size(520, 2400));
      final features = tester.getTopLeft(find.text('Features and appearance')).dy;
      final appearance = tester.getTopLeft(find.text('Appearance')).dy;
      final sync = tester.getTopLeft(find.text('Sync and storage')).dy;
      expect(appearance, greaterThan(features));
      expect(sync, greaterThan(appearance));
      // Summary shows chosen scroll edge + screen corners as a MetaLine: no dot.
      expect(find.textContaining('Tide line', findRichText: true), findsOneWidget);
      expect(find.textContaining('Match screen', findRichText: true), findsOneWidget);
      expect(find.textContaining('·', findRichText: true), findsNothing);
    });

    testWidgets('should_openAppearancePageWithInlineScrollEdge_when_rowTapped', (tester) async {
      await pump(tester, const SettingsScreen(), size: const Size(420, 2400));
      await tester.tap(find.text('Appearance'));
      await tester.pumpAndSettle();
      expect(find.byType(AppearanceScreen), findsOneWidget);
      expect(find.byType(ScrollEdgeStyleCard), findsNWidgets(3));
      expect(find.text('Screen corners'), findsOneWidget);
    });

    testWidgets('should_openScreenCornersInPane_when_wideWindow', (tester) async {
      await pump(tester, const SettingsScreen(), size: const Size(1280, 2000));
      await tester.tap(find.text('Appearance'));
      await tester.pumpAndSettle();
      expect(find.byType(AppearanceScreen), findsOneWidget);
      await tester.ensureVisible(find.text('Screen corners'));
      await tester.tap(find.text('Screen corners'));
      await tester.pumpAndSettle();
      expect(find.byType(ScreenCornersScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget, reason: 'still beside it');
    });
  });

  group('scroll edge page', () {
    testWidgets('should_listEveryStyleWithDefaultBadge_when_open', (tester) async {
      await pump(tester, const ScrollEdgeScreen(), size: const Size(420, 1400));
      for (final name in ['Tide line', 'Dots', 'Cookie wave']) {
        expect(find.text(name), findsOneWidget);
      }
      expect(find.text('Default'), findsOneWidget);
      expect(find.byType(ScrollEdgeStyleCard), findsNWidgets(3));
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_applyAndStoreStyle_when_cardTapped', (tester) async {
      await pump(tester, const ScrollEdgeScreen(), size: const Size(420, 1400));
      await tester.tap(find.text('Cookie wave'));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(ScrollEdgeScreen)));
      expect(container.read(seamTopStyleProvider), SeamTopStyle.cookie);
      expect(store.values['seam_top_style'], 'cookie');
      final cards = tester.widgetList<ScrollEdgeStyleCard>(find.byType(ScrollEdgeStyleCard));
      expect([for (final c in cards) c.selected], [false, false, true]);
    });

    testWidgets('should_breakCoversIntoPrints_when_previewIsDotsOrCookie', (tester) async {
      await pump(tester, const ScrollEdgeScreen(), size: const Size(420, 1400));
      // Each preview rests part-way through its seam: Dots and Cookie mask the covers there, the tide paints over them.
      expect(_masks(tester), isNotEmpty);
      final tides = tester.widgetList<CustomPaint>(
        find.byWidgetPredicate((w) => w is CustomPaint && w.foregroundPainter is ChromeTidePainter),
      );
      expect(tides, isNotEmpty);
    });
  });

  group('chrome seam', () {
    Widget list(SeamTopStyle style) => Consumer(
      builder: (context, ref, _) => ChromeSeam(
        style: style,
        background: const SeamBackground([Colors.white]),
        child: ListView(
          children: [
            for (var i = 0; i < 30; i++)
              Padding(
                padding: const EdgeInsets.all(8),
                child: SizedBox(
                  height: 80,
                  child: Row(
                    children: [
                      const SizedBox.square(
                        dimension: 80,
                        child: SeamDissolve(child: ColoredBox(color: Colors.blue)),
                      ),
                      Expanded(child: SeamFade(fontSize: 16, child: Text('Row $i'))),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );

    Future<void> scrollTo(WidgetTester tester, double offset) async {
      tester.state<ScrollableState>(find.byType(Scrollable)).position.jumpTo(offset);
      await tester.pumpAndSettle();
    }

    testWidgets('should_maskNothing_when_atRest', (tester) async {
      await pump(tester, Scaffold(body: list(SeamTopStyle.dots)));
      expect(_masks(tester), isEmpty);
    });

    testWidgets('should_dissolveCoversAtTheSeam_when_dotsAndScrolled', (tester) async {
      await pump(tester, Scaffold(body: list(SeamTopStyle.dots)));
      await scrollTo(tester, 140);
      expect(_masks(tester), isNotEmpty);
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_cutTheLip_when_cookieAndScrolled', (tester) async {
      await pump(tester, Scaffold(body: list(SeamTopStyle.cookie)));
      await scrollTo(tester, 140);
      expect(_masks(tester), isNotEmpty);
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_paintTideAndLeaveCoversWhole_when_tideAndScrolled', (tester) async {
      await pump(tester, Scaffold(body: list(SeamTopStyle.tide)));
      await scrollTo(tester, 140);
      final painter =
          tester
                  .widget<CustomPaint>(
                    find.byWidgetPredicate((w) => w is CustomPaint && w.foregroundPainter is ChromeTidePainter),
                  )
                  .foregroundPainter!
              as ChromeTidePainter;
      expect(painter.seam.reveal, 1);
      expect(
        (Canvas c) => painter.paint(c, const Size(420, 900)),
        paints
          ..path()
          ..path(),
      );
      final covers = tester.renderObjectList<RenderSeamDissolve>(find.byType(SeamDissolve));
      expect(covers.every((r) => !r.alwaysNeedsCompositing), isTrue, reason: 'under the tide covers just go under');
    });

    testWidgets('should_followUserStyle_when_notPinned', (tester) async {
      await pump(
        tester,
        Scaffold(
          body: ChromeSeam(
            background: const SeamBackground([Colors.white]),
            child: ListView(
              children: [
                for (var i = 0; i < 30; i++)
                  const SizedBox(
                    height: 80,
                    child: SeamDissolve(child: ColoredBox(color: Colors.blue)),
                  ),
              ],
            ),
          ),
        ),
      );
      await scrollTo(tester, 130);
      expect(_masks(tester), isEmpty, reason: 'tide by default');
      final container = ProviderScope.containerOf(tester.element(find.byType(ChromeSeam)));
      await container.read(seamTopStyleProvider.notifier).select(SeamTopStyle.dots);
      await tester.pumpAndSettle();
      expect(_masks(tester), isNotEmpty, reason: 'repainted live in the new style');
    });
  });

  group('screen corners page', () {
    testWidgets('should_setCornerAndStoreOnRelease_when_barDragged', (tester) async {
      await pump(tester, const ScreenCornersScreen());
      final bar = find.byType(ScreenCornerBar);
      final gesture = await tester.startGesture(tester.getCenter(bar));
      await gesture.moveTo(tester.getTopRight(bar) + const Offset(40, ScreenCornerTokens.barHeight / 2));
      await tester.pump();
      final container = ProviderScope.containerOf(tester.element(bar));
      expect(container.read(screenCornerRadiusProvider), ScreenCornerTokens.max);
      expect(store.values.containsKey(ScreenCornersPreference.key), isFalse, reason: 'no write while moving');
      await gesture.up();
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(store.values[ScreenCornersPreference.key], '64.0');
      expect(find.text('Screen corners'), findsWidgets);
      expect(find.text('Match screen'), findsOneWidget);
    });

    testWidgets('should_followTheScreenAgain_when_matchScreenTapped', (tester) async {
      store.values[ScreenCornersPreference.key] = '12.0';
      await pump(tester, const ScreenCornersScreen(), display: 44);
      expect(find.text('12 dp'), findsOneWidget);
      await tester.tap(find.text('Match screen'));
      await tester.runAsync(() => Future<void>.delayed(Duration.zero));
      await tester.pumpAndSettle();
      expect(find.text('44 dp'), findsOneWidget);
      expect(find.textContaining('·'), findsNothing);
      expect(store.values.containsKey(ScreenCornersPreference.key), isFalse);
    });

    testWidgets('should_moveToNextExample_when_phoneTapped', (tester) async {
      await pump(tester, const ScreenCornersScreen());
      expect(find.text('Now Playing opening'), findsOneWidget);
      await tester.tap(find.byType(CornerExamples));
      await tester.pumpAndSettle();
      expect(find.text('Memories closing'), findsOneWidget);
    });

    test('should_keepALightPage_when_darkTheme', () {
      final dark = ColorScheme.fromSeed(seedColor: Colors.purple, brightness: Brightness.dark);
      final page = screenCornersPageScheme(dark);
      expect(page.primaryContainer, dark.onPrimaryContainer);
      expect(page.onSurface, dark.primaryContainer);
      final light = ColorScheme.fromSeed(seedColor: Colors.purple);
      expect(screenCornersPageScheme(light).primaryContainer, light.primaryContainer);
    });
  });
}
