// Screenshots of HarmonyOS two-column Home (parity p4 C2): a MatePad held landscape (1280 × 800) at rest, in edit
// mode, mid-drag beside a section and mid span drag; light and dark, English and Chinese, with the app's fonts.
// Off by default:
//
//   flutter test test/home/home_two_column_screenshots_test.dart \
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
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_grid_resize.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
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

  const size = Size(1280, 800);

  Future<({ProviderContainer container, GlobalKey boundary})> pumpHome(
    WidgetTester tester,
    Brightness brightness,
    String lang,
  ) async {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final starred = Starred(
      tracks: [for (var i = 0; i < 16; i++) track('s$i', starred: '2026-11-1${i % 3}T00:00:00Z')],
      albums: [for (var i = 0; i < 20; i++) album('n$i', starred: '2026-11-0${1 + i % 9}T00:00:00Z')],
    );
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(
        starred: starred,
        playlists: [for (var i = 0; i < 8; i++) playlist('p$i')],
        recent: [for (var i = 0; i < 12; i++) album('r$i')],
      ),
      log: MemoryActivityLog([
        albumEvent('a0', minutesAgo: 1),
        for (var i = 1; i < 14; i++) i.isEven ? albumEvent('a$i', minutesAgo: i * 3) : playlistEvent('p$i', minutesAgo: i * 3),
      ]),
    );
    final profiles = jsonDecode(store.values['profiles.v1']!) as List;
    final id = (profiles.single as Map)['id'] as String;
    store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
      HomeGridPools(
        cachedAtMs: homeTestNow.millisecondsSinceEpoch,
        albums: [for (var i = 0; i < 14; i++) album('g$i')],
        tracks: [for (var i = 0; i < 10; i++) track('t$i')],
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
    return (container: h.container, boundary: boundary);
  }

  Future<void> finish(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  Finder plate(HomeSection s) => find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == s);

  for (final brightness in Brightness.values) {
    for (final lang in ['en', 'zh']) {
      final tag = 'tablet-landscape-${brightness.name}-$lang';

      testWidgets('two-column home $tag', (tester) async {
        final (:container, :boundary) = await pumpHome(tester, brightness, lang);
        await _capture(tester, boundary, 'home-2col-rest-$tag');
        container.read(homeEditProvider.notifier).enter();
        await _frames(tester, 40);
        await _capture(tester, boundary, 'home-2col-edit-$tag');
        // Recently Played (a full row) held and dragged right and up toward All Memories' row: it pairs beside it.
        final from = tester.getCenter(plate(HomeSection.memoryGrid));
        final gesture = await tester.startGesture(from);
        await tester.pump(const Duration(milliseconds: 400));
        for (var i = 0; i < 16; i++) {
          await gesture.moveBy(const Offset(25, 0));
          await tester.pump(const Duration(milliseconds: 16));
        }
        await _frames(tester, 10);
        await _capture(tester, boundary, 'home-2col-drag-$tag');
        await gesture.up();
        await _frames(tester, 40);
        await _capture(tester, boundary, 'home-2col-dropped-$tag');
        // The span ⌟ of the first row's left half dragged right: the dashed outline says full width.
        final handle = find.byKey(homeGridHandleKey(HomeSection.activities));
        final span = await tester.startGesture(tester.getCenter(handle));
        for (var i = 0; i < 20; i++) {
          await span.moveBy(const Offset(20, 0));
          await tester.pump(const Duration(milliseconds: 16));
        }
        await _frames(tester, 6);
        await _capture(tester, boundary, 'home-2col-span-$tag');
        await span.up();
        await _frames(tester, 40);
        await finish(tester, container);
      }, skip: _dir.isEmpty);
    }
  }
}
