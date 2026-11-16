import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/data/transfer/playlist_transfer.dart';
import 'package:yoin/data/transfer/restore_review.dart';
import 'package:yoin/data/transfer/track_matcher.dart';

import '../../support/backup_fakes.dart';

void main() {
  final a = song('a', 'Entropy', 'Beach Bunny', durationSec: 221);
  final b1 = song('b1', 'Lemon', '米津玄師', durationSec: 255);
  final b2 = song('b2', 'Lemon (Cover)', 'Someone', durationSec: 250);

  List<TrackResolution> resolutions() => [
    TrackResolution(position: 2, source: a, decision: const MatchNotFound()),
    TrackResolution(position: 0, source: a, decision: MatchMatched(a, MatchMethod.search)),
    TrackResolution(position: 1, source: b1, decision: MatchAmbiguous([b1, b2, a, b1])),
  ];

  group('RestoreReview', () {
    test('should_checkMatchesAndLeaveTheRestUnchecked_when_rowsAreBuilt', () {
      final rows = RestoreReview.rows(resolutions());
      expect([for (final r in rows) r.position], [0, 1, 2]);
      expect([for (final r in rows) r.status], [ReviewStatus.matched, ReviewStatus.uncertain, ReviewStatus.notFound]);
      expect([for (final r in rows) r.checked], [true, false, false]);
      expect(rows[1].candidates.length, RestoreReview.maxCandidates);
      expect(RestoreReview.trackIdsToWrite(rows), [a.id]);
    });

    test('should_readAnEmptyAmbiguousAsNotFound_when_rowsAreBuilt', () {
      final rows = RestoreReview.rows([TrackResolution(position: 0, source: a, decision: const MatchAmbiguous([]))]);
      expect(rows.single.status, ReviewStatus.notFound);
      expect(
        RestoreReview.statusOf(TrackResolution(position: 0, source: a, decision: const MatchAmbiguous([]))),
        ReviewStatus.notFound,
      );
    });

    test('should_checkTheRow_when_aCandidateIsChosen', () {
      final rows = RestoreReview.choose(RestoreReview.rows(resolutions()), 1, 1);
      expect(rows[1].checked, isTrue);
      expect(rows[1].choice, same(b2));
      expect(RestoreReview.trackIdsToWrite(rows), [a.id, b2.id]);
    });

    test('should_keepANotFoundRowUnchecked_when_checkingIt', () {
      final rows = RestoreReview.setChecked(RestoreReview.rows(resolutions()), 2, true);
      expect(rows[2].checked, isFalse);
    });

    test('should_markThePickedSong_when_theUserSearchedForIt', () {
      final picked = song('p', 'Found', 'Someone');
      final rows = RestoreReview.pick(RestoreReview.rows(resolutions()), 2, picked);
      expect(rows[2].status, ReviewStatus.picked);
      expect(rows[2].checked, isTrue);
      expect(RestoreReview.summary(rows), const ReviewSummary(matched: 1, toCheck: 1, notFound: 0, picked: 1));
      expect(RestoreReview.trackIdsToWrite(rows), [a.id, picked.id]);
    });
  });

  group('PlaylistTransfer', () {
    test('should_matchByIsrcFirst_when_theTargetKnowsIsrcs', () async {
      final onTarget = song('am1', 'Entropy', 'Beach Bunny', durationSec: 221, provider: MediaId.providerAppleMusic);
      final target = FakeIsrcSource(
        provider: MediaId.providerAppleMusic,
        byIsrc: {
          'USABC1234567': [onTarget],
        },
      );
      final result = await const PlaylistTransfer().resolve([MatchableTrack(a, isrc: 'USABC1234567')], target);
      final decision = result.single.decision as MatchMatched;
      expect(decision.method, MatchMethod.isrc);
      expect(decision.track, same(onTarget));
      expect(target.searches, isEmpty);
    });

    test('should_searchTheTitleAlone_when_titleAndArtistFindNothing', () async {
      final onTarget = song('t1', 'Lemon', 'Kenshi Yonezu', durationSec: 255);
      final target = FakeTransferSource(catalogue: [onTarget]);
      final progress = <(int, int)>[];
      final result = await const PlaylistTransfer().resolve(
        [MatchableTrack(b1)],
        target,
        onProgress: (done, total) => progress.add((done, total)),
      );
      expect(target.searches, ['Lemon 米津玄師', 'Lemon']);
      // The artist spelt in another script can't pass the artist floor: offered as nothing, never added.
      expect(result.single.decision, isNot(isA<MatchMatched>()));
      expect(progress, [(1, 1)]);
    });

    test('should_leaveTheTrackUnmatched_when_theSearchFails', () async {
      final target = FakeTransferSource(failSearch: true);
      final result = await const PlaylistTransfer().resolve([MatchableTrack(a)], target);
      expect(result.single.decision, isA<MatchNotFound>());
    });

    test('should_makeNothing_when_noTrackIsChecked', () async {
      final target = FakeTransferSource();
      final write = await const PlaylistTransfer().writeTracks('Mix', const [], target);
      expect(write.created, isNull);
      expect(target.created, isEmpty);
    });

    test('should_reportWhatWentIn_when_theCreateStopsPartWay', () async {
      final made = Playlist(id: MediaId.subsonic('p'), name: 'Mix');
      final target = FakeTransferSource(createFailure: PartialPlaylistCreate(made, 1, Exception('later')));
      final write = await const PlaylistTransfer().writeTracks('Mix', [a.id, b1.id], target);
      expect(write.created, same(made));
      expect(write.added, 1);
      expect(write.requested, 2);
    });

    test('should_writeEveryTrackInOrder_when_theCreateSucceeds', () async {
      final target = FakeTransferSource();
      final write = await const PlaylistTransfer().writeTracks('Mix', [b1.id, a.id], target);
      expect(write.added, 2);
      expect(target.created.single.tracks, [b1.id, a.id]);
    });
  });
}
