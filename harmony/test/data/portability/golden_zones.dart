// The time zones the portability golden vectors are written in. Kotlin reads
// them from the platform's tz database; Dart has none (the app writes in the
// device's own zone), so the tests carry the two rules they need.
import 'package:yoin/data/portability/backup_format.dart';

BackupZone goldenZone(String id) => switch (id) {
  'Asia/Shanghai' => const FixedBackupZone(8 * 3600000),
  'America/New_York' => const _UsEastern(),
  _ => BackupZone.fixed(id) ?? (throw ArgumentError('No test rules for $id')),
};

/// US Eastern since 2007: EDT (-4) from the second Sunday of March 02:00 EST
/// to the first Sunday of November 02:00 EDT, EST (-5) otherwise.
final class _UsEastern extends BackupZone {
  const _UsEastern();

  @override
  int offsetMillis(int epochMs) {
    final year = DateTime.fromMillisecondsSinceEpoch(epochMs, isUtc: true).year;
    final start = _nthSunday(year, 3, 2).add(const Duration(hours: 2 + 5)); // 02:00 EST in UTC
    final end = _nthSunday(year, 11, 1).add(const Duration(hours: 2 + 4)); // 02:00 EDT in UTC
    final t = DateTime.fromMillisecondsSinceEpoch(epochMs, isUtc: true);
    final dst = !t.isBefore(start) && t.isBefore(end);
    return (dst ? -4 : -5) * 3600000;
  }

  static DateTime _nthSunday(int year, int month, int n) {
    var d = DateTime.utc(year, month);
    while (d.weekday != DateTime.sunday) {
      d = d.add(const Duration(days: 1));
    }
    return d.add(Duration(days: 7 * (n - 1)));
  }
}
