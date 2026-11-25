// Screenshots of the album About on the scrapbook and its sheet, phone and tablet, light and dark, English
// and Chinese, with the app's fonts (Google Sans Flex, HarmonyOS Sans SC, Yoin Symbols). Off by default:
//
//   flutter test test/detail/album_about_screenshots_test.dart \
//     --dart-define=YOIN_SCREENSHOT_DIR=/some/dir [--dart-define=YOIN_CJK_FONT_DIR=~/Library/Fonts]
//
// Writes PNGs into YOIN_SCREENSHOT_DIR; HarmonyOS Sans SC is read from YOIN_CJK_FONT_DIR when given.
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/about/album_about_store.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/albumabout/album_about_models.dart';
import 'package:yoin/data/albumabout/yoin_album_about.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_ai_client.dart';
import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

const _dir = String.fromEnvironment('YOIN_SCREENSHOT_DIR');
const _cjkDir = String.fromEnvironment('YOIN_CJK_FONT_DIR');

final _album = Album(
  id: MediaId.subsonic('al1'),
  name: 'Blue',
  artist: 'Joni Mitchell',
  releaseDate: '1971-06-22',
  recordLabels: const ['Reprise'],
  tracks: [
    for (final (i, t) in ['All I Want', 'My Old Man', 'Little Green', 'Carey', 'Blue', 'California'].indexed)
      Track(id: MediaId.subsonic('t$i'), title: t, artist: 'Joni Mitchell', trackNumber: i + 1, durationSec: 200),
  ],
);

const _blurb = {
  'en':
      "Blue is Joni Mitchell's fourth studio album, recorded at A&M Studios in Los Angeles in early 1971 after a "
      'long stay in Europe. Spare and confessional, it sets her voice against piano, acoustic guitar and '
      'Appalachian dulcimer, with James Taylor and Stephen Stills sitting in on a few songs. It is often named '
      'among the defining records of the singer-songwriter era; Mitchell later said she had no defences left '
      'when she made it.',
  'zh':
      '《Blue》是琼尼·米切尔的第四张录音室专辑，1971 年初她结束欧洲长途旅行后在洛杉矶 A&M 录音室录成。编制极简，只有人声、'
      '钢琴、木吉他和阿巴拉契亚扬琴，詹姆斯·泰勒和斯蒂芬·斯蒂尔斯在几首歌里客串。它常被视为创作歌手时代的标志性唱片，'
      '米切尔后来说，录这张专辑时她已经毫无防备。',
};

const _credits = {
  'en': AlbumAiCredits(
    producers: ['Joni Mitchell'],
    mixing: ['Henry Lewy'],
    recordedTime: 'Jan–Mar 1971',
    recordedStudios: ['A&M Studios, Los Angeles'],
  ),
  'zh': AlbumAiCredits(
    producers: ['Joni Mitchell'],
    mixing: ['Henry Lewy'],
    recordedTime: '1971年1月至3月',
    recordedStudios: ['A&M Studios，洛杉矶'],
  ),
};

const _question = {'en': 'Who plays the dulcimer?', 'zh': '扬琴是谁弹的？'};
const _title = {'en': 'Mitchell on dulcimer', 'zh': '米切尔自己弹的扬琴'};
const _answer = {
  'en': 'Mitchell plays it herself: she bought an Appalachian dulcimer in 1969 and wrote several of the songs on it.',
  'zh': '是米切尔自己。她 1969 年买了一把阿巴拉契亚扬琴，专辑里好几首歌都是用它写的。',
};

AlbumAboutEntry _row(String kind, String key, String answer, {String? prompt, String? title, int at = 1}) =>
    AlbumAboutEntry(
      provider: 'subsonic',
      albumId: 'al1',
      albumDisplay: 'Blue',
      artistDisplay: 'Joni Mitchell',
      kind: kind,
      entryKey: key,
      promptText: prompt,
      titleText: title,
      answerText: answer,
      createdAt: at,
      updatedAt: at,
    );

enum _State { generated, empty, questions }

List<AlbumAboutEntry> _rows(_State state, String lang) => switch (state) {
  _State.generated => [
    _row('canonical', 'credits', _credits[lang]!.encode()),
    _row('canonical', 'blurb', _blurb[lang]!),
    _row(
      'ask',
      YoinAlbumAbout.questionKey(_question[lang]!),
      _answer[lang]!,
      prompt: _question[lang],
      title: _title[lang],
    ),
  ],
  _State.empty => [_row('canonical', 'credits', '{}'), _row('canonical', 'blurb', '')],
  _State.questions => [
    _row(
      'ask',
      YoinAlbumAbout.questionKey(_question[lang]!),
      _answer[lang]!,
      prompt: _question[lang],
      title: _title[lang],
    ),
  ],
};

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
  // The sibling checkout pubspec.yaml builds Yoin Symbols from.
  await load('packages/yoin_symbols/YoinSymbols', ['../../yoin-symbols/flutter/fonts/YoinSymbols.ttf']);
  if (_cjkDir.isNotEmpty) {
    await load('HarmonyOS Sans SC', [
      for (final w in ['Regular', 'Medium', 'Bold']) '$_cjkDir/HarmonyOS_Sans_SC_$w.ttf',
    ]);
  }
}

void main() {
  setUpAll(_loadFonts);

  const sizes = {'phone': Size(412, 915), 'tablet': Size(1280, 800)};

  Future<void> shoot(
    WidgetTester tester, {
    required String name,
    required Size size,
    required Brightness brightness,
    required String lang,
    required _State state,
    bool sheet = false,
  }) async {
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final kv = InMemoryStore();
    await AlbumAboutStore(kv).upsertAll(_rows(state, lang));
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activityLogProvider.overrideWith((ref) async => null),
        memoryStoreProvider.overrideWith((ref) async => MemoryStore(InMemoryStore(), profileId: 'p')),
        playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
        aiClientProvider.overrideWithValue(FakeAiClient()),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: UncontrolledProviderScope(
          container: c,
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
                    onPressed: () => DetailRoutes.openAlbum(context, _album.id),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    Future<void> settle() async {
      for (var i = 0; i < 25; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
    }

    await tester.tap(find.text('open'));
    await settle();
    await tester.tap(find.bySemanticsLabel(lang == 'zh' ? '剪贴簿' : 'Scrapbook'));
    await settle();
    if (sheet) {
      final card = state == _State.empty
          ? find.byKey(const ValueKey('albumAboutEmpty'))
          : find.text(state == _State.generated ? _blurb[lang]! : _question[lang]!);
      await tester.tap(card.first);
      await settle();
    }
    final render = boundary.currentContext!.findRenderObject()! as RenderRepaintBoundary;
    await tester.runAsync(() async {
      final image = await render.toImage(pixelRatio: 2);
      final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
      Directory(_dir).createSync(recursive: true);
      File('$_dir/$name.png').writeAsBytesSync(bytes!.buffer.asUint8List());
    });
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  for (final MapEntry(key: device, value: size) in sizes.entries) {
    for (final brightness in Brightness.values) {
      for (final lang in ['en', 'zh']) {
        final tag = '$device-${brightness.name}-$lang';
        testWidgets('scrapbook $tag', (tester) async {
          await shoot(
            tester,
            name: 'scrapbook-$tag',
            size: size,
            brightness: brightness,
            lang: lang,
            state: _State.generated,
          );
        }, skip: _dir.isEmpty);
        testWidgets('sheet $tag', (tester) async {
          await shoot(
            tester,
            name: 'sheet-$tag',
            size: size,
            brightness: brightness,
            lang: lang,
            state: _State.generated,
            sheet: true,
          );
        }, skip: _dir.isEmpty);
      }
    }
  }
  for (final state in [_State.empty, _State.questions]) {
    for (final lang in ['en', 'zh']) {
      testWidgets('${state.name} phone $lang', (tester) async {
        await shoot(
          tester,
          name: 'scrapbook-${state.name}-phone-light-$lang',
          size: sizes['phone']!,
          brightness: Brightness.light,
          lang: lang,
          state: state,
        );
        await shoot(
          tester,
          name: 'sheet-${state.name}-phone-light-$lang',
          size: sizes['phone']!,
          brightness: Brightness.light,
          lang: lang,
          state: state,
          sheet: true,
        );
      }, skip: _dir.isEmpty);
    }
  }
}
