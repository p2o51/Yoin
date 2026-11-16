import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_service.dart';
import 'package:yoin/data/backup/playlist_backup_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/transfer/restore_review.dart';
import 'package:yoin/platform/storage/app_files.dart';

import '../../support/backup_fakes.dart';

const _hour = 60 * 60 * 1000;

Playlist _playlist(String id, List<Track> tracks, {String name = 'Night drive', String? comment, String? created}) =>
    Playlist(
      id: MediaId.subsonic(id),
      name: name,
      tracks: tracks,
      songCount: tracks.length,
      comment: comment,
      libraryAddedAt: created,
    );

PlaylistBackupContent _described(PlaylistBackupContent c) =>
    PlaylistBackupContent(description: 'late night', tracks: c.tracks, unexported: c.unexported);

void main() {
  final t1 = song('t1', 'Entropy', 'Beach Bunny', durationSec: 221, album: 'Emotional Creature');
  final t2 = song('t2', 'Lemon', 'Kenshi Yonezu', durationSec: 255);
  final untitled = Track(id: MediaId.subsonic('t3'), title: '  ', artist: 'Ghost');

  group('PlaylistSnapshots', () {
    test('should_keepTracksInOrderAndListWhatWasLeftOut_when_backingUp', () {
      final content = PlaylistSnapshots.content(
        _playlist('p1', [t1, untitled, t2], comment: ' late night '),
        isrcOf: (id) => id == t1.id ? 'USABC1234567' : null,
      );
      expect(content.description, 'late night');
      expect([for (final t in content.tracks) t.title], ['Entropy', 'Lemon']);
      expect(content.tracks.first.isrc, 'USABC1234567');
      expect(content.tracks.first.sourceTrackId, 'subsonic:t1');
      expect(content.unexported.single.position, 1);
      expect(content.unexported.single.reasonKind, UnexportedReason.unavailable);
    });

    test('should_keepEveryCreditedArtist_when_theServiceNamesThem', () {
      final duet = Track(id: MediaId.subsonic('t9'), title: 'Duet', artist: 'A & B', creditedArtists: const ['A', 'B']);
      final content = PlaylistSnapshots.content(_playlist('p1', [duet, t1]));
      // Android's creditedArtists: the named ones, else the one artist line.
      expect(content.tracks.first.artists, ['A', 'B']);
      expect(content.tracks.last.artists, ['Beach Bunny']);
    });

    test('should_writeAndroidsWireFormat_when_contentIsEncoded', () {
      final content = PlaylistBackupContent(
        tracks: [
          BackupTrack(title: 'Entropy', artists: const ['Beach Bunny'], sourceTrackId: 'subsonic:t1'),
        ],
      );
      expect(
        PlaylistBackupCodec.encodeContent(content),
        '{"description":null,"tracks":[{"title":"Entropy","artists":["Beach Bunny"],"album":null,'
        '"durationSec":null,"isrc":null,"sourceId":"subsonic:t1"}],"unexported":[]}',
      );
    });

    test('should_readUnknownFieldsAndReasons_when_aNewerDeviceWroteTheBackup', () {
      final content = PlaylistBackupCodec.decodeContent(
        '{"tracks":[{"title":"A","sourceId":"x:1","mood":"calm"}],'
        '"unexported":[{"position":3,"reason":"hologram"}],"future":1}',
      );
      expect(content.tracks.single.artists, isEmpty);
      expect(content.unexported.single.reasonKind, UnexportedReason.unavailable);
    });

    test('should_keepTheLatestFiveVersions_when_thePlaylistKeepsChanging', () {
      var backup = PlaylistSnapshots.backup(
        id: 'b',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: 'p',
        profileLabel: 'Home',
        now: 1,
      );
      for (var i = 2; i < 10; i++) {
        backup = PlaylistSnapshots.refreshed(
          backup: backup,
          playlist: _playlist('p1', [t1, for (var k = 0; k < i; k++) t2]),
          profileId: 'p',
          profileLabel: 'Home',
          now: i,
        );
      }
      expect(backup.versions.length, PlaylistSnapshots.keepVersions - 1);
      expect(backup.versions.first.until, 9);
      expect(backup.lastChangedAt, 9);
    });

    test('should_addNoVersion_when_nothingChanged', () {
      final backup = PlaylistSnapshots.backup(
        id: 'b',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: 'p',
        profileLabel: 'Home',
        now: 1,
      );
      final again = PlaylistSnapshots.refreshed(
        backup: backup,
        playlist: _playlist('p1', [t1]),
        profileId: 'p',
        profileLabel: 'Home',
        now: 5,
      );
      expect(again.versions, isEmpty);
      expect(again.lastChangedAt, 1);
      expect(again.lastBackedUpAt, 5);
      expect(again.updatedAt, 5);
    });

    test('should_readBackTheSameBackup_when_roundTrippedThroughTheRow', () {
      final backup =
          PlaylistSnapshots.backup(
            id: 'b',
            playlist: _playlist('p1', [t1, untitled], created: '2024-03-01T10:00:00Z'),
            provider: MediaId.providerSubsonic,
            profileId: 'p',
            profileLabel: 'Home',
            now: 1,
            account: 's-abc',
          ).copyWith(
            restores: const [
              PlaylistRestoreRecord(
                at: 2,
                targetProvider: 'applemusic',
                targetLabel: 'AM',
                added: 1,
                skipped: 0,
                notFound: 0,
              ),
            ],
          );
      final row = PlaylistBackupCodec.toRow(backup);
      expect(row.trackCount, 1);
      expect(row.unexportedCount, 1);
      expect(PlaylistBackupCodec.fromRow(PlaylistBackupRow.tryParse(jsonDecode(jsonEncode(row.toJson())))!), backup);
    });
  });

  group('PlaylistBackupStore', () {
    test('should_keepEachBackupInItsOwnFile_when_savedInTheFilesFolder', () async {
      final dir = await Directory.systemTemp.createTemp('yoin-backups');
      addTearDown(() => dir.delete(recursive: true));
      final storage = DirectoryPlaylistBackupStorage(DirectoryAppFilesHost(dir));
      final store = PlaylistBackupStore(storage);
      final backup = PlaylistSnapshots.backup(
        id: 'b1',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: 'p',
        profileLabel: 'Home',
        now: 1,
      );
      await store.save(backup);
      final files = Directory('${dir.path}/${DirectoryPlaylistBackupStorage.folderName}').listSync();
      expect(files, hasLength(1));
      // A fresh store (the next start) reads it back.
      expect(await PlaylistBackupStore(storage).get('b1'), backup);
      await store.delete('b1');
      expect(await PlaylistBackupStore(storage).getAll(), isEmpty);
    });

    test('should_appendToTheHistoryAndMoveUpdatedAtForward_when_aRestoreIsRecorded', () async {
      final store = PlaylistBackupStore(InMemoryPlaylistBackupStorage(), clock: () => 5);
      final backup = PlaylistSnapshots.backup(
        id: 'b1',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: 'p',
        profileLabel: 'Home',
        now: 10,
      );
      await store.save(backup);
      const record = PlaylistRestoreRecord(
        at: 5,
        targetProvider: 'subsonic',
        targetLabel: 'X',
        added: 1,
        skipped: 0,
        notFound: 0,
      );
      expect(await store.recordRestore('b1', record), isTrue);
      final read = (await store.get('b1'))!;
      expect(read.restores, [record]);
      expect(read.updatedAt, 11);
      expect(await store.recordRestore('gone', record), isFalse);
    });
  });

  group('PlaylistBackupService', () {
    late PlaylistBackupStore store;
    late FakeTransferSource home;
    late FakeBackupProfiles profiles;
    var now = 1000;
    var ids = 0;
    const homeProfile = Profile(id: 'home', provider: MediaId.providerSubsonic, displayName: 'Home');
    const amProfile = Profile(id: 'am', provider: MediaId.providerAppleMusic, displayName: 'Apple Music');

    PlaylistBackupService service() =>
        PlaylistBackupService(profiles: profiles, store: store, clock: () => now, newId: () => 'b${++ids}');

    setUp(() {
      now = 1000;
      ids = 0;
      store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
      home = FakeTransferSource(
        playlists: {
          MediaId.subsonic('p1'): _playlist('p1', [t1]),
        },
      );
      profiles = FakeBackupProfiles(all: [homeProfile, amProfile], activeId: 'home', sources: {'home': home});
    });

    test('should_keepOneBackupPerPlaylist_when_backedUpTwice', () async {
      await service().backUp(MediaId.subsonic('p1'));
      home.playlists = {
        MediaId.subsonic('p1'): _playlist('p1', [t1, t2]),
      };
      now = 2000;
      final again = await service().backUp(MediaId.subsonic('p1'));
      expect((await store.getAll()).single.id, again.id);
      expect(again.trackCount, 2);
      expect(again.versions.single.content.tracks.length, 1);
    });

    test('should_backUpTheChange_when_theFollowedPlaylistChanged', () async {
      await service().backUp(MediaId.subsonic('p1'));
      home.playlists = {
        MediaId.subsonic('p1'): _playlist('p1', [t2]),
      };
      now += PlaylistBackupService.followIntervalMs;
      final summary = await service().followDue();
      expect(summary, const FollowSummary(checked: 1, changed: 1, gone: 0));
      expect((await store.getAll()).single.content.tracks.single.title, 'Lemon');
    });

    test('should_waitForTheInterval_when_followedRecently', () async {
      await service().backUp(MediaId.subsonic('p1'));
      now += _hour;
      expect((await service().followDue()).checked, 0);
    });

    test('should_stopFollowingButKeepTheBackup_when_thePlaylistIsGone', () async {
      await service().backUp(MediaId.subsonic('p1'));
      home.playlists = {};
      now += PlaylistBackupService.followIntervalMs;
      expect((await service().followDue()).gone, 1);
      final kept = (await store.getAll()).single;
      expect(kept.following, isFalse);
      expect(kept.sourceGoneAt, now);
      expect(kept.trackCount, 1);
    });

    test('should_leaveTheBackupAlone_when_theServiceCantBeReached', () async {
      final made = await service().backUp(MediaId.subsonic('p1'));
      home.failPlaylists = true;
      now += PlaylistBackupService.followIntervalMs;
      expect((await service().followDue()).changed, 0);
      expect((await store.getAll()).single, made);
    });

    test('should_recordRestoreWithCounts_when_reviewedTracksAreCreated', () async {
      final backup = await service().backUp(MediaId.subsonic('p1'));
      final am = FakeTransferSource(provider: MediaId.providerAppleMusic);
      profiles.sources = {...profiles.sources, 'am': am};
      final picked = song('am1', 'Entropy', 'Beach Bunny', provider: MediaId.providerAppleMusic);
      final rows = [
        ReviewRow(position: 0, status: ReviewStatus.matched, candidates: [picked], chosen: 0, checked: true),
        const ReviewRow(position: 1, status: ReviewStatus.notFound, candidates: [], chosen: 0, checked: false),
        ReviewRow(position: 2, status: ReviewStatus.uncertain, candidates: [picked], chosen: 0, checked: false),
      ];
      const target = RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic);
      final outcome = await service().create(backup.copyWith(content: _described(backup.content)), target, rows);
      expect(am.created.single.name, 'Night drive');
      // The backup's description goes with the new playlist (Android createPlaylistWithTracks).
      expect(am.descriptions.single, 'late night');
      expect(outcome.record.added, 1);
      expect(outcome.record.skipped, 1);
      expect(outcome.record.notFound, 1);
      expect(outcome.record.failed, isFalse);
      expect((await store.get(backup.id))!.restores.single, outcome.record);
    });

    test('should_recordFailedRestore_when_targetProfileIsGone', () async {
      final backup = await service().backUp(MediaId.subsonic('p1'));
      final rows = [
        ReviewRow(position: 0, status: ReviewStatus.matched, candidates: [t1], chosen: 0, checked: true),
      ];
      const target = RestoreTarget(profileId: 'gone', displayName: 'Old', provider: MediaId.providerSubsonic);
      final outcome = await service().create(backup, target, rows);
      expect(outcome.record.failed, isTrue);
      expect(outcome.record.added, 0);
      expect((await store.get(backup.id))!.restores.single.failed, isTrue);
    });

    test('should_offerEveryImportingProfile_when_listingRestoreTargets', () async {
      expect(await service().restoreTargets(), const [
        RestoreTarget(profileId: 'home', displayName: 'Home', provider: MediaId.providerSubsonic),
        RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic),
      ]);
    });

    test('should_keepABackupPerAccount_when_twoServersHaveAPlaylistWithTheSameId', () async {
      final work = FakeTransferSource(
        playlists: {
          MediaId.subsonic('p1'): _playlist('p1', [t2], name: 'Work'),
        },
      );
      const workProfile = Profile(id: 'work', provider: MediaId.providerSubsonic, displayName: 'Work');
      profiles
        ..all = [homeProfile, workProfile]
        ..sources = {'home': home, 'work': work}
        ..accounts = {'home': 's-home', 'work': 's-work'};
      await service().backUp(MediaId.subsonic('p1'));
      profiles.activeId = 'work';
      await service().backUp(MediaId.subsonic('p1'));
      final all = await store.getAll();
      expect(all, hasLength(2));
      expect({for (final b in all) b.sourceAccount}, {'s-home', 's-work'});
    });

    test('should_takeUpAnotherDevicesBackup_when_itIsFromTheSameAccount', () async {
      final elsewhere = PlaylistSnapshots.backup(
        id: 'remote',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: 'phone-profile',
        profileLabel: 'Home',
        now: 1,
        account: 's-home',
      );
      await store.save(elsewhere);
      profiles.accounts = {'home': 's-home'};
      final backup = await service().backUp(MediaId.subsonic('p1'));
      expect(backup.id, 'remote');
      expect(backup.sourceProfileId, 'home');
    });

    test('should_takeUpAnOldBackup_only_when_itWasMadeUnderThisProfilesName', () async {
      final other = PlaylistSnapshots.backup(
        id: 'old',
        playlist: _playlist('p1', [t1]),
        provider: MediaId.providerSubsonic,
        profileId: null,
        profileLabel: 'Someone else',
        now: 1,
      );
      await store.save(other);
      final backup = await service().backUp(MediaId.subsonic('p1'));
      expect(backup.id, isNot('old'));
      expect(await store.getAll(), hasLength(2));
    });

    test('should_leaveTheBackupAlone_when_itsProfileIsNowAnotherAccount', () async {
      profiles.accounts = {'home': 's-home'};
      await service().backUp(MediaId.subsonic('p1'));
      profiles.accounts = {'home': 's-other'};
      home.playlists = {
        MediaId.subsonic('p1'): _playlist('p1', [t2]),
      };
      now += PlaylistBackupService.followIntervalMs;
      expect((await service().followDue()).changed, 0);
      expect((await store.getAll()).single.content.tracks.single.title, 'Entropy');
    });

    test('should_matchTheBackupsTracks_when_restoringToAnotherService', () async {
      final backup = await service().backUp(MediaId.subsonic('p1'));
      final onAm = song('am1', 'Entropy', 'Beach Bunny', durationSec: 221, provider: MediaId.providerAppleMusic);
      profiles.sources = {
        ...profiles.sources,
        'am': FakeTransferSource(provider: MediaId.providerAppleMusic, catalogue: [onAm]),
      };
      const target = RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic);
      final progress = <int>[];
      final resolutions = await service().match(backup, target, onProgress: (done, _) => progress.add(done));
      expect(RestoreReview.rows(resolutions).single.choice, same(onAm));
      expect(progress, [1]);
    });
  });
}
