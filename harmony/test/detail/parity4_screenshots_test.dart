// Screenshots of HarmonyOS parity 4 (album release date, the track sheet's @ editor, AI service naming),
// phone and tablet, light and dark, English and Chinese, with the app's fonts (Google Sans Flex,
// HarmonyOS Sans SC, Yoin Symbols). Off by default:
//
//   flutter test test/detail/parity4_screenshots_test.dart \
//     --dart-define=YOIN_SCREENSHOT_DIR=/some/dir [--dart-define=YOIN_CJK_FONT_DIR=~/Library/Fonts]
//
// Writes PNGs into YOIN_SCREENSHOT_DIR; HarmonyOS Sans SC is read from YOIN_CJK_FONT_DIR, else ~/Library/Fonts.
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_scrapbook_track_sheet.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/notes/editor/note_markup_field.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/ai_service_scope.dart';

import '../support/fake_ai_client.dart';
import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';
import '../support/nowplaying_fakes.dart';

const _dir = String.fromEnvironment('YOIN_SCREENSHOT_DIR');
const _cjkDir = String.fromEnvironment('YOIN_CJK_FONT_DIR');

final _album = Album(
  id: MediaId.subsonic('al1'),
  name: 'Blue',
  artist: 'Joni Mitchell',
  year: 1971,
  releaseDate: '1971-06-22',
  tracks: [
    for (final (i, t) in ['All I Want', 'My Old Man', 'Little Green', 'Carey', 'Blue', 'California'].indexed)
      Track(
        id: MediaId.subsonic('t$i'),
        title: t,
        artist: 'Joni Mitchell',
        album: 'Blue',
        albumId: MediaId.subsonic('al1'),
        trackNumber: i + 1,
        durationSec: 200 + i * 37,
      ),
  ],
);

const _noteText = {'en': 'the dulcimer comes in here', 'zh': '扬琴从这里进来'};

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
  // The receipt's 'monospace' is the system's on a device; the Mac's Courier New stands in.
  await load('monospace', ['/System/Library/Fonts/Supplemental/Courier New.ttf']);
  final cjk = _cjkDir.isNotEmpty ? _cjkDir : '${Platform.environment['HOME']}/Library/Fonts';
  await load('HarmonyOS Sans SC', [for (final w in ['Regular', 'Medium', 'Bold']) '$cjk/HarmonyOS_Sans_SC_$w.ttf']);
}

Future<void> _settle(WidgetTester tester, {int frames = 25}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 100));
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

Widget _app({required Brightness brightness, required String lang, required Widget home}) => MaterialApp(
  debugShowCheckedModeBanner: false,
  theme: yoinTheme(brightness),
  locale: Locale(lang),
  supportedLocales: const [Locale('en'), Locale('zh')],
  localizationsDelegates: GlobalMaterialLocalizations.delegates,
  home: home,
);

void main() {
  setUpAll(_loadFonts);

  const sizes = {'phone': Size(412, 915), 'tablet': Size(1280, 800)};

  /// The album page (header, then pulled up to the date under the last track), its scrapbook's receipt,
  /// and the track sheet's note editor with a mention chip.
  Future<void> album(WidgetTester tester, String device, Size size, Brightness brightness, String lang) async {
    final tag = '$device-${brightness.name}-$lang';
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final store = MemoryStore(InMemoryStore(), profileId: 'p');
    for (var i = 0; i < 9; i++) {
      await store.recordAlbumPlay(_album.id, atMs: DateTime(2026, 11, 1 + i).millisecondsSinceEpoch);
    }
    await store.putSongNote(
      SongNote(
        id: 'n1',
        trackId: _album.tracks[3].id,
        albumId: _album.id,
        content: _noteText[lang]!,
        createdAt: 1,
        updatedAt: 1,
        positionMs: 61000,
      ),
    );
    final kv = InMemoryStore();
    final secrets = InMemoryStore();
    await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(secrets),
        activityLogProvider.overrideWith((ref) async => null),
        memoryStoreProvider.overrideWith((ref) async => store),
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
          child: ActiveAiServiceScope(
            child: _app(
              brightness: brightness,
              lang: lang,
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
      ),
    );
    await tester.tap(find.text('open'));
    await _settle(tester);
    await _capture(tester, boundary, 'album-header-$tag');

    // Pull the hero up into the track list.
    await tester.drag(find.textContaining('Little', findRichText: true).first, const Offset(0, -600));
    await _settle(tester);
    final date = find.byKey(const ValueKey('album-release-date'));
    if (date.evaluate().isEmpty) {
      await tester.scrollUntilVisible(date, 200, scrollable: find.byType(Scrollable).last);
      await _settle(tester);
    }
    await _capture(tester, boundary, 'album-end-$tag');

    await tester.tap(find.bySemanticsLabel(lang == 'zh' ? '剪贴簿' : 'Scrapbook'));
    await _settle(tester);
    final receipt = find.text('Released');
    await tester.scrollUntilVisible(receipt, 250, scrollable: find.byType(Scrollable).last);
    await _settle(tester);
    await _capture(tester, boundary, 'receipt-$tag');

    // Back up to the note, its sheet on Notes, then Edit: the @ editor with a mention chip.
    await tester.scrollUntilVisible(find.text(_noteText[lang]!), -250, scrollable: find.byType(Scrollable).last);
    await _settle(tester);
    await tester.tap(find.text(_noteText[lang]!).first);
    await _settle(tester);
    await tester.tap(find.descendant(of: find.byType(ScrapTrackSheet), matching: find.text('1:01')));
    await _settle(tester);
    await tester.tap(find.text(lang == 'zh' ? '修改' : 'Edit'));
    await _settle(tester);
    final field = tester.widget<NoteMarkupField>(find.byType(NoteMarkupField));
    field.controller.text = '${_noteText[lang]!}${lang == 'zh' ? '，像 ' : ', like '}';
    field.controller.insertMention(
      NoteMention(kind: MentionKind.album, provider: 'subsonic', id: 'al9', title: 'Court and Spark'),
    );
    await _settle(tester);
    expect(find.byType(AlbumScrapbookPage), findsOneWidget);
    await _capture(tester, boundary, 'note-editor-$tag');

    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  /// Now Playing's expanded About with Qwen chosen: the Ask bar names it.
  Future<void> nowPlaying(WidgetTester tester, String device, Size size, Brightness brightness, String lang) async {
    final tag = '$device-${brightness.name}-$lang';
    tester.view.physicalSize = size * 2;
    tester.view.devicePixelRatio = 2;
    addTearDown(tester.view.reset);
    final kv = InMemoryStore();
    final secrets = InMemoryStore();
    await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
    final playback = NowPlayingFakeController(playingState([_album.tracks[4]]));
    final controller = NowPlayingOverlayController(vsync: tester);
    addTearDown(controller.dispose);
    controller.window = YoinWindowInfo(size.width, size.height);
    controller.height = size.height;
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: playback),
            upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
            keyValueStoreProvider.overrideWithValue(kv),
            secretStoreProvider.overrideWithValue(secrets),
            aiClientProvider.overrideWithValue(
              FakeAiClient(
                reply:
                    '[CREATION_TIME]1971[/CREATION_TIME][LYRICIST]Joni Mitchell[/LYRICIST]'
                    '[COMPOSER]Joni Mitchell[/COMPOSER][PRODUCER]Joni Mitchell[/PRODUCER]'
                    '[REVIEW]${lang == 'zh' ? '一首安静的歌。' : 'A quiet song.'}[/REVIEW]',
              ),
            ),
          ],
          child: ActiveAiServiceScope(
            child: _app(
              brightness: brightness,
              lang: lang,
              home: Material(
                child: NowPlayingOverlayScope(
                  controller: controller,
                  child: NowPlayingScreen(controller: controller),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    controller.openPlayer();
    await _settle(tester);
    if (size.width > 1000 && !controller.fullscreen) {
      controller.toggleFullscreen();
      await _settle(tester);
    }
    await tester.tap(find.text(lang == 'zh' ? '关于' : 'About').last);
    await _settle(tester);
    controller.expandStage();
    await _settle(tester);
    await _capture(tester, boundary, 'np-ask-$tag');
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  for (final MapEntry(key: device, value: size) in sizes.entries) {
    for (final brightness in Brightness.values) {
      for (final lang in ['en', 'zh']) {
        final tag = '$device-${brightness.name}-$lang';
        testWidgets('album $tag', (tester) => album(tester, device, size, brightness, lang), skip: _dir.isEmpty);
        testWidgets(
          'now playing $tag',
          (tester) => nowPlaying(tester, device, size, brightness, lang),
          skip: _dir.isEmpty,
        );
      }
    }
  }
}
