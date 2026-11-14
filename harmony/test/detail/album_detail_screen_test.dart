import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memory_rate_sheet.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/detail/album_detail_screen.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/pull_up_reveal.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

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

Future<(ProviderContainer, FakePlaybackEngine)> _open(WidgetTester tester) async {
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

  testWidgets('should_offerQueueRowsOnly_when_albumNamesNoArtistId', (tester) async {
    final (c, _) = await _open(tester);
    final rows = c.read(topDetailActionsProvider)!.menuItems.map((r) => r.label);
    expect(rows, ['Play next', 'Add to queue']);
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
