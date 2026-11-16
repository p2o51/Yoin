import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memory_rate_sheet.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/album_detail_screen.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/detail_share.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/pull_up_reveal.dart';
import 'package:yoin/detail/scrapbook_focus.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';
import '../support/link_fakes.dart';

final _album = LibraryFixtures.album(
  'al1',
  name: 'Blue',
  artist: 'Joni',
  year: 1971,
  tracks: [
    LibraryFixtures.track('t1', artist: 'Joni', number: 1),
    LibraryFixtures.track('t2', artist: 'Joni', number: 2),
    LibraryFixtures.track('t3', artist: 'Joni', number: 3),
  ],
);

/// [LibraryFakeSource] whose account files songs into playlists.
class _FilingSource extends LibraryFakeSource implements MusicPlaylistWrites {
  _FilingSource() : super(albumDetails: {_album.id: _album});

  @override
  Set<Capability> get capabilities => {...super.capabilities, Capability.playlistAdd};

  @override
  bool acceptsTracks(Playlist playlist) => true;
  @override
  Future<Playlist> createPlaylist(String name, {String? description, List<MediaId> tracks = const []}) async =>
      Playlist(id: MediaId.subsonic('new'), name: name);
  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {}
  @override
  Future<void> deletePlaylist(MediaId playlistId) async {}
  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {}
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}
}

late RecordingTextShare _shared;

Future<(ProviderContainer, FakePlaybackEngine)> _open(WidgetTester tester, {MusicSource? source}) async {
  final engine = FakePlaybackEngine();
  _shared = RecordingTextShare();
  DetailShare.resetForTest();
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source ?? LibraryFakeSource(albumDetails: {_album.id: _album})),
      textShareProvider.overrideWithValue(_shared),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(engine),
    ],
  );
  addTearDown(c.dispose);
  c.listen(playbackControllerProvider, (_, _) {});
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(onPressed: () => DetailRoutes.openAlbum(context, _album.id), child: const Text('open')),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await _settle(tester);
  return (c, engine);
}

/// Unmounts the pages inside the test body: they unregister from the bar a
/// frame later, and that write must land before the binding checks timers.
Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  testWidgets('should_showHeaderAndFlowingTitles_when_loaded', (tester) async {
    await _open(tester);

    expect(find.byType(AlbumDetailScreen), findsOneWidget);
    expect(find.text('Blue'), findsOneWidget);
    expect(find.text('1971', findRichText: true), findsWidgets);
    expect(find.textContaining('Song\u00A0t2', findRichText: true), findsWidgets);
  });

  testWidgets('should_publishPlayActions_when_mounted', (tester) async {
    final (c, engine) = await _open(tester);

    final actions = c.read(topDetailActionsProvider);
    expect(actions, isNotNull);
    actions!.onPlay();
    await _settle(tester);

    expect(engine.startIndex, 0);
    expect(engine.queue.map((i) => i.id), ['subsonic:t1', 'subsonic:t2', 'subsonic:t3']);
    expect(c.read(playbackControllerProvider).context, isA<AlbumPlaybackContext>());
    await _close(tester);
  });

  testWidgets('should_playFromRow_when_trackListRowTapped', (tester) async {
    final (c, engine) = await _open(tester);
    // Pull the hero up into the list.
    await tester.drag(find.textContaining('Song\u00A0t2', findRichText: true).first, const Offset(0, -500));
    await _settle(tester);
    expect(find.text('Song t3'), findsOneWidget);

    await tester.tap(find.text('Song t3'));
    await _settle(tester);

    expect(engine.startIndex, 2);
    final context = c.read(playbackControllerProvider).context as AlbumPlaybackContext;
    expect(context.albumId, _album.id);
  });

  testWidgets('should_popPage_when_backButtonTapped', (tester) async {
    await _open(tester);
    await tester.tap(find.byTooltip('Back'));
    await _settle(tester);
    expect(find.byType(AlbumDetailScreen), findsNothing);
  });

  testWidgets('should_showUnratedEmblem_when_loaded', (tester) async {
    final semantics = tester.ensureSemantics();
    await _open(tester);
    expect(find.byType(AlbumScoreEmblem), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('Not rated')), findsOneWidget);
    semantics.dispose();
    await _close(tester);
  });

  testWidgets('should_offerQueueRowsAndShare_when_albumNamesNoArtistId', (tester) async {
    final (c, _) = await _open(tester);
    final rows = c.read(topDetailActionsProvider)!.menuItems.map((r) => r.label);
    expect(rows, ['Play next', 'Add to queue', 'Share']);
    await _close(tester);
  });

  testWidgets('should_offerAddToPlaylist_when_accountFilesSongs', (tester) async {
    final (c, _) = await _open(tester, source: _FilingSource());
    final rows = c.read(topDetailActionsProvider)!.menuItems.map((r) => r.label);
    expect(rows, ['Play next', 'Add to queue', 'Add to playlist', 'Share']);
    await _close(tester);
  });

  testWidgets('should_shareAlbumAndArtist_when_shareChosen', (tester) async {
    final (c, _) = await _open(tester);
    c.read(topDetailActionsProvider)!.menuItems.last.onTap();
    await _settle(tester);
    expect(_shared.shared.single.$1, 'Blue – Joni');
    await _close(tester);
  });

  testWidgets('should_openScrapbookAtSong_when_requestedFromElsewhere', (tester) async {
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
        keyValueStoreProvider.overrideWithValue(InMemoryStore()),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activityLogProvider.overrideWith((ref) async => null),
        playbackEngineProvider.overrideWithValue(engine),
      ],
    );
    addTearDown(c.dispose);
    // Now Playing's View in Scrapbook leaves the request, then opens the album.
    ScrapbookFocusRequest.request(c, MediaId.subsonic('t2'));
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
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
    );
    await tester.tap(find.text('open'));
    await _settle(tester);
    expect(c.read(scrapbookFocusRequestProvider), isNull, reason: 'the album page took it');
    final pager = tester.widget<PageView>(find.byType(PageView));
    expect(pager.controller!.page, closeTo(1, 0.01));
    expect(find.byType(AlbumScrapbookPage), findsOneWidget);
    await _close(tester);
  });

  testWidgets('should_queueAlbumAndConfirm_when_addToQueueChosen', (tester) async {
    final (c, engine) = await _open(tester);
    final messages = <String>[];
    final sub = c.read(playbackControllerProvider.notifier).notices.listen((n) {
      if (n is TextNotice) messages.add(n.text);
    });
    addTearDown(sub.cancel);
    c.read(topDetailActionsProvider)!.menuItems[1].onTap();
    await _settle(tester);

    // Nothing was loaded: the queued tracks become the queue, paused.
    expect(engine.queue.map((i) => i.id), ['subsonic:t1', 'subsonic:t2', 'subsonic:t3']);
    // The confirmation goes to the window's one snackbar (the shell's host).
    expect(messages, ['Added 3 songs to the queue']);
    await _close(tester);
  });

  testWidgets('should_showNoConfirmation_when_queueEditAddsNothing', (tester) async {
    final (c, engine) = await _open(tester);
    // A queue is loaded and the base engine can't edit it: addToQueue returns without adding.
    c.read(topDetailActionsProvider)!.onPlay();
    await _settle(tester);
    c.read(topDetailActionsProvider)!.menuItems[1].onTap();
    await _settle(tester);

    expect(engine.queue, hasLength(3));
    expect(find.textContaining('to the queue'), findsNothing);
    expect(tester.takeException(), isNull);
    await _close(tester);
  });

  test('should_settleToList_when_releasedPastHalf', () {
    // Pure reveal rule the page uses on release.
    expect(PullUpMath.releaseToList(0.3, 0, 400), isTrue);
  });
}
