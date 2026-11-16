import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_hero_marks.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_spectrum_layer.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/detail_share.dart';
import 'package:yoin/detail/playlist_sleeves.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/media_artwork.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';
import '../support/link_fakes.dart';

final _album = LibraryFixtures.album(
  'al1',
  name: 'Blue',
  artist: 'Joni',
  year: 1971,
  tracks: [for (var i = 1; i <= 12; i++) LibraryFixtures.track('t$i', artist: 'Joni', number: i)],
);

final _artist = ArtistDetail(
  id: MediaId.subsonic('ar1'),
  name: 'Joni Mitchell',
  albums: [for (var i = 0; i < 4; i++) LibraryFixtures.album('ra$i', name: 'Record $i', year: 1970 + i)],
);

final _playlist = Playlist(
  id: MediaId.subsonic('pl1'),
  name: 'Road',
  owner: 'me',
  songCount: 6,
  tracks: [for (var i = 1; i <= 6; i++) LibraryFixtures.track('p$i', albumId: 'pa${i % 3}')],
);

enum _Page { album, artist, playlist }

/// A provider without favourites (Apple Music, Spotify's remote): nothing to follow.
class _NoFavoritesSource extends LibraryFakeSource {
  _NoFavoritesSource({super.albumDetails, super.artistDetails, super.playlistDetails});

  @override
  Set<Capability> get capabilities => const {Capability.search, Capability.playlistsRead};
}

/// Opens [page] in a [size] window (its own YoinWindowInfo, as the app measures it).
Future<void> _open(WidgetTester tester, _Page page, Size size, {bool favorites = true}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  DetailShare.resetForTest();
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith(
        (ref) async => favorites
            ? LibraryFakeSource(
                albumDetails: {_album.id: _album},
                artistDetails: {_artist.id: _artist},
                playlistDetails: {_playlist.id: _playlist},
              )
            : _NoFavoritesSource(
                albumDetails: {_album.id: _album},
                artistDetails: {_artist.id: _artist},
                playlistDetails: {_playlist.id: _playlist},
              ),
      ),
      textShareProvider.overrideWithValue(RecordingTextShare()),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(c.dispose);
  c.listen(playbackControllerProvider, (_, _) {});
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        builder: (context, child) => YoinWindowScope.measured(child: child!),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => switch (page) {
                  _Page.album => DetailRoutes.openAlbum(context, _album.id),
                  _Page.artist => DetailRoutes.openArtist(context, _artist.id),
                  _Page.playlist => DetailRoutes.openPlaylist(context, _playlist.id),
                },
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await _settle(tester);
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// Unmounts the page inside the test body (its bar registration leaves a frame later).
Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}

/// The cover the page flies into the bar (decoded at 640) or sets in place.
Finder _cover(int decode) => find.byWidgetPredicate((w) => w is MediaArtwork && w.decodeWidth == decode);

void main() {
  group('Album', () {
    testWidgets('should_setCoverBesideStackedMeta_when_windowTooShortForHero', (tester) async {
      await _open(tester, _Page.album, const Size(420, 640));

      // Short: the cover on the 16 line, Last Play stacked beside it without its caption.
      final cover = tester.getRect(_cover(640));
      expect(cover.left, closeTo(16, 0.5));
      expect(cover.width, closeTo(260, 0.5));
      expect(find.byType(AlbumLastPlayBlock), findsOneWidget);
      expect(find.text('Last Play'), findsNothing);
      expect(find.text('Never'), findsOneWidget);
      // No butterfly on short screens (owner 2026-11-07); the dots sit in the title row.
      expect(find.byType(AlbumButterfly), findsNothing);
      expect(tester.getCenter(find.byType(AlbumPageDots)).dy, closeTo(tester.getCenter(find.text('Blue')).dy, 16));
      await _close(tester);
    });

    testWidgets('should_pullUpIntoList_when_shortHeroDragged', (tester) async {
      await _open(tester, _Page.album, const Size(420, 640));
      await tester.dragFrom(const Offset(210, 560), const Offset(0, -400));
      await _settle(tester);

      // The cover docked as the stamp at the header's end; the rows are live.
      expect(tester.getRect(_cover(640)).width, closeTo(48, 0.5));
      expect(find.text('Song t1'), findsOneWidget);

      // The liner notes at the list's end run its full width: Last Play keeps its caption there.
      await tester.dragFrom(const Offset(210, 500), const Offset(0, -2000));
      await _settle(tester);
      expect(find.text('Last Play'), findsOneWidget);
      await _close(tester);
    });

    testWidgets('should_turnHeroSidewaysWithHuggingMark_when_landscapeHandset', (tester) async {
      await _open(tester, _Page.album, const Size(844, 390));

      final cover = tester.getRect(_cover(640));
      expect(cover.width, closeTo(256, 0.5));
      final butterfly = tester.widget<AlbumButterfly>(find.byType(AlbumButterfly));
      expect(butterfly.hugging, isTrue);
      // What sits under the cover on a phone sits beside it here.
      expect(tester.getTopLeft(find.text('Last Play')).dx, greaterThan(cover.right));
      await _close(tester);
    });

    testWidgets('should_centreHeroRowInPageColumn_when_tablet', (tester) async {
      await _open(tester, _Page.album, const Size(800, 1280));

      // The hero row: cover 240 at the 720 column's start, the meta beside it, no butterfly.
      final rest = tester.getRect(_cover(640));
      expect(rest.width, closeTo(240, 0.5));
      expect(rest.left, closeTo(56, 0.5));
      expect(find.byType(AlbumButterfly), findsNothing);
      await _close(tester);
    });

    testWidgets('should_formStampOnScrollAndReturnOnTap_when_medium', (tester) async {
      await _open(tester, _Page.album, const Size(700, 1000));

      final rest = tester.getRect(_cover(640));
      expect(rest.width, closeTo(240, 0.5));
      expect(rest.left, closeTo(16, 0.5));
      expect(find.byType(AlbumButterfly), findsNothing);
      expect(find.text('Last Play'), findsOneWidget);

      await tester.drag(find.text('Song t3'), const Offset(0, -400));
      await _settle(tester);
      expect(tester.getRect(_cover(640)).width, closeTo(48, 0.5));

      // The stamp is a button: back to the top of the page.
      await tester.tap(_cover(640));
      await _settle(tester);
      expect(tester.getRect(_cover(640)).topLeft.dy, closeTo(rest.top, 0.5));
      await _close(tester);
    });

    testWidgets('should_setIdentityColumnBesideList_when_wide', (tester) async {
      await _open(tester, _Page.album, const Size(1280, 800));

      // No bar: the 360 cover stays in its column; the whole list beside it.
      expect(find.byType(AlbumSpectrumLayer), findsNothing);
      final cover = tester.getRect(_cover(900));
      expect(cover.width, closeTo(360, 0.5));
      expect(find.byType(AlbumButterfly), findsNothing);
      expect(tester.getTopLeft(find.text('Song t1')).dx, greaterThan(cover.right));
      await _close(tester);
    });
    testWidgets('should_keepScrapbookPage_when_layoutChangesWithWindow', (tester) async {
      await _open(tester, _Page.album, const Size(420, 900));
      final dots = tester.getRect(find.byType(AlbumPageDots));
      await tester.tapAt(Offset(dots.right - 6, dots.center.dy));
      await _settle(tester);
      double page() => tester.widget<PageView>(find.byType(PageView)).controller!.page!;
      expect(page(), closeTo(1, 0.01));

      // A rotation or a resize that changes the layout keeps the page shown (one pager, hoisted).
      for (final size in const [Size(1280, 800), Size(800, 1280), Size(844, 390), Size(420, 900)]) {
        tester.view.physicalSize = size;
        await _settle(tester);
        expect(page(), closeTo(1, 0.01), reason: '$size');
      }
      await _close(tester);
    });
  });

  group('Playlist', () {
    testWidgets('should_setCoverOnSleevesInHeroRow_when_medium', (tester) async {
      await _open(tester, _Page.playlist, const Size(800, 1280));

      // The cover sits in the row on its sleeves; no stamp on a playlist past Compact.
      expect(find.byType(AlbumSpectrumLayer), findsNothing);
      expect(tester.getRect(_cover(640)).width, closeTo(240, 0.5));
      expect(find.byType(PlaylistCoverSleeves), findsOneWidget);
      expect(find.text('Length'), findsOneWidget);
      await _close(tester);
    });

    testWidgets('should_pullUpIntoList_when_landscapeHeroDragged', (tester) async {
      await _open(tester, _Page.playlist, const Size(844, 390));
      expect(tester.getRect(_cover(640)).width, closeTo(256, 0.5));
      expect(find.text('Song p1'), findsNothing);

      await tester.dragFrom(const Offset(600, 300), const Offset(0, -300));
      await _settle(tester);
      expect(find.text('Song p1'), findsOneWidget);
      await _close(tester);
    });

    testWidgets('should_setIdentityColumn_when_wide', (tester) async {
      await _open(tester, _Page.playlist, const Size(1280, 800));
      expect(tester.getRect(_cover(900)).width, closeTo(360, 0.5));
      expect(find.text('Song p1'), findsOneWidget);
      await _close(tester);
    });
  });

  group('Artist', () {
    testWidgets('should_moveNameAndStarIntoHero_when_medium', (tester) async {
      await _open(tester, _Page.artist, const Size(800, 1280));

      // The name appears once, in the hero's identity column, with the star's label.
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Favorite'), findsOneWidget);
      expect(tester.getTopLeft(find.text('Joni Mitchell')).dy, greaterThan(100));
      await _close(tester);
    });

    testWidgets('should_dropStar_when_providerHasNoFavorites', (tester) async {
      // Medium: the hero would carry the star with its label; phone: the header would.
      await _open(tester, _Page.artist, const Size(800, 1280), favorites: false);
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Favorite'), findsNothing);
      expect(find.byIcon(YoinSymbols.star), findsNothing);
      await _close(tester);

      await _open(tester, _Page.artist, const Size(390, 844), favorites: false);
      expect(find.byIcon(YoinSymbols.star), findsNothing);
      await _close(tester);
    });

    testWidgets('should_showStarInHeader_when_phoneAndProviderHasFavorites', (tester) async {
      await _open(tester, _Page.artist, const Size(390, 844));
      expect(find.byIcon(YoinSymbols.star), findsOneWidget);
      // The label is the hero's alone (Medium up).
      expect(find.text('Favorite'), findsNothing);
      await _close(tester);
    });

    testWidgets('should_keepNameInHeader_when_landscapeHandset', (tester) async {
      await _open(tester, _Page.artist, const Size(844, 390));

      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(tester.getTopLeft(find.text('Joni Mitchell')).dy, lessThan(60));
      expect(find.text('Favorite'), findsNothing);
      await _close(tester);
    });

    testWidgets('should_setMostPlayedBesideDiscography_when_wide', (tester) async {
      await _open(tester, _Page.artist, const Size(1280, 800));

      expect(find.byWidgetPredicate((w) => w is SpringSizeReveal && w.axis == Axis.horizontal), findsOneWidget);
      expect(find.text('Joni Mitchell'), findsOneWidget);
      await _close(tester);
    });
  });
}
