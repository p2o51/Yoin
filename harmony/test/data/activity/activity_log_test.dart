import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/activity/activity.dart';
import 'package:yoin/platform/storage/stores.dart';

ActivityEvent _event(String id, int ts, {ActivityActionType action = ActivityActionType.played}) => ActivityEvent(
      entityType: ActivityEntityType.album,
      actionType: action,
      entityId: id,
      title: 'Album $id',
      timestampMs: ts,
    );

void main() {
  test('should_collapseSameEntityNewestFirst_when_recordedTwice', () async {
    final log = StoredActivityLog(InMemoryStore(), profileId: 'p');
    await log.record(_event('a', 1));
    await log.record(_event('b', 2));
    await log.record(_event('a', 3, action: ActivityActionType.visited));

    final rows = await log.recent();
    expect(rows.map((r) => r.entityId), ['a', 'b']);
    expect(rows.first.actionType, ActivityActionType.visited);
  });

  test('should_persistAcrossInstances_when_sameStore', () async {
    final store = InMemoryStore();
    await StoredActivityLog(store, profileId: 'p').record(_event('a', 1));
    expect((await StoredActivityLog(store, profileId: 'p').recent()).single.entityId, 'a');
    expect(await StoredActivityLog(store, profileId: 'other').recent(), isEmpty);
  });

  test('should_capRows_when_overflowing', () async {
    final log = StoredActivityLog(InMemoryStore(), profileId: 'p');
    for (var i = 0; i < ActivityLog.cap + 5; i++) {
      await log.record(_event('$i', i));
    }
    final rows = await log.recent();
    expect(rows.length, ActivityLog.cap);
    expect(rows.first.entityId, '${ActivityLog.cap + 4}');
  });
}
