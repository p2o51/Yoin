import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/background/background_transfer.dart';
import 'package:yoin/platform/background/shared_background_transfer.dart';

import '../support/transfer_fakes.dart';

const _downloading = TransferNotice(title: 'Downloading', text: '1 of 3', percent: 30);
const _matching = TransferNotice(title: 'Matching', text: 'Night drive', percent: 10, chip: '1 / 10');

void main() {
  late RecordingTransferHost host;
  late SharedBackgroundTransfer shared;

  setUp(() {
    host = RecordingTransferHost();
    shared = SharedBackgroundTransfer(host);
  });

  test('should_showTheLeaseThatBeganLast_when_downloadsAndARestoreBothHold', () async {
    final downloads = shared.lease('downloads');
    final restore = shared.lease('restore');
    expect(await downloads.hold(_downloading), isTrue);
    expect(await restore.hold(_matching), isTrue);
    // The downloads' next update keeps the restore on show (it began holding later).
    expect(await downloads.hold(_downloading), isTrue);
    expect(host.calls, ['hold Downloading', 'hold Matching', 'hold Matching']);
    expect(shared.shown, _matching);
  });

  test('should_keepTheTaskForTheDownloads_when_theRestoreLetsGo', () async {
    final downloads = shared.lease('downloads');
    final restore = shared.lease('restore');
    await downloads.hold(_downloading);
    await restore.hold(_matching);
    await restore.release();
    expect(host.calls, isNot(contains('release')));
    expect(host.calls.last, 'hold Downloading');
    expect(shared.shown, _downloading);
    await downloads.release();
    expect(host.calls.last, 'release');
  });

  test('should_keepTheTaskForTheRestore_when_theDownloadsFinish', () async {
    final downloads = shared.lease('downloads');
    final restore = shared.lease('restore');
    await restore.hold(_matching);
    await downloads.hold(_downloading);
    await downloads.release();
    expect(host.calls, ['hold Matching', 'hold Downloading', 'hold Matching']);
    await restore.release();
    expect(host.calls.last, 'release');
  });

  test('should_doNothing_when_aLeaseThatDoesntHoldReleases', () async {
    final downloads = shared.lease('downloads');
    await downloads.release();
    await shared.settled;
    expect(host.calls, isEmpty);
  });

  test('should_tellEveryHoldingLease_when_theTaskEnds', () async {
    final downloads = shared.lease('downloads');
    final restore = shared.lease('restore');
    final idle = shared.lease('idle');
    final heard = <String>[];
    downloads.ended.listen((byUser) => heard.add('downloads $byUser'));
    restore.ended.listen((byUser) => heard.add('restore $byUser'));
    idle.ended.listen((byUser) => heard.add('idle $byUser'));
    await downloads.hold(_downloading);
    await restore.hold(_matching);
    host.end(byUser: true);
    await Future<void>.delayed(Duration.zero);
    expect(heard, ['downloads true', 'restore true']);
    expect(shared.shown, isNull);
    // Ended already: letting go asks nothing of the system.
    await restore.release();
    expect(host.calls, isNot(contains('release')));
  });

  test('should_answerNoAndForgetTheLease_when_theSystemRefuses', () async {
    final restore = shared.lease('restore');
    host.refuse = true;
    expect(await restore.hold(_matching), isFalse);
    expect(shared.shown, isNull);
    host.refuse = false;
    expect(await restore.hold(_matching), isTrue);
    expect(shared.shown, _matching);
  });

  test('should_passNotificationsThrough_when_aLeaseAsksOrPosts', () async {
    final restore = shared.lease('restore');
    host.allowed = true;
    expect(await restore.notificationsAllowed(ask: true), isTrue);
    await restore.notify(7, _matching);
    await restore.cancelNotice(7);
    expect(host.calls, ['ask', 'notify 7 Matching', 'cancel 7']);
  });

  test('should_carryTheChipAndLeaveOutAMissingBar_when_sentToThePlatform', () {
    const writing = TransferNotice(title: 'Creating on Apple Music', text: 'Night drive', percent: null, chip: '12');
    expect(writing.toMap(), {'title': 'Creating on Apple Music', 'text': 'Night drive', 'chip': '12'});
    expect(_downloading.toMap(), {'title': 'Downloading', 'text': '1 of 3', 'percent': 30});
    const linked = TransferNotice(title: 'Created', text: '3 added', percent: null, link: 'playlistBackup:b1');
    expect(linked.toMap(), {'title': 'Created', 'text': '3 added', 'link': 'playlistBackup:b1'});
  });

  test('should_hearTheHostsTaps_when_aLeaseListens', () async {
    final host = RecordingTransferHost();
    final shared = SharedBackgroundTransfer(host);
    final taps = <String>[];
    final subscription = shared.lease('restore b1').taps.listen(taps.add);
    host.tapped.add('playlistBackup:b1');
    await Future<void>.delayed(Duration.zero);
    expect(taps, ['playlistBackup:b1']);
    await subscription.cancel();
  });
}
