import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/scrapbook_focus.dart';

void main() {
  final song = MediaId.subsonic('s1');
  final t0 = DateTime(2026, 11, 16, 12);

  ScrapbookFocusRequests requests() {
    final c = ProviderContainer();
    addTearDown(c.dispose);
    return c.read(scrapbookFocusRequestProvider.notifier);
  }

  test('should_takeRequest_when_pageListsTheSong', () {
    final r = requests()..request(song, now: t0);
    final taken = r.takeFor([MediaId.subsonic('s0'), song], now: t0.add(const Duration(seconds: 2)));
    expect(taken?.songId, song);
    // Taken once only.
    expect(r.takeFor([song], now: t0), isNull);
  });

  test('should_leaveRequest_when_pageDoesNotListTheSong', () {
    final r = requests()..request(song, now: t0);
    expect(r.takeFor([MediaId.subsonic('other')], now: t0), isNull);
    expect(r.takeFor([song], now: t0)?.songId, song);
  });

  test('should_dropRequest_when_nobodyTookItInTime', () {
    final r = requests()..request(song, now: t0);
    expect(r.takeFor([song], now: t0.add(ScrapbookFocusRequest.freshFor + const Duration(seconds: 1))), isNull);
    expect(r.takeFor([song], now: t0), isNull, reason: 'a stale request is dropped on the way');
  });
}
