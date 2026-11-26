// Screenshots of HarmonyOS Home edit v2 (parity p4 C1): a block mid-drag in place (its drop slot framed, the
// neighbours made way) and the compact list a pinch folds the feed into; phone and tablet, light and dark,
// English and Chinese, with the app's fonts. Off by default:
//
//   flutter test test/home/home_edit_v2_screenshots_test.dart \
//     --dart-define=YOIN_SCREENSHOT_DIR=/some/dir [--dart-define=YOIN_CJK_FONT_DIR=~/Library/Fonts]
//
// Writes PNGs into YOIN_SCREENSHOT_DIR; HarmonyOS Sans SC is read from YOIN_CJK_FONT_DIR, else ~/Library/Fonts.
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

const _dir = String.fromEnvironment('YOIN_SCREENSHOT_DIR');
const _cjkDir = String.fromEnvironment('YOIN_CJK_FONT_DIR');

Future<void> _loadFonts() async {
  Future<void> load(String family, List<String> files) async {
    final loader = FontLoader(family);
    for (final f in files) {
      final file = File(f);
      if (file.existsSync()) loader.addFont(Future.value(ByteData.sublistView(file.readAsBytesSync())));
    }
    await loader.load();
  }

  await load('GoogleSansFlex', ['assets/fonts/GoogleSansFlex-Variable.ttf']);
  await load('packages/yoin_symbols/YoinSymbols', ['../../yoin-symbols/flutter/fonts/YoinSymbols.ttf']);
  final cjk = _cjkDir.isNotEmpty ? _cjkDir : '${Platform.environment['HOME']}/Library/Fonts';
  await load('HarmonyOS Sans SC', [
    for (final w in ['Regular', 'Medium', 'Bold']) '$cjk/HarmonyOS_Sans_SC_$w.ttf',
  ]);
}

Future<void> _frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}

Future<void> _capture(WidgetTester tester, GlobalKey boundary, String name) async {
  final render = boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  await tester.runAsync(() async {
    final image = await render.toImage(pixelRatio: 2);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    Directory(_dir).createSync(recursive: true);
    File('$_dir/$name.png').writeAsBytesSync(bytes!.buffer.asUint8List());
  });
}

void main() {
  setUpAll(_loadFonts);

  const sizes = {'phone': Size(412, 915), 'tablet': Size(1280, 800)};

  Future<({ProviderContainer container, GlobalKey boundary})> pumpHome(
    WidgetTester tester,
    Size size,
    Brightness brightness,
    String lang,
  ) async {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(
        playlists: [playlist('p1'), playlist('p2'), playlist('p3')],
        recent: [for (var i = 0; i < 6; i++) album('r$i')],
      ),
      log: MemoryActivityLog([for (var i = 0; i < 5; i++) albumEvent('a$i', minutesAgo: 1 + i)]),
    );
    final profiles = jsonDecode(store.values['profiles.v1']!) as List;
    final id = (profiles.single as Map)['id'] as String;
    store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
      HomeGridPools(
        cachedAtMs: homeTestNow.millisecondsSinceEpoch,
        albums: [for (var i = 0; i < 9; i++) album('g$i')],
      ).toJson(),
    );
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: UncontrolledProviderScope(
          container: h.container,
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: yoinTheme(brightness),
            locale: Locale(lang),
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            builder: (context, child) => YoinWindowScope.measured(child: child!),
            home: const Scaffold(body: HomeScreen()),
          ),
        ),
      ),
    );
    await _frames(tester, 40);
    h.container.read(homeEditProvider.notifier).enter();
    await _frames(tester, 40);
    return (container: h.container, boundary: boundary);
  }

  Future<void> finish(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  for (final MapEntry(key: device, value: size) in sizes.entries) {
    for (final brightness in Brightness.values) {
      for (final lang in ['en', 'zh']) {
        final tag = '$device-${brightness.name}-$lang';

        testWidgets('home edit drag mid-state $tag', (tester) async {
          final (:container, :boundary) = await pumpHome(tester, size, brightness, lang);
          // Activities' handle, held and dragged down past Jump Back In: lifted under the finger, its slot
          // framed, Jump Back In made way above it. Short of the bottom band: no edge scroll in the picture.
          final handle = find.byIcon(YoinSymbols.dragHandle).first;
          final gesture = await tester.startGesture(tester.getCenter(handle));
          for (var i = 0; i < 13; i++) {
            await gesture.moveBy(const Offset(0, 20));
            await tester.pump(const Duration(milliseconds: 16));
          }
          await _frames(tester, 20);
          await _capture(tester, boundary, 'home-edit-drag-$tag');
          await gesture.up();
          await _frames(tester, 30);
          await finish(tester, container);
        }, skip: _dir.isEmpty);

        testWidgets('home edit pinch list $tag', (tester) async {
          final (:container, :boundary) = await pumpHome(tester, size, brightness, lang);
          final mid = size.height / 2;
          final a = await tester.startGesture(Offset(size.width / 2, mid - 220), pointer: 1);
          final b = await tester.startGesture(Offset(size.width / 2, mid + 220), pointer: 2);
          for (var i = 0; i < 10; i++) {
            await a.moveBy(const Offset(0, 14));
            await b.moveBy(const Offset(0, -14));
            await tester.pump(const Duration(milliseconds: 16));
          }
          // Mid-pinch: the list follows the fingers.
          await _capture(tester, boundary, 'home-edit-pinching-$tag');
          await b.up();
          await a.up();
          await _frames(tester, 40);
          await _capture(tester, boundary, 'home-edit-list-$tag');
          await finish(tester, container);
        }, skip: _dir.isEmpty);
      }
    }
  }
}
