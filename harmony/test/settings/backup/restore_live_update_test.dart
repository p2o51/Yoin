import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/backup/playlist_backup_models.dart';
import 'package:yoin/data/backup/playlist_backup_service.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/background/background_transfer.dart';
import 'package:yoin/platform/background/shared_background_transfer.dart';
import 'package:yoin/settings/backup/playlist_backup_strings.dart';
import 'package:yoin/settings/backup/restore_live_update.dart';

import '../../support/transfer_fakes.dart';

const _target = RestoreTarget(profileId: 'am', displayName: 'Apple Music', provider: MediaId.providerAppleMusic);

final _backup = PlaylistBackupRecord(
  id: 'b1',
  name: 'Night drive',
  sourceProvider: MediaId.providerSubsonic,
  sourceProfileLabel: 'Home',
  sourcePlaylistId: 'subsonic:p1',
  sourceProfileId: 'home',
  playlistCreatedAt: null,
  firstBackedUpAt: 0,
  lastBackedUpAt: 0,
  lastChangedAt: 0,
  sourceGoneAt: null,
  content: const PlaylistBackupContent(),
  restores: const [],
  updatedAt: 0,
);

const _record = PlaylistRestoreRecord(
  at: 0,
  targetProvider: MediaId.providerAppleMusic,
  targetLabel: 'Apple Music',
  added: 38,
  skipped: 4,
  notFound: 3,
);

/// A host that keeps every notice it was asked to show.
class _NoticeHost extends RecordingTransferHost {
  final notices = <TransferNotice>[];
  final posted = <TransferNotice>[];

  @override
  Future<bool> hold(TransferNotice notice) async {
    final ok = await super.hold(notice);
    if (ok) notices.add(notice);
    return ok;
  }

  @override
  Future<void> notify(int id, TransferNotice notice) async {
    posted.add(notice);
    await super.notify(id, notice);
  }
}

void main() {
  late _NoticeHost host;
  late SharedBackgroundTransfer shared;

  var clock = DateTime.fromMillisecondsSinceEpoch(0);

  // Widget tests run in fake time: pump moves the timers, [clock] the update's own clock.
  RestoreLiveUpdate live(WidgetTester tester, {bool chinese = false}) => RestoreLiveUpdate(
    host: shared.lease('restore b1'),
    strings: () => PlaylistBackupStrings.forLanguage(chinese: chinese),
    now: () => clock,
  );

  Future<void> elapse(WidgetTester tester, Duration duration) async {
    // In steps, so each timer sees the clock at its own time.
    const step = Duration(milliseconds: 20);
    var left = duration;
    while (left > Duration.zero) {
      final d = left < step ? left : step;
      clock = clock.add(d);
      await tester.pump(d);
      left -= d;
    }
  }

  // Made inside each test, in its fake time: a future completed outside it would run on the real clock.
  void fresh() {
    clock = DateTime.fromMillisecondsSinceEpoch(0);
    host = _NoticeHost();
    shared = SharedBackgroundTransfer(host);
  }

  testWidgets('should_showTheTargetTheBackupAndTheCount_when_matching', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await elapse(tester, const Duration(seconds: 2));
    update.matching(_backup, _target, 12, 45);
    await tester.pump();
    expect(
      host.notices.last,
      const TransferNotice(
        title: 'Matching on Apple Music',
        text: 'Night drive',
        percent: 26,
        chip: '12 / 45',
        link: 'playlistBackup:b1',
      ),
    );
    update.dispose();
  });

  testWidgets('should_sayItInChinese_when_theSystemIsInChinese', (tester) async {
    fresh();
    final update = live(tester, chinese: true);
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    expect(host.notices.single.title, '正在 Apple Music 匹配');
    update.writing(_backup, _target, 40);
    await tester.pump();
    expect(host.notices.last.title, '正在 Apple Music 上创建');
    update.finished(_backup, _target, _record);
    await tester.pump();
    expect(host.posted.single.title, '已在 Apple Music 上创建');
    expect(host.posted.single.text, '已添加 38 首');
  });

  testWidgets('should_redrawAtMostOnceASecond_when_songsMatchQuickly', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    for (var done = 1; done <= 20; done++) {
      update.matching(_backup, _target, done, 45);
      await elapse(tester, const Duration(milliseconds: 40));
    }
    // The first count at once; the rest wait for the second to pass, then the latest shows.
    expect(host.notices.map((n) => n.chip), ['0 / 45']);
    await elapse(tester, const Duration(seconds: 1));
    expect(host.notices.map((n) => n.chip), ['0 / 45', '20 / 45']);
    // The last song shows at once.
    update.matching(_backup, _target, 45, 45);
    await tester.pump();
    expect(host.notices.last.chip, '45 / 45');
    expect(host.notices.last.percent, 100);
    update.dispose();
  });

  testWidgets('should_publishAgain_when_nothingChangesForMinutes', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await elapse(tester, const Duration(minutes: 7));
    // The system ends a data-transfer task not updated for 10 minutes.
    expect(host.notices, hasLength(3));
    update.dispose();
  });

  testWidgets('should_showNoBarAndTheSongCount_when_writing', (tester) async {
    fresh();
    final update = live(tester);
    update.writing(_backup, _target, 40);
    await tester.pump();
    expect(
      host.notices.single,
      const TransferNotice(
        title: 'Creating on Apple Music',
        text: 'Night drive',
        percent: null,
        chip: '40',
        link: 'playlistBackup:b1',
      ),
    );
    update.dispose();
  });

  testWidgets('should_letTheTaskGoAndLeaveTheResult_when_written', (tester) async {
    fresh();
    final update = live(tester);
    update.writing(_backup, _target, 40);
    await tester.pump();
    update.finished(_backup, _target, _record);
    await tester.pump();
    expect(host.calls.sublist(host.calls.length - 2), [
      'release',
      'notify ${RestoreLiveUpdate.resultNoticeId} Created on Apple Music',
    ]);
    expect(host.posted.single.text, '38 added');
    expect(host.posted.single.subText, 'Night drive');
    // Tapped, the result opens its backup's page (Android's contentIntent).
    expect(host.posted.single.link, 'playlistBackup:b1');
    update.dispose();
    await elapse(tester, const Duration(minutes: 5));
    expect(host.notices, hasLength(1));
  });

  testWidgets('should_sayNotCreated_when_theWriteFailed', (tester) async {
    fresh();
    final update = live(tester);
    update.writing(_backup, _target, 40);
    update.finished(
      _backup,
      _target,
      const PlaylistRestoreRecord(
        at: 0,
        targetProvider: MediaId.providerAppleMusic,
        targetLabel: 'Apple Music',
        added: 0,
        skipped: 0,
        notFound: 0,
        failed: true,
      ),
    );
    await tester.pump();
    expect(host.posted.single.title, 'Not created on Apple Music');
    expect(host.posted.single.text, '0 added');
  });

  testWidgets('should_letTheTaskGoAndTakeTheResultAway_when_cleared', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 2);
    await tester.pump();
    update.clear();
    await tester.pump();
    expect(host.calls, [
      'cancel ${RestoreLiveUpdate.resultNoticeId}',
      'hold Matching on Apple Music',
      'release',
      'cancel ${RestoreLiveUpdate.resultNoticeId}',
    ]);
    // Nothing left running.
    await elapse(tester, const Duration(minutes: 5));
    expect(host.calls, hasLength(4));
  });

  testWidgets('should_takeTheLastResultAway_when_aNewRestoreStarts', (tester) async {
    fresh();
    final update = live(tester);
    update.writing(_backup, _target, 40);
    update.finished(_backup, _target, _record);
    await tester.pump();
    host.calls.clear();
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    expect(host.calls, ['cancel ${RestoreLiveUpdate.resultNoticeId}', 'hold Matching on Apple Music']);
    update.dispose();
  });

  testWidgets('should_stopShowingTheMatch_when_theUserRemovedTheNotification', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    host.end(byUser: true);
    await tester.pump();
    update.matching(_backup, _target, 30, 45);
    await elapse(tester, const Duration(minutes: 5));
    expect(host.notices, hasLength(1));
    // Writing is a new step: it shows again.
    update.writing(_backup, _target, 40);
    await tester.pump();
    expect(host.notices.last.title, 'Creating on Apple Music');
    update.dispose();
  });

  testWidgets('should_askAgainLater_when_theSystemEndedTheTask', (tester) async {
    fresh();
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    host.end(byUser: false);
    await tester.pump();
    update.matching(_backup, _target, 1, 45);
    await tester.pump();
    expect(host.notices, hasLength(1));
    await elapse(tester, const Duration(seconds: 31));
    update.matching(_backup, _target, 2, 45);
    await tester.pump();
    expect(host.notices.last.chip, '2 / 45');
    update.dispose();
  });

  testWidgets('should_askAgainLater_when_theSystemRefusesTheTask', (tester) async {
    fresh();
    final update = live(tester);
    host.refuse = true;
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    host.refuse = false;
    update.matching(_backup, _target, 1, 45);
    await elapse(tester, const Duration(seconds: 2));
    expect(host.notices, isEmpty);
    await elapse(tester, const Duration(seconds: 30));
    update.matching(_backup, _target, 5, 45);
    await tester.pump();
    expect(host.notices.single.chip, '5 / 45');
    update.dispose();
  });

  testWidgets('should_keepTheDownloadsRunning_when_theRestoreIsDone', (tester) async {
    fresh();
    final downloads = shared.lease('downloads');
    downloads.hold(const TransferNotice(title: 'Downloading', text: '1 of 3', percent: 30));
    final update = live(tester);
    update.matching(_backup, _target, 0, 45);
    await tester.pump();
    update.clear();
    await tester.pump();
    expect(host.calls, isNot(contains('release')));
    expect(host.notices.last.title, 'Downloading');
  });

  test('should_askForThePermission_when_aRestoreStarts', () async {
    fresh();
    final update = RestoreLiveUpdate(host: shared.lease('restore b1'));
    await update.askToNotify();
    expect(host.calls, ['ask']);
    update.dispose();
  });

  test('should_readTheBackupBack_when_aRestoresLinkIsTapped', () {
    expect(RestoreLiveUpdate.backupOf(RestoreLiveUpdate.linkTo('b 1')), 'b 1');
    expect(RestoreLiveUpdate.backupOf('playlistBackup:'), isNull);
    expect(RestoreLiveUpdate.backupOf('download:1'), isNull);
  });
}
