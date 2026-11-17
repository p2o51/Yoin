import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/detail_share.dart';
import 'package:yoin/detail/playlist_detail_screen.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/library/playlist_deletions.dart';
import 'package:yoin/platform/share/text_share.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/shell/app_messages.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';
import '../support/link_fakes.dart';

final _id = MediaId.subsonic('pl1');

/// A playlist of the account's own on a service that edits playlists; [refuse] refuses the next write.
class _EditingSource extends LibraryFakeSource implements MusicPlaylistWrites {
  _EditingSource({this.edit = true}) {
    _store(['a', 'b', 'c']);
  }

  final bool edit;
  String name = 'Road';
  final writes = <String>[];
  Object? refuse;

  void _store(List<String> songs) {
    playlistDetails[_id] = Playlist(
      id: _id,
      name: name,
      canWrite: true,
      songCount: songs.length,
      tracks: [for (final s in songs) LibraryFixtures.track(s)],
    );
  }

  List<String> get songs => [for (final t in playlistDetails[_id]!.tracks) t.id.rawId];

  @override
  Set<Capability> get capabilities => {
    ...super.capabilities,
    Capability.playlistAdd,
    if (edit) Capability.playlistsWrite,
  };

  void _write(String call) {
    writes.add(call);
    if (refuse case final error?) {
      refuse = null;
      throw error;
    }
  }

  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {
    _write('remove:${positions.join(',')}');
    _store([...songs]..removeAt(positions.single));
  }

  @override
  Future<void> deletePlaylist(MediaId playlistId) async => _write('delete');
  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {
    _write('rename:$name');
    this.name = name;
    _store(songs);
  }

  @override
  bool acceptsTracks(Playlist playlist) => true;
  @override
  Future<Playlist> createPlaylist(String name, {String? description, List<MediaId> tracks = const []}) async =>
      Playlist(id: MediaId.subsonic('new'), name: name);
  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}
}

late List<String> _messages;

Future<ProviderContainer> _open(WidgetTester tester, _EditingSource source) async {
  _messages = [];
  DetailShare.resetForTest();
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      textShareProvider.overrideWithValue(RecordingTextShare()),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
      appMessageSinkProvider.overrideWithValue(_messages.add),
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
              child: TextButton(onPressed: () => DetailRoutes.openPlaylist(context, _id), child: const Text('open')),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await _settle(tester);
  return c;
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}

/// Pulls the hero up into the list.
Future<void> _pullUp(WidgetTester tester) async {
  await tester.drag(find.textContaining('Song b', findRichText: true).first, const Offset(0, -500));
  await _settle(tester);
}

Future<void> _removeRow(WidgetTester tester, String title) async {
  await tester.longPress(find.text(title));
  await _settle(tester);
  await tester.tap(find.text('Remove from playlist'));
  await _settle(tester);
}

void main() {
  testWidgets('should_takeTheRowOutAndCloseUpTheList_when_removeFromPlaylistTapped', (tester) async {
    final source = _EditingSource();
    await _open(tester, source);
    await _pullUp(tester);
    expect(find.text('Song b'), findsOneWidget);
    final cBefore = tester.getTopLeft(find.text('Song c')).dy;

    await _removeRow(tester, 'Song b');

    expect(source.writes, ['remove:1']);
    expect(find.text('Song b'), findsNothing);
    // The rows under it moved up into its place.
    expect(tester.getTopLeft(find.text('Song c')).dy, lessThan(cBefore));
    expect(_messages, isEmpty);
    await _close(tester);
  });

  testWidgets('should_bringTheRowBackAndSaySo_when_theServiceRefuses', (tester) async {
    final source = _EditingSource()..refuse = const SourceServerException('refused');
    await _open(tester, source);
    await _pullUp(tester);
    final cBefore = tester.getTopLeft(find.text('Song c')).dy;

    await _removeRow(tester, 'Song b');

    expect(find.text('Song b'), findsOneWidget);
    expect(tester.getTopLeft(find.text('Song c')).dy, cBefore);
    expect(_messages, ["Couldn't remove track"]);
    await _close(tester);
  });

  testWidgets('should_offerNoRemove_when_theServiceDoesNotEditPlaylists', (tester) async {
    await _open(tester, _EditingSource(edit: false));
    await _pullUp(tester);
    await tester.longPress(find.text('Song b'));
    await _settle(tester);
    expect(find.text('Play next'), findsOneWidget);
    expect(find.text('Remove from playlist'), findsNothing);
    await _close(tester);
  });

  testWidgets('should_closeAtOnceAndHandTheDeletionOver_when_deleteConfirmed', (tester) async {
    final source = _EditingSource();
    final c = await _open(tester, source);
    final delete = c.read(topDetailActionsProvider)!.menuItems.firstWhere((r) => r.label == 'Delete');
    delete.onTap();
    await _settle(tester);
    await tester.tap(find.widgetWithText(TextButton, 'Delete'));
    await _settle(tester);
    expect(c.read(playlistDeletionsProvider).doneAfter(0).map((k) => k.id), [_id]);
    expect(find.byType(PlaylistDetailScreen), findsNothing);
    expect(source.writes, ['delete']);
    expect(_messages, ['Playlist deleted']);
    await _close(tester);
  });

  testWidgets('should_showTheNewNameAtOnce_when_renamed', (tester) async {
    final source = _EditingSource();
    final c = await _open(tester, source);
    final rename = c.read(topDetailActionsProvider)!.menuItems.firstWhere((r) => r.label == 'Rename');
    rename.onTap();
    await _settle(tester);
    await tester.enterText(find.byType(EditableText), 'Night');
    await tester.tap(find.widgetWithText(TextButton, 'Rename'));
    await _settle(tester);
    expect((c.read(playlistDetailProvider(_id)) as PlaylistDetailContent).playlist.name, 'Night');
    expect(find.text('Night'), findsWidgets);
    expect(source.writes, ['rename:Night']);
    expect(_messages, ['Renamed']);
    await _close(tester);
  });
}
