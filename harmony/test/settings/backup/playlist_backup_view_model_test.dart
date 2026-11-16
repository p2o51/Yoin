import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_providers.dart';
import 'package:yoin/data/backup/playlist_backup_service.dart';
import 'package:yoin/data/backup/playlist_backup_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/transfer/restore_review.dart';
import 'package:yoin/settings/backup/playlist_backup_view_model.dart';

import '../../support/backup_fakes.dart';

void main() {
  late PlaylistBackupStore store;
  late FakeBackupProfiles profiles;
  late ProviderContainer container;
  late FakeTransferSource home;
  late FakeTransferSource am;
  final t1 = song('t1', 'Entropy', 'Beach Bunny', durationSec: 221);
  final t2 = song('t2', 'Lemon', 'Kenshi Yonezu', durationSec: 255);
  final onAm = song('am1', 'Entropy', 'Beach Bunny', durationSec: 221, provider: MediaId.providerAppleMusic);
  const target = RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic);

  Future<void> settle() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  setUp(() async {
    store = PlaylistBackupStore(InMemoryPlaylistBackupStorage());
    home = FakeTransferSource(
      playlists: {
        MediaId.subsonic('p1'): Playlist(id: MediaId.subsonic('p1'), name: 'Night drive', tracks: [t1, t2]),
      },
    );
    am = FakeTransferSource(provider: MediaId.providerAppleMusic, catalogue: [onAm]);
    profiles = FakeBackupProfiles(
      all: const [
        Profile(id: 'home', provider: MediaId.providerSubsonic, displayName: 'Home'),
        Profile(id: 'am', provider: MediaId.providerAppleMusic, displayName: 'Apple Music'),
      ],
      activeId: 'home',
      sources: {'home': home, 'am': am},
    );
    final service = PlaylistBackupService(profiles: profiles, store: store, newId: () => 'b1');
    container = ProviderContainer(
      overrides: [
        playlistBackupStoreProvider.overrideWithValue(store),
        playlistBackupServiceProvider.overrideWithValue(service),
      ],
    );
    addTearDown(container.dispose);
    await service.backUp(MediaId.subsonic('p1'));
  });

  PlaylistBackupNotifier notifier() => container.read(playlistBackupPageProvider('b1').notifier);
  PlaylistBackupPageState state() => container.read(playlistBackupPageProvider('b1'));

  Future<void> open() async {
    container.listen(playlistBackupPageProvider('b1'), (_, _) {});
    await settle();
  }

  test('should_showTheBackupAndItsTargets_when_opened', () async {
    await open();
    expect(state().backup, isA<PlaylistBackupContentState>());
    expect(state().targets.map((t) => t.profileId), ['home', 'am']);
  });

  test('should_keepMatchingAndReview_when_sheetIsClosedMidway', () async {
    await open();
    notifier().openRestore();
    expect(state().restore, isA<RestoreChoosingTarget>());
    final matching = notifier().restoreTo(target);
    expect(state().restore, isA<RestoreMatching>());
    notifier().dismissSheet();
    expect(state().restore, isA<RestoreMatching>());
    await matching;
    final review = state().restore as RestoreReviewing;
    expect(review.rows.first.status, ReviewStatus.matched);
    expect(review.rows.last.status, ReviewStatus.notFound);
    notifier().dismissSheet();
    expect(state().restore, isA<RestoreReviewing>());
    // Restore reopens the review where it was.
    notifier().openRestore();
    expect(state().restore, isA<RestoreReviewing>());
  });

  test('should_writeCheckedRowsAndShowRecord_when_creating', () async {
    await open();
    notifier().openRestore();
    await notifier().restoreTo(target);
    await notifier().createPlaylist();
    final done = state().restore as RestoreDone;
    expect(done.record.added, 1);
    expect(done.record.notFound, 1);
    expect(am.created.single.tracks, [onAm.id]);
    await settle();
    expect((state().backup as PlaylistBackupContentState).backup.restores.single, done.record);
    notifier().dismissSheet();
    expect(state().restore, isA<RestoreIdle>());
  });

  test('should_showFailure_when_targetCantBeReached', () async {
    await open();
    notifier().openRestore();
    await notifier().restoreTo(const RestoreTarget(profileId: 'gone', displayName: 'Gone', provider: 'subsonic'));
    expect(state().restore, isA<RestoreFailed>());
  });

  test('should_pickTheSearchedSong_when_theUserSearchesTheTarget', () async {
    await open();
    notifier().openRestore();
    await notifier().restoreTo(target);
    final lemon = song('am2', 'Lemon', 'Kenshi Yonezu', provider: MediaId.providerAppleMusic);
    am.catalogue.add(lemon);
    notifier().openSearch(1, 'Lemon');
    await settle();
    expect(state().search!.results, [lemon]);
    notifier().pickSearchResult(lemon);
    expect(state().search, isNull);
    final row = (state().restore as RestoreReviewing).rows[1];
    expect(row.status, ReviewStatus.picked);
    expect(row.checked, isTrue);
  });

  test('should_sayTheSearchFailed_when_theTargetCantBeReached', () async {
    await open();
    notifier().openRestore();
    await notifier().restoreTo(target);
    am.failSearch = true;
    // The review's own search goes through the service, which answers nothing for a failed source search.
    notifier().openSearch(1, 'Lemon');
    await settle();
    expect(state().search!.loading, isFalse);
    expect(state().search!.results, isEmpty);
  });

  test('should_dropTheReview_when_cancelled', () async {
    await open();
    notifier().openRestore();
    await notifier().restoreTo(target);
    notifier().cancelRestore();
    expect(state().restore, isA<RestoreIdle>());
    expect(am.created, isEmpty);
  });

  test('should_restoreTheEarlierVersion_when_oneIsChosen', () async {
    home.playlists = {
      MediaId.subsonic('p1'): Playlist(id: MediaId.subsonic('p1'), name: 'Night drive', tracks: [t2]),
    };
    await container.read(playlistBackupServiceProvider).backUp(MediaId.subsonic('p1'));
    await open();
    final backup = (state().backup as PlaylistBackupContentState).backup;
    final earlier = backup.versions.single;
    notifier().openRestore(earlier.since);
    expect(state().restoreBackup!.trackCount, 2);
    expect(state().restoreVersion, earlier.since);
  });

  test('should_backUpTheStoredRecord_when_anEarlierVersionWasLastOpenedForRestore', () async {
    home.playlists = {
      MediaId.subsonic('p1'): Playlist(id: MediaId.subsonic('p1'), name: 'Night drive', tracks: [t2]),
    };
    await container.read(playlistBackupServiceProvider).backUp(MediaId.subsonic('p1'));
    await open();
    final earlier = (state().backup as PlaylistBackupContentState).backup.versions.single;
    notifier().openRestore(earlier.since);
    notifier().dismissSheet();
    await notifier().backUpAgain();
    await settle();
    final after = (state().backup as PlaylistBackupContentState).backup;
    // Unchanged: the stored songs were read again, not the earlier version's.
    expect(after.versions.length, 1);
    expect(after.content.tracks.single.title, 'Lemon');
    expect(state().backingUp, isFalse);
  });

  test('should_closeThePage_when_theBackupIsDeleted', () async {
    await open();
    await notifier().delete();
    await settle();
    expect(state().deleted, isTrue);
    expect(state().backup, isA<PlaylistBackupMissing>());
  });

  test('should_restoreSnapshotTheCurrentContents_when_noVersionIsChosen', () {
    const content = PlaylistBackupContent();
    final backup = PlaylistBackupRecord(
      id: 'x',
      name: 'n',
      sourceProvider: 's',
      sourceProfileLabel: 'l',
      sourcePlaylistId: 'p',
      sourceProfileId: null,
      playlistCreatedAt: null,
      firstBackedUpAt: 0,
      lastBackedUpAt: 0,
      lastChangedAt: 0,
      sourceGoneAt: null,
      content: content,
      restores: const [],
      updatedAt: 0,
    );
    expect(restoreSnapshot(backup, null), same(backup));
    expect(restoreSnapshot(backup, 42), same(backup));
  });
}
