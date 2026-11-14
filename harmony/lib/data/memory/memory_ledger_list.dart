/// All Memories under the grid (Android data/memory MemoryLedgerList.kt,
/// owner 2026-11-09): one button switches three views, no filters.
///  · Timeline: by month, newest first. An album is one whole card a month
///    (its memory title, its score, its review, the songs scored on it);
///    song notes are small cards between them. Months with nothing fold into
///    one quiet line so a sparse year doesn't scroll forever.
///  · Cards: every album and every noted song once, two a row.
///  · List: the same items, a line each, in the order picked (recent, score, name).
library;

import 'package:flutter/foundation.dart';

import 'memory_ledger.dart';

enum MemoryLedgerView { timeline, cards, list }

enum MemoryLedgerSort { recent, score, name }

/// A song scored on an album, as its card lists it.
@immutable
final class MemoryLedgerSongScore {
  const MemoryLedgerSongScore(this.title, this.score, this.at);
  final String? title;
  final double score;
  final int at;
}

/// One album or one song, with everything kept on it.
sealed class MemoryLedgerItem {
  const MemoryLedgerItem();

  String get key;

  /// Its newest mark.
  int get at;
  MemoryLedgerAlbum? get album;
  double? get score;

  /// What it's called (a song's name can be unknown: empty).
  String get name;
}

final class MemoryLedgerAlbumItem extends MemoryLedgerItem {
  const MemoryLedgerAlbumItem({
    required this.album,
    this.title,
    this.score,
    this.review,
    this.songs = const [],
    required this.at,
  });

  @override
  final MemoryLedgerAlbum album;

  /// The memory title given to it.
  final String? title;
  @override
  final double? score;
  final String? review;

  /// Best first.
  final List<MemoryLedgerSongScore> songs;
  @override
  final int at;

  @override
  String get key => 'album:${album.key}';
  @override
  String get name => album.name;
}

final class MemoryLedgerSongItem extends MemoryLedgerItem {
  const MemoryLedgerSongItem({
    required this.key,
    this.title,
    this.album,
    this.score,
    this.note,
    this.notes = 0,
    required this.at,
  });

  @override
  final String key;
  final String? title;
  @override
  final MemoryLedgerAlbum? album;
  @override
  final double? score;

  /// The newest note on it; null for a song only scored whose album isn't known.
  final MemoryLedgerEntry? note;
  final int notes;
  @override
  final int at;

  @override
  String get name => title ?? '';
}

/// One song: its id, else (an old row) its name.
String _songKey(MemoryLedgerEntry e) => 'song:${e.songId?.toString() ?? 'title:${e.trackTitle ?? e.id}'}';

MemoryLedgerEntry? _newest(List<MemoryLedgerEntry> sorted, MemoryLedgerKind kind) {
  for (final e in sorted) {
    if (e.kind == kind) return e;
  }
  return null;
}

/// [group]: one album's entries other than song notes.
MemoryLedgerAlbumItem _albumItem(MemoryLedgerAlbum album, List<MemoryLedgerEntry> group) {
  final sorted = [...group]..sort(compareLedgerNewestFirst);
  final seen = <String>{};
  final songs = [
    for (final e in sorted)
      if (e.kind == MemoryLedgerKind.trackRating && e.score != null && seen.add(_songKey(e)))
        MemoryLedgerSongScore(e.trackTitle, e.score!, e.at),
  ]..sort((a, b) => b.score != a.score ? b.score.compareTo(a.score) : b.at.compareTo(a.at));
  final title = _newest(sorted, MemoryLedgerKind.title)?.text;
  final review = _newest(sorted, MemoryLedgerKind.review)?.text;
  return MemoryLedgerAlbumItem(
    album: album,
    title: title == null || title.trim().isEmpty ? null : title,
    score: _newest(sorted, MemoryLedgerKind.albumRating)?.score,
    review: review == null || review.trim().isEmpty ? null : review,
    songs: songs,
    at: sorted.first.at,
  );
}

/// The Cards and List views' items, newest first: an album for every album
/// with a title, score, review or scored song; a song for every song with a
/// note (with its score, if scored). A score whose album Yoin never learned
/// stands as a song of its own, so it isn't lost.
List<MemoryLedgerItem> memoryLedgerItems(List<MemoryLedgerEntry> entries) {
  final out = <MemoryLedgerItem>[];
  final byAlbum = <String, List<MemoryLedgerEntry>>{};
  for (final e in entries) {
    if (e.kind != MemoryLedgerKind.songNote && e.album != null) (byAlbum[e.album!.key] ??= []).add(e);
  }
  for (final group in byAlbum.values) {
    out.add(_albumItem(group.first.album!, group));
  }
  final scores = <String, MemoryLedgerEntry>{};
  for (final e in [...entries]..sort(compareLedgerNewestFirst)) {
    if (e.kind == MemoryLedgerKind.trackRating && e.score != null) scores.putIfAbsent(_songKey(e), () => e);
  }
  final notesBySong = <String, List<MemoryLedgerEntry>>{};
  for (final e in entries) {
    if (e.kind == MemoryLedgerKind.songNote) (notesBySong[_songKey(e)] ??= []).add(e);
  }
  for (final MapEntry(:key, :value) in notesBySong.entries) {
    final newest = ([...value]..sort(compareLedgerNewestFirst)).first;
    final score = scores[key];
    out.add(
      MemoryLedgerSongItem(
        key: key,
        title: newest.trackTitle ?? score?.trackTitle,
        album: newest.album ?? score?.album,
        score: score?.score,
        note: newest,
        notes: value.length,
        at: score == null || newest.at > score.at ? newest.at : score.at,
      ),
    );
  }
  for (final MapEntry(:key, value: score) in scores.entries) {
    if (score.album != null || notesBySong.containsKey(key)) continue;
    out.add(MemoryLedgerSongItem(key: key, title: score.trackTitle, score: score.score, at: score.at));
  }
  return sortLedgerItems(out, MemoryLedgerSort.recent);
}

/// [items] in [sort]'s order. Unscored items go last by score; nameless ones last by name.
List<MemoryLedgerItem> sortLedgerItems(List<MemoryLedgerItem> items, MemoryLedgerSort sort) {
  int recent(MemoryLedgerItem a, MemoryLedgerItem b) {
    final c = b.at.compareTo(a.at);
    return c != 0 ? c : a.key.compareTo(b.key);
  }

  final sorted = [...items];
  switch (sort) {
    case MemoryLedgerSort.recent:
      sorted.sort(recent);
    case MemoryLedgerSort.score:
      sorted.sort((a, b) {
        if ((a.score == null) != (b.score == null)) return a.score == null ? 1 : -1;
        final c = (b.score ?? 0).compareTo(a.score ?? 0);
        return c != 0 ? c : recent(a, b);
      });
    case MemoryLedgerSort.name:
      sorted.sort((a, b) {
        final blankA = a.name.trim().isEmpty;
        final blankB = b.name.trim().isEmpty;
        if (blankA != blankB) return blankA ? 1 : -1;
        final c = a.name.toLowerCase().compareTo(b.name.toLowerCase());
        return c != 0 ? c : recent(a, b);
      });
  }
  return sorted;
}

/// A month as the first day of it (UTC midnight).
typedef LedgerMonth = DateTime;

LedgerMonth _monthOf(DateTime day) => DateTime.utc(day.year, day.month);

sealed class MemoryTimelineRow {
  const MemoryTimelineRow();
  String get key;
}

final class TimelineMonth extends MemoryTimelineRow {
  const TimelineMonth(this.month, this.count);
  final LedgerMonth month;
  final int count;
  @override
  String get key => 'month:${month.year}-${month.month}';
}

/// An album's card for [month]. [end]: it sits at the far side (they alternate).
final class TimelineAlbum extends MemoryTimelineRow {
  const TimelineAlbum(this.month, this.item, {required this.end});
  final LedgerMonth month;
  final MemoryLedgerAlbumItem item;
  final bool end;
  @override
  String get key => 'album:${month.year}-${month.month}:${item.album.key}';
}

/// A small card: a song note (its album known or not).
final class TimelineNote extends MemoryTimelineRow {
  const TimelineNote(this.entry);
  final MemoryLedgerEntry entry;
  @override
  String get key => 'note:${entry.id}';
}

/// The month's song scores whose album nobody could name, on one card. Newest first.
final class TimelineScores extends MemoryTimelineRow {
  const TimelineScores(this.month, this.entries);
  final LedgerMonth month;
  final List<MemoryLedgerEntry> entries;
  @override
  String get key => 'scores:${month.year}-${month.month}';
}

/// Empty months, newest first: [from] is the later month, [to] the earlier.
final class TimelineQuiet extends MemoryTimelineRow {
  const TimelineQuiet(this.from, this.to);
  final LedgerMonth from;
  final LedgerMonth to;
  @override
  String get key => 'quiet:${from.year}-${from.month}:${to.year}-${to.month}';
}

/// The timeline for [entries], from [today]'s month back to the oldest
/// entry's. In a month, an album's card stands where its newest mark falls;
/// song notes stand on their own between the cards.
List<MemoryTimelineRow> memoryTimelineRows(
  List<MemoryLedgerEntry> entries, {
  required DateTime today,
  required DateTime Function(int epochMs) dayOf,
}) {
  if (entries.isEmpty) return const [];
  final byMonth = <LedgerMonth, List<MemoryLedgerEntry>>{};
  for (final e in entries) {
    (byMonth[_monthOf(dayOf(e.at))] ??= []).add(e);
  }
  final months = byMonth.keys.toList()..sort();
  final newest = _monthOf(today).isAfter(months.last) ? _monthOf(today) : months.last;
  final oldest = months.first;
  final rows = <MemoryTimelineRow>[];
  LedgerMonth? quietFrom;
  LedgerMonth? quietTo;
  void flushQuiet() {
    final from = quietFrom;
    if (from == null) return;
    rows.add(TimelineQuiet(from, quietTo ?? from));
    quietFrom = null;
    quietTo = null;
  }

  var cards = 0;
  for (var month = newest; !month.isBefore(oldest); month = DateTime.utc(month.year, month.month - 1)) {
    final inMonth = [...?byMonth[month]]..sort(compareLedgerNewestFirst);
    if (inMonth.isEmpty) {
      quietFrom ??= month;
      quietTo = month;
      continue;
    }
    flushQuiet();
    rows.add(TimelineMonth(month, inMonth.length));
    final albums = <String, List<MemoryLedgerEntry>>{};
    for (final e in inMonth) {
      if (e.kind != MemoryLedgerKind.songNote && e.album != null) (albums[e.album!.key] ??= []).add(e);
    }
    final shown = <String>{};
    final orphanScores = [
      for (final e in inMonth)
        if (e.kind == MemoryLedgerKind.trackRating && e.album == null) e,
    ];
    for (final e in inMonth) {
      final album = e.album;
      if (e.kind == MemoryLedgerKind.trackRating && album == null) {
        // One card for all of them, where the newest falls.
        if (identical(e, orphanScores.first)) rows.add(TimelineScores(month, orphanScores));
      } else if (e.kind == MemoryLedgerKind.songNote || album == null) {
        rows.add(TimelineNote(e));
      } else if (shown.add(album.key)) {
        rows.add(TimelineAlbum(month, _albumItem(album, albums[album.key]!), end: cards++ % 2 == 1));
      }
    }
  }
  flushQuiet();
  return rows;
}
