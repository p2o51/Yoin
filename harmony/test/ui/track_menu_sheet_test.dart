import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/detail_share.dart';
import 'package:yoin/detail/scrapbook_focus.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/ui/component/track_menu_sheet.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../support/link_fakes.dart';
import '../support/nowplaying_fakes.dart';

class _QueueingController extends NowPlayingFakeController {
  _QueueingController(super.initial);

  @override
  Future<void> addToQueue(List<Track> tracks, {bool next = false}) async {
    calls.add('${next ? 'next' : 'queue'}:${tracks.map((t) => t.id.rawId).join(',')}');
    state = state.copyWith(entryIds: [...state.entryIds, 'new${state.entryIds.length}']);
  }
}

class _FavoriteSource extends NowPlayingFakeSource implements MusicWriteActions {
  _FavoriteSource({this.fail = false});
  final bool fail;
  final List<String> writes = [];

  @override
  MusicWriteActions get writeActions => this;

  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {
    writes.add('${id.rawId}:$favorite');
    if (fail) throw const SourceServerException('nope');
  }

  @override
  Future<void> setRating(MediaId trackId, int rating) async {}
}

final _song = Track(
  id: MediaId.subsonic('s1'),
  title: 'Lantern',
  artist: 'Mono',
  albumId: MediaId.subsonic('al1'),
  artistId: MediaId.subsonic('ar1'),
);

TrackMenuQuickAction _action(TrackMenuActionId id) =>
    TrackMenuQuickAction(id: id, label: id.name, icon: YoinSymbols.add, keepsOpen: true, onTap: () {});

void main() {
  group('trackMenuLayout', () {
    test('should_putQueueAndFavoriteOnTop_when_songIsNotPlaying', () {
      final layout = trackMenuLayout(
        nowPlaying: false,
        playNext: _action(TrackMenuActionId.playNext),
        addToQueue: _action(TrackMenuActionId.addToQueue),
        favorite: _action(TrackMenuActionId.favorite),
        addToPlaylist: _action(TrackMenuActionId.addToPlaylist),
      );
      expect(
        [for (final a in layout.quick) a.id],
        [TrackMenuActionId.playNext, TrackMenuActionId.addToQueue, TrackMenuActionId.favorite],
      );
      expect(layout.folded, isEmpty);
    });

    test('should_dropQueueActions_when_songIsPlaying', () {
      final layout = trackMenuLayout(
        nowPlaying: true,
        playNext: _action(TrackMenuActionId.playNext),
        addToQueue: _action(TrackMenuActionId.addToQueue),
        favorite: _action(TrackMenuActionId.favorite),
        addToPlaylist: _action(TrackMenuActionId.addToPlaylist),
      );
      expect([for (final a in layout.quick) a.id], [TrackMenuActionId.favorite, TrackMenuActionId.addToPlaylist]);
    });

    test('should_putShareLastOnTop_when_songHasIt', () {
      final layout = trackMenuLayout(
        nowPlaying: false,
        playNext: _action(TrackMenuActionId.playNext),
        addToQueue: _action(TrackMenuActionId.addToQueue),
        favorite: _action(TrackMenuActionId.favorite),
        share: _action(TrackMenuActionId.share),
      );
      expect(
        [for (final a in layout.quick) a.id],
        [TrackMenuActionId.playNext, TrackMenuActionId.addToQueue, TrackMenuActionId.favorite, TrackMenuActionId.share],
      );
      final playing = trackMenuLayout(
        nowPlaying: true,
        favorite: _action(TrackMenuActionId.favorite),
        addToPlaylist: _action(TrackMenuActionId.addToPlaylist),
        share: _action(TrackMenuActionId.share),
      );
      expect(
        [for (final a in playing.quick) a.id],
        [TrackMenuActionId.favorite, TrackMenuActionId.addToPlaylist, TrackMenuActionId.share],
      );
    });

    test('should_foldShareIntoList_when_itIsTheOnlyAction', () {
      final layout = trackMenuLayout(nowPlaying: true, share: _action(TrackMenuActionId.share));
      expect(layout.quick, isEmpty);
      expect([for (final a in layout.folded) a.id], [TrackMenuActionId.share]);
    });

    test('should_foldLoneActionIntoList_when_rowWouldHoldOne', () {
      final layout = trackMenuLayout(nowPlaying: true, addToPlaylist: _action(TrackMenuActionId.addToPlaylist));
      expect(layout.quick, isEmpty);
      expect([for (final a in layout.folded) a.id], [TrackMenuActionId.addToPlaylist]);
      // A lone Favorite leaves, as on Android.
      final favOnly = trackMenuLayout(nowPlaying: true, favorite: _action(TrackMenuActionId.favorite));
      expect(favOnly.quick, isEmpty);
      expect(favOnly.folded, isEmpty);
    });
  });

  group('TrackMenuSheet', () {
    late _QueueingController controller;
    late List<String> messages;
    late List<String> visits;

    late List<String> filed;
    late RecordingTextShare shared;

    Future<void> pump(
      WidgetTester tester, {
      bool nowPlaying = false,
      _FavoriteSource? source,
      bool canFile = false,
      Track? track,
      ValueChanged<MediaId>? onViewInScrapbook,
    }) async {
      filed = [];
      shared = RecordingTextShare();
      DetailShare.resetForTest();
      controller = _QueueingController(playingState(nowPlayingTracks(2)).copyWith(entryIds: ['e0', 'e1']));
      messages = [];
      visits = [];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            ...nowPlayingOverrides(controller: controller, source: source ?? _FavoriteSource()),
            textShareProvider.overrideWithValue(shared),
          ],
          child: MaterialApp(
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => showTrackMenuSheet(
                      context,
                      track: track ?? _song,
                      nowPlaying: nowPlaying,
                      onGoToAlbum: (id) => visits.add('album:${id.rawId}'),
                      onGoToArtist: (id) => visits.add('artist:${id.rawId}'),
                      onViewInScrapbook: onViewInScrapbook,
                      onAddToPlaylist: canFile ? (tracks) => filed.add(tracks.single.id.rawId) : null,
                      onMessage: messages.add,
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
    }

    testWidgets('should_queueNextAndStayOpen_when_playNextTapped', (tester) async {
      await pump(tester);
      expect(find.text('Lantern'), findsOneWidget);
      await tester.tap(find.text('Play next'));
      await tester.pumpAndSettle();
      expect(controller.calls, contains('next:s1'));
      expect(messages, ['1 song will play next']);
      expect(find.text('Lantern'), findsOneWidget);
    });

    testWidgets('should_appendToQueue_when_addToQueueTapped', (tester) async {
      await pump(tester);
      await tester.tap(find.text('Add to queue'));
      await tester.pumpAndSettle();
      expect(controller.calls, contains('queue:s1'));
      expect(messages, ['Added 1 song to the queue']);
    });

    testWidgets('should_writeFavoriteAndShowFavorited_when_favoriteTapped', (tester) async {
      final source = _FavoriteSource();
      await pump(tester, source: source);
      await tester.tap(find.text('Favorite'));
      await tester.pumpAndSettle();
      expect(source.writes, ['s1:true']);
      expect(find.text('Favorited'), findsOneWidget);
    });

    testWidgets('should_revertAndSayWhy_when_favoriteWriteFails', (tester) async {
      await pump(tester, source: _FavoriteSource(fail: true));
      await tester.tap(find.text('Favorite'));
      await tester.pumpAndSettle();
      expect(find.text('Favorite'), findsOneWidget);
      expect(messages, ["Couldn't save to favorites"]);
    });

    testWidgets('should_closeAndOpenAlbum_when_goToAlbumTapped', (tester) async {
      await pump(tester);
      await tester.tap(find.text('Go to album'));
      await tester.pumpAndSettle();
      expect(visits, ['album:al1']);
      expect(find.text('Lantern'), findsNothing);
    });

    testWidgets('should_leaveOutQueueActions_when_sheetIsForPlayingSong', (tester) async {
      await pump(tester, nowPlaying: true);
      expect(find.text('Play next'), findsNothing);
      expect(find.text('Add to queue'), findsNothing);
      expect(find.text('Go to artist'), findsOneWidget);
    });

    testWidgets('should_closeAndOpenAddToPlaylist_when_rowTapped', (tester) async {
      await pump(tester, canFile: true);
      await tester.tap(find.text('Add to playlist'));
      await tester.pumpAndSettle();
      expect(filed, ['s1']);
      expect(find.text('Lantern'), findsNothing);
    });

    testWidgets('should_putAddToPlaylistOnTop_when_sheetIsForPlayingSong', (tester) async {
      await pump(tester, nowPlaying: true, canFile: true);
      expect(find.text('Favorite'), findsOneWidget);
      expect(find.text('Add to playlist'), findsOneWidget);
    });

    testWidgets('should_shareTitleAndArtistAndClose_when_shareTapped', (tester) async {
      await pump(tester);
      expect(find.text('Share'), findsOneWidget);
      await tester.tap(find.text('Share'));
      await tester.pumpAndSettle();
      expect(shared.shared.single.$1, 'Lantern — Mono');
      expect(find.text('Lantern'), findsNothing);
    });

    testWidgets('should_moveToScrapbookInPlace_when_sheetIsOnTheAlbumPage', (tester) async {
      final viewed = <MediaId>[];
      await pump(tester, onViewInScrapbook: viewed.add);
      await tester.tap(find.text('View in Scrapbook'));
      await tester.pumpAndSettle();
      expect(viewed, [MediaId.subsonic('s1')]);
      expect(visits, isEmpty);
    });

    testWidgets('should_openAlbumAtScrapbook_when_viewInScrapbookTappedElsewhere', (tester) async {
      await pump(tester, nowPlaying: true);
      final container = ProviderScope.containerOf(tester.element(find.byType(Scaffold).first));
      await tester.tap(find.text('View in Scrapbook'));
      await tester.pumpAndSettle();
      expect(visits, ['album:al1']);
      expect(container.read(scrapbookFocusRequestProvider)?.songId, MediaId.subsonic('s1'));
    });

    testWidgets('should_offerEachArtist_when_songNamesSeveral', (tester) async {
      await pump(
        tester,
        track: Track(
          id: MediaId.subsonic('s2'),
          title: 'Duet',
          artist: 'Ann & Bo',
          artistId: MediaId.subsonic('a'),
          albumId: MediaId.subsonic('al1'),
          artists: [ArtistCredit('Ann', MediaId.subsonic('a')), ArtistCredit('Bo', MediaId.subsonic('b'))],
        ),
      );
      expect(find.text('Go to artist'), findsNothing);
      expect(find.text('Go to Ann'), findsOneWidget);
      await tester.ensureVisible(find.text('Go to Bo'));
      await tester.tap(find.text('Go to Bo'));
      await tester.pumpAndSettle();
      expect(visits, ['artist:b']);
    });
  });
}
