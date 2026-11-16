import 'dart:async';

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
import 'package:yoin/settings/backup/restore_live_update.dart';

import '../../support/backup_fakes.dart';

/// What the restore showed outside the app, in order.
class _RecordingSink implements RestoreProgressSink {
  final calls = <String>[];

  @override
  Future<void> askToNotify() async => calls.add('ask');

  @override
  void matching(PlaylistBackupRecord backup, RestoreTarget target, int done, int total) =>
      calls.add('matching $done/$total');

  @override
  void writing(PlaylistBackupRecord backup, RestoreTarget target, int count) => calls.add('writing $count');

  @override
  void finished(PlaylistBackupRecord backup, RestoreTarget target, PlaylistRestoreRecord record) =>
      calls.add('finished ${record.added}');

  @override
  void clear() => calls.add('clear');

  @override
  void dispose() => calls.add('dispose');
}

/// A target whose searches wait for [gate] (a slow network), so a match can be caught midway.
class _GatedSource extends FakeTransferSource {
  _GatedSource({super.provider, super.catalogue});

  Completer<void>? gate;

  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async {
    final waiting = gate;
    if (waiting != null) await waiting.future;
    return super.search(query, trackCount: trackCount, albumCount: albumCount, artistCount: artistCount);
  }
}

void main() {
  late PlaylistBackupStore store;
  late FakeBackupProfiles profiles;
  late ProviderContainer container;
  late FakeTransferSource home;
  late _GatedSource am;
  late _RecordingSink sink;
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
    am = _GatedSource(provider: MediaId.providerAppleMusic, catalogue: [onAm]);
    sink = _RecordingSink();
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
        restoreProgressFactoryProvider.overrideWithValue((_) => sink),
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

  group('in the background', () {
    test('should_askToNotifyAndShowEachStepOutside_when_aRestoreRuns', () async {
      await open();
      notifier().openRestore();
      await notifier().restoreTo(target);
      // Matching shows outside the app, then goes: the review waits in the app.
      expect(sink.calls.first, 'ask');
      expect(sink.calls[1], 'matching 0/2');
      expect(sink.calls.where((c) => c.startsWith('matching')).last, 'matching 2/2');
      expect(sink.calls.last, 'clear');
      sink.calls.clear();
      await notifier().createPlaylist();
      expect(sink.calls, ['writing 1', 'finished 1']);
    });

    test('should_takeTheProgressAway_when_theRestoreIsCancelledMidMatch', () async {
      await open();
      notifier().openRestore();
      am.gate = Completer<void>();
      final matching = notifier().restoreTo(target);
      await settle();
      notifier().cancelRestore();
      expect(sink.calls.last, 'clear');
      am.gate!.complete();
      await matching;
      // The dropped match reports to no one: no second clear, no review.
      expect(sink.calls.where((c) => c == 'clear'), hasLength(1));
      expect(state().restore, isA<RestoreIdle>());
    });

    test('should_takeTheProgressAway_when_matchingFails', () async {
      await open();
      notifier().openRestore();
      await notifier().restoreTo(const RestoreTarget(profileId: 'gone', displayName: 'Gone', provider: 'subsonic'));
      expect(state().restore, isA<RestoreFailed>());
      expect(sink.calls.last, 'clear');
    });

    test('should_keepMatchingAndShowItLive_when_thePageClosesAndOpensAgain', () async {
      final page = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      await settle();
      notifier().openRestore();
      am.gate = Completer<void>();
      final matching = notifier().restoreTo(target);
      await settle();
      // The page (and its sheet) goes while the songs are matched.
      notifier().dismissSheet();
      page.close();
      await settle();
      expect(sink.calls, isNot(contains('dispose')));
      // Back on the page: the same restore, still matching.
      final again = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      addTearDown(again.close);
      expect(state().restore, isA<RestoreMatching>());
      notifier().openRestore();
      expect(state().restore, isA<RestoreMatching>());
      am.gate!.complete();
      await matching;
      expect(state().restore, isA<RestoreReviewing>());
    });

    test('should_keepTheReviewWaiting_when_matchingEndsWithThePageClosed', () async {
      final page = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      await settle();
      notifier().openRestore();
      final matching = notifier().restoreTo(target);
      page.close();
      await matching;
      await settle();
      final again = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      addTearDown(again.close);
      expect(state().restore, isA<RestoreReviewing>());
    });

    test('should_finishAndRecordTheWrite_when_thePageClosesWhileWriting', () async {
      final page = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      await settle();
      notifier().openRestore();
      await notifier().restoreTo(target);
      final writing = notifier().createPlaylist();
      page.close();
      await writing;
      await settle();
      expect(sink.calls, contains('finished 1'));
      expect(am.created, hasLength(1));
      expect((await store.get('b1'))!.restores, hasLength(1));
      // Settled with no page open: the state goes, and the task's lease with it.
      expect(sink.calls.last, 'dispose');
    });

    test('should_keepTheFailure_when_matchingFailsWithThePageClosed', () async {
      final page = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      await settle();
      notifier().openRestore();
      final matching = notifier().restoreTo(
        const RestoreTarget(profileId: 'gone', displayName: 'Gone', provider: 'subsonic'),
      );
      page.close();
      await matching;
      await settle();
      // Nothing outside the app says it failed: coming back does.
      expect(sink.calls, isNot(contains('dispose')));
      final again = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      expect(state().restore, isA<RestoreFailed>());
      notifier().openRestore();
      expect(state().restore, isA<RestoreChoosingTarget>());
      notifier().dismissSheet();
      again.close();
      await settle();
      expect(sink.calls.last, 'dispose');
    });

    test('should_leaveOtherResultsAlone_when_aBackupWithNoRestoreIsDeleted', () async {
      await open();
      await notifier().delete();
      expect(sink.calls, isNot(contains('clear')));
    });

    test('should_takeTheProgressAway_when_deletedMidMatch', () async {
      await open();
      notifier().openRestore();
      am.gate = Completer<void>();
      final matching = notifier().restoreTo(target);
      await settle();
      await notifier().delete();
      expect(sink.calls.last, 'clear');
      am.gate!.complete();
      await matching;
      expect(sink.calls.where((c) => c == 'clear'), hasLength(1));
    });

    test('should_letTheStateGo_when_noRestoreIsUnderWay', () async {
      final page = container.listen(playlistBackupPageProvider('b1'), (_, _) {});
      await settle();
      notifier().openRestore();
      page.close();
      await settle();
      expect(sink.calls, ['dispose']);
    });
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
