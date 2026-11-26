// Screenshots of the Explicit "E" / release kinds port (Android PR #343): Library's Albums grid on the
// backdrop shapes in the covers' colours with the band's marks (an EP, a single, an explicit album with a
// version tag, a plain album) and the kind filter; the album header of an explicit EP; Now Playing's
// portrait hero with an explicit title. Phone, light and dark, English and Chinese, with the app's fonts
// (Google Sans Flex, HarmonyOS Sans SC, Yoin Symbols). Off by default:
//
//   flutter test test/library/parity_explicit_screenshots_test.dart \
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
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/release/release_marks.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/home/widgets/cover_palette.dart';
import 'package:yoin/library/library_screen.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/artwork_image.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';
import '../support/nowplaying_fakes.dart';

const _dir = String.fromEnvironment('YOIN_SCREENSHOT_DIR');
const _cjkDir = String.fromEnvironment('YOIN_CJK_FONT_DIR');

CoverRef _demo(int i) => CoverUrl(DemoArtwork.url(i));

/// The fake source, its made-up covers passed through as they are (the album page asks the source).
class _DemoSource extends LibraryFakeSource {
  _DemoSource({super.albums, super.albumDetails});

  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) =>
      ref is CoverUrl ? ref.url : super.resolveCoverUrl(ref, size: size);
}

final _albums = [
  Album(
    id: MediaId.subsonic('ep'),
    name: 'Section.80 - EP',
    artist: 'Kendrick Lamar',
    coverArt: _demo(0),
    releaseType: ReleaseType.ep,
    contentRating: ContentRating.explicit,
    year: 2011,
  ),
  Album(
    id: MediaId.subsonic('single'),
    name: 'HUMBLE. - Single',
    artist: 'Kendrick Lamar',
    coverArt: _demo(1),
    releaseType: ReleaseType.single,
    year: 2017,
  ),
  Album(
    id: MediaId.subsonic('explicit'),
    name: 'DAMN. (Deluxe)',
    version: 'Deluxe',
    artist: 'Kendrick Lamar',
    coverArt: _demo(2),
    releaseType: ReleaseType.album,
    contentRating: ContentRating.explicit,
    year: 2017,
  ),
  Album(
    id: MediaId.subsonic('plain'),
    name: 'Blue',
    artist: 'Joni Mitchell',
    coverArt: _demo(3),
    releaseType: ReleaseType.album,
    contentRating: ContentRating.clean,
    year: 1971,
  ),
  Album(
    id: MediaId.subsonic('compilation'),
    name: 'Now 100',
    artist: 'Various Artists',
    coverArt: _demo(4),
    releaseType: ReleaseType.compilation,
    year: 2018,
  ),
  Album(id: MediaId.subsonic('unknown'), name: 'Court and Spark', artist: 'Joni Mitchell', coverArt: _demo(5)),
];

final _ep = Album(
  id: MediaId.subsonic('ep'),
  name: 'Section.80 - EP',
  artist: 'Kendrick Lamar',
  coverArt: _demo(0),
  releaseType: ReleaseType.ep,
  contentRating: ContentRating.explicit,
  year: 2011,
  releaseDate: '2011-07-02',
  tracks: [
    for (final (i, (t, explicit)) in [
      ('Fuck Your Ethnicity', true),
      ('Hol\' Up', true),
      ('A.D.H.D', true),
      ('No Make-Up (Her Vice)', false),
      ('Tammy\'s Song (Her Evils)', false),
    ].indexed)
      Track(
        id: MediaId.subsonic('t$i'),
        title: t,
        artist: 'Kendrick Lamar',
        album: 'Section.80 - EP',
        albumId: MediaId.subsonic('ep'),
        trackNumber: i + 1,
        durationSec: 180 + i * 23,
        coverArt: _demo(0),
        contentRating: explicit ? ContentRating.explicit : ContentRating.clean,
      ),
  ],
);

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

/// Reads the demo covers' colours for real (the palette runs in an isolate) and lets their pixels paint.
Future<void> _warmCovers(WidgetTester tester) async {
  await tester.runAsync(() async {
    for (var i = 0; i < DemoArtwork.count; i++) {
      await CoverPaletteCache.instance.colorsFor(DemoArtwork.url(i), DemoArtwork(i));
    }
  });
}

Future<void> _settle(WidgetTester tester, {int frames = 25}) async {
  for (var i = 0; i < frames; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
  // The demo covers paint off the fake clock.
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 300)));
  for (var i = 0; i < 5; i++) {
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

const _phone = Size(412, 915);

void _phoneView(WidgetTester tester) {
  tester.view.physicalSize = _phone * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
}

ProviderContainer _container(LibraryFakeSource source) {
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(c.dispose);
  return c;
}

void main() {
  setUpAll(_loadFonts);

  Future<void> library(WidgetTester tester, Brightness brightness, String lang) async {
    final tag = '${brightness.name}-$lang';
    _phoneView(tester);
    await _warmCovers(tester);
    final c = _container(_DemoSource(albums: _albums));
    c.read(shellSectionProvider.notifier).select(YoinSection.library);
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: UncontrolledProviderScope(
          container: c,
          child: _app(
            brightness: brightness,
            lang: lang,
            home: const Scaffold(body: LibraryScreen()),
          ),
        ),
      ),
    );
    await _settle(tester, frames: 10);
    await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.albums);
    await _settle(tester);
    await _capture(tester, boundary, 'library-grid-$tag');
    // Singles & EPs: the filter narrows the shelf.
    c.read(libraryViewModelProvider.notifier).selectAlbumKindFilter(ReleaseKindFilter.singlesAndEps);
    await _settle(tester);
    await _capture(tester, boundary, 'library-grid-singles-eps-$tag');
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  Future<void> albumHeader(WidgetTester tester, Brightness brightness, String lang) async {
    final tag = '${brightness.name}-$lang';
    _phoneView(tester);
    await _warmCovers(tester);
    final c = _container(_DemoSource(albumDetails: {_ep.id: _ep}));
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: UncontrolledProviderScope(
          container: c,
          child: _app(
            brightness: brightness,
            lang: lang,
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => DetailRoutes.openAlbum(context, _ep.id),
                    child: const Text('open'),
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
    await _capture(tester, boundary, 'album-header-ep-$tag');
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  Future<void> nowPlaying(WidgetTester tester, Brightness brightness, String lang) async {
    final tag = '${brightness.name}-$lang';
    _phoneView(tester);
    final playback = NowPlayingFakeController(playingState([_ep.tracks[1], _ep.tracks[3]]));
    final controller = NowPlayingOverlayController(vsync: tester);
    addTearDown(controller.dispose);
    controller.window = YoinWindowInfo(_phone.width, _phone.height);
    controller.height = _phone.height;
    final boundary = GlobalKey();
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: playback),
            upNextPrefetchDelayProvider.overrideWithValue(Duration.zero),
            keyValueStoreProvider.overrideWithValue(InMemoryStore()),
            secretStoreProvider.overrideWithValue(InMemoryStore()),
          ],
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
    );
    controller.openPlayer();
    await _settle(tester);
    await _capture(tester, boundary, 'now-playing-hero-$tag');
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(milliseconds: 1));
  }

  for (final brightness in Brightness.values) {
    for (final lang in ['en', 'zh']) {
      final tag = '${brightness.name}-$lang';
      testWidgets('library grid $tag', (tester) => library(tester, brightness, lang), skip: _dir.isEmpty);
      testWidgets('album header $tag', (tester) => albumHeader(tester, brightness, lang), skip: _dir.isEmpty);
      testWidgets('now playing $tag', (tester) => nowPlaying(tester, brightness, lang), skip: _dir.isEmpty);
    }
  }
}
