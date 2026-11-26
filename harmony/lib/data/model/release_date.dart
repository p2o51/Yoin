/// An album's release date at the precision its service gives (Android data/model/Album.kt
/// ReleaseDate): a year, a year and month, or a full date. [Album.releaseDate] keeps it as ISO
/// text ("2023-10-27", "2023-10", "2023"); this is that text read back.
final class ReleaseDate {
  const ReleaseDate._(this.year, this.month, this.day);

  final int year;
  final int? month;
  final int? day;

  /// The date from its parts, kept as far as they make a real date: an invalid month drops the
  /// month and day, an invalid day drops the day. Null without a usable year (null, 0, negative).
  static ReleaseDate? of(int? year, [int? month, int? day]) {
    if (year == null || year < 1 || year > 9999) return null;
    if (month == null || month < 1 || month > 12) return ReleaseDate._(year, null, null);
    final valid = day != null && day >= 1 && day <= _daysIn(year, month);
    return ReleaseDate._(year, month, valid ? day : null);
  }

  /// "2023-10-27", "2023-10" or "2023", each at its own precision; text after the day (a time) is
  /// ignored. Null when the text holds no usable year (Android ReleaseDate.parseIso).
  static ReleaseDate? parseIso(String? text) {
    final trimmed = text?.trim() ?? '';
    if (trimmed.isEmpty) return null;
    final parts = trimmed.substring(0, trimmed.length < 10 ? trimmed.length : 10).split('-');
    int? part(int i, int length) => i < parts.length && parts[i].length == length ? int.tryParse(parts[i]) : null;
    return of(part(0, 4), part(1, 2), part(2, 2));
  }

  /// Back to ISO at this precision.
  String get iso {
    final buffer = StringBuffer(year.toString().padLeft(4, '0'));
    if (month case final m?) {
      buffer.write('-${m.toString().padLeft(2, '0')}');
      if (day case final d?) buffer.write('-${d.toString().padLeft(2, '0')}');
    }
    return buffer.toString();
  }

  static int _daysIn(int year, int month) => DateTime.utc(year, month + 1, 0).day;

  @override
  bool operator ==(Object other) =>
      other is ReleaseDate && other.year == year && other.month == month && other.day == day;

  @override
  int get hashCode => Object.hash(year, month, day);

  @override
  String toString() => 'ReleaseDate($iso)';
}
