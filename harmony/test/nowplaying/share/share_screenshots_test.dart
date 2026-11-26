// Screenshots of the share sheet (phone and tablet, light and dark, English
// and Chinese, each tab) and of every exported image, with the app's fonts
// (Google Sans Flex, HarmonyOS Sans SC, Yoin Symbols). Off by default:
//
//   flutter test test/nowplaying/share/share_screenshots_test.dart \
//     --dart-define=YOIN_SCREENSHOT_DIR=/some/dir [--dart-define=YOIN_CJK_FONT_DIR=~/Library/Fonts]
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/nowplaying/lyrics_share.dart';
import 'package:yoin/nowplaying/share_cards.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/nowplaying/share_sheet.dart';
import 'package:yoin/platform/share/image_share.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../../support/library_fake_source.dart';
import 'share_fixtures.dart';

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
  if (_cjkDir.isNotEmpty) {
    await load('HarmonyOS Sans SC', [
      for (final w in ['Regular', 'Medium', 'Bold']) '$_cjkDir/HarmonyOS_Sans_SC_$w.ttf',
    ]);
  }
}

class _Share implements ImageShare {
  @override
  bool get canSave => true;

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async => true;

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async => true;
}

/// The fixture with a mention in its note (a chip with the kind's symbol: no cover is known offline).
ShareContent _content(String lang) {
  final base = shareFixture(lang: lang, translation: true);
  final note = base.note!;
  final mention = lang == 'zh'
      ? '[@晴天](yoin:track/subsonic:t1?a=%E5%91%A8%E6%9D%B0%E5%80%AB)'
      : '[@Hey Jude](yoin:track/subsonic:t2?a=The%20Beatles)';
  return ShareContent(
    song: base.song,
    lyrics: base.lyrics,
    about: base.about,
    note: ShareNote(
      noteId: note.noteId,
      content: '${note.content}\n${lang == 'zh' ? '像' : 'Like'} $mention',
      positionMs: note.positionMs,
      createdAt: note.createdAt,
    ),
    aiProvider: base.aiProvider,
  );
}

List<Override> _overrides() => [
  activeSourceProvider.overrideWith((ref) async => LibraryFakeSource()),
  keyValueStoreProvider.overrideWithValue(InMemoryStore()),
  secretStoreProvider.overrideWithValue(InMemoryStore()),
  activityLogProvider.overrideWith((ref) async => null),
  memoryStoreProvider.overrideWith((ref) async => MemoryStore(InMemoryStore(), profileId: 'p')),
  imageShareProvider.overrideWithValue(_Share()),
  shareLayoutStoreProvider.overrideWithValue(ShareLayoutStore.inMemory()),
];

Future<void> _write(WidgetTester tester, RenderRepaintBoundary render, String name, double ratio) async {
  await tester.runAsync(() async {
    final image = await render.toImage(pixelRatio: ratio);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    Directory(_dir).createSync(recursive: true);
    File('$_dir/$name.png').writeAsBytesSync(bytes!.buffer.asUint8List());
  });
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  setUpAll(_loadFonts);

  const sizes = {'phone': Size(412, 915), 'tablet': Size(1280, 800)};

  Future<void> shootSheet(
    WidgetTester tester, {
    required String name,
    required Size size,
    required Brightness brightness,
    required String lang,
    required ShareTab tab,
    ShareFormat format = ShareFormat.card,
  }) async {
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final content = _content(lang);
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: ProviderScope(
          overrides: _overrides(),
          child: MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: yoinTheme(brightness),
            locale: Locale(lang),
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => showShareSheet(context, content: content, initialTab: tab, onMessage: (_) {}),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.runAsync(
      () => precacheImage(shareCoverImage(content.song.coverArtUrl!), tester.element(find.text('open'))),
    );
    await tester.tap(find.text('open'));
    await _settle(tester);
    if (format == ShareFormat.story) {
      await tester.tap(find.byKey(const ValueKey('share-format-Story')));
      await _settle(tester);
    }
    await _write(tester, boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary, name, 2);
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  for (final MapEntry(key: device, value: size) in sizes.entries) {
    for (final brightness in Brightness.values) {
      for (final lang in ['en', 'zh']) {
        for (final tab in ShareTab.values) {
          final tag = '$device-${brightness.name}-$lang-${tab.storeKey.toLowerCase()}';
          testWidgets('sheet $tag', (tester) async {
            await shootSheet(tester, name: 'sheet-$tag', size: size, brightness: brightness, lang: lang, tab: tab);
          }, skip: _dir.isEmpty);
        }
      }
    }
  }
  testWidgets('sheet phone story', (tester) async {
    await shootSheet(
      tester,
      name: 'sheet-phone-light-zh-lyrics-story',
      size: sizes['phone']!,
      brightness: Brightness.light,
      lang: 'zh',
      tab: ShareTab.lyrics,
      format: ShareFormat.story,
    );
  }, skip: _dir.isEmpty);

  // Every exported image, as the PNG (Card 1440 wide, Story 1080 × 1920).
  for (final lang in ['zh', 'en']) {
    for (final brightness in Brightness.values) {
      testWidgets('images $lang ${brightness.name}', (tester) async {
        tester.view.physicalSize = const Size(800, 1400);
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final content = _content(lang);
        for (final layout in ShareLayout.values) {
          if (!content.layoutsFor(layout.tab).contains(layout)) continue;
          for (final format in ShareFormat.values) {
            final key = GlobalKey();
            final tones = layout.tab.role.tones();
            final tone = format == ShareFormat.card ? tones[layout.index % 4] : tones[0];
            await tester.pumpWidget(
              ProviderScope(
                overrides: _overrides(),
                child: MaterialApp(
                  debugShowCheckedModeBanner: false,
                  theme: yoinTheme(brightness),
                  locale: Locale(lang),
                  supportedLocales: const [Locale('en'), Locale('zh')],
                  localizationsDelegates: GlobalMaterialLocalizations.delegates,
                  home: SingleChildScrollView(
                    child: Align(
                      alignment: Alignment.topLeft,
                      child: RepaintBoundary(
                        key: key,
                        child: ShareImage(
                          content: content,
                          layout: layout,
                          format: format,
                          tone: tone,
                          animateTone: false,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
            await tester.runAsync(
              () => precacheImage(shareCoverImage(content.song.coverArtUrl!), tester.element(find.byType(ShareImage))),
            );
            await _settle(tester);
            await _write(
              tester,
              key.currentContext!.findRenderObject()! as RenderRepaintBoundary,
              'image-$lang-${brightness.name}-${layout.storeValue}-${format.kotlinName}-${tone.kotlinName}',
              sharePixelRatio(format) / 2,
            );
          }
        }
      }, skip: _dir.isEmpty);
    }
  }
}
