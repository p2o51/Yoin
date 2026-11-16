import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/playlist_detail_view_model.dart';
import 'package:yoin/library/playlist_deletions.dart';
import 'package:yoin/library/playlist_writes.dart';
import 'package:yoin/shell/app_messages.dart';

import '../support/fake_music_source.dart';

/// One playlist on a Subsonic-like service whose writes can be held
/// ([hold]) and refused ([refuse]).
class _EditSource extends FakeMusicSource implements MusicWriteActions, MusicPlaylistWrites {
  _EditSource({List<String> songs = const ['a', 'b', 'c'], this.canWrite = true, this.edit = true})
    : songs = [...songs];

  final List<String> songs;
  final bool canWrite;
  final bool edit;
  String name = 'Road';
  final calls = <String>[];

  /// Writes wait for the next of these, in order, while any is queued.
  final hold = <Completer<void>>[];

  /// The next writes throw these, in order.
  final refuse = <Object>[];

  /// The write named so (`rename:Day`) throws this.
  final refuseOn = <String, Object>{};

  static final id = MediaId.subsonic('pl1');

  @override
  Set<Capability> get capabilities => {
    Capability.playlistsRead,
    Capability.playlistAdd,
    if (edit) Capability.playlistsWrite,
  };

  @override
  MusicWriteActions get writeActions => this;

  Playlist get playlist => Playlist(
    id: id,
    name: name,
    canWrite: canWrite,
    songCount: songs.length,
    durationSec: songs.length * 100,
    tracks: [for (final s in songs) Track(id: MediaId.subsonic(s), title: s, durationSec: 100)],
  );

  @override
  Future<Playlist?> getPlaylist(MediaId id) async => playlist;
  @override
  Future<List<Playlist>> getPlaylists() async => [playlist];

  Future<void> _write(String call) async {
    calls.add(call);
    if (hold.isNotEmpty) await hold.removeAt(0).future;
    if (refuseOn.remove(call) case final error?) throw error;
    if (refuse.isNotEmpty) throw refuse.removeAt(0);
  }

  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async {
    await _write('rename:$name');
    this.name = name;
  }

  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async {
    await _write('remove:${positions.join(',')}');
    for (final i in [...positions]..sort((a, b) => b.compareTo(a))) {
      songs.removeAt(i);
    }
  }

  @override
  Future<void> deletePlaylist(MediaId playlistId) => _write('delete');

  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description}) =>
      throw UnimplementedError();
  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async {}
  @override
  bool acceptsTracks(Playlist playlist) => playlist.canWrite;
  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {}
  @override
  Future<void> setRating(MediaId trackId, int rating) async {}
}

Future<void> _settle() async {
  for (var i = 0; i < 30; i++) {
    await Future<void>.delayed(Duration.zero);
  }
}

void main() {
  late _EditSource source;
  late ProviderContainer c;
  late List<String> messages;
  String? account;
  final id = _EditSource.id;

  ProviderContainer containerFor(_EditSource s) {
    source = s;
    final container = ProviderContainer(
      overrides: [
        activeSourceProvider.overrideWith((ref) async => source),
        appMessageSinkProvider.overrideWithValue(messages.add),
        playlistAccountProvider.overrideWith((ref) async => account),
      ],
    );
    addTearDown(container.dispose);
    return container;
  }

  setUp(() {
    messages = [];
    account = 'p1';
    c = containerFor(_EditSource());
  });

  PlaylistDetailContent shown() => c.read(playlistDetailProvider(id)) as PlaylistDetailContent;
  PlaylistDetailNotifier vm() => c.read(playlistDetailProvider(id).notifier);
  List<String> rows() => [for (final e in shown().entries) e.track.id.rawId];

  Future<ProviderSubscription<PlaylistDetailState>> open() async {
    await c.read(activeSourceProvider.future);
    await c.read(playlistAccountProvider.future);
    final sub = c.listen(playlistDetailProvider(id), (_, _) {});
    addTearDown(sub.close);
    await _settle();
    expect(c.read(playlistDetailProvider(id)), isA<PlaylistDetailContent>());
    return sub;
  }

  /// Another account comes into use: the edits made before it are not sent.
  Future<void> switchAccount() async {
    account = 'p2';
    c.invalidate(playlistAccountProvider);
    await c.read(playlistAccountProvider.future);
  }

  group('Remove from playlist', () {
    test('should_takeTheRowOffAtOnceAndKeepItOff_when_removalSucceeds', () async {
      await open();
      final gate = Completer<void>();
      source.hold.add(gate);
      vm().removeTrack(shown().entries[1].rowKey, PlaylistEditLines.en);
      expect(rows(), ['a', 'c']);
      expect(shown().trackCount, 2);
      expect(shown().totalDurationSec, 200);
      gate.complete();
      await _settle();
      expect(source.calls, ['remove:1']);
      expect(rows(), ['a', 'c']);
      expect(messages, isEmpty);
    });

    test('should_keepTheOtherRowsKeys_when_playlistIsReadAgainAfterRemoval', () async {
      await open();
      final keys = [for (final e in shown().entries) e.rowKey];
      vm().removeTrack(keys[0], PlaylistEditLines.en);
      await _settle();
      expect([for (final e in shown().entries) e.rowKey], [keys[1], keys[2]]);
    });

    test('should_putTheRowBackWhereItWas_when_removalFails', () async {
      await open();
      final keys = [for (final e in shown().entries) e.rowKey];
      source.refuse.add(const SourceNetworkException('down', kind: NetworkFailureKind.connect));
      source.refuse.add(const SourceServerException('refused'));
      vm().removeTrack(keys[1], PlaylistEditLines.en);
      await _settle();
      expect(rows(), ['a', 'b', 'c']);
      expect([for (final e in shown().entries) e.rowKey], keys);
      expect(shown().trackCount, 3);
      vm().removeTrack(keys[1], PlaylistEditLines.en);
      await _settle();
      expect(rows(), ['a', 'b', 'c']);
      // The first refusal says the failure's own line, the second the page's.
      expect(messages, [PlaylistEditLines.en.errors.unreachableConnect, "Couldn't remove track"]);
    });

    test('should_sayWhatTheServiceSaid_when_removalFailsWithAMessage', () async {
      await open();
      source.refuse.add(const SourceServerException('Permission denied', code: 50));
      vm().removeTrack(shown().entries[0].rowKey, PlaylistEditLines.en);
      await _settle();
      expect(messages, ['Permission denied']);
    });

    test('should_notOfferOrSendRemoval_when_serviceDoesNotWritePlaylists', () async {
      c = containerFor(_EditSource(edit: false));
      await open();
      expect(shown().canRemoveSongs, isFalse);
      vm().removeTrack(shown().entries[0].rowKey, PlaylistEditLines.en);
      await _settle();
      expect(rows(), ['a', 'b', 'c']);
      expect(source.calls, isEmpty);
    });

    test('should_notOfferOrSendRemoval_when_playlistIsNotTheAccountsToEdit', () async {
      c = containerFor(_EditSource(canWrite: false));
      await open();
      expect(shown().canRemoveSongs, isFalse);
      vm().removeTrack(shown().entries[0].rowKey, PlaylistEditLines.en);
      await _settle();
      expect(source.calls, isEmpty);
    });

    test('should_removeOnlyTheRowTapped_when_songIsInThePlaylistTwice', () async {
      c = containerFor(_EditSource(songs: ['a', 'b', 'a']));
      await open();
      final keys = [for (final e in shown().entries) e.rowKey];
      expect(keys.toSet(), hasLength(3));
      vm().removeTrack(keys[2], PlaylistEditLines.en);
      await _settle();
      expect(source.calls, ['remove:2']);
      expect(rows(), ['a', 'b']);
      expect([for (final e in shown().entries) e.rowKey], [keys[0], keys[1]]);
    });

    test('should_sendEachPositionAsThePlaylistThenStands_when_twoRowsAreRemovedOneAfterAnother', () async {
      await open();
      final keys = [for (final e in shown().entries) e.rowKey];
      final gate = Completer<void>();
      source.hold.add(gate);
      vm().removeTrack(keys[0], PlaylistEditLines.en);
      vm().removeTrack(keys[2], PlaylistEditLines.en);
      expect(rows(), ['b']);
      await _settle();
      expect(source.calls, ['remove:0']);
      gate.complete();
      await _settle();
      // 'c' was third; with 'a' gone it is second.
      expect(source.calls, ['remove:0', 'remove:1']);
      expect(rows(), ['b']);
    });

    test('should_notSendTheRemoval_when_theAccountChangedBeforeItsTurn', () async {
      await open();
      final keys = [for (final e in shown().entries) e.rowKey];
      final gate = Completer<void>();
      source.hold.add(gate);
      vm().removeTrack(keys[0], PlaylistEditLines.en);
      vm().removeTrack(keys[1], PlaylistEditLines.en);
      await _settle();
      await switchAccount();
      gate.complete();
      await _settle();
      expect(source.calls, ['remove:0']);
      expect(rows(), ['b', 'c']);
    });
  });

  group('Rename', () {
    test('should_showTheNewNameAtOnce_when_theServiceHasNotAnsweredYet', () async {
      await open();
      final gate = Completer<void>();
      source.hold.add(gate);
      vm().rename('  Night  ', PlaylistEditLines.en);
      expect(shown().playlist.name, 'Night');
      await _settle();
      expect(source.calls, ['rename:Night']);
      expect(shown().playlist.name, 'Night');
      gate.complete();
      await _settle();
      expect(shown().playlist.name, 'Night');
      expect(messages, ['Renamed']);
    });

    test('should_putTheOldNameBack_when_theServiceRefuses', () async {
      await open();
      source.refuse.add(const SourceServerException('refused'));
      vm().rename('Night', PlaylistEditLines.en);
      expect(shown().playlist.name, 'Night');
      await _settle();
      expect(shown().playlist.name, 'Road');
      expect(messages, ["Couldn't rename playlist"]);
    });

    test('should_sendNothing_when_theNameIsBlankOrUnchanged', () async {
      await open();
      vm().rename('   ', PlaylistEditLines.en);
      vm().rename(' Road ', PlaylistEditLines.en);
      await _settle();
      expect(source.calls, isEmpty);
      expect(messages, isEmpty);
    });

    test('should_keepTheLaterName_when_anEarlierRenameIsRefused', () async {
      await open();
      final first = Completer<void>();
      source.hold.add(first);
      source.refuseOn['rename:Night'] = const SourceServerException('refused');
      vm().rename('Night', PlaylistEditLines.en);
      vm().rename('Day', PlaylistEditLines.en);
      expect(shown().playlist.name, 'Day');
      first.complete();
      await _settle();
      expect(source.calls, ['rename:Night', 'rename:Day']);
      expect(shown().playlist.name, 'Day');
      expect(messages, ["Couldn't rename playlist", 'Renamed']);
    });

    test('should_goBackToTheEarlierRename_when_onlyTheLaterOneIsRefused', () async {
      await open();
      final first = Completer<void>();
      source.hold.add(first);
      source.refuseOn['rename:Day'] = const SourceServerException('refused');
      vm().rename('Night', PlaylistEditLines.en);
      vm().rename('Day', PlaylistEditLines.en);
      expect(shown().playlist.name, 'Day');
      first.complete();
      await _settle();
      expect(source.calls, ['rename:Night', 'rename:Day']);
      expect(shown().playlist.name, 'Night');
      expect(messages, ['Renamed', "Couldn't rename playlist"]);
    });

    test('should_goBackToTheNameTheServiceHolds_when_twoRenamesInARowAreRefused', () async {
      await open();
      source.refuse
        ..add(const SourceServerException('refused'))
        ..add(const SourceServerException('refused'));
      vm().rename('Night', PlaylistEditLines.en);
      vm().rename('Day', PlaylistEditLines.en);
      await _settle();
      expect(shown().playlist.name, 'Road');
    });

    test('should_finishTheWrite_when_thePageClosesUnderIt', () async {
      final sub = await open();
      final gate = Completer<void>();
      source.hold.add(gate);
      vm().rename('Night', PlaylistEditLines.en);
      await _settle();
      sub.close();
      await _settle();
      gate.complete();
      await _settle();
      expect(source.name, 'Night');
      expect(messages, ['Renamed']);
    });

    test('should_notSendTheQueuedRename_when_theAccountChangedBeforeItsTurn', () async {
      await open();
      final first = Completer<void>();
      source.hold.add(first);
      vm().rename('Night', PlaylistEditLines.en);
      vm().rename('Day', PlaylistEditLines.en);
      await _settle();
      await switchAccount();
      first.complete();
      await _settle();
      expect(source.calls, ['rename:Night']);
      expect(messages, ['Renamed', "Couldn't rename playlist"]);
    });
  });

  group('Delete', () {
    test('should_leaveAtOnceAndHandTheDeletionOver_when_deleteIsConfirmed', () async {
      await open();
      final gate = Completer<void>();
      source.hold.add(gate);
      expect(vm().delete((deleted: 'Playlist deleted', failed: "Couldn't delete playlist")), isTrue);
      expect(c.read(playlistDeletionsProvider).pendingFor('p1'), {id});
      gate.complete();
      await _settle();
      expect(source.calls, ['delete']);
      expect(c.read(playlistDeletionsProvider).pending, isEmpty);
      expect(c.read(playlistDeletionsProvider).doneAfter(0), [PlaylistDeletionKey('p1', id)]);
      expect(messages, ['Playlist deleted']);
    });

    test('should_handTheDeletionOverOnce_when_deleteIsConfirmedTwice', () async {
      await open();
      const lines = (deleted: 'Playlist deleted', failed: "Couldn't delete playlist");
      expect(vm().delete(lines), isTrue);
      expect(vm().delete(lines), isFalse);
      await _settle();
      expect(source.calls, ['delete']);
      expect(messages, ['Playlist deleted']);
    });
  });
}
