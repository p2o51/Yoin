import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/jbi_memory_turn.dart';
import 'package:yoin/platform/storage/stores.dart';

void main() {
  late InMemoryStore store;
  late int now;

  JbiMemoryTurn turn() => JbiMemoryTurn(store, clock: () => now);

  setUp(() {
    store = InMemoryStore();
    now = 0;
  });

  test('should_takeTheNextTurn_when_theAppStarts', () async {
    expect(await turn().start(), 1);
    expect(await turn().start(), 2, reason: 'a cold start remembers the last one');
    expect(await store.read(JbiMemoryTurn.key), '2');
  });

  test('should_takeTheNextTurn_when_backAfterHalfAnHourAway', () async {
    final t = turn();
    expect(await t.start(), 1);
    t.hidden();
    now += JbiMemoryTurn.defaultAwayMs;
    expect(await t.shown(), 2);
  });

  test('should_keepTheTurn_when_backSooner', () async {
    final t = turn();
    await t.start();
    t.hidden();
    now += JbiMemoryTurn.defaultAwayMs - 1;
    expect(await t.shown(), isNull);
    // Shown again without having left: nothing.
    now += JbiMemoryTurn.defaultAwayMs;
    expect(await t.shown(), isNull);
    expect(await store.read(JbiMemoryTurn.key), '1');
  });
}
