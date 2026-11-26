/// An album's release date where the Album page shows it (Android ui/detail/AlbumReleaseDateText.kt):
/// compact in the header's meta line, in the language's own words under the last track, and in the
/// scrapbook receipt's English.
library;

import '../data/model/model.dart';

/// The album header's date, in place of the year: "22.6.12" (two-digit year, month and day without
/// leading zeros), "22.6" for a year and month, the full year "2022" for a year alone. Numeric, so the
/// same in every language. Null when neither [date] nor [year] is known.
String? albumHeaderDate(ReleaseDate? date, int? year) {
  if (date == null) return year != null && year > 0 ? '$year' : null;
  final shortYear = (date.year % 100).toString().padLeft(2, '0');
  return switch ((date.month, date.day)) {
    (null, _) => '${date.year}',
    (final m?, null) => '$shortYear.$m',
    (final m?, final d?) => '$shortYear.$m.$d',
  };
}

// The scrapbook receipt is English by design (its other lines are too); the labels are provisional,
// owned by copy, and kept here to be found and renamed (Android ReceiptReleasedLabel / ReceiptLengthLabel).
const receiptReleasedLabel = 'Released';
const receiptLengthLabel = 'Length';

const _receiptMonths = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// The receipt's Released value: "Jun 12, 2022", "Jun 2022", or "2022", in the receipt's English date style.
String receiptReleaseDate(ReleaseDate date) => switch ((date.month, date.day)) {
  (null, _) => '${date.year}',
  (final m?, null) => '${_receiptMonths[m - 1]} ${date.year}',
  (final m?, final d?) => '${_receiptMonths[m - 1]} $d, ${date.year}',
};

/// The receipt's Length value: "47 min", "1 hr 12 min", "2 hr" (whole minutes, as the track-count line
/// counts them; under a minute reads "1 min"). Null for no length.
String? receiptAlbumLength(int? seconds) {
  if (seconds == null || seconds <= 0) return null;
  final minutes = seconds ~/ 60 < 1 ? 1 : seconds ~/ 60;
  final hours = minutes ~/ 60;
  final rest = minutes % 60;
  if (hours == 0) return '$minutes min';
  return rest == 0 ? '$hours hr' : '$hours hr $rest min';
}
