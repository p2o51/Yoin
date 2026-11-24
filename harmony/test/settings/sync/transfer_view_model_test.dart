// Transfer Memories' view model: its steps, matching over a fake catalogue,
// the review's picks and the copy, over the real portability service.
import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/portability/memories/memory_matching.dart';
import 'package:yoin/data/portability/memories/transfer_models.dart';
import 'package:yoin/data/transfer/restore_review.dart';
import 'package:yoin/settings/sync/transfer_view_model.dart';

import '../../data/portability/portability_device.dart';
import 'cloud_sync_screen_test.dart' show FakeCloudSync;
import 'portability_fixtures.dart';

Track _track(String id, String title, {String artist = 'Artist', String album = 'bal1'}) => Track(
  id: MediaId('subsonic', id),
  title: title,
  artist: artist,
  albumId: MediaId('subsonic', album),
  album: 'Rain',
);

void main() {
  late Directory cache;
  late FakeDocumentFiles files;
  late FakeCloudSync sync;
  late PortabilityDevice device;
  late List<String> removed;

  setUp(() async {
    cache = await Directory.systemTemp.createTemp('yoin_xfer_vm');
    files = FakeDocumentFiles();
    sync = FakeCloudSync();
    device = PortabilityDevice(profiles: [alice, bob]);
    removed = [];
    await seedAlice(device);
  });

  tearDown(() async {
    if (await cache.exists()) await cache.delete(recursive: true);
  });

  Future<(ProviderContainer, TransferNotifier)> flow({
    TransferArgs args = const TransferArgs(),
    MemoryCatalog? catalog,
  }) async {
    final container = ProviderContainer(
      overrides: portabilityOverrides(
        portability: await portabilityOf(device),
        sync: sync,
        files: files,
        cache: cache,
        catalog: catalog,
        removed: removed,
      ),
    );
    addTearDown(container.dispose);
    container.listen(transferProvider(args), (_, _) {});
    // The accounts load right after the flow is built.
    await pumpEventQueue();
    return (container, container.read(transferProvider(args).notifier));
  }

  Future<void> settle() => pumpEventQueue();

  test('should_listThisDevicesAccountsAndOnlyOthersAsTargets_when_opened', () async {
    final (container, vm) = await flow();
    var state = container.read(transferProvider(const TransferArgs()));
    expect(state.step, TransferStep.source);
    expect(state.firstStep, TransferStep.source);
    expect([for (final a in state.sources) a.key], ['local:p1', 'local:p2']);
    expect(vm.handlesBack, isFalse, reason: 'the first step closes the page');

    vm.pickSource('local:p1');
    vm.next();
    state = container.read(transferProvider(const TransferArgs()));
    expect(state.step, TransferStep.target);
    expect([for (final a in state.targets) a.key], ['local:p2']);
    expect(transferKindOf(state.source!.account, state.targets.single.account), TransferKind.match);
    expect(vm.handlesBack, isTrue);
    vm.back();
    expect(container.read(transferProvider(const TransferArgs())).step, TransferStep.source);
  });

  test('should_startAtSummary_when_entryPresetsSourceAndTarget', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    final (container, _) = await flow(args: args);
    await settle();
    final state = container.read(transferProvider(args));
    expect(state.step, TransferStep.summary);
    expect(state.firstStep, TransferStep.summary);
    expect(state.draft, isNotNull);
    expect(state.needsMatching, isTrue, reason: 'another server: songs need a counterpart');
    expect(state.totalItems, greaterThan(0));
    expect(state.stepCount, 5);
  });

  test('should_reviewUnsureSongAndCopyThePick_when_candidateChosen', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    // Only other takes of the song: unsure, never added unasked.
    final catalog = FakeCatalog([_track('b1', 'Song One (Live)'), _track('b2', 'Song One (Acoustic)')]);
    final (container, vm) = await flow(args: args, catalog: catalog);
    await settle();
    final started = vm.start();
    expect(container.read(transferProvider(args)).step, TransferStep.matching);
    await started;
    var state = container.read(transferProvider(args));
    expect(state.step, TransferStep.review);
    final row = state.rows.single;
    expect(row.status, ReviewStatus.uncertain);
    expect(row.checked, isFalse);
    expect(row.candidates, hasLength(2));
    expect(state.trackMatches.single.status, MatchStatus.uncertain);

    vm.choose(row.position, 1);
    state = container.read(transferProvider(args));
    expect(state.rows.single.checked, isTrue);
    expect(state.rows.single.choice?.id, MediaId('subsonic', 'b2'));
    expect(state.canCopyReviewed, isTrue);

    await vm.copyReviewed();
    state = container.read(transferProvider(args));
    expect(state.step, TransferStep.done);
    expect(state.result!.copied.songNotes, 1);
    final notes = (await device.stores.memory('p2').syncRead()).songNotes;
    expect(notes.single.trackId, MediaId('subsonic', 'b2'));
    expect(sync.calls, isNot(contains('syncNow')), reason: 'Cloud sync is off');
  });

  test('should_leaveEverySongNotFound_when_theTargetHasNoCatalog', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    final (container, vm) = await flow(args: args);
    await settle();
    await vm.start();
    final state = container.read(transferProvider(args));
    expect(state.step, TransferStep.review);
    expect(state.rows.single.status, ReviewStatus.notFound);
    expect(state.canCopyReviewed, isFalse);
    expect(state.albumMatches.every((a) => a.status == MatchStatus.unmatched), isTrue);
  });

  test('should_closeWithoutWriting_when_cancelledInReview', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    final (container, vm) = await flow(args: args, catalog: FakeCatalog([_track('b1', 'Song One')]));
    await settle();
    await vm.start();
    expect(container.read(transferProvider(args)).rows.single.status, ReviewStatus.matched);
    vm.cancel();
    expect(container.read(transferProvider(args)).finished, isA<TransferClosed>());
    expect((await device.stores.memory('p2').syncRead()).songNotes, isEmpty);
  });

  test('should_removeTheOriginalCard_when_askedOnDone', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    final (container, vm) = await flow(args: args, catalog: FakeCatalog([_track('b1', 'Song One')]));
    await settle();
    await vm.start();
    await vm.copyReviewed();
    expect(container.read(transferProvider(args)).step, TransferStep.done);
    vm.setRemoveCard(true);
    vm.back(); // Back on Done finishes, as the Done button does.
    await settle();
    expect(removed, ['p1']);
    expect(
      container.read(transferProvider(args)).finished,
      isA<TransferCardRemoved>().having((f) => f.sourceName, 'sourceName', 'Alice'),
    );
  });

  test('should_offerABackupsAccounts_when_aFileIsPicked', () async {
    final phone = PortabilityDevice(profiles: [alice]);
    await seedAlice(phone);
    files.picked = (await (await portabilityOf(phone)).export(language: 'en')).bytes;
    final fresh = PortabilityDevice(profiles: [bob]);
    device = fresh;
    final (container, vm) = await flow(catalog: FakeCatalog([_track('b1', 'Song One')]));
    await vm.pickBackup();
    var state = container.read(transferProvider(const TransferArgs()));
    final fromFile = state.sources.where((a) => a.fromBackup).single;
    expect(fromFile.account.displayName, 'Alice');
    expect(fromFile.counts?.notes, 1);
    vm.pickSource(fromFile.key);
    vm.next();
    vm.pickTarget('local:p2');
    vm.next();
    await settle();
    await vm.start();
    await vm.copyReviewed();
    state = container.read(transferProvider(const TransferArgs()));
    expect(state.step, TransferStep.done);
    expect((await fresh.stores.memory('p2').syncRead()).songNotes.single.trackId, MediaId('subsonic', 'b1'));
  });

  test('should_sayWhy_when_thePickedFileIsNotABackup', () async {
    files.picked = Uint8List.fromList([1, 2, 3]);
    final (container, vm) = await flow();
    await vm.pickBackup();
    final notice = container.read(transferProvider(const TransferArgs())).notice;
    expect(notice, isA<TransferBackupNotice>());
  });

  test('should_refuseBack_when_matching', () async {
    const args = TransferArgs(sourceProfileId: 'p1', targetProfileId: 'p2');
    final slow = Completer<List<Track>>();
    final (container, vm) = await flow(args: args, catalog: _SlowCatalog(slow.future));
    await settle();
    unawaited(vm.start());
    await settle();
    expect(container.read(transferProvider(args)).step, TransferStep.matching);
    expect(vm.handlesBack, isTrue);
    vm.back();
    expect(container.read(transferProvider(args)).notice, isA<TransferBusyNotice>());
    expect(container.read(transferProvider(args)).step, TransferStep.matching);
    slow.complete(const []);
    await settle();
  });

  test('should_keepUncheckedSongsOut_when_reviewApplied', () {
    final matches = [
      TrackMatch(
        source: const SourceRef('subsonic', 't1'),
        sourceTrack: null,
        status: MatchStatus.matched,
        candidates: [_track('b1', 'Song One')],
        chosen: _track('b1', 'Song One'),
      ),
    ];
    final rows = reviewRowsOf(matches);
    expect(rows.single.checked, isTrue);
    final applied = applyReview(matches, RestoreReview.setChecked(rows, 0, false));
    expect(applied.single.chosen, isNull);
    expect(applied.single.toPick().chosen, isNull);
  });
}

final class _SlowCatalog extends FakeCatalog {
  _SlowCatalog(this.answer) : super(const []);

  final Future<List<Track>> answer;

  @override
  Future<List<Track>> searchTracks(String query) => answer;
}
