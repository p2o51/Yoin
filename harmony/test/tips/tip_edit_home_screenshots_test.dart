// Screenshots of the Rearrange Home tip's demo (parity p4 C3): a phone's (edit, pinch, drag) and a large
// screen's (a tablet on its side, adding the drop beside), at a few moments of the script, light and dark, English
// and Chinese, with the app's fonts. Off by default:
//
//   flutter test test/tips/tip_edit_home_screenshots_test.dart \
//     --dart-define=YOIN_SCREENSHOT_DIR=/some/dir [--dart-define=YOIN_CJK_FONT_DIR=~/Library/Fonts]
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/tips/tip_illustration.dart';

import '../support/fake_music_source.dart';

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

void main() {
  setUpAll(_loadFonts);

  // The moments to keep (ms into the page): edit mode, mid-pinch, the list, the drag up, (wide) the drop beside
  // and the pair it makes. YOIN_PROBE=true writes a frame every 250ms instead (one variant), to find them.
  const probe = bool.fromEnvironment('YOIN_PROBE');
  final moments = probe
      ? {for (var t = 1000; t < 16000; t += 250) 't$t': t}
      : const {'edit': 2500, 'pinching': 4000, 'list': 5000, 'drag': 9750, 'beside': 13750, 'paired': 15500};

  for (final wide in [false, true]) {
    for (final brightness in Brightness.values) {
      for (final lang in ['en', 'zh']) {
        final tag = '${wide ? 'tablet' : 'phone'}-${brightness.name}-$lang';
        testWidgets('rearrange home tip demo $tag', (tester) async {
          HomeBubbleTip.largeScreenOverride = wide;
          addTearDown(() => HomeBubbleTip.largeScreenOverride = null);
          tester.view.physicalSize = const Size(1000, 760) * 2;
          tester.view.devicePixelRatio = 2;
          addTearDown(tester.view.reset);
          final store = InMemoryStore();
          final boundary = GlobalKey();
          await tester.pumpWidget(
            ProviderScope(
              overrides: [
                keyValueStoreProvider.overrideWithValue(store),
                secretStoreProvider.overrideWithValue(store),
                musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
              ],
              child: MaterialApp(
                debugShowCheckedModeBanner: false,
                theme: yoinTheme(brightness),
                locale: Locale(lang),
                supportedLocales: const [Locale('en'), Locale('zh')],
                localizationsDelegates: GlobalMaterialLocalizations.delegates,
                home: Scaffold(
                  body: Center(
                    child: RepaintBoundary(
                      key: boundary,
                      child: const SizedBox(
                        width: 640,
                        height: 420,
                        child: TipIllustration(tip: HomeBubbleTip.editHome, playing: true),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          );
          var t = 0;
          for (final MapEntry(key: name, value: at) in moments.entries) {
            if ((name == 'beside' || name == 'paired') && !wide) continue;
            if (probe && !(wide && brightness == Brightness.light && lang == 'en')) break;
            while (t < at) {
              await tester.pump(const Duration(milliseconds: 16));
              t += 16;
            }
            final render = boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
            await tester.runAsync(() async {
              final image = await render.toImage(pixelRatio: 2);
              final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
              Directory(_dir).createSync(recursive: true);
              File('$_dir/tip-rearrange-home-$name-$tag.png').writeAsBytesSync(bytes!.buffer.asUint8List());
            });
          }
          await tester.pumpWidget(const SizedBox());
          await tester.pump(const Duration(seconds: 1));
        }, skip: _dir.isEmpty);
      }
    }
  }
}
