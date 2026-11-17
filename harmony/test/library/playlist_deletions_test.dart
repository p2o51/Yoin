import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/library/playlist_deletions.dart';
import 'package:yoin/library/playlist_writes.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/app_messages.dart';

import '../support/library_fake_source.dart';

/// The Library's lists on a service that deletes playlists; deletions wait
/// for [hold] while any is queued and throw [refuse] while set.
class _DeletingSource extends LibraryFakeSource implements MusicPlaylistWrites {
  _DeletingSource({required super.playlists});

  final deletes = <String>[];
  final hold = <Completer<void>>[];
  Object? refuse;

  @override
  Set<Capability> get capabilities => {...super.capabilities, Capability.playlistAdd, Capability.playlistsWrite};

  @override
  Future<void> deletePlaylist(MediaId playlistId) async {
    deletes.add(playlistId.rawId);
    if (hold.isNotEmpty) await hold.removeAt(0).future;
    if (refuse case final error?) throw error;
    playlists = [
      for (final p in playlists)
        if (p.id != playlistId) p,
    ];
  }

  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description}) =>
      throw UnimplementedError();
  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {}
  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {}
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  bool acceptsTracks(Playlist playlist) => true;
}

Future<void> _pump([int times = 40]) async {
  for (var i = 0; i < times; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

const _lines = (deleted: 'Playlist deleted', failed: "Couldn't delete playlist");

void main() {
  final mix = Playlist(id: MediaId.subsonic('pl1'), name: 'Mix', libraryAddedAt: '2024-03-01');
  final road = Playlist(id: MediaId.subsonic('pl2'), name: 'Road', libraryAddedAt: '2024-02-01');
  final night = Playlist(id: MediaId.subsonic('pl3'), name: 'Night', libraryAddedAt: '2024-01-01');

  late _DeletingSource source;
  late InMemoryStore kv;
  late StoredActivityLog log;
  late List<String> messages;
  late ProviderContainer c;
  String? account;

  setUp(() async {
    source = _DeletingSource(playlists: [mix, road, night]);
    kv = InMemoryStore();
    log = StoredActivityLog(kv, profileId: 'p1');
    messages = [];
    account = 'p1';
    c = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        keyValueStoreProvider.overrideWithValue(kv),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activityLogProvider.overrideWith((ref) async => log),
        appMessageSinkProvider.overrideWithValue(messages.add),
        playlistAccountProvider.overrideWith((ref) async => account),
      ],
    );
    addTearDown(c.dispose);
    await c.read(playlistAccountProvider.future);
    c.listen(playlistDeletionsProvider, (_, _) {});
  });

  List<String> shownPlaylists() {
    final view = (c.read(libraryViewModelProvider) as LibraryContent).shownPlaylists!;
    return [for (final i in view.items) i.name];
  }

  Future<List<String>> openPlaylists() async {
    c.listen(libraryViewModelProvider, (_, _) {});
    await _pump();
    await c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.playlists);
    await _pump();
    return shownPlaylists();
  }

  PlaylistDeletions deletions() => c.read(playlistDeletionsProvider.notifier);

  test('should_leaveTheLibraryAtOnceAndGoForGood_when_theServiceDeletesIt', () async {
    expect(await openPlaylists(), ['Mix', 'Road', 'Night']);
    final gate = Completer<void>();
    source.hold.add(gate);
    deletions().delete(road.id, _lines);
    await _pump();
    expect(shownPlaylists(), ['Mix', 'Night']);
    expect(source.deletes, ['pl2']);
    gate.complete();
    await _pump();
    expect(shownPlaylists(), ['Mix', 'Night']);
    expect((c.read(libraryViewModelProvider) as LibraryContent).playlists!.map((p) => p.name), ['Mix', 'Night']);
    expect(messages, ['Playlist deleted']);
  });

  test('should_comeBackWhereItWas_when_theServiceRefuses', () async {
    await openPlaylists();
    source.refuse = const SourceServerException('refused');
    deletions().delete(road.id, _lines);
    await _pump();
    expect(shownPlaylists(), ['Mix', 'Road', 'Night']);
    expect(c.read(playlistDeletionsProvider).pending, isEmpty);
    expect(messages, ["Couldn't delete playlist"]);
  });

  test('should_comeBack_when_theRequestFailsOnTheNetwork', () async {
    await openPlaylists();
    source.refuse = const SourceNetworkException('down');
    deletions().delete(mix.id, _lines);
    await _pump();
    expect(shownPlaylists(), ['Mix', 'Road', 'Night']);
    expect(messages, ["Couldn't delete playlist"]);
  });

  test('should_sendNothingToTheNewAccount_when_theAccountChangesBeforeTheRequest', () async {
    await openPlaylists();
    final gate = Completer<void>();
    source.hold.add(gate);
    deletions().delete(mix.id, _lines);
    deletions().delete(road.id, _lines);
    await _pump();
    account = 'p2';
    c.invalidate(playlistAccountProvider);
    await c.read(playlistAccountProvider.future);
    gate.complete();
    await _pump();
    expect(source.deletes, ['pl1']);
    expect(messages, ['Playlist deleted', "Couldn't delete playlist"]);
  });

  test('should_sendOneAfterAnother_when_twoDeletionsAreMade', () async {
    await openPlaylists();
    final gate = Completer<void>();
    source.hold.add(gate);
    deletions().delete(mix.id, _lines);
    deletions().delete(night.id, _lines);
    await _pump();
    expect(source.deletes, ['pl1']);
    expect(shownPlaylists(), ['Road']);
    gate.complete();
    await _pump();
    expect(source.deletes, ['pl1', 'pl3']);
    expect(shownPlaylists(), ['Road']);
  });

  test('should_handNothingOverTwice_when_theSamePlaylistIsDeletedAgainWhileOut', () async {
    await openPlaylists();
    final gate = Completer<void>();
    source.hold.add(gate);
    deletions().delete(mix.id, _lines);
    deletions().delete(mix.id, _lines);
    gate.complete();
    await _pump();
    expect(source.deletes, ['pl1']);
  });

  test('should_forgetHomesStoredRows_when_theServiceDeletesIt', () async {
    await log.record(
      ActivityEvent(
        entityType: ActivityEntityType.playlist,
        actionType: ActivityActionType.visited,
        entityId: 'pl2',
        title: 'Road',
        timestampMs: 1,
      ),
    );
    await log.record(
      ActivityEvent(
        entityType: ActivityEntityType.album,
        actionType: ActivityActionType.visited,
        entityId: 'pl2',
        title: 'An album with the same raw id',
        timestampMs: 2,
      ),
    );
    final pools = HomeGridPoolStore(kv);
    await pools.write('p1', MediaId.providerSubsonic, HomeGridPools(playlists: [mix, road], cachedAtMs: 5));
    deletions().delete(road.id, _lines);
    await _pump();
    expect([for (final e in await log.recent()) '${e.entityType.name}:${e.entityId}'], ['album:pl2']);
    final kept = await pools.read('p1', MediaId.providerSubsonic);
    expect([for (final p in kept!.playlists) p.id.rawId], ['pl1']);
    expect(kept.cachedAtMs, 5);
    expect(jsonDecode((await kv.read(HomeGridPoolStore.keyFor('p1', MediaId.providerSubsonic)))!), isA<Map>());
  });

  test('should_changeTheRowsOfTheAccountTheEditWasMadeOn_when_anotherAccountIsInUseByThen', () async {
    ActivityEvent visit(String id) => ActivityEvent(
      entityType: ActivityEntityType.playlist,
      actionType: ActivityActionType.visited,
      entityId: id,
      title: id,
      timestampMs: 1,
    );
    final earlier = StoredActivityLog(kv, profileId: 'p0');
    await earlier.record(visit('pl2'));
    await log.record(visit('pl2'));
    final writes = c.read(playlistWritesProvider);
    expect(await writes.activityLogOf('p1'), same(log));
    await (await writes.activityLogOf('p0'))!.forget(ActivityEntityType.playlist, 'pl2');
    expect(await StoredActivityLog(kv, profileId: 'p0').recent(), isEmpty);
    expect([for (final e in await log.recent()) e.entityId], ['pl2']);
    expect(await writes.activityLogOf(null), isNull);
  });
}
