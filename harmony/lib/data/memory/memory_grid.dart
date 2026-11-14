/// The Memories grid (Android data/memory MemoryGrid.kt, owner 2026-11-08
/// "B3"): a calendar wall whose cells grow when you write less, so a
/// listener who writes once a month doesn't stare at an empty wall. How many
/// days you wrote or scored on in the last 17 weeks picks the scale:
///  · fewer than 10: one cell a month, each a 2×2 of the month's top four albums;
///  · 10 to 29: one cell a week, the last 26 weeks;
///  · 30 or more: one cell a day, the last 17 weeks (a column a week).
/// A cell shows up to two albums (a week or day; spliced on the diagonal),
/// or four (a month). An album you wrote about ranks ahead of one you only
/// scored, then the one with more marks.
///
/// Days are calendar days as UTC midnights (MemoryDay), so day arithmetic
/// never crosses a DST jump.
library;

import 'package:flutter/foundation.dart';

import 'memory_ledger.dart';

enum MemoryGridScale { month, week, day }

/// One album in a cell: whether anything was written about it there, and how many marks it got.
@immutable
final class MemoryGridMark {
  const MemoryGridMark({required this.album, required this.wrote, required this.count});
  final MemoryLedgerAlbum album;
  final bool wrote;
  final int count;
}

@immutable
final class MemoryGridCell {
  const MemoryGridCell({
    required this.start,
    required this.end,
    required this.marks,
    required this.writings,
    required this.entries,
    required this.future,
  });

  /// First day the cell covers.
  final DateTime start;

  /// Last day the cell covers (inclusive).
  final DateTime end;

  /// Ranked, at most [MemoryGrid.capacity] long; empty when nothing was kept then.
  final List<MemoryGridMark> marks;

  /// Writings (not scores, not titles) in the cell.
  final int writings;

  /// Every entry in the cell, scores included.
  final int entries;

  /// A day or week that hasn't come yet (the tail of the current week).
  final bool future;

  bool get isEmpty => entries == 0;

  bool contains(DateTime day) => !day.isBefore(start) && !day.isAfter(end);
}

@immutable
final class MemoryGrid {
  const MemoryGrid(this.scale, this.cells);

  final MemoryGridScale scale;

  /// Oldest first.
  final List<MemoryGridCell> cells;

  int get capacity => capacityOf(scale);

  /// Days looked back on to choose the scale: 17 weeks, about four months.
  static const int lookbackDays = 119;
  static const int weekThreshold = 10;
  static const int dayThreshold = 30;
  static const int weeks = 26;
  static const int dayWeeks = 17;
  static const int minMonths = 6;
  static const int maxMonths = 12;

  static int capacityOf(MemoryGridScale scale) => scale == MemoryGridScale.month ? 4 : 2;

  static MemoryGridScale scaleFor(int activeDays) => activeDays < weekThreshold
      ? MemoryGridScale.month
      : activeDays < dayThreshold
      ? MemoryGridScale.week
      : MemoryGridScale.day;
}

DateTime _addDays(DateTime day, int n) => DateTime.utc(day.year, day.month, day.day + n);

int _monthIndex(DateTime d) => d.year * 12 + d.month - 1;

DateTime _monthStart(int index) => DateTime.utc(index ~/ 12, index % 12 + 1, 1);

/// Lays [entries] out on the grid as of [today] (a UTC midnight). [dayOf]
/// gives an instant's calendar day; weeks start on [firstDayOfWeek]
/// (DateTime.monday … DateTime.sunday). An entry without an album still
/// counts toward its cell and the scale, but colours nothing.
MemoryGrid buildMemoryGrid(
  List<MemoryLedgerEntry> entries, {
  required DateTime today,
  required DateTime Function(int epochMs) dayOf,
  int firstDayOfWeek = DateTime.monday,
  MemoryGridScale? scale,
}) {
  final dated = [for (final e in entries) (dayOf(e.at), e)];
  final lookbackStart = _addDays(today, -(MemoryGrid.lookbackDays - 1));
  final activeDays = {
    for (final (day, _) in dated)
      if (!day.isBefore(lookbackStart) && !day.isAfter(today)) day,
  }.length;
  final chosen = scale ?? MemoryGrid.scaleFor(activeDays);
  final ranges = <(DateTime, DateTime)>[];
  switch (chosen) {
    case MemoryGridScale.month:
      final now = _monthIndex(today);
      final oldest = dated.isEmpty ? now : dated.map((d) => _monthIndex(d.$1)).reduce((a, b) => a < b ? a : b);
      final back = (now - oldest + 1).clamp(MemoryGrid.minMonths, MemoryGrid.maxMonths);
      for (var ago = back - 1; ago >= 0; ago--) {
        final start = _monthStart(now - ago);
        ranges.add((start, DateTime.utc(start.year, start.month + 1, 0)));
      }
    case MemoryGridScale.week || MemoryGridScale.day:
      final weeks = chosen == MemoryGridScale.week ? MemoryGrid.weeks : MemoryGrid.dayWeeks;
      final thisWeek = _addDays(today, -((today.weekday - firstDayOfWeek) % 7));
      final first = _addDays(thisWeek, -7 * (weeks - 1));
      if (chosen == MemoryGridScale.week) {
        for (var w = 0; w < weeks; w++) {
          final start = _addDays(first, 7 * w);
          ranges.add((start, _addDays(start, 6)));
        }
      } else {
        for (var d = 0; d < weeks * 7; d++) {
          final day = _addDays(first, d);
          ranges.add((day, day));
        }
      }
  }
  final capacity = MemoryGrid.capacityOf(chosen);
  final cells = [
    for (final (start, end) in ranges)
      () {
        final inCell = [
          for (final (day, e) in dated)
            if (!day.isBefore(start) && !day.isAfter(end)) e,
        ];
        return MemoryGridCell(
          start: start,
          end: end,
          marks: rankMarks(inCell, capacity),
          writings: inCell.where((e) => e.kind.wrote && e.kind != MemoryLedgerKind.title).length,
          entries: inCell.length,
          future: start.isAfter(today),
        );
      }(),
  ];
  return MemoryGrid(chosen, cells);
}

/// A cell's albums, best first: written about, then more marks, then newer.
List<MemoryGridMark> rankMarks(List<MemoryLedgerEntry> entries, int capacity) {
  final tallies = <String, _Tally>{};
  for (final e in entries) {
    final album = e.album;
    if (album == null) continue;
    final t = tallies.putIfAbsent(album.key, () => _Tally(album, e.at));
    t.wrote = t.wrote || e.kind.wrote;
    t.count++;
    if (e.at > t.latest) t.latest = e.at;
  }
  final sorted = tallies.values.toList()
    ..sort((a, b) {
      var c = (b.wrote ? 1 : 0).compareTo(a.wrote ? 1 : 0);
      if (c != 0) return c;
      c = b.count.compareTo(a.count);
      if (c != 0) return c;
      c = b.latest.compareTo(a.latest);
      if (c != 0) return c;
      return a.album.key.compareTo(b.album.key);
    });
  return [for (final t in sorted.take(capacity)) MemoryGridMark(album: t.album, wrote: t.wrote, count: t.count)];
}

final class _Tally {
  _Tally(this.album, this.latest);
  final MemoryLedgerAlbum album;
  bool wrote = false;
  int count = 0;
  int latest;
}
